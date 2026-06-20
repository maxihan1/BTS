// IssueTransitionAdapter 통합 테스트 — 보드 카드 이동이 기존 전이 경로에 위임됨을 검증

package com.bts.issue.adapter.outbound.board

import com.bts.issue.adapter.inbound.rest.CurrentActor
import com.bts.issue.adapter.outbound.AlwaysAllowIssuePermissionResolver
import com.bts.issue.application.IssueApplicationService
import com.bts.issue.domain.IssueTransitionNotAllowedException
import com.bts.issue.domain.IssueVersionConflictException
import com.bts.issue.domain.IssueWorkflowNotConfiguredException
import com.bts.issue.event.IssueEventPublisher
import com.bts.issue.repository.IssueRepository
import com.bts.issue.repository.IssueTestcontainersBase
import com.bts.issue.resolution.repository.ResolutionRepository
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.shared.board.BoardTransitionCommand
import com.bts.workflow.adapter.inbound.WorkflowTransitionAdapter
import com.bts.workflow.cache.WorkflowCache
import com.bts.workflow.engine.WorkflowDefinitionRepository
import com.bts.workflow.engine.WorkflowEngine
import com.bts.workflow.engine.WorkflowPostActionFactory
import com.bts.workflow.engine.WorkflowValidatorFactory
import com.bts.workflow.repository.WorkflowRepository
import com.bts.workflow.scheme.adapter.inbound.WorkflowKeyResolverImpl
import com.bts.workflow.scheme.adapter.inbound.WorkflowResolverImpl
import com.bts.workflow.scheme.adapter.outbound.AlwaysAllowWorkflowSchemePermissionResolver
import com.bts.workflow.scheme.adapter.outbound.JdbcProjectLookupAdapter
import com.bts.workflow.scheme.adapter.outbound.WorkflowSchemeEventPublisher
import com.bts.workflow.scheme.application.WorkflowSchemeApplicationService
import com.bts.workflow.scheme.application.port.IssueTypeLookupPort
import com.bts.workflow.scheme.repository.ProjectWorkflowSchemeAssignmentRepository
import com.bts.workflow.scheme.repository.SchemeIssueTypeMappingRepository
import com.bts.workflow.scheme.repository.WorkflowSchemeRepository
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.configuration.FluentConfiguration
import org.jooq.DSLContext
import org.jooq.SQLDialect
import org.jooq.impl.DSL
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.jdbc.datasource.DriverManagerDataSource
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.transaction.support.TransactionTemplate
import java.sql.DriverManager
import java.time.Clock
import java.util.UUID

/**
 * [IssueTransitionAdapter] 통합 테스트 (FR-BD-01 Task 5).
 *
 * 보드 카드 이동(`transition(cmd)`)이 기존 `IssueApplicationService.transitionIssue`
 * 경로에 위임되는지를 실제 Testcontainers PostgreSQL + 두 BC 전체 스택으로 검증한다.
 * mock 없이 실제 워크플로우 시드를 사용해 전이 규칙·OCC·예외 전파 경로를 확인한다.
 *
 * ## 검증 시나리오
 *
 * - S1. 정상 전이 — open → in_progress 성공, currentStateKey / version 갱신.
 * - S2. 전이 불가 — open → done(미정의 전이) → [IssueTransitionNotAllowedException] 전파.
 * - S3. 버전 충돌 — expectedVersion 불일치 → [IssueVersionConflictException] 전파.
 * - S4. 워크플로우 미설정 — no-scheme 프로젝트 → [IssueWorkflowNotConfiguredException] 전파.
 * - S5. 권한 강제 — SecurityContext actor 추출. 인증 없으면 401 ResponseStatusException.
 *
 * ## 마이그레이션 전략
 *
 * [IssueControllerTransitionIntegrationTest.TestConfig] 와 동형 구조.
 * issue-tracking V001~V004 + project-workflow V200~V202 순차 적용.
 *
 * ## actor 추출 계약
 *
 * adapter 는 cmd 에서 actor 를 받지 않고 `CurrentActor.current()` 로
 * SecurityContext 에서 추출한다 (actor 위조 차단, sec CONCERN-3).
 * 이 테스트는 `@BeforeEach` 에서 SecurityContext 를 설정하고
 * `@AfterEach` 에서 clear 하여 격리한다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IssueTransitionAdapterTest : IssueTestcontainersBase() {

    // ── 전체 스택 빈 (IssueControllerTransitionIntegrationTest.TestConfig 동형) ──────

    private lateinit var workflowDsl: DSLContext
    private lateinit var adapter: IssueTransitionAdapter

    private val actorId: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")

    /** issue-tracking + project-workflow 마이그레이션을 함께 적용한다 */
    override fun configureFlyway(builder: FluentConfiguration): FluentConfiguration =
        super.configureFlyway(builder).locations(
            "classpath:db/migration/issue-tracking",
            "classpath:db/migration/project-workflow",
        )

    @BeforeAll
    fun setUpAll() {
        val dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        workflowDsl = DSL.using(dataSource, SQLDialect.POSTGRES)

        val objectMapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())
        val txManager = DataSourceTransactionManager(dataSource)
        val txTemplate = TransactionTemplate(txManager)

        // ── project-workflow 빈 ──────────────────────────────────────────────
        val workflowRepo = WorkflowRepository(workflowDsl)
        val workflowCache = WorkflowCache(workflowRepo, workflowDsl)
        val workflowDefRepo =
            mockk<WorkflowDefinitionRepository> {
                every { findValidators(any(), any()) } returns emptyList()
                every { findPostActions(any(), any()) } returns emptyList()
            }
        val validatorFactory = mockk<WorkflowValidatorFactory>(relaxed = true)
        val postActionFactory = mockk<WorkflowPostActionFactory>(relaxed = true)
        val workflowEngine = WorkflowEngine(workflowCache, validatorFactory, postActionFactory, workflowDefRepo)
        val workflowTransitionAdapter = WorkflowTransitionAdapter(workflowEngine)

        val schemeRepo = WorkflowSchemeRepository(workflowDsl)
        val assignmentRepo = ProjectWorkflowSchemeAssignmentRepository(workflowDsl)
        val mappingRepo = SchemeIssueTypeMappingRepository(workflowDsl)
        val schemeEventPublisher = WorkflowSchemeEventPublisher(workflowDsl, objectMapper)
        val schemePermResolver = AlwaysAllowWorkflowSchemePermissionResolver()
        val projectLookup = JdbcProjectLookupAdapter(workflowDsl)
        val issueTypeLookupPort = mockk<IssueTypeLookupPort>(relaxed = true)

        val schemeAS =
            WorkflowSchemeApplicationService(
                schemeRepo = schemeRepo,
                assignmentRepo = assignmentRepo,
                mappingRepo = mappingRepo,
                eventPublisher = schemeEventPublisher,
                permissionResolver = schemePermResolver,
                workflowRepo = workflowRepo,
                issueTypeLookupPort = issueTypeLookupPort,
            )
        val workflowResolver =
            WorkflowResolverImpl(
                projectLookup = projectLookup,
                assignmentRepo = assignmentRepo,
                mappingRepo = mappingRepo,
                workflowRepo = workflowRepo,
                schemeAS = schemeAS,
            )
        val workflowKeyResolver = WorkflowKeyResolverImpl(workflowResolver = workflowResolver)

        // ── issue-tracking 빈 ────────────────────────────────────────────────
        val issueRepo = IssueRepository(dsl)
        val issueTypeRepo = IssueTypeRepository(dsl)
        val resolutionRepo = ResolutionRepository(dsl)
        val issueEventPublisher = IssueEventPublisher(dsl, objectMapper)
        val permResolver = AlwaysAllowIssuePermissionResolver()

        val issueApplicationService =
            IssueApplicationService(
                repo = issueRepo,
                issueTypeRepository = issueTypeRepo,
                resolutionRepository = resolutionRepo,
                eventPublisher = issueEventPublisher,
                permissionResolver = permResolver,
                workflowPort = workflowTransitionAdapter,
                workflowKeyResolver = workflowKeyResolver,
                userLookupPort =
                    object : com.bts.shared.user.UserLookupPort {
                        override fun exists(userId: UUID): Boolean = true
                    },
                componentRepository = mockk(relaxed = true),
                projectLeadRepository = mockk(relaxed = true),
                versionRepository = mockk(relaxed = true),
                clock = Clock.systemUTC(),
                historyRecorder = mockk(relaxed = true),
            )

        // adapter 는 IssueApplicationService + TransactionTemplate 을 주입받아 동기 호출한다.
        adapter = IssueTransitionAdapter(issueApplicationService, txTemplate)

        seedWorkflowsAndSchemes()
    }

    @AfterEach
    fun clearSecurityContext() {
        SecurityContextHolder.clearContext()
    }

    // ── 헬퍼 ────────────────────────────────────────────────────────────────────

    /** SecurityContext 에 actor UUID 를 심는다 (CurrentActor.current() 성공 조건). */
    private fun setActor(id: UUID = actorId) {
        SecurityContextHolder.getContext().authentication =
            UsernamePasswordAuthenticationToken(
                id.toString(),
                null,
                listOf(SimpleGrantedAuthority("ROLE_USER")),
            )
    }

    /** issue_types.id 는 BIGSERIAL(Long) — IssueTypeId(Long) value class */
    private val taskTypeId: Long by lazy {
        var typeId: Long? = null
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement("SELECT id FROM issue_types WHERE key = 'task' LIMIT 1").use { stmt ->
                stmt.executeQuery().use { rs ->
                    if (rs.next()) typeId = rs.getLong(1)
                }
            }
        }
        requireNotNull(typeId) { "issue_types seed: task 타입이 없습니다." }
    }

    /**
     * 시드 프로젝트에 이슈를 SQL 직접 삽입하고 이슈 키 문자열을 반환한다.
     *
     * [IssueTestcontainersBase.testProjectId] 의 프로젝트(key=TPRJ)를 사용하며,
     * [currentStateKey] 는 `"open"` 으로 초기화한다.
     */
    private fun insertIssue(
        projectKey: String = "TPRJ",
        summary: String = "전이 테스트 이슈",
        currentStateKey: String = "open",
    ): String {
        var issueKey = ""
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement(
                """
                UPDATE projects SET key_sequence = key_sequence + 1 WHERE key = ?
                RETURNING key_sequence
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, projectKey)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    issueKey = "$projectKey-${rs.getInt(1)}"
                }
            }

            conn.prepareStatement(
                """
                INSERT INTO issues (id, key, project_id, type_id, summary, current_state_key, reporter_id, version)
                SELECT gen_random_uuid(), ?, p.id, ?, ?, ?, ?, 1
                FROM projects p WHERE p.key = ?
                """.trimIndent(),
            ).use { stmt ->
                stmt.setString(1, issueKey)
                stmt.setLong(2, taskTypeId)
                stmt.setString(3, summary)
                stmt.setString(4, currentStateKey)
                stmt.setObject(5, actorId)
                stmt.setString(6, projectKey)
                stmt.executeUpdate()
            }
        }
        return issueKey
    }

    /** issue-tracking + project-workflow 워크플로우 시드 (IssueControllerTransitionIntegrationTest 동형). */
    @Suppress("LongMethod")
    private fun seedWorkflowsAndSchemes() {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            // TPRJ 프로젝트는 IssueTestcontainersBase.bootstrap() 에서 이미 삽입됨.
            // NOSCHEME 프로젝트 — 워크플로우 스킴 미배정 (S4 시나리오)
            conn.prepareStatement(
                "INSERT INTO projects (key, name) VALUES ('NOSCHEME', 'No Scheme Project') ON CONFLICT (key) DO NOTHING",
            ).use { it.executeUpdate() }

            // software-default 워크플로우 삽입 (이미 존재하면 skip)
            conn.prepareStatement(
                "SELECT COUNT(*) FROM workflows WHERE workflow_key = 'software-default'",
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    rs.next()
                    if (rs.getInt(1) > 0) return
                }
            }

            // workflow row
            val workflowId = UUID.randomUUID()
            conn.prepareStatement(
                """
                INSERT INTO workflows (id, workflow_key, name, states_json, transitions_json, version)
                VALUES (?, 'software-default', 'Software Default',
                '[{"key":"open","name":"Open","isDone":false,"category":"TODO","displayOrder":0},
                  {"key":"in_progress","name":"In Progress","isDone":false,"category":"IN_PROGRESS","displayOrder":1},
                  {"key":"done","name":"Done","isDone":true,"category":"DONE","displayOrder":2}]',
                '[{"name":"Start Work","fromStateKey":"open","toStateKey":"in_progress","validators":[],"postActions":[]},
                  {"name":"Finish","fromStateKey":"in_progress","toStateKey":"done","validators":[],"postActions":[]}]',
                1)
                """.trimIndent(),
            ).use { stmt ->
                stmt.setObject(1, workflowId)
                stmt.executeUpdate()
            }

            // workflow scheme
            val schemeId = UUID.randomUUID()
            conn.prepareStatement(
                """
                INSERT INTO workflow_schemes (id, name, description) VALUES (?, 'Software Scheme', '')
                ON CONFLICT DO NOTHING
                """.trimIndent(),
            ).use { stmt ->
                stmt.setObject(1, schemeId)
                stmt.executeUpdate()
            }

            // scheme default mapping
            conn.prepareStatement(
                """
                INSERT INTO workflow_scheme_issue_type_mappings (id, scheme_id, issue_type_key, workflow_key, is_default)
                VALUES (gen_random_uuid(), ?, NULL, 'software-default', TRUE)
                ON CONFLICT DO NOTHING
                """.trimIndent(),
            ).use { stmt ->
                stmt.setObject(1, schemeId)
                stmt.executeUpdate()
            }

            // TPRJ ← scheme 배정
            conn.prepareStatement(
                """
                INSERT INTO project_workflow_scheme_assignments (id, project_key, scheme_id)
                VALUES (gen_random_uuid(), 'TPRJ', ?)
                ON CONFLICT (project_key) DO NOTHING
                """.trimIndent(),
            ).use { stmt ->
                stmt.setObject(1, schemeId)
                stmt.executeUpdate()
            }
            // NOSCHEME 는 배정하지 않음 (S4 시나리오)
        }
    }

    // ── S1. 정상 전이 — open → in_progress ──────────────────────────────────────

    /**
     * S1. 보드 카드 이동이 기존 전이 경로에 위임되어 상태 갱신 + version 증가를 반환한다.
     *
     * Given  TPRJ-N 이슈 (currentStateKey="open", version=1)
     * When   adapter.transition(cmd(toStateKey="in_progress", expectedVersion=1))
     * Then   result.currentStateKey == "in_progress", result.version == 2
     */
    @Test
    fun `transition delegates to IssueApplicationService and returns updated state`() {
        setActor()
        val issueKey = insertIssue(currentStateKey = "open")

        val cmd =
            BoardTransitionCommand(
                issueKey = issueKey,
                toStateKey = "in_progress",
                expectedVersion = 1L,
                resolutionId = null,
            )

        val result = adapter.transition(cmd)

        assertThat(result.issueKey).isEqualTo(issueKey)
        assertThat(result.currentStateKey).isEqualTo("in_progress")
        assertThat(result.version).isEqualTo(2L)
    }

    // ── S2. 전이 불가 — 미정의 전이 → IssueTransitionNotAllowedException ──────────

    /**
     * S2. 정의되지 않은 전이 시 [IssueTransitionNotAllowedException] 이 그대로 전파된다.
     *
     * Given  TPRJ-N 이슈 (currentStateKey="open")
     * When   adapter.transition(cmd(toStateKey="done", expectedVersion=1))
     *        — software-default.yaml 에 open→done 미정의
     * Then   IssueTransitionNotAllowedException
     */
    @Test
    fun `transition propagates IssueTransitionNotAllowedException for invalid transition`() {
        setActor()
        val issueKey = insertIssue(currentStateKey = "open")

        val cmd =
            BoardTransitionCommand(
                issueKey = issueKey,
                toStateKey = "done",
                expectedVersion = 1L,
                resolutionId = null,
            )

        assertThatThrownBy { adapter.transition(cmd) }
            .isInstanceOf(IssueTransitionNotAllowedException::class.java)
    }

    // ── S3. 버전 충돌 — expectedVersion 불일치 → IssueVersionConflictException ────

    /**
     * S3. expectedVersion 이 실제 DB version 과 다르면 [IssueVersionConflictException] 이 전파된다.
     *
     * Given  TPRJ-N 이슈 (version=1)
     * When   adapter.transition(cmd(toStateKey="in_progress", expectedVersion=99))
     * Then   IssueVersionConflictException
     */
    @Test
    fun `transition propagates IssueVersionConflictException on version mismatch`() {
        setActor()
        val issueKey = insertIssue(currentStateKey = "open")

        val cmd =
            BoardTransitionCommand(
                issueKey = issueKey,
                toStateKey = "in_progress",
                expectedVersion = 99L,
                resolutionId = null,
            )

        assertThatThrownBy { adapter.transition(cmd) }
            .isInstanceOf(IssueVersionConflictException::class.java)
    }

    // ── S4. 워크플로우 미설정 → IssueWorkflowNotConfiguredException ──────────────

    /**
     * S4. 워크플로우 스킴이 배정되지 않은 프로젝트는 [IssueWorkflowNotConfiguredException] 이 전파된다.
     *
     * Given  NOSCHEME-N 이슈 (no scheme assigned)
     * When   adapter.transition(cmd)
     * Then   IssueWorkflowNotConfiguredException
     */
    @Test
    fun `transition propagates IssueWorkflowNotConfiguredException when no scheme`() {
        setActor()
        val issueKey = insertIssue(projectKey = "NOSCHEME", currentStateKey = "open")

        val cmd =
            BoardTransitionCommand(
                issueKey = issueKey,
                toStateKey = "in_progress",
                expectedVersion = 1L,
                resolutionId = null,
            )

        assertThatThrownBy { adapter.transition(cmd) }
            .isInstanceOf(IssueWorkflowNotConfiguredException::class.java)
    }

    // ── S5. 권한 강제 — SecurityContext actor 추출 ────────────────────────────────

    /**
     * S5. SecurityContext 에 인증 정보가 없으면 `CurrentActor.current()` 가 401 을 던진다.
     *
     * adapter 는 cmd 에서 actor 를 받지 않고 SecurityContext 에서 추출한다.
     * actor 위조 차단 설계 (sec CONCERN-3) 의 핵심 게이트.
     */
    @Test
    fun `transition throws 401 ResponseStatusException when SecurityContext is not authenticated`() {
        // SecurityContext 를 명시적으로 clear — setActor() 호출 없음
        SecurityContextHolder.clearContext()
        val issueKey = insertIssue(currentStateKey = "open")

        val cmd =
            BoardTransitionCommand(
                issueKey = issueKey,
                toStateKey = "in_progress",
                expectedVersion = 1L,
                resolutionId = null,
            )

        assertThatThrownBy { adapter.transition(cmd) }
            .isInstanceOf(org.springframework.web.server.ResponseStatusException::class.java)
            .extracting { (it as org.springframework.web.server.ResponseStatusException).statusCode.value() }
            .isEqualTo(401)
    }
}
