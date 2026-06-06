// createIssue 컴포넌트 자동 담당자 배정 + 컴포넌트 링크 영속 통합 테스트 (FR-CM-03 Task 4 / FR-CM-04 Task 5)

package com.bts.issue.application

import com.bts.issue.adapter.inbound.rest.IssueControllerTransitionIntegrationTest.TestConfig
import com.bts.issue.component.repository.ComponentRepository
import com.bts.issue.event.IssueEventPublisher
import com.bts.issue.project.repository.ProjectLeadRepository
import com.bts.issue.repository.IssueRepository
import com.bts.issue.resolution.repository.ResolutionRepository
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.user.UserLookupPort
import com.bts.workflow.adapter.inbound.WorkflowTransitionAdapter
import com.bts.workflow.scheme.adapter.inbound.WorkflowKeyResolverImpl
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
 * createIssue 컴포넌트 자동 담당자 배정 전 구간 Testcontainers 통합 테스트 (FR-CM-03 Task 4).
 *
 * 컨트롤러 → 서비스 → 리포지토리 → 실 PostgreSQL 전 구간을 검증한다.
 * 단위 mock 이 못 잡는 결선·트랜잭션 경계·version=1 계약을 실증한다.
 *
 * ## 검증 시나리오
 * - S1. 리드 Alice 인 컴포넌트로 생성 → assignee=Alice + componentIds 영속 + version=1
 * - S2. 컴포넌트 리드 없음 + 프로젝트 리드 지정 → 프로젝트 리드 배정 (FR-CM-04 Task 5 RED)
 * - S3. 컴포넌트 없는 이슈 + 프로젝트 리드 → 프로젝트 리드 배정 (FR-CM-04 Task 5 RED)
 * - S4. 리드 다른 두 컴포넌트(이름 다름) → 이름 사전순 첫 번째의 리드가 assignee
 * - S4-fallback. 컴포넌트 리드 없음 + 프로젝트 리드 없음 → null (FR-CM-04 Task 5 RED)
 * - S5. 리드 null 컴포넌트로 생성 → assignee null
 * - noComponent. 컴포넌트 없이 생성 → assignee null, componentIds 빈
 * - invalidOtherProject. 다른 프로젝트 컴포넌트 → 422 IssueComponentNotFoundException
 * - invalidDeleted. 소프트삭제 컴포넌트 → 422 IssueComponentNotFoundException
 * - regression. 기존 createIssue (컴포넌트 없음) 회귀 0 — assignee null, version=1
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(
    classes = [TestConfig::class, IssueCreateComponentAutoAssignIntegrationTest.AutoAssignTestConfig::class],
)
@WebAppConfiguration
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IssueCreateComponentAutoAssignIntegrationTest {
    /**
     * 자동 담당자 배정 통합 테스트 보조 설정.
     *
     * 실 ComponentRepository + ProjectLeadRepository + IssueApplicationService 재조립.
     * TestConfig 의 componentRepository=mockk(relaxed=true) 를 실 DB 로 대체한다.
     */
    @Configuration
    @Suppress("LongParameterList")
    open class AutoAssignTestConfig {
        @Bean
        open fun realComponentRepository(dsl: DSLContext): ComponentRepository = ComponentRepository(dsl)

        @Bean
        open fun realProjectLeadRepository(dsl: DSLContext): ProjectLeadRepository = ProjectLeadRepository(dsl)

        @Bean
        @Primary
        open fun issueApplicationServiceWithRealComponents(
            repo: IssueRepository,
            issueTypeRepository: IssueTypeRepository,
            resolutionRepository: ResolutionRepository,
            eventPublisher: IssueEventPublisher,
            permissionResolver: IssuePermissionResolver,
            workflowTransitionAdapter: WorkflowTransitionAdapter,
            workflowKeyResolver: WorkflowKeyResolverImpl,
            userLookupPort: UserLookupPort,
            componentRepository: ComponentRepository,
            projectLeadRepository: ProjectLeadRepository,
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
                projectLeadRepository = projectLeadRepository,
                clock = clock,
            )
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var issueApplicationService: IssueApplicationService

    companion object {
        private const val PROJECT_KEY = "AUTOASSIGN"
        private const val OTHER_PROJECT_KEY = "OTHERASGN"

        /** Alice — S1/S4 시나리오용 리드 UUID */
        val ALICE_ID: UUID = UUID.fromString("00000000-0000-4000-8000-000000000101")

        /** Bob — S4 시나리오 두 번째 컴포넌트 리드 UUID */
        val BOB_ID: UUID = UUID.fromString("00000000-0000-4000-8000-000000000102")

        /** Dave — S2/S3/S4-fallback 프로젝트 리드 UUID (FR-CM-04 Task 5) */
        val PROJECT_LEAD_ID: UUID = UUID.fromString("00000000-0000-4000-8000-000000000104")

        /** 이슈 생성 actor */
        val ACTOR_ID = com.bts.issue.domain.ActorId(UUID.fromString("00000000-0000-4000-8000-000000000001"))

        private var migrated = false
        private var seeded = false

        /** S1 — 리드 Alice 인 컴포넌트 */
        lateinit var compWithAliceLead: UUID

        /** S4 — 이름 사전순 첫 번째(Alpha), 리드 Alice */
        lateinit var compAlpha: UUID

        /** S4 — 이름 사전순 두 번째(Zeta), 리드 Bob */
        lateinit var compZeta: UUID

        /** S5/S2 — 리드 없는 컴포넌트 */
        lateinit var compNoLead: UUID

        /** invalidOtherProject — OTHERASGN 소속 컴포넌트 */
        lateinit var compOtherProject: UUID
    }

    @BeforeAll
    fun setUpAll() {
        if (!migrated) {
            applyMigrations()
            migrated = true
        }
        if (!seeded) {
            seedProjectsAndComponents()
            seeded = true
        }
    }

    @BeforeEach
    fun cleanIssues() {
        conn().use { c ->
            c.createStatement().use { stmt ->
                stmt.execute(
                    "DELETE FROM issue_components WHERE issue_id IN " +
                        "(SELECT id FROM issues WHERE key LIKE '$PROJECT_KEY-%')",
                )
                stmt.execute("DELETE FROM issues WHERE key LIKE '$PROJECT_KEY-%'")
                stmt.execute("UPDATE projects SET key_sequence = 0 WHERE key = '$PROJECT_KEY'")
                // FR-CM-04 Task 5: 테스트 간 리드 격리 — 기본값 null 로 리셋
                stmt.execute("UPDATE projects SET lead_user_id = NULL WHERE key = '$PROJECT_KEY'")
            }
        }
    }

    // ── S1. 리드 Alice 인 컴포넌트로 생성 → assignee=Alice + componentIds 영속 + version=1 ─

    /**
     * S1 자동 배정 해피패스.
     *
     * Given  AUTOASSIGN 프로젝트, 리드=Alice 인 컴포넌트
     * When   createIssue(componentIds=[compWithAliceLead])
     * Then   반환 이슈 assigneeId=Alice
     * And    DB issues.assignee_id=Alice
     * And    issue_components 1행 존재
     * And    version=1 (replaceComponents 의 version bump 없음)
     */
    @Test
    fun `S1 리드 Alice 컴포넌트로 생성 - assignee Alice와 componentIds 영속 및 version 1 확인`() {
        val request =
            CreateIssueRequest(
                projectKey = PROJECT_KEY,
                summary = "S1 자동 배정 테스트",
                reporterId = ACTOR_ID,
                componentIds = listOf(compWithAliceLead),
            )

        val issue = issueApplicationService.createIssue(ACTOR_ID, request)

        assert(issue.assigneeId?.value == ALICE_ID) {
            "assigneeId 가 Alice 여야 하지만 ${issue.assigneeId?.value} 입니다."
        }
        assert(issue.version == 1L) {
            "version 이 1 이어야 하지만 ${issue.version} 입니다."
        }

        val dbAssignee = fetchAssigneeId(issue.key.value)
        assert(dbAssignee == ALICE_ID) {
            "DB assignee_id 가 Alice 여야 하지만 $dbAssignee 입니다."
        }

        val compCount = countIssueComponents(issue.key.value)
        assert(compCount == 1) {
            "issue_components 행이 1이어야 하지만 $compCount 입니다."
        }

        val dbVersion = fetchVersion(issue.key.value)
        assert(dbVersion == 1L) {
            "DB version 이 1 이어야 하지만 $dbVersion 입니다."
        }
    }


    // ── S2. 컴포넌트 리드 없음 + 프로젝트 리드 → 프로젝트 리드 배정 (FR-CM-04 Task 5) ──

    /**
     * S2 프로젝트 리드 폴백.
     *
     * Given  AUTOASSIGN 프로젝트에 리드=Dave 지정, 리드 없는 컴포넌트
     * When   createIssue(componentIds=[compNoLead])
     * Then   assigneeId=Dave (컴포넌트 리드 없으므로 프로젝트 리드 폴백)
     */
    @Test
    fun `S2 컴포넌트 리드 없음 + 프로젝트 리드 지정 - 프로젝트 리드 배정`() {
        setProjectLead(PROJECT_KEY, PROJECT_LEAD_ID)

        val request =
            CreateIssueRequest(
                projectKey = PROJECT_KEY,
                summary = "S2 프로젝트 리드 폴백 테스트",
                reporterId = ACTOR_ID,
                componentIds = listOf(compNoLead),
            )

        val issue = issueApplicationService.createIssue(ACTOR_ID, request)

        assert(issue.assigneeId?.value == PROJECT_LEAD_ID) {
            "프로젝트 리드 Dave 가 assignee 여야 하지만 ${issue.assigneeId?.value} 입니다."
        }

        val dbAssignee = fetchAssigneeId(issue.key.value)
        assert(dbAssignee == PROJECT_LEAD_ID) {
            "DB assignee_id 가 Dave 여야 하지만 $dbAssignee 입니다."
        }
    }

    // ── S3. 컴포넌트 없는 이슈 + 프로젝트 리드 → 프로젝트 리드 배정 (FR-CM-04 Task 5) ──

    /**
     * S3 컴포넌트 없음 + 프로젝트 리드 폴백.
     *
     * Given  AUTOASSIGN 프로젝트에 리드=Dave 지정
     * When   createIssue(componentIds=[])
     * Then   assigneeId=Dave (빈 컴포넌트 경로도 프로젝트 리드 폴백)
     */
    @Test
    fun `S3 컴포넌트 없는 이슈 + 프로젝트 리드 - 프로젝트 리드 배정`() {
        setProjectLead(PROJECT_KEY, PROJECT_LEAD_ID)

        val request =
            CreateIssueRequest(
                projectKey = PROJECT_KEY,
                summary = "S3 컴포넌트 없음 + 프로젝트 리드 테스트",
                reporterId = ACTOR_ID,
                componentIds = emptyList(),
            )

        val issue = issueApplicationService.createIssue(ACTOR_ID, request)

        assert(issue.assigneeId?.value == PROJECT_LEAD_ID) {
            "프로젝트 리드 Dave 가 assignee 여야 하지만 ${issue.assigneeId?.value} 입니다."
        }
        assert(issue.version == 1L) {
            "version 이 1 이어야 하지만 ${issue.version} 입니다."
        }

        val compCount = countIssueComponents(issue.key.value)
        assert(compCount == 0) {
            "컴포넌트 없는 이슈의 issue_components 행이 0이어야 하지만 $compCount 입니다."
        }
    }

        // ── S4-fallback. 컴포넌트 리드 없음 + 프로젝트 리드 없음 → null (FR-CM-04 Task 5) ──

    /**
     * S4-fallback 둘 다 없음 → 미할당.
     *
     * Given  프로젝트 리드=null, 리드 없는 컴포넌트
     * When   createIssue(componentIds=[compNoLead])
     * Then   assigneeId=null
     */
    @Test
    fun `S4-fallback 컴포넌트 리드 없음 + 프로젝트 리드 없음 - assignee null`() {
        // cleanIssues 에서 lead_user_id=null 로 리셋됨 — 추가 설정 불필요

        val request =
            CreateIssueRequest(
                projectKey = PROJECT_KEY,
                summary = "S4-fallback 둘 다 없음 테스트",
                reporterId = ACTOR_ID,
                componentIds = listOf(compNoLead),
            )

        val issue = issueApplicationService.createIssue(ACTOR_ID, request)

        assert(issue.assigneeId == null) {
            "컴포넌트/프로젝트 리드 모두 없으면 assigneeId=null 이어야 하지만 \${issue.assigneeId?.value} 입니다."
        }
    }

    // ── S4. 리드 다른 두 컴포넌트 → 이름 사전순 첫 번째의 리드가 assignee ────────

    /**
     * S4 두 컴포넌트 — 이름 오름차순 우선순위.
     *
     * Given  Alpha(리드=Alice), Zeta(리드=Bob) 두 컴포넌트
     * When   createIssue(componentIds=[compAlpha, compZeta])
     * Then   assigneeId=Alice (Alpha 가 Zeta 보다 사전순 먼저)
     */
    @Test
    fun `S4 두 컴포넌트 이름 사전순 - 첫 번째 리드가 assignee`() {
        val request =
            CreateIssueRequest(
                projectKey = PROJECT_KEY,
                summary = "S4 이름순 배정 테스트",
                reporterId = ACTOR_ID,
                componentIds = listOf(compAlpha, compZeta),
            )

        val issue = issueApplicationService.createIssue(ACTOR_ID, request)

        assert(issue.assigneeId?.value == ALICE_ID) {
            "이름순 첫 번째 컴포넌트 Alpha 의 리드 Alice 가 assignee 여야 하지만 ${issue.assigneeId?.value} 입니다."
        }
        assert(issue.version == 1L) {
            "version 이 1 이어야 하지만 ${issue.version} 입니다."
        }

        val compCount = countIssueComponents(issue.key.value)
        assert(compCount == 2) {
            "issue_components 행이 2이어야 하지만 $compCount 입니다."
        }
    }

    // ── S5. 리드 null 컴포넌트로 생성 → assignee null ────────────────────────

    /**
     * S5 리드 없는 컴포넌트 — assignee null.
     *
     * Given  리드=null 인 컴포넌트
     * When   createIssue(componentIds=[compNoLead])
     * Then   assigneeId=null (자동 배정 미발동)
     * And    issue_components 1행 영속
     */
    @Test
    fun `S5 리드 없는 컴포넌트로 생성 - assignee null`() {
        val request =
            CreateIssueRequest(
                projectKey = PROJECT_KEY,
                summary = "S5 리드 없는 컴포넌트 테스트",
                reporterId = ACTOR_ID,
                componentIds = listOf(compNoLead),
            )

        val issue = issueApplicationService.createIssue(ACTOR_ID, request)

        assert(issue.assigneeId == null) {
            "리드 없는 컴포넌트는 assigneeId=null 이어야 하지만 ${issue.assigneeId?.value} 입니다."
        }

        val compCount = countIssueComponents(issue.key.value)
        assert(compCount == 1) {
            "issue_components 행이 1이어야 하지만 $compCount 입니다."
        }
    }

    // ── noComponent. 컴포넌트 없이 생성 → assignee null, componentIds 빈 ──────

    /**
     * 컴포넌트 없이 생성 — 기존 회귀.
     *
     * Given  componentIds=빈 리스트
     * When   createIssue(componentIds=[])
     * Then   assigneeId=null + version=1 + issue_components 0행
     */
    @Test
    fun `컴포넌트 없이 생성 - assignee null과 version 1 확인 (회귀)`() {
        val request =
            CreateIssueRequest(
                projectKey = PROJECT_KEY,
                summary = "컴포넌트 없는 이슈 회귀 테스트",
                reporterId = ACTOR_ID,
                componentIds = emptyList(),
            )

        val issue = issueApplicationService.createIssue(ACTOR_ID, request)

        assert(issue.assigneeId == null) {
            "컴포넌트 없는 이슈는 assigneeId=null 이어야 하지만 ${issue.assigneeId?.value} 입니다."
        }
        assert(issue.version == 1L) {
            "version 이 1 이어야 하지만 ${issue.version} 입니다."
        }

        val compCount = countIssueComponents(issue.key.value)
        assert(compCount == 0) {
            "issue_components 행이 0이어야 하지만 $compCount 입니다."
        }
    }

    // ── invalidOtherProject. 다른 프로젝트 컴포넌트 → 422 ──────────────────────

    /**
     * 타 프로젝트 컴포넌트 → IssueComponentNotFoundException.
     *
     * Given  OTHERASGN 소속 컴포넌트
     * When   AUTOASSIGN 이슈 생성 시 그 컴포넌트 지정
     * Then   IssueComponentNotFoundException (422)
     */
    @Test
    @Suppress("SwallowedException")
    fun `타 프로젝트 컴포넌트 - IssueComponentNotFoundException 422`() {
        val request =
            CreateIssueRequest(
                projectKey = PROJECT_KEY,
                summary = "타 프로젝트 컴포넌트 테스트",
                reporterId = ACTOR_ID,
                componentIds = listOf(compOtherProject),
            )

        try {
            issueApplicationService.createIssue(ACTOR_ID, request)
            assert(false) { "IssueComponentNotFoundException 이 발생해야 합니다." }
        } catch (e: com.bts.issue.domain.IssueComponentNotFoundException) {
            // 422 IssueComponentNotFoundException 정상 발생 — 예외 삼킴 의도적 (검증 완료)
        }

        val issueCount = countIssues()
        assert(issueCount == 0) {
            "예외 발생 시 이슈가 생성되지 않아야 하지만 $issueCount 개 존재합니다."
        }
    }

    // ── invalidDeleted. 소프트삭제 컴포넌트 → 422 ──────────────────────────────

    /**
     * 소프트삭제 컴포넌트 → IssueComponentNotFoundException.
     *
     * Given  소프트삭제된 컴포넌트 UUID
     * When   createIssue 시 그 컴포넌트 지정
     * Then   IssueComponentNotFoundException (422)
     */
    @Test
    @Suppress("SwallowedException")
    fun `소프트삭제 컴포넌트 - IssueComponentNotFoundException 422`() {
        val deletedComp = insertComponent(PROJECT_KEY, "DeletedComp-${UUID.randomUUID()}", null)
        softDeleteComponent(deletedComp)

        val request =
            CreateIssueRequest(
                projectKey = PROJECT_KEY,
                summary = "소프트삭제 컴포넌트 테스트",
                reporterId = ACTOR_ID,
                componentIds = listOf(deletedComp),
            )

        try {
            issueApplicationService.createIssue(ACTOR_ID, request)
            assert(false) { "IssueComponentNotFoundException 이 발생해야 합니다." }
        } catch (e: com.bts.issue.domain.IssueComponentNotFoundException) {
            // 422 정상 발생 — 예외 삼킴 의도적 (검증 완료)
        }
    }

    // ── private helpers ───────────────────────────────────────────────────────

    /** 프로젝트 lead_user_id 를 설정한다. null 이면 리드 해제. */
    private fun setProjectLead(
        projectKey: String,
        leadUserId: UUID?,
    ) {
        conn().use { c ->
            c.prepareStatement("UPDATE projects SET lead_user_id = ? WHERE key = ?").use { stmt ->
                stmt.setObject(1, leadUserId)
                stmt.setString(2, projectKey)
                stmt.executeUpdate()
            }
        }
    }

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
    private fun seedProjectsAndComponents() {
        conn().use { c ->
            c.autoCommit = false
            c.prepareStatement(
                "INSERT INTO projects (key, name) VALUES (?, ?) ON CONFLICT (key) DO NOTHING",
            ).use { stmt ->
                stmt.setString(1, PROJECT_KEY)
                stmt.setString(2, "Auto Assign Integration Test Project")
                stmt.executeUpdate()
            }
            c.prepareStatement(
                "INSERT INTO projects (key, name) VALUES (?, ?) ON CONFLICT (key) DO NOTHING",
            ).use { stmt ->
                stmt.setString(1, OTHER_PROJECT_KEY)
                stmt.setString(2, "Other Project for Auto Assign Test")
                stmt.executeUpdate()
            }

            // software-default 워크플로우 + 스킴 + 프로젝트 배정 (createIssue 의 resolveStart 가 필요로 함)
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

            // open 상태만 필요 (createIssue 는 startState 만 조회)
            c.prepareStatement(
                "INSERT INTO workflow_states (workflow_id, key, name, category, display_order) " +
                    "VALUES (?, 'open', 'Open', 'TODO', 0) " +
                    "ON CONFLICT (workflow_id, key) DO UPDATE SET display_order = EXCLUDED.display_order",
            ).use { stmt ->
                stmt.setObject(1, wfId)
                stmt.executeUpdate()
            }

            // autoassign-scheme 독립 생성 — software-scheme 과 충돌 방지 (싱글톤 DB 공유)
            c.createStatement().use { stmt ->
                stmt.execute(
                    """
                    INSERT INTO workflow_schemes (key, name, is_default)
                    VALUES ('autoassign-scheme', 'Auto Assign Test Scheme', false)
                    ON CONFLICT (key) DO NOTHING
                    """.trimIndent(),
                )
            }

            // autoassign-scheme + default mapping
            c.createStatement().use { stmt ->
                stmt.execute(
                    """
                    INSERT INTO workflow_scheme_issue_type_mappings (scheme_id, issue_type_id, workflow_id)
                    SELECT s.id, NULL, '$wfId'
                    FROM workflow_schemes s
                    WHERE s.key = 'autoassign-scheme'
                      AND NOT EXISTS (
                        SELECT 1 FROM workflow_scheme_issue_type_mappings m
                        WHERE m.scheme_id = s.id AND m.issue_type_id IS NULL
                      )
                    """.trimIndent(),
                )
            }

            // AUTOASSIGN 프로젝트에 autoassign-scheme 배정
            c.createStatement().use { stmt ->
                stmt.execute(
                    """
                    INSERT INTO project_workflow_scheme_assignments (project_id, workflow_scheme_id, assigned_at, assigned_by)
                    SELECT p.id, s.id, NOW(), '00000000-0000-0000-0000-000000000000'::uuid
                    FROM projects p, workflow_schemes s
                    WHERE p.key = '$PROJECT_KEY'
                      AND s.key = 'autoassign-scheme'
                    ON CONFLICT (project_id) DO NOTHING
                    """.trimIndent(),
                )
            }

            c.commit()
        }

        compWithAliceLead = insertComponent(PROJECT_KEY, "BackendWithAlice", ALICE_ID)
        compAlpha = insertComponent(PROJECT_KEY, "Alpha", ALICE_ID)
        compZeta = insertComponent(PROJECT_KEY, "Zeta", BOB_ID)
        compNoLead = insertComponent(PROJECT_KEY, "NoLeadComp", null)
        compOtherProject = insertComponent(OTHER_PROJECT_KEY, "OtherComp", ALICE_ID)
    }

    private fun insertComponent(
        projectKey: String,
        name: String,
        leadUserId: UUID?,
    ): UUID {
        val projectId = fetchProjectId(projectKey)
        return conn().use { c ->
            c.prepareStatement(
                "INSERT INTO components (project_id, name, lead_user_id) VALUES (?, ?, ?) RETURNING id",
            ).use { stmt ->
                stmt.setObject(1, projectId)
                stmt.setString(2, name)
                stmt.setObject(3, leadUserId)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getObject(1) as UUID
                }
            }
        }
    }

    private fun softDeleteComponent(componentId: UUID) {
        conn().use { c ->
            c.prepareStatement("UPDATE components SET deleted_at = NOW() WHERE id = ?").use { stmt ->
                stmt.setObject(1, componentId)
                stmt.executeUpdate()
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

    @Suppress("NestedBlockDepth") // conn/stmt/rs 3단 use 중첩 — JDBC 표준 패턴, 분리 실익 없음
    private fun fetchAssigneeId(issueKey: String): UUID? =
        conn().use { c ->
            c.prepareStatement("SELECT assignee_id FROM issues WHERE key = ?").use { stmt ->
                stmt.setString(1, issueKey)
                stmt.executeQuery().use { rs ->
                    if (!rs.next()) return null
                    rs.getObject(1) as UUID?
                }
            }
        }

    private fun fetchVersion(issueKey: String): Long =
        conn().use { c ->
            c.prepareStatement("SELECT version FROM issues WHERE key = ?").use { stmt ->
                stmt.setString(1, issueKey)
                stmt.executeQuery().use { rs ->
                    check(rs.next()) { "$issueKey 이슈가 없습니다." }
                    rs.getLong(1)
                }
            }
        }

    private fun countIssueComponents(issueKey: String): Int =
        conn().use { c ->
            c.prepareStatement(
                "SELECT COUNT(*) FROM issue_components ic JOIN issues i ON ic.issue_id = i.id WHERE i.key = ?",
            ).use { stmt ->
                stmt.setString(1, issueKey)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
                }
            }
        }

    private fun countIssues(): Int =
        conn().use { c ->
            c.prepareStatement(
                "SELECT COUNT(*) FROM issues WHERE key LIKE '$PROJECT_KEY-%'",
            ).use { stmt ->
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getInt(1)
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
