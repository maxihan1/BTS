// IssueImportAdapter 통합 테스트 — 행 원자성/권한 위임/이메일·이름 매핑 (FR-IM-01 Task 8, 실 repo+시드)

package com.bts.issue.adapter.outbound.imports

import com.bts.issue.adapter.inbound.rest.IssueControllerTransitionIntegrationTest.TestConfig
import com.bts.issue.application.IssueApplicationService
import com.bts.issue.component.repository.ComponentRepository
import com.bts.issue.event.IssueEventPublisher
import com.bts.issue.repository.IssueRepository
import com.bts.issue.resolution.repository.ResolutionRepository
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.shared.issue.IssueImportCommand
import com.bts.shared.issue.IssueImportResult
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.bts.shared.user.UserLookupPort
import com.bts.workflow.adapter.inbound.WorkflowTransitionAdapter
import com.bts.workflow.scheme.adapter.inbound.WorkflowKeyResolverImpl
import io.mockk.mockk
import org.flywaydb.core.Flyway
import org.jooq.DSLContext
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.junit.jupiter.SpringExtension
import org.springframework.test.context.web.WebAppConfiguration
import org.springframework.web.context.WebApplicationContext
import java.sql.DriverManager
import java.time.Clock
import java.util.UUID

/**
 * [IssueImportAdapter] 전 구간 Testcontainers 통합 테스트 (FR-IM-01 Task 8).
 *
 * 어댑터 → [IssueApplicationService] → 실 repository → 실 PostgreSQL 전 구간을 검증한다.
 * mockk 로는 표면화되지 않는 트랜잭션 경계(행 원자성)·권한 위임·jOOQ 매핑을 실증한다.
 *
 * ## 검증 시나리오
 * - S1. 코어 필드 + typeName 매칭(대소문자무시) + priority/labels/assignee 반영
 * - S2. reporterEmail 매칭 → reporterId=매칭 사용자 / 미매칭 → requesterUserId 폴백
 * - S3. assigneeEmail 매칭 → assigneeId=매칭 사용자 / 미매칭 → assigneeId=null
 * - S4. typeName 미매칭 → Task 폴백 + warning
 * - S5. componentNames 일부 미발견 → 매칭분만 연결 + 미발견 warning
 * - S6. CREATE_ISSUE 권한 없는 requester → Failure(FORBIDDEN), 이슈 미생성
 * - S7. dryRun=true → 이슈/이벤트 미생성, 검증 결과만 반환
 * - S8(CONCERN #1). update 강제 실패(priority 범위 밖) → 이슈 롤백 + IssueCreated 이벤트 미발행
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [TestConfig::class, IssueImportAdapterTest.ImportTestConfig::class])
@WebAppConfiguration
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IssueImportAdapterTest {
    /**
     * import 어댑터 통합 테스트 보조 설정.
     *
     * 실 [ComponentRepository] + 선택적 거부 [IssuePermissionResolver] + fixture [UserLookupPort] 로
     * [TestConfig] 의 stub 들을 대체하고, 이를 사용하는 [IssueApplicationService] 를 [Primary] 로 재조립한다.
     */
    @Configuration
    @Suppress("LongParameterList")
    open class ImportTestConfig {
        @Bean
        open fun importComponentRepository(dsl: DSLContext): ComponentRepository = ComponentRepository(dsl)

        @Bean
        @Primary
        open fun importUserLookupPort(): UserLookupPort = FakeImportUserLookupPort()

        @Bean
        @Primary
        open fun importPermissionResolver(): IssuePermissionResolver =
            SelectiveDenyPermissionResolver(deniedActorId = FORBIDDEN_REQUESTER_ID)

        @Bean
        @Primary
        open fun issueApplicationServiceForImport(
            repo: IssueRepository,
            issueTypeRepository: IssueTypeRepository,
            resolutionRepository: ResolutionRepository,
            eventPublisher: IssueEventPublisher,
            permissionResolver: IssuePermissionResolver,
            workflowTransitionAdapter: WorkflowTransitionAdapter,
            workflowKeyResolver: WorkflowKeyResolverImpl,
            userLookupPort: UserLookupPort,
            componentRepository: ComponentRepository,
            clock: Clock,
        ): IssueApplicationService =
            IssueApplicationService(
                repo = repo,
                issueTypeRepository = issueTypeRepository,
                resolutionRepository = resolutionRepository,
                eventPublisher = eventPublisher,
                permissionResolver = permissionResolver,
                workflowPort = workflowTransitionAdapter,
                workflowKeyResolver = workflowKeyResolver,
                userLookupPort = userLookupPort,
                componentRepository = componentRepository,
                projectLeadRepository = mockk(relaxed = true),
                versionRepository = mockk(relaxed = true),
                clock = clock,
                historyRecorder = mockk(relaxed = true),
            )

        @Bean
        open fun issueImportAdapter(
            issueApplicationService: IssueApplicationService,
            issueRepository: IssueRepository,
            issueTypeRepository: IssueTypeRepository,
            componentRepository: ComponentRepository,
            userLookupPort: UserLookupPort,
            permissionResolver: IssuePermissionResolver,
        ): IssueImportAdapter =
            IssueImportAdapter(
                issueApplicationService = issueApplicationService,
                issueRepository = issueRepository,
                issueTypeRepository = issueTypeRepository,
                componentRepository = componentRepository,
                userLookupPort = userLookupPort,
                permissionResolver = permissionResolver,
            )
    }

    /** CREATE_ISSUE 권한을 지정 actor 에게만 거부하고 그 외에는 모두 허용하는 테스트 전용 resolver. */
    private class SelectiveDenyPermissionResolver(private val deniedActorId: UUID) : IssuePermissionResolver {
        override fun hasPermission(
            actorId: UUID,
            permission: IssuePermission,
            scope: IssueScope,
        ): Boolean = !(actorId == deniedActorId && permission == IssuePermission.CREATE)
    }

    /** 고정 fixture 이메일→UUID 매핑을 제공하는 테스트 전용 [UserLookupPort]. */
    private class FakeImportUserLookupPort : UserLookupPort {
        override fun exists(userId: UUID): Boolean = true

        override fun resolveByEmails(emails: Set<String>): Map<String, UUID> =
            FIXTURES.filterKeys { it in emails }

        companion object {
            private val FIXTURES =
                mapOf(
                    ALICE_EMAIL to ALICE_ID,
                    BOB_EMAIL to BOB_ID,
                )
        }
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var issueImportAdapter: IssueImportAdapter

    @Autowired
    lateinit var issueRepository: IssueRepository

    companion object {
        private const val PROJECT_KEY = "IMPORT"

        /** CREATE_ISSUE 권한을 보유한 정상 요청자. */
        val NORMAL_REQUESTER_ID: UUID = UUID.fromString("00000000-0000-4000-8000-000000000201")

        /** [SelectiveDenyPermissionResolver] 가 CREATE_ISSUE 를 거부하도록 지정한 요청자. */
        val FORBIDDEN_REQUESTER_ID: UUID = UUID.fromString("00000000-0000-4000-8000-000000000202")

        /** reporterEmail 매칭 fixture. */
        val ALICE_ID: UUID = UUID.fromString("00000000-0000-4000-8000-000000000203")
        const val ALICE_EMAIL = "alice@example.com"

        /** assigneeEmail 매칭 fixture. */
        val BOB_ID: UUID = UUID.fromString("00000000-0000-4000-8000-000000000204")
        const val BOB_EMAIL = "bob@example.com"

        private var migrated = false
        private var seeded = false

        /** S5 — 매칭 대상 컴포넌트. */
        lateinit var backendComponentId: UUID
    }

    @BeforeAll
    fun setUpAll() {
        if (!migrated) {
            applyMigrations()
            migrated = true
        }
        if (!seeded) {
            seedProjectAndComponents()
            seeded = true
        }
    }

    @BeforeEach
    fun cleanState() {
        conn().use { c ->
            c.createStatement().use { stmt ->
                stmt.execute(
                    "DELETE FROM issue_components WHERE issue_id IN " +
                        "(SELECT id FROM issues WHERE key LIKE '$PROJECT_KEY-%')",
                )
                stmt.execute("DELETE FROM issues WHERE key LIKE '$PROJECT_KEY-%'")
                stmt.execute("UPDATE projects SET key_sequence = 0 WHERE key = '$PROJECT_KEY'")
                stmt.execute("SELECT pgmq.purge_queue('q_issue_events')")
            }
        }
    }

    // ── S1. 코어 필드 + typeName 매칭 + priority/labels/assignee 반영 ─────────────

    @Test
    fun `S1 코어 필드 cmd - 이슈 생성 성공과 issueKey 반환 및 priority labels assignee typeName 반영`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = NORMAL_REQUESTER_ID,
                summary = "S1 코어 필드 임포트 테스트",
                typeName = "bug",
                priority = 1,
                assigneeEmail = BOB_EMAIL,
                labels = listOf("backend", "urgent"),
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Success) { "Success 여야 하지만 $result 입니다." }
        assert(result.issueKey.startsWith("$PROJECT_KEY-")) {
            "issueKey 가 $PROJECT_KEY- 로 시작해야 하지만 ${result.issueKey} 입니다."
        }

        val saved = issueRepository.findByKey(com.bts.issue.domain.IssueKey(result.issueKey))
        checkNotNull(saved) { "생성된 이슈를 DB 에서 찾을 수 없습니다: ${result.issueKey}" }
        assert(saved.priority == 1) { "priority 가 1 이어야 하지만 ${saved.priority} 입니다." }
        assert(saved.labels == listOf("backend", "urgent")) {
            "labels 가 [backend, urgent] 여야 하지만 ${saved.labels} 입니다."
        }
        assert(saved.assigneeId?.value == BOB_ID) {
            "assigneeId 가 Bob 이어야 하지만 ${saved.assigneeId?.value} 입니다."
        }
        assert(saved.reporterId.value == NORMAL_REQUESTER_ID) {
            "reporterEmail 미지정이므로 reporterId 가 requesterUserId 여야 하지만 ${saved.reporterId.value} 입니다."
        }

        val typeKey = fetchTypeKey(result.issueKey)
        assert(typeKey == "bug") { "typeName='bug' 매칭이므로 typeKey='bug' 여야 하지만 $typeKey 입니다." }
    }

    // ── S2. reporterEmail 매칭/미매칭 ────────────────────────────────────────────

    @Test
    fun `S2 reporterEmail 매칭 - reporterId가 매칭된 사용자로 설정된다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = NORMAL_REQUESTER_ID,
                summary = "S2 reporter 매칭 테스트",
                reporterEmail = ALICE_EMAIL,
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Success) { "Success 여야 하지만 $result 입니다." }
        val saved = issueRepository.findByKey(com.bts.issue.domain.IssueKey(result.issueKey))
        checkNotNull(saved)
        assert(saved.reporterId.value == ALICE_ID) {
            "reporterEmail 매칭이므로 reporterId 가 Alice 여야 하지만 ${saved.reporterId.value} 입니다."
        }
    }

    @Test
    fun `S2 reporterEmail 미매칭 - reporterId가 requesterUserId로 폴백된다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = NORMAL_REQUESTER_ID,
                summary = "S2 reporter 미매칭 테스트",
                reporterEmail = "unknown@example.com",
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Success) { "Success 여야 하지만 $result 입니다." }
        val saved = issueRepository.findByKey(com.bts.issue.domain.IssueKey(result.issueKey))
        checkNotNull(saved)
        assert(saved.reporterId.value == NORMAL_REQUESTER_ID) {
            "reporterEmail 미매칭이므로 reporterId 가 requesterUserId 로 폴백돼야 하지만 ${saved.reporterId.value} 입니다."
        }
    }

    // ── S3. assigneeEmail 매칭/미매칭 ────────────────────────────────────────────

    @Test
    fun `S3 assigneeEmail 매칭 - assigneeId가 매칭된 사용자로 설정된다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = NORMAL_REQUESTER_ID,
                summary = "S3 assignee 매칭 테스트",
                assigneeEmail = BOB_EMAIL,
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Success) { "Success 여야 하지만 $result 입니다." }
        val saved = issueRepository.findByKey(com.bts.issue.domain.IssueKey(result.issueKey))
        checkNotNull(saved)
        assert(saved.assigneeId?.value == BOB_ID) {
            "assigneeEmail 매칭이므로 assigneeId 가 Bob 이어야 하지만 ${saved.assigneeId?.value} 입니다."
        }
    }

    @Test
    fun `S3 assigneeEmail 미매칭 - assigneeId가 null로 유지된다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = NORMAL_REQUESTER_ID,
                summary = "S3 assignee 미매칭 테스트",
                assigneeEmail = "unknown@example.com",
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Success) { "Success 여야 하지만 $result 입니다." }
        val saved = issueRepository.findByKey(com.bts.issue.domain.IssueKey(result.issueKey))
        checkNotNull(saved)
        assert(saved.assigneeId == null) {
            "assigneeEmail 미매칭이므로 assigneeId 가 null 이어야 하지만 ${saved.assigneeId?.value} 입니다."
        }
    }

    // ── S4. typeName 미매칭 → Task 폴백 + warning ────────────────────────────────

    @Test
    fun `S4 typeName 미매칭 - Task 타입으로 폴백하고 warning을 남긴다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = NORMAL_REQUESTER_ID,
                summary = "S4 typeName 미매칭 테스트",
                typeName = "Improvement",
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Success) { "Success 여야 하지만 $result 입니다." }
        assert(result.warnings.isNotEmpty()) { "typeName 미매칭 warning 이 있어야 하지만 비어 있습니다." }

        val typeKey = fetchTypeKey(result.issueKey)
        assert(typeKey == "task") { "typeName 미매칭이므로 Task 로 폴백돼야 하지만 typeKey=$typeKey 입니다." }
    }

    // ── S5. componentNames 일부 미발견 → 매칭분만 연결 + warning ──────────────────

    @Test
    fun `S5 componentNames 일부 미발견 - 매칭된 컴포넌트만 연결하고 미발견 이름은 warning으로 남긴다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = NORMAL_REQUESTER_ID,
                summary = "S5 component 매칭 테스트",
                componentNames = listOf("backend", "Nonexistent"),
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Success) { "Success 여야 하지만 $result 입니다." }
        assert(result.warnings.any { it.contains("Nonexistent") }) {
            "미발견 컴포넌트 이름에 대한 warning 이 있어야 하지만 ${result.warnings} 입니다."
        }

        val connectedComponentIds = fetchComponentIds(result.issueKey)
        assert(connectedComponentIds == setOf(backendComponentId)) {
            "매칭된 backend 컴포넌트만 연결돼야 하지만 $connectedComponentIds 입니다."
        }
    }

    // ── S6. CREATE_ISSUE 권한 없는 requester → Failure(FORBIDDEN), 이슈 미생성 ────

    @Test
    fun `S6 CREATE_ISSUE 권한 없는 requester - Failure FORBIDDEN 이고 이슈가 생성되지 않는다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = FORBIDDEN_REQUESTER_ID,
                summary = "S6 권한 없음 테스트",
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Failure) { "Failure 여야 하지만 $result 입니다." }
        assert(result.reasonCode == IssueImportResult.FORBIDDEN) {
            "reasonCode 가 FORBIDDEN 이어야 하지만 ${result.reasonCode} 입니다."
        }
        assert(countImportIssues() == 0) { "권한 거부 시 이슈가 생성되지 않아야 하지만 ${countImportIssues()} 개 존재합니다." }
    }

    // ── S7. dryRun=true → 이슈/이벤트 미생성, 검증 결과만 반환 ───────────────────

    @Test
    fun `S7 dryRun true - 이슈를 생성하지 않고 검증 결과만 반환한다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = NORMAL_REQUESTER_ID,
                summary = "S7 dryRun 테스트",
                priority = 2,
                dryRun = true,
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Success) { "Success 여야 하지만 $result 입니다." }
        assert(result.issueKey == IssueImportAdapter.DRY_RUN_MARKER) {
            "dryRun 은 마커 값을 반환해야 하지만 ${result.issueKey} 입니다."
        }
        assert(countImportIssues() == 0) { "dryRun 은 이슈를 생성하지 않아야 하지만 ${countImportIssues()} 개 존재합니다." }
        assert(fetchKeySequence() == 0L) { "dryRun 은 key_sequence 를 증가시키지 않아야 하지만 ${fetchKeySequence()} 입니다." }
    }

    // ── S8(CONCERN #1). update 강제 실패 → 이슈 롤백 + IssueCreated 이벤트 미발행 ──

    /**
     * 행 원자성 — create 는 성공하지만 후속 updateIssue(priority 범위 밖=99)가
     * IllegalArgumentException 을 던지는 경우, 트랜잭션 전체가 롤백돼야 한다.
     *
     * Given priority=99(1..5 범위 밖)
     * When  importIssue 호출
     * Then  Failure(VALIDATION) 반환
     * And   이슈가 DB 에 남아있지 않음(create 롤백)
     * And   q_issue_events 큐에 IssueCreated 메시지가 없음(pgmq.send 도 같은 트랜잭션이라 함께 롤백)
     */
    @Test
    fun `S8 update 강제 실패시 이슈가 롤백되고 IssueCreated 이벤트가 발행되지 않는다`() {
        val cmd =
            IssueImportCommand(
                projectKey = PROJECT_KEY,
                requesterUserId = NORMAL_REQUESTER_ID,
                summary = "S8 행원자성 테스트",
                priority = 99,
            )

        val result = issueImportAdapter.importIssue(cmd)

        check(result is IssueImportResult.Failure) { "Failure 여야 하지만 $result 입니다." }
        assert(result.reasonCode == IssueImportResult.VALIDATION) {
            "reasonCode 가 VALIDATION 이어야 하지만 ${result.reasonCode} 입니다."
        }
        assert(countImportIssues() == 0) {
            "update 실패 시 create 까지 롤백되어 이슈가 없어야 하지만 ${countImportIssues()} 개 존재합니다."
        }
        assert(readIssueEventsQueue().isEmpty()) {
            "롤백된 트랜잭션의 IssueCreated 이벤트는 q_issue_events 에 존재하지 않아야 합니다."
        }
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private fun applyMigrations() {
        Flyway.configure()
            .dataSource(
                TestConfig.postgres.jdbcUrl,
                TestConfig.postgres.username,
                TestConfig.postgres.password,
            )
            .placeholderReplacement(false)
            .locations(
                "classpath:db/migration/issue-tracking",
                "classpath:db/migration/project-workflow",
            )
            .load()
            .migrate()
    }

    @Suppress("LongMethod")
    private fun seedProjectAndComponents() {
        conn().use { c ->
            c.autoCommit = false
            c.prepareStatement(
                "INSERT INTO projects (key, name) VALUES (?, ?) ON CONFLICT (key) DO NOTHING",
            ).use { stmt ->
                stmt.setString(1, PROJECT_KEY)
                stmt.setString(2, "Import Adapter Integration Test Project")
                stmt.executeUpdate()
            }

            // software-default 워크플로우 + open 상태 (createIssue 의 resolveStart 가 필요로 함)
            val wfId =
                c.prepareStatement(
                    "INSERT INTO workflows (key, name) VALUES ('software-default', '소프트웨어 개발 기본 워크플로우') " +
                        "ON CONFLICT (key) DO UPDATE SET name = EXCLUDED.name RETURNING id",
                ).use { stmt ->
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getObject(1) as UUID
                    }
                }

            c.prepareStatement(
                "INSERT INTO workflow_states (workflow_id, key, name, category, display_order) " +
                    "VALUES (?, 'open', 'Open', 'TODO', 0) " +
                    "ON CONFLICT (workflow_id, key) DO UPDATE SET display_order = EXCLUDED.display_order",
            ).use { stmt ->
                stmt.setObject(1, wfId)
                stmt.executeUpdate()
            }

            // import-scheme 독립 생성 — 다른 통합 테스트의 scheme 과 충돌 방지(싱글톤 DB 공유)
            c.createStatement().use { stmt ->
                stmt.execute(
                    """
                    INSERT INTO workflow_schemes (key, name, is_default)
                    VALUES ('import-scheme', 'Import Adapter Test Scheme', false)
                    ON CONFLICT (key) DO NOTHING
                    """.trimIndent(),
                )
            }

            c.createStatement().use { stmt ->
                stmt.execute(
                    """
                    INSERT INTO workflow_scheme_issue_type_mappings (scheme_id, issue_type_id, workflow_id)
                    SELECT s.id, NULL, '$wfId'
                    FROM workflow_schemes s
                    WHERE s.key = 'import-scheme'
                      AND NOT EXISTS (
                        SELECT 1 FROM workflow_scheme_issue_type_mappings m
                        WHERE m.scheme_id = s.id AND m.issue_type_id IS NULL
                      )
                    """.trimIndent(),
                )
            }

            c.createStatement().use { stmt ->
                stmt.execute(
                    """
                    INSERT INTO project_workflow_scheme_assignments (project_id, workflow_scheme_id, assigned_at, assigned_by)
                    SELECT p.id, s.id, NOW(), '00000000-0000-0000-0000-000000000000'::uuid
                    FROM projects p, workflow_schemes s
                    WHERE p.key = '$PROJECT_KEY'
                      AND s.key = 'import-scheme'
                    ON CONFLICT (project_id) DO NOTHING
                    """.trimIndent(),
                )
            }

            c.commit()
        }

        backendComponentId = insertComponent(PROJECT_KEY, "Backend")
    }

    private fun insertComponent(
        projectKey: String,
        name: String,
    ): UUID {
        val projectId = fetchProjectId(projectKey)
        return conn().use { c ->
            c.prepareStatement(
                "INSERT INTO components (project_id, name) VALUES (?, ?) ON CONFLICT DO NOTHING RETURNING id",
            ).use { stmt ->
                stmt.setObject(1, projectId)
                stmt.setString(2, name)
                stmt.executeQuery().use { rs ->
                    if (rs.next()) return rs.getObject(1) as UUID
                }
            }
            fetchComponentId(projectId, name)
        }
    }

    private fun fetchComponentId(
        projectId: UUID,
        name: String,
    ): UUID =
        conn().use { c ->
            c.prepareStatement("SELECT id FROM components WHERE project_id = ? AND name = ?").use { stmt ->
                stmt.setObject(1, projectId)
                stmt.setString(2, name)
                stmt.executeQuery().use { rs ->
                    check(rs.next()) { "$name 컴포넌트가 없습니다." }
                    rs.getObject(1) as UUID
                }
            }
        }

    private fun fetchProjectId(projectKey: String): UUID =
        conn().use { c ->
            c.prepareStatement("SELECT id FROM projects WHERE key = ?").use { stmt ->
                stmt.setString(1, projectKey)
                stmt.executeQuery().use { rs ->
                    check(rs.next()) { "$projectKey 프로젝트가 없습니다." }
                    rs.getObject(1) as UUID
                }
            }
        }

    private fun fetchTypeKey(issueKey: String): String? =
        conn().use { c ->
            c.prepareStatement(
                "SELECT it.key FROM issues i JOIN issue_types it ON i.type_id = it.id WHERE i.key = ?",
            ).use { stmt ->
                stmt.setString(1, issueKey)
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    rs.getString(1)
                }
            }
        }

    private fun fetchComponentIds(issueKey: String): Set<UUID> =
        conn().use { c ->
            c.prepareStatement(
                "SELECT ic.component_id FROM issue_components ic JOIN issues i ON ic.issue_id = i.id WHERE i.key = ?",
            ).use { stmt ->
                stmt.setString(1, issueKey)
                stmt.executeQuery().use { rs ->
                    val ids = mutableSetOf<UUID>()
                    while (rs.next()) ids += rs.getObject(1) as UUID
                    ids
                }
            }
        }

    private fun countImportIssues(): Int =
        conn().use { c ->
            c.prepareStatement("SELECT COUNT(*) FROM issues WHERE key LIKE '$PROJECT_KEY-%'").use { stmt ->
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    private fun fetchKeySequence(): Long =
        conn().use { c ->
            c.prepareStatement("SELECT key_sequence FROM projects WHERE key = ?").use { stmt ->
                stmt.setString(1, PROJECT_KEY)
                stmt.executeQuery().use { rs ->
                    check(rs.next()) { "$PROJECT_KEY 프로젝트가 없습니다." }
                    rs.getLong(1)
                }
            }
        }

    /** q_issue_events 큐에서 최대 10건을 읽어 반환한다(visibility_timeout=1초). */
    private fun readIssueEventsQueue(): List<String> =
        conn().use { c ->
            c.prepareStatement("SELECT message FROM pgmq.read(?, ?, ?)").use { stmt ->
                stmt.setString(1, "q_issue_events")
                stmt.setInt(2, 1)
                stmt.setInt(3, 10)
                stmt.executeQuery().use { rs ->
                    val messages = mutableListOf<String>()
                    while (rs.next()) messages += rs.getString(1)
                    messages
                }
            }
        }

    private fun conn() =
        DriverManager.getConnection(
            TestConfig.postgres.jdbcUrl,
            TestConfig.postgres.username,
            TestConfig.postgres.password,
        )
}
