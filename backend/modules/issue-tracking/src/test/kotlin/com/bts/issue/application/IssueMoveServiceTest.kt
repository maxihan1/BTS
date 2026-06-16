// IssueMoveService 통합 테스트 — 단건 이슈 이동 실행 검증 (FR-MV-01 Task 7)

package com.bts.issue.application

import com.bts.issue.component.repository.ComponentRepository
import com.bts.issue.customfield.repository.CustomFieldDefinitionRepository
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.IncompleteSubtaskMappingException
import com.bts.issue.domain.InvalidTargetMappingException
import com.bts.issue.domain.IssueDomainException
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssueVersionConflictException
import com.bts.issue.domain.IssueWorkflowNotConfiguredException
import com.bts.issue.domain.RequiredFieldMissingException
import com.bts.issue.domain.SubtaskHasOwnSubtasksException
import com.bts.issue.history.IssueHistoryRecorder
import com.bts.issue.repository.IssueKeyRedirectRepository
import com.bts.issue.repository.IssueRepository
import com.bts.issue.version.repository.VersionRepository
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.bts.shared.workflow.ProjectKey
import com.bts.shared.workflow.WorkflowKeyResolver
import com.bts.shared.workflow.WorkflowStartState
import com.bts.shared.workflow.WorkflowStateCatalog
import com.bts.shared.workflow.WorkflowStateView
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager
import java.util.UUID

/**
 * IssueMoveService 통합 테스트.
 *
 * Testcontainers PostgreSQL + Flyway 전체 스키마 적용.
 * 단위 mock 으로는 검출 불가한 DB 영속/redirect/id 보존/resolution clear/버전링크 교체를 검증한다.
 *
 * ## 검증 시나리오
 * - S1. 정상 이동 — 새 키 발번 + redirect 삽입 + id 불변 + 상태 매핑 + parent_id=null
 * - S2. OCC 409 — expectedVersion 불일치 시 IssueVersionConflictException
 * - S3. 자식 존재 422 — parent_id 가 이슈를 자식으로 가진 이슈 이동 시 도메인 예외
 * - S4. 같은 프로젝트 422 — 원본·대상 프로젝트 동일 시 도메인 예외
 * - S5. 워크플로우 미설정 422 — resolveExisting null 반환 시 IssueWorkflowNotConfiguredException
 * - S6. resolution clear — 대상 상태가 DONE이 아닐 때 resolution_id=null
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Suppress("LongMethod", "LargeClass")
class IssueMoveServiceTest {
    companion object {
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer(
                DockerImageName.parse("quay.io/tembo/pg16-pgmq:latest")
                    .asCompatibleSubstituteFor("postgres"),
            )
                .withDatabaseName("bts_move_svc_test")
                .withUsername("bts")
                .withPassword("bts_move_svc_test")
                .apply { start() }

        private const val SRC_PROJECT = "MSRC"
        private const val DST_PROJECT = "MDST"
        private const val STATE_OPEN = "open"
    }

    private lateinit var dsl: DSLContext
    private lateinit var txManager: DataSourceTransactionManager
    private lateinit var txTemplate: TransactionTemplate
    private lateinit var issueRepository: IssueRepository
    private lateinit var redirectRepository: IssueKeyRedirectRepository

    // mockk 기반 SPI 협력자
    private val permissionResolver = mockk<IssuePermissionResolver>(relaxed = true)
    private val workflowKeyResolver = mockk<WorkflowKeyResolver>(relaxed = true)
    private val workflowStateCatalog = mockk<WorkflowStateCatalog>(relaxed = true)
    private val historyRecorder = mockk<IssueHistoryRecorder>(relaxed = true)
    private lateinit var componentRepository: ComponentRepository
    private lateinit var versionRepository: VersionRepository
    private lateinit var customFieldDefinitionRepository: CustomFieldDefinitionRepository

    private val actor = ActorId(UUID.fromString("11111111-1111-4111-8111-111111111111"))

    private lateinit var sut: IssueMoveService

    private var srcProjectId = UUID.randomUUID()
    private var dstProjectId = UUID.randomUUID()

    private var bootstrapped = false

    @BeforeAll
    fun bootstrap() {
        if (bootstrapped) return

        Flyway.configure()
            .dataSource(postgres.jdbcUrl, postgres.username, postgres.password)
            .placeholderReplacement(false)
            .locations("classpath:db/migration/issue-tracking")
            .load()
            .migrate()

        val dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        dsl = DSL.using(dataSource, SQLDialect.POSTGRES)
        txManager = DataSourceTransactionManager(dataSource)
        txTemplate = TransactionTemplate(txManager)
        issueRepository = IssueRepository(dsl)
        redirectRepository = IssueKeyRedirectRepository(dsl)
        componentRepository = ComponentRepository(dsl)
        versionRepository = VersionRepository(dsl)
        customFieldDefinitionRepository = CustomFieldDefinitionRepository(dsl)

        // SRC / DST 프로젝트 삽입
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute(
                    "INSERT INTO projects (key, name) VALUES ('$SRC_PROJECT', 'Move Source') " +
                        "ON CONFLICT (key) DO NOTHING",
                )
                stmt.execute(
                    "INSERT INTO projects (key, name) VALUES ('$DST_PROJECT', 'Move Dest') " +
                        "ON CONFLICT (key) DO NOTHING",
                )
            }
            conn.prepareStatement("SELECT id FROM projects WHERE key = '$SRC_PROJECT'").use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next()
                    srcProjectId = rs.getObject(1) as UUID
                }
            }
            conn.prepareStatement("SELECT id FROM projects WHERE key = '$DST_PROJECT'").use { ps ->
                ps.executeQuery().use { rs ->
                    rs.next()
                    dstProjectId = rs.getObject(1) as UUID
                }
            }
        }

        bootstrapped = true
    }

    @BeforeEach
    fun setUp() {
        // 권한 모두 허용
        every {
            permissionResolver.hasPermission(any(), IssuePermission.UPDATE, any<IssueScope.Project>())
        } returns true
        every {
            permissionResolver.hasPermission(any(), IssuePermission.CREATE, any<IssueScope.Project>())
        } returns true

        // 워크플로우 기본 stub — resolveExisting 이 대상 프로젝트 초기 상태 "open" 반환
        every {
            workflowKeyResolver.resolveExisting(ProjectKey.of(DST_PROJECT), null)
        } returns WorkflowStartState(workflowKey = "default-workflow", startStateKey = STATE_OPEN)

        // workflowStateCatalog — 대상 프로젝트에 "open" 상태 존재
        every {
            workflowStateCatalog.listStates(ProjectKey.of(DST_PROJECT), null)
        } returns
            listOf(
                WorkflowStateView(key = STATE_OPEN, name = "열림"),
                WorkflowStateView(key = "in_progress", name = "진행 중"),
            )

        sut =
            IssueMoveService(
                issueRepository = issueRepository,
                redirectRepository = redirectRepository,
                permissionResolver = permissionResolver,
                workflowKeyResolver = workflowKeyResolver,
                workflowStateCatalog = workflowStateCatalog,
                historyRecorder = historyRecorder,
                componentRepository = componentRepository,
                versionRepository = versionRepository,
                customFieldDefinitionRepository = customFieldDefinitionRepository,
            )

        // 테이블 초기화
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("DELETE FROM issue_key_redirects")
                stmt.execute("DELETE FROM issues WHERE project_id IN ('$srcProjectId', '$dstProjectId')")
                stmt.execute("UPDATE projects SET key_sequence = 0 WHERE key IN ('$SRC_PROJECT', '$DST_PROJECT')")
            }
        }
    }

    @AfterEach
    fun cleanUp() {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("DELETE FROM issue_key_redirects")
                stmt.execute("DELETE FROM issues WHERE project_id IN ('$srcProjectId', '$dstProjectId')")
                stmt.execute("UPDATE projects SET key_sequence = 0 WHERE key IN ('$SRC_PROJECT', '$DST_PROJECT')")
            }
        }
    }

    // ── S1. 정상 이동 ─────────────────────────────────────────────────────────

    /**
     * S1 — 정상 이동 시 새 키 발번 + redirect 삽입 + id 불변 + 상태 매핑 + parent_id=null.
     *
     * Given  SRC 프로젝트에 이슈 MSRC-1 (state=open, parent=없음)
     * When   IssueMoveService.move(actor, MSRC-1, DST_PROJECT, targetStateKey=null, ...)
     * Then   - 새 키 MDST-1 발번
     *        - issues.id 불변 (이슈 ID 보존)
     *        - issues.project_id = dstProjectId
     *        - issues.key = "MDST-1"
     *        - issues.current_state_key = "open" (매핑된 상태)
     *        - issues.parent_id = null
     *        - issue_key_redirects (MSRC-1 → MDST-1) 행 존재
     */
    @Test
    fun `S1 정상 이동 - 새 키 발번 redirect 삽입 id 불변 상태 매핑 parent null`() {
        val originalIssueId = insertIssue(SRC_PROJECT, srcProjectId, STATE_OPEN)
        val srcKey = IssueKey.of(SRC_PROJECT, 1)

        val request =
            IssueMoveRequest(
                targetProjectKey = DST_PROJECT,
                expectedVersion = 1L,
                targetStateKey = null,
                targetStateIsDone = false,
                componentMapping = emptyMap(),
                affectsVersionMapping = emptyMap(),
                fixVersionMapping = emptyMap(),
                additionalCustomFields = emptyMap(),
            )

        val result =
            txTemplate.execute {
                sut.move(actor, srcKey, request)
            }

        assertThat(result).isNotNull
        assertThat(result!!.newKey.value).isEqualTo("MDST-1")
        assertThat(result.movedSubtasks).isEmpty()

        // 새 키 MDST-1
        val dstKey = IssueKey.of(DST_PROJECT, 1)
        val movedIssue = issueRepository.findByKey(dstKey)
        assertThat(movedIssue).isNotNull
        assertThat(movedIssue!!.id.value).isEqualTo(originalIssueId)
        assertThat(movedIssue.key.value).isEqualTo("MDST-1")
        assertThat(movedIssue.currentStateKey).isEqualTo(STATE_OPEN)
        assertThat(movedIssue.parentId).isNull()

        // redirect 삽입 확인
        val redirect = redirectRepository.findByOldKey(srcKey)
        assertThat(redirect).isNotNull
        assertThat(redirect!!.newKey).isEqualTo(dstKey)

        // 원본 키로 조회 불가(key 변경됨)
        val srcIssue = issueRepository.findByKey(srcKey)
        assertThat(srcIssue).isNull()
    }

    // ── S2. OCC 409 ──────────────────────────────────────────────────────────

    /**
     * S2 — expectedVersion 불일치 시 IssueVersionConflictException.
     *
     * Given  MSRC 프로젝트에 이슈 (version=1)
     * When   expectedVersion=999 (불일치) 로 이동 요청
     * Then   IssueVersionConflictException
     */
    @Test
    fun `S2 OCC - expectedVersion 불일치 시 IssueVersionConflictException`() {
        insertIssue(SRC_PROJECT, srcProjectId, STATE_OPEN)
        val srcKey = IssueKey.of(SRC_PROJECT, 1)

        val request =
            IssueMoveRequest(
                targetProjectKey = DST_PROJECT,
                // 의도적 불일치
                expectedVersion = 999L,
                targetStateKey = null,
                targetStateIsDone = false,
                componentMapping = emptyMap(),
                affectsVersionMapping = emptyMap(),
                fixVersionMapping = emptyMap(),
                additionalCustomFields = emptyMap(),
            )

        assertThrows<IssueVersionConflictException> {
            txTemplate.execute { sut.move(actor, srcKey, request) }
        }
    }

    // ── S3. 자식 존재 422 ────────────────────────────────────────────────────

    /**
     * S3 — 자식(서브태스크) 이슈가 있는 이슈 이동 시 도메인 예외.
     *
     * Given  MSRC 프로젝트에 부모 이슈 MSRC-1, 자식 이슈 MSRC-2 (parent_id = MSRC-1.id)
     * When   MSRC-1 이동 시도
     * Then   IssueHasSubtasksException (IssueDomainException)
     */
    @Test
    fun `S3 자식 이슈 존재 - IssueHasSubtasksException`() {
        val parentId = insertIssue(SRC_PROJECT, srcProjectId, STATE_OPEN)
        // 자식 이슈 삽입 (parent_id = parentId)
        insertIssue(SRC_PROJECT, srcProjectId, STATE_OPEN, parentId = parentId)
        val srcKey = IssueKey.of(SRC_PROJECT, 1)

        val request =
            IssueMoveRequest(
                targetProjectKey = DST_PROJECT,
                expectedVersion = 1L,
                targetStateKey = null,
                targetStateIsDone = false,
                componentMapping = emptyMap(),
                affectsVersionMapping = emptyMap(),
                fixVersionMapping = emptyMap(),
                additionalCustomFields = emptyMap(),
            )

        assertThrows<IssueDomainException> {
            txTemplate.execute { sut.move(actor, srcKey, request) }
        }
    }

    // ── S4. 같은 프로젝트 422 ────────────────────────────────────────────────

    /**
     * S4 — 원본과 대상 프로젝트가 동일할 때 도메인 예외.
     *
     * Given  MSRC 프로젝트에 이슈
     * When   targetProjectKey = SRC_PROJECT (동일 프로젝트)
     * Then   MoveSameProjectException (IssueDomainException)
     */
    @Test
    fun `S4 같은 프로젝트 - MoveSameProjectException`() {
        insertIssue(SRC_PROJECT, srcProjectId, STATE_OPEN)
        val srcKey = IssueKey.of(SRC_PROJECT, 1)

        val request =
            IssueMoveRequest(
                // 동일 프로젝트
                targetProjectKey = SRC_PROJECT,
                expectedVersion = 1L,
                targetStateKey = null,
                targetStateIsDone = false,
                componentMapping = emptyMap(),
                affectsVersionMapping = emptyMap(),
                fixVersionMapping = emptyMap(),
                additionalCustomFields = emptyMap(),
            )

        assertThrows<IssueDomainException> {
            txTemplate.execute { sut.move(actor, srcKey, request) }
        }
    }

    // ── S5. 워크플로우 미설정 422 ────────────────────────────────────────────

    /**
     * S5 — 대상 프로젝트에 워크플로우 스킴 미설정 시 IssueWorkflowNotConfiguredException.
     *
     * Given  대상 프로젝트 resolveExisting → null
     * When   이동 시도
     * Then   IssueWorkflowNotConfiguredException
     */
    @Test
    fun `S5 워크플로우 미설정 - IssueWorkflowNotConfiguredException`() {
        insertIssue(SRC_PROJECT, srcProjectId, STATE_OPEN)
        val srcKey = IssueKey.of(SRC_PROJECT, 1)

        // 워크플로우 미설정 override
        every {
            workflowKeyResolver.resolveExisting(ProjectKey.of(DST_PROJECT), null)
        } returns null

        val request =
            IssueMoveRequest(
                targetProjectKey = DST_PROJECT,
                expectedVersion = 1L,
                targetStateKey = null,
                targetStateIsDone = false,
                componentMapping = emptyMap(),
                affectsVersionMapping = emptyMap(),
                fixVersionMapping = emptyMap(),
                additionalCustomFields = emptyMap(),
            )

        assertThrows<IssueWorkflowNotConfiguredException> {
            txTemplate.execute { sut.move(actor, srcKey, request) }
        }
    }

    // ── S6. resolution clear ────────────────────────────────────────────────

    /**
     * S6 — 대상 상태가 DONE이 아닐 때 resolution_id=null clear.
     *
     * Given  MSRC 이슈에 resolution_id 세팅됨
     * When   이동 요청 targetStateIsDone=false
     * Then   이동 후 issues.resolution_id IS NULL
     */
    @Test
    fun `S6 비DONE 대상 상태 - resolution_id null clear`() {
        val resolutionId = UUID.fromString("00000000-0000-4000-8000-000000000099")
        insertIssueWithResolution(SRC_PROJECT, srcProjectId, STATE_OPEN, resolutionId)
        val srcKey = IssueKey.of(SRC_PROJECT, 1)

        val request =
            IssueMoveRequest(
                targetProjectKey = DST_PROJECT,
                expectedVersion = 1L,
                targetStateKey = null,
                // DONE 아님 → resolution clear
                targetStateIsDone = false,
                componentMapping = emptyMap(),
                affectsVersionMapping = emptyMap(),
                fixVersionMapping = emptyMap(),
                additionalCustomFields = emptyMap(),
            )

        txTemplate.execute { sut.move(actor, srcKey, request) }

        val dstKey = IssueKey.of(DST_PROJECT, 1)
        val movedIssue = issueRepository.findByKey(dstKey)
        assertThat(movedIssue).isNotNull
        assertThat(movedIssue!!.resolutionId).isNull()
    }

    // ── EC8. 타 프로젝트 소속 컴포넌트 매핑 거부 (회귀) ─────────────────────────

    /**
     * EC8 회귀 — 대상 프로젝트에 없는 컴포넌트 UUID를 componentMapping 값으로 보내면
     * InvalidTargetMappingException(422) 을 던진다.
     *
     * 수정 전 코드: componentMappingTargetIds == targetProjectComponentIds (항상 통과)
     * 수정 후 코드: targetProjectComponentIds = componentRepository.findByProject(targetProjectId) 실조회
     *
     * Given  DST 프로젝트에 컴포넌트 없음, 임의 UUID를 componentMapping 값으로 전달
     * When   IssueMoveService.move(...)
     * Then   InvalidTargetMappingException
     */
    @Test
    fun `EC8 회귀 - 타 프로젝트 컴포넌트 UUID 매핑 시 InvalidTargetMappingException`() {
        val issueId = insertIssue(SRC_PROJECT, srcProjectId, STATE_OPEN)
        val srcKey = IssueKey.of(SRC_PROJECT, 1)

        // SRC 프로젝트에 컴포넌트 삽입 후 해당 id를 매핑 source 로 사용
        val fakeSourceComponentId = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")
        val nonExistentTargetComponentId = UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")

        val request =
            IssueMoveRequest(
                targetProjectKey = DST_PROJECT,
                expectedVersion = 1L,
                targetStateKey = null,
                targetStateIsDone = false,
                // DST 프로젝트에 존재하지 않는 UUID를 매핑 대상으로 지정
                componentMapping = mapOf(fakeSourceComponentId to nonExistentTargetComponentId),
                affectsVersionMapping = emptyMap(),
                fixVersionMapping = emptyMap(),
                additionalCustomFields = emptyMap(),
            )

        assertThrows<InvalidTargetMappingException> {
            txTemplate.execute { sut.move(actor, srcKey, request) }
        }
    }

    /**
     * EC8 회귀 — 대상 프로젝트에 없는 버전 UUID를 fixVersionMapping 값으로 보내면
     * InvalidTargetMappingException(422) 을 던진다.
     *
     * Given  DST 프로젝트에 버전 없음, 임의 UUID를 fixVersionMapping 값으로 전달
     * When   IssueMoveService.move(...)
     * Then   InvalidTargetMappingException
     */
    @Test
    fun `EC8 회귀 - 타 프로젝트 버전 UUID 매핑 시 InvalidTargetMappingException`() {
        insertIssue(SRC_PROJECT, srcProjectId, STATE_OPEN)
        val srcKey = IssueKey.of(SRC_PROJECT, 1)

        val fakeSourceVersionId = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc")
        val nonExistentTargetVersionId = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd")

        val request =
            IssueMoveRequest(
                targetProjectKey = DST_PROJECT,
                expectedVersion = 1L,
                targetStateKey = null,
                targetStateIsDone = false,
                componentMapping = emptyMap(),
                affectsVersionMapping = emptyMap(),
                // DST 프로젝트에 존재하지 않는 버전 UUID를 매핑 대상으로 지정
                fixVersionMapping = mapOf(fakeSourceVersionId to nonExistentTargetVersionId),
                additionalCustomFields = emptyMap(),
            )

        assertThrows<InvalidTargetMappingException> {
            txTemplate.execute { sut.move(actor, srcKey, request) }
        }
    }

    // ── EC9. 대상 프로젝트 필수 커스텀필드 미제공 거부 (회귀) ───────────────────────

    /**
     * EC9 회귀 — 대상 프로젝트에 required=true 커스텀필드가 있고
     * additionalCustomFields / issue 기존값에 해당 키가 없으면 RequiredFieldMissingException 을 던진다.
     *
     * Given  DST 프로젝트에 required 커스텀필드 "priority_score" 삽입
     *        이슈 기존 customFields 에 "priority_score" 없음
     *        additionalCustomFields 에도 없음
     * When   IssueMoveService.move(...)
     * Then   RequiredFieldMissingException
     */
    @Test
    fun `EC9 회귀 - 대상 프로젝트 required 커스텀필드 미제공 시 RequiredFieldMissingException`() {
        insertIssue(SRC_PROJECT, srcProjectId, STATE_OPEN)
        val srcKey = IssueKey.of(SRC_PROJECT, 1)

        // DST 프로젝트에 required 커스텀필드 삽입
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                """INSERT INTO custom_field_definitions
                   (id, project_id, key, name, field_type, required, display_order, deleted_at)
                   VALUES (gen_random_uuid(), ?, 'priority_score', '우선순위 점수', 'SHORT_TEXT', true, 0, NULL)
                   ON CONFLICT DO NOTHING""",
            ).use { ps ->
                ps.setObject(1, dstProjectId)
                ps.executeUpdate()
            }
        }

        try {
            val request =
                IssueMoveRequest(
                    targetProjectKey = DST_PROJECT,
                    expectedVersion = 1L,
                    targetStateKey = null,
                    targetStateIsDone = false,
                    componentMapping = emptyMap(),
                    affectsVersionMapping = emptyMap(),
                    fixVersionMapping = emptyMap(),
                    // 필수 커스텀필드 'priority_score' 미제공
                    additionalCustomFields = emptyMap(),
                )

            assertThrows<RequiredFieldMissingException> {
                txTemplate.execute { sut.move(actor, srcKey, request) }
            }
        } finally {
            // EC9 테스트용 커스텀필드 정리 (다른 테스트에 영향 차단)
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.prepareStatement(
                    "DELETE FROM custom_field_definitions WHERE project_id = ? AND key = 'priority_score'",
                ).use { ps ->
                    ps.setObject(1, dstProjectId)
                    ps.executeUpdate()
                }
            }
        }
    }

    // ── C4. 비DONE 이동 후 resolution_id = null (기존 S6 강화) ────────────────────

    /**
     * C4 회귀 — 이동 전 resolution_id 가 세팅된 이슈를 비DONE 상태 대상으로 이동하면
     * DB의 resolution_id 가 실제로 null 로 clear 됨을 단언한다.
     *
     * 기존 S6은 targetStateIsDone=false 만 전달하고 DB 단언이 있었으나,
     * 이동 전 resolution_id 가 실제로 세팅된 상태에서 clear 여부를 명시적으로 확인한다.
     *
     * Given  이슈 resolution_id = 고정 UUID (이동 전)
     * When   targetStateIsDone = false 로 이동
     * Then   이동 후 DB issues.resolution_id IS NULL
     */
    @Test
    @Suppress("NestedBlockDepth") // DB 단언을 위한 중첩 use/use 블록 — 리소스 해제 안전성 우선
    fun `C4 회귀 - 이동 전 resolution_id 세팅 후 비DONE 이동 시 DB resolution_id null`() {
        val resolutionId = UUID.fromString("00000000-0000-4000-8000-000000000077")
        insertIssueWithResolution(SRC_PROJECT, srcProjectId, STATE_OPEN, resolutionId)
        val srcKey = IssueKey.of(SRC_PROJECT, 1)

        // 이동 전 resolution_id 설정됐는지 DB 확인
        val beforeResolution =
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.prepareStatement(
                    "SELECT resolution_id FROM issues WHERE key = ?",
                ).use { ps ->
                    ps.setString(1, srcKey.value)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) rs.getObject(1) else null
                    }
                }
            }
        assertThat(beforeResolution).isNotNull()
        assertThat(beforeResolution as UUID).isEqualTo(resolutionId)

        val request =
            IssueMoveRequest(
                targetProjectKey = DST_PROJECT,
                expectedVersion = 1L,
                targetStateKey = null,
                targetStateIsDone = false,
                componentMapping = emptyMap(),
                affectsVersionMapping = emptyMap(),
                fixVersionMapping = emptyMap(),
                additionalCustomFields = emptyMap(),
            )

        txTemplate.execute { sut.move(actor, srcKey, request) }

        val dstKey = IssueKey.of(DST_PROJECT, 1)
        val afterResolution =
            DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
                conn.prepareStatement(
                    "SELECT resolution_id FROM issues WHERE key = ?",
                ).use { ps ->
                    ps.setString(1, dstKey.value)
                    ps.executeQuery().use { rs ->
                        if (rs.next()) rs.getObject(1) else "ROW_NOT_FOUND"
                    }
                }
            }
        assertThat(afterResolution).isNull()
    }

    // ── ST1. 동반 이동 happy path ─────────────────────────────────────────────

    /**
     * ST1 — 루트+자식 동반 이동: 새 키 발번 + redirect 노드별 + 자식 parent_id 유지 + 루트 detach.
     *
     * Given  MSRC-1(루트) + MSRC-2(자식, parent_id=MSRC-1.id)
     * When   subtasks=[MSRC-2 매핑] 포함 이동 요청
     * Then   - 루트 새 키 MDST-1, 자식 새 키 MDST-2
     *        - 자식 parent_id = 루트의 원래 id(불변) 유지
     *        - 루트 parent_id = null (외부 부모 끊김)
     *        - redirect MSRC-1→MDST-1, MSRC-2→MDST-2
     *        - MoveResult.movedSubtasks.size == 1
     */
    @Test
    fun `ST1 동반 이동 - 루트+자식 새키+redirect+자식 parent유지+루트 detach`() {
        val rootId = insertIssue(SRC_PROJECT, srcProjectId, STATE_OPEN)
        insertIssue(SRC_PROJECT, srcProjectId, STATE_OPEN, parentId = rootId)
        val srcRootKey = IssueKey.of(SRC_PROJECT, 1)
        val srcChildKey = IssueKey.of(SRC_PROJECT, 2)

        // setUp() 이 listStates(ProjectKey.of(DST_PROJECT), null) → STATE_OPEN 포함 목록을 stub 했으므로 추가 stub 불필요.

        val request =
            IssueMoveRequest(
                targetProjectKey = DST_PROJECT,
                expectedVersion = 1L,
                targetStateKey = null,
                targetStateIsDone = false,
                componentMapping = emptyMap(),
                affectsVersionMapping = emptyMap(),
                fixVersionMapping = emptyMap(),
                additionalCustomFields = emptyMap(),
                subtasks =
                    listOf(
                        SubtaskMoveSpec(
                            issueKey = srcChildKey.value,
                            expectedVersion = 1L,
                            targetStateKey = null,
                            targetStateIsDone = false,
                            componentMapping = emptyMap(),
                            affectsVersionMapping = emptyMap(),
                            fixVersionMapping = emptyMap(),
                            additionalCustomFields = emptyMap(),
                        ),
                    ),
            )

        val result = txTemplate.execute { sut.move(actor, srcRootKey, request) }

        assertThat(result).isNotNull
        assertThat(result!!.movedSubtasks).hasSize(1)

        // 루트 새 키 MDST-1
        val dstRootKey = IssueKey.of(DST_PROJECT, 1)
        val movedRoot = issueRepository.findByKey(dstRootKey)
        assertThat(movedRoot).isNotNull
        assertThat(movedRoot!!.id.value).isEqualTo(rootId)
        assertThat(movedRoot.parentId).isNull()

        // 자식 새 키 MDST-2, parent_id = 루트 id(불변)
        val dstChildKey = IssueKey.of(DST_PROJECT, 2)
        val movedChild = issueRepository.findByKey(dstChildKey)
        assertThat(movedChild).isNotNull
        assertThat(movedChild!!.parentId).isEqualTo(rootId)

        // redirect 노드별
        assertThat(redirectRepository.findByOldKey(srcRootKey)).isNotNull
        assertThat(redirectRepository.findByOldKey(srcChildKey)).isNotNull
    }

    // ── ST2. 다단계(자식의 자식) 거부 ────────────────────────────────────────

    /**
     * ST2 — 자식 노드가 또 자식을 가지면 SubtaskHasOwnSubtasksException.
     *
     * Given  루트 MSRC-1 → 자식 MSRC-2 → 손자 MSRC-3
     * When   subtasks=[MSRC-2] 동반 이동
     * Then   SubtaskHasOwnSubtasksException (전체 롤백)
     */
    @Test
    fun `ST2 자식의 자식 있으면 SubtaskHasOwnSubtasksException 전체롤백`() {
        val rootId = insertIssue(SRC_PROJECT, srcProjectId, STATE_OPEN)
        val childId = insertIssue(SRC_PROJECT, srcProjectId, STATE_OPEN, parentId = rootId)
        // 손자
        insertIssue(SRC_PROJECT, srcProjectId, STATE_OPEN, parentId = childId)
        val srcRootKey = IssueKey.of(SRC_PROJECT, 1)
        val srcChildKey = IssueKey.of(SRC_PROJECT, 2)

        val request =
            IssueMoveRequest(
                targetProjectKey = DST_PROJECT,
                expectedVersion = 1L,
                targetStateKey = null,
                targetStateIsDone = false,
                componentMapping = emptyMap(),
                affectsVersionMapping = emptyMap(),
                fixVersionMapping = emptyMap(),
                additionalCustomFields = emptyMap(),
                subtasks =
                    listOf(
                        SubtaskMoveSpec(
                            issueKey = srcChildKey.value,
                            expectedVersion = 1L,
                            targetStateKey = null,
                            targetStateIsDone = false,
                            componentMapping = emptyMap(),
                            affectsVersionMapping = emptyMap(),
                            fixVersionMapping = emptyMap(),
                            additionalCustomFields = emptyMap(),
                        ),
                    ),
            )

        assertThrows<SubtaskHasOwnSubtasksException> {
            txTemplate.execute { sut.move(actor, srcRootKey, request) }
        }
    }

    // ── ST3. 불완전 매핑(자식 일부 누락) 거부 ─────────────────────────────────

    /**
     * ST3 — 실제 자식 키 집합 ≠ 제공 자식 키 집합이면 IncompleteSubtaskMappingException.
     *
     * Given  루트 MSRC-1 → 자식 MSRC-2, MSRC-3 (자식 2개)
     * When   subtasks=[MSRC-2만] (MSRC-3 누락)
     * Then   IncompleteSubtaskMappingException
     */
    @Test
    fun `ST3 자식 매핑 불완전이면 IncompleteSubtaskMappingException`() {
        val rootId = insertIssue(SRC_PROJECT, srcProjectId, STATE_OPEN)
        insertIssue(SRC_PROJECT, srcProjectId, STATE_OPEN, parentId = rootId)
        insertIssue(SRC_PROJECT, srcProjectId, STATE_OPEN, parentId = rootId)
        val srcRootKey = IssueKey.of(SRC_PROJECT, 1)
        val srcChild1Key = IssueKey.of(SRC_PROJECT, 2)

        val request =
            IssueMoveRequest(
                targetProjectKey = DST_PROJECT,
                expectedVersion = 1L,
                targetStateKey = null,
                targetStateIsDone = false,
                componentMapping = emptyMap(),
                affectsVersionMapping = emptyMap(),
                fixVersionMapping = emptyMap(),
                additionalCustomFields = emptyMap(),
                // 자식 2개 중 1개만 제공
                subtasks =
                    listOf(
                        SubtaskMoveSpec(
                            issueKey = srcChild1Key.value,
                            expectedVersion = 1L,
                            targetStateKey = null,
                            targetStateIsDone = false,
                            componentMapping = emptyMap(),
                            affectsVersionMapping = emptyMap(),
                            fixVersionMapping = emptyMap(),
                            additionalCustomFields = emptyMap(),
                        ),
                    ),
            )

        assertThrows<IncompleteSubtaskMappingException> {
            txTemplate.execute { sut.move(actor, srcRootKey, request) }
        }
    }

    // ── ST4. 자식 OCC 충돌 → 전체 롤백 ─────────────────────────────────────

    /**
     * ST4 — 자식 expectedVersion 불일치 시 IssueVersionConflictException + 전체 롤백.
     *
     * Given  루트 MSRC-1(version=1) + 자식 MSRC-2(version=1)
     * When   자식 expectedVersion=999 (불일치)
     * Then   IssueVersionConflictException, 루트도 미이동
     */
    @Test
    fun `ST4 자식 OCC 충돌이면 IssueVersionConflictException 전체롤백`() {
        val rootId = insertIssue(SRC_PROJECT, srcProjectId, STATE_OPEN)
        insertIssue(SRC_PROJECT, srcProjectId, STATE_OPEN, parentId = rootId)
        val srcRootKey = IssueKey.of(SRC_PROJECT, 1)
        val srcChildKey = IssueKey.of(SRC_PROJECT, 2)

        // setUp() 이 listStates(ProjectKey.of(DST_PROJECT), null) stub 했으므로 추가 stub 불필요.

        val request =
            IssueMoveRequest(
                targetProjectKey = DST_PROJECT,
                expectedVersion = 1L,
                targetStateKey = null,
                targetStateIsDone = false,
                componentMapping = emptyMap(),
                affectsVersionMapping = emptyMap(),
                fixVersionMapping = emptyMap(),
                additionalCustomFields = emptyMap(),
                subtasks =
                    listOf(
                        SubtaskMoveSpec(
                            issueKey = srcChildKey.value,
                            // 의도적 불일치
                            expectedVersion = 999L,
                            targetStateKey = null,
                            targetStateIsDone = false,
                            componentMapping = emptyMap(),
                            affectsVersionMapping = emptyMap(),
                            fixVersionMapping = emptyMap(),
                            additionalCustomFields = emptyMap(),
                        ),
                    ),
            )

        assertThrows<IssueVersionConflictException> {
            txTemplate.execute { sut.move(actor, srcRootKey, request) }
        }

        // 루트도 미이동
        assertThat(issueRepository.findByKey(srcRootKey)).isNotNull
    }

    // ── ST5. 자식 없으면 단건 회귀 ───────────────────────────────────────────

    /**
     * ST5 — subtasks 빈 배열 + 실제 자식 없으면 단건 #153과 동일 동작(회귀 보존).
     *
     * Given  루트 MSRC-1 (자식 없음)
     * When   subtasks=[] 빈 배열로 이동 요청
     * Then   MoveResult.newKey = MDST-1, movedSubtasks 빈 배열
     */
    @Test
    fun `ST5 자식 없으면 단건 회귀 - subtasks 빈 배열 movedSubtasks 빈 배열`() {
        insertIssue(SRC_PROJECT, srcProjectId, STATE_OPEN)
        val srcKey = IssueKey.of(SRC_PROJECT, 1)

        val request =
            IssueMoveRequest(
                targetProjectKey = DST_PROJECT,
                expectedVersion = 1L,
                targetStateKey = null,
                targetStateIsDone = false,
                componentMapping = emptyMap(),
                affectsVersionMapping = emptyMap(),
                fixVersionMapping = emptyMap(),
                additionalCustomFields = emptyMap(),
                subtasks = emptyList(),
            )

        val result = txTemplate.execute { sut.move(actor, srcKey, request) }

        assertThat(result).isNotNull
        assertThat(result!!.newKey.value).isEqualTo("MDST-1")
        assertThat(result.movedSubtasks).isEmpty()
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /**
     * 이슈를 DB에 직접 삽입하고 생성된 이슈 UUID를 반환한다.
     *
     * @param projectKey 프로젝트 키 (예: "MSRC")
     * @param projectId 프로젝트 UUID
     * @param stateKey 초기 상태 키
     * @param parentId 부모 이슈 UUID. null이면 최상위 이슈.
     * @return 삽입된 이슈 UUID
     */
    private fun insertIssue(
        projectKey: String,
        projectId: UUID,
        stateKey: String,
        parentId: UUID? = null,
    ): UUID {
        val issueId = UUID.randomUUID()
        // key_sequence를 증가시켜 키 발번
        val seq =
            txTemplate.execute {
                issueRepository.incrementKeySequence(projectKey)
            }!!
        val issueKey = IssueKey.of(projectKey, seq)

        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                """INSERT INTO issues
                   (id, key, project_id, summary, reporter_id, current_state_key, type_id, version, parent_id)
                   VALUES (?, ?, ?, 'Test Issue', ?, ?, (SELECT id FROM issue_types WHERE key='task' LIMIT 1), 1, ?)""",
            ).use { ps ->
                ps.setObject(1, issueId)
                ps.setString(2, issueKey.value)
                ps.setObject(3, projectId)
                ps.setObject(4, actor.value)
                ps.setString(5, stateKey)
                ps.setObject(6, parentId)
                ps.executeUpdate()
            }
        }
        return issueId
    }

    /**
     * resolution_id가 설정된 이슈를 삽입하고 UUID를 반환한다.
     */
    private fun insertIssueWithResolution(
        projectKey: String,
        projectId: UUID,
        stateKey: String,
        resolutionId: UUID,
    ): UUID {
        val issueId = UUID.randomUUID()
        val seq =
            txTemplate.execute {
                issueRepository.incrementKeySequence(projectKey)
            }!!
        val issueKey = IssueKey.of(projectKey, seq)

        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                """INSERT INTO issues
                   (id, key, project_id, summary, reporter_id, current_state_key, type_id, version, resolution_id)
                   VALUES (?, ?, ?, 'Test Issue', ?, ?, (SELECT id FROM issue_types WHERE key='task' LIMIT 1), 1, ?)""",
            ).use { ps ->
                ps.setObject(1, issueId)
                ps.setString(2, issueKey.value)
                ps.setObject(3, projectId)
                ps.setObject(4, actor.value)
                ps.setString(5, stateKey)
                ps.setObject(6, resolutionId)
                ps.executeUpdate()
            }
        }
        return issueId
    }
}
