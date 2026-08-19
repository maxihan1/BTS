// changeComponents 자동 담당자 배정 Testcontainers 통합 테스트 (FR-CM-03 Task 5 / FR-CM-04 Task 5)

package com.bts.issue.application

import com.bts.issue.testsupport.insertWorkflowStatus
import com.bts.issue.adapter.inbound.rest.IssueControllerTransitionIntegrationTest.TestConfig
import com.bts.issue.component.repository.ComponentRepository
import com.bts.issue.event.IssueEventPublisher
import com.bts.issue.project.repository.ProjectLeadRepository
import com.bts.issue.repository.IssueRepository
import com.bts.issue.resolution.repository.ResolutionRepository
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.issue.version.repository.VersionRepository
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
 * changeComponents 자동 담당자 배정 전 구간 Testcontainers 통합 테스트 (FR-CM-03 Task 5 / FR-CM-04 Task 5).
 *
 * 컨트롤러 → 서비스 → 리포지토리 → 실 PostgreSQL 전 구간을 검증한다.
 * 단위 mock 이 못 잡는 이중 version bump 방지, 트랜잭션 경계, 자동 배정 영속을 실증한다.
 *
 * ## 검증 시나리오
 * - S2. 미할당 이슈에 리드 보유 컴포넌트 지정 → assignee 자동 설정 + version 정확히 +1 (이중 bump 금지)
 * - S3. 명시 담당자(Carol) 있는 이슈에 컴포넌트 지정 → Carol 보존 (덮어쓰기 안 함)
 * - S6. 컴포넌트 전부 해제(componentIds=[]) → 기존 담당자 유지 (자동 unassign 없음)
 * - S7. 리드 없는 컴포넌트로 교체 + 프로젝트 리드 → 프로젝트 리드 재배정 (FR-CM-04 Task 5 RED)
 * - occ. 낙관락 충돌(expectedVersion 불일치) → IssueVersionConflictException (409 회귀)
 * - regression. 기존 changeComponents (FR-CM-02) version +1 단언 — 자동 배정 추가 후에도 version 변화 없음
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(
    classes = [TestConfig::class, IssueChangeComponentsAutoAssignIntegrationTest.AutoAssignChangeConfig::class],
)
@WebAppConfiguration
@ActiveProfiles("test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class IssueChangeComponentsAutoAssignIntegrationTest {
    /**
     * 자동 담당자 배정 통합 테스트 보조 설정.
     *
     * 실 ComponentRepository + ProjectLeadRepository + IssueApplicationService 재조립.
     * TestConfig 의 componentRepository=mockk(relaxed=true) 를 실 DB 로 대체한다.
     */
    @Configuration
    @Suppress("LongParameterList")
    open class AutoAssignChangeConfig {
        @Bean
        open fun realComponentRepository(dsl: DSLContext): ComponentRepository = ComponentRepository(dsl)

        @Bean
        open fun realProjectLeadRepository(dsl: DSLContext): ProjectLeadRepository = ProjectLeadRepository(dsl)

        @Bean
        open fun realVersionRepository(dsl: DSLContext): VersionRepository = VersionRepository(dsl)

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
            versionRepository: VersionRepository,
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
                versionRepository = versionRepository,
                clock = clock,
                historyRecorder = io.mockk.mockk(relaxed = true),
            )
    }

    @Autowired
    lateinit var webApplicationContext: WebApplicationContext

    @Autowired
    lateinit var issueApplicationService: IssueApplicationService

    companion object {
        private const val PROJECT_KEY = "CHGCMP"

        /** Alice — 자동 배정 대상 리드 UUID */
        val ALICE_ID: UUID = UUID.fromString("00000000-0000-4000-8000-000000000201")

        /** Carol — S3 시나리오 명시 담당자 UUID */
        val CAROL_ID: UUID = UUID.fromString("00000000-0000-4000-8000-000000000203")

        /** Eve — S7 프로젝트 리드 UUID (FR-CM-04 Task 5) */
        val PROJECT_LEAD_ID: UUID = UUID.fromString("00000000-0000-4000-8000-000000000205")

        /** 이슈 조작 actor */
        val ACTOR_ID = com.bts.issue.domain.ActorId(UUID.fromString("00000000-0000-4000-8000-000000000001"))

        private var migrated = false
        private var seeded = false

        /** S2 — 리드=Alice 인 컴포넌트 */
        lateinit var compWithAliceLead: UUID

        /** S3/S6/S7 — 리드 없는 컴포넌트 (담당자 보존/프로젝트 리드 폴백 확인용) */
        lateinit var compNoLead: UUID
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

    // ── S2. 미할당 이슈 + 리드 보유 컴포넌트 → assignee 자동 설정 + version 정확히 +1 ──

    /**
     * S2 자동 배정 해피패스 + 이중 version bump 금지.
     *
     * Given  미할당 이슈(version=1)
     * When   changeComponents(componentIds=[compWithAliceLead], expectedVersion=1)
     * Then   반환 이슈 assigneeId=Alice
     * And    DB issues.assignee_id=Alice
     * And    DB version=2 (replaceComponents 에서 +1, setAssignee 에서 bump 없음)
     */
    @Test
    fun `S2 미할당 이슈에 리드 보유 컴포넌트 지정 - assignee 자동 설정과 version 정확히 +1 확인`() {
        val key = insertIssue("S2 자동 배정 테스트", assigneeId = null)
        val versionBefore = fetchVersion(key)

        val request =
            AppChangeComponentsRequest(
                componentIds = listOf(compWithAliceLead),
                expectedVersion = versionBefore,
            )
        issueApplicationService.changeComponents(ACTOR_ID, com.bts.issue.domain.IssueKey(key), request)

        val versionAfter = fetchVersion(key)
        assert(versionAfter == versionBefore + 1) {
            "version 이 정확히 +1 이어야 하지만 before=$versionBefore after=$versionAfter 입니다 (이중 bump 의심)."
        }

        val dbAssignee = fetchAssigneeId(key)
        assert(dbAssignee == ALICE_ID) {
            "DB assignee_id 가 Alice 여야 하지만 $dbAssignee 입니다."
        }
    }

    // ── S3. 명시 담당자(Carol) 있는 이슈 + 컴포넌트 지정 → Carol 보존 ──────────

    /**
     * S3 담당자 보존 — 덮어쓰기 안 함.
     *
     * Given  Carol 이 담당자인 이슈(version=1)
     * When   changeComponents(componentIds=[compWithAliceLead], expectedVersion=1)
     * Then   assigneeId=Carol (Alice 로 교체되지 않음)
     * And    version=2 (+1 만)
     */
    @Test
    fun `S3 명시 담당자 Carol 있는 이슈에 컴포넌트 지정 - Carol 보존 확인`() {
        val key = insertIssue("S3 담당자 보존 테스트", assigneeId = CAROL_ID)
        val versionBefore = fetchVersion(key)

        val request =
            AppChangeComponentsRequest(
                componentIds = listOf(compWithAliceLead),
                expectedVersion = versionBefore,
            )
        issueApplicationService.changeComponents(ACTOR_ID, com.bts.issue.domain.IssueKey(key), request)

        val dbAssignee = fetchAssigneeId(key)
        assert(dbAssignee == CAROL_ID) {
            "담당자가 Carol 이어야 하지만 $dbAssignee 입니다 (덮어쓰기 발생)."
        }

        val versionAfter = fetchVersion(key)
        assert(versionAfter == versionBefore + 1) {
            "version 이 정확히 +1 이어야 하지만 before=$versionBefore after=$versionAfter 입니다."
        }
    }

    // ── S6. 컴포넌트 전부 해제 → 기존 담당자 유지 ──────────────────────────────

    /**
     * S6 컴포넌트 전부 해제 — 자동 unassign 없음.
     *
     * Given  Carol 이 담당자인 이슈에 compNoLead 할당 상태(version=2)
     * When   changeComponents(componentIds=[], expectedVersion=2)
     * Then   assigneeId=Carol 유지 (자동 해제 없음)
     * And    version=3 (+1 만)
     */
    @Test
    fun `S6 컴포넌트 전부 해제 - 기존 담당자 Carol 유지 확인`() {
        val key = insertIssue("S6 전부 해제 테스트", assigneeId = CAROL_ID)

        // 먼저 컴포넌트 하나 할당 (version 1→2)
        issueApplicationService.changeComponents(
            ACTOR_ID,
            com.bts.issue.domain.IssueKey(key),
            AppChangeComponentsRequest(componentIds = listOf(compNoLead), expectedVersion = 1L),
        )

        val versionBefore = fetchVersion(key)
        assert(versionBefore == 2L) { "version 이 2 여야 하지만 $versionBefore 입니다." }

        // 컴포넌트 전부 해제
        issueApplicationService.changeComponents(
            ACTOR_ID,
            com.bts.issue.domain.IssueKey(key),
            AppChangeComponentsRequest(componentIds = emptyList(), expectedVersion = versionBefore),
        )

        val dbAssignee = fetchAssigneeId(key)
        assert(dbAssignee == CAROL_ID) {
            "컴포넌트 해제 후에도 담당자가 Carol 이어야 하지만 $dbAssignee 입니다."
        }

        val versionAfter = fetchVersion(key)
        assert(versionAfter == versionBefore + 1) {
            "version 이 정확히 +1 이어야 하지만 before=$versionBefore after=$versionAfter 입니다."
        }
    }

    // ── S7. 리드 없는 컴포넌트로 교체 + 프로젝트 리드 → 프로젝트 리드 재배정 (FR-CM-04 Task 5) ──

    /**
     * S7 changeComponents 프로젝트 리드 폴백.
     *
     * Given  미할당 이슈(version=1), CHGCMP 프로젝트 리드=Eve
     * When   changeComponents(componentIds=[compNoLead]) (리드 없는 컴포넌트)
     * Then   assigneeId=Eve (컴포넌트 리드 없으므로 프로젝트 리드 폴백)
     * And    version=2 (정확히 +1)
     */
    @Test
    fun `S7 리드 없는 컴포넌트 교체 + 프로젝트 리드 - 프로젝트 리드 재배정`() {
        setProjectLead(PROJECT_KEY, PROJECT_LEAD_ID)

        val key = insertIssue("S7 프로젝트 리드 폴백 테스트", assigneeId = null)
        val versionBefore = fetchVersion(key)

        val request =
            AppChangeComponentsRequest(
                componentIds = listOf(compNoLead),
                expectedVersion = versionBefore,
            )
        issueApplicationService.changeComponents(ACTOR_ID, com.bts.issue.domain.IssueKey(key), request)

        val dbAssignee = fetchAssigneeId(key)
        assert(dbAssignee == PROJECT_LEAD_ID) {
            "프로젝트 리드 Eve 가 assignee 여야 하지만 $dbAssignee 입니다."
        }

        val versionAfter = fetchVersion(key)
        assert(versionAfter == versionBefore + 1) {
            "version 이 정확히 +1 이어야 하지만 before=$versionBefore after=$versionAfter 입니다."
        }
    }

    // ── occ. 낙관락 충돌 → IssueVersionConflictException (409 회귀) ─────────────

    /**
     * 낙관락 충돌 회귀.
     *
     * Given  이슈(version=1)
     * When   changeComponents(expectedVersion=99) (stale)
     * Then   IssueVersionConflictException
     * And    DB version 변화 없음
     */
    @Test
    @Suppress("SwallowedException")
    fun `낙관락 충돌 expectedVersion 불일치 - IssueVersionConflictException 회귀`() {
        val key = insertIssue("낙관락 충돌 테스트", assigneeId = null)
        val versionBefore = fetchVersion(key)

        val request =
            AppChangeComponentsRequest(
                componentIds = listOf(compWithAliceLead),
                expectedVersion = 99L,
            )

        try {
            issueApplicationService.changeComponents(ACTOR_ID, com.bts.issue.domain.IssueKey(key), request)
            assert(false) { "IssueVersionConflictException 이 발생해야 합니다." }
        } catch (e: com.bts.issue.domain.IssueVersionConflictException) {
            // 409 정상 발생 — 예외 삼킴 의도적 (검증 완료)
        }

        val versionAfter = fetchVersion(key)
        assert(versionAfter == versionBefore) {
            "충돌 시 version 변화가 없어야 하지만 before=$versionBefore after=$versionAfter 입니다."
        }
    }

    // ── regression. 기존 FR-CM-02 version +1 단언 ─────────────────────────────

    /**
     * FR-CM-02 회귀 — 컴포넌트 교체 후 version 정확히 +1.
     *
     * 자동 배정 로직 추가 후에도 기존 FR-CM-02 version 계약이 깨지지 않음을 검증한다.
     * 리드 없는 컴포넌트를 사용해 자동 배정이 발동하지 않는 경로를 테스트한다.
     *
     * Given  미할당 이슈(version=1), 리드 없는 컴포넌트, 프로젝트 리드 없음
     * When   changeComponents(componentIds=[compNoLead], expectedVersion=1)
     * Then   version=2 (정확히 +1, 이중 bump 없음)
     * And    assigneeId=null (자동 배정 미발동)
     */
    @Test
    fun `FR-CM-02 회귀 - 자동 배정 추가 후에도 version 정확히 +1 유지`() {
        // cleanIssues 에서 lead_user_id=null 리셋됨 — 추가 설정 불필요
        val key = insertIssue("FR-CM-02 회귀 테스트", assigneeId = null)
        val versionBefore = fetchVersion(key)

        val request =
            AppChangeComponentsRequest(
                componentIds = listOf(compNoLead),
                expectedVersion = versionBefore,
            )
        issueApplicationService.changeComponents(ACTOR_ID, com.bts.issue.domain.IssueKey(key), request)

        val versionAfter = fetchVersion(key)
        assert(versionAfter == versionBefore + 1) {
            "version 이 정확히 +1 이어야 하지만 before=$versionBefore after=$versionAfter 입니다."
        }

        val dbAssignee = fetchAssigneeId(key)
        assert(dbAssignee == null) {
            "리드 없는 컴포넌트 + 프로젝트 리드 없음은 assigneeId=null 이어야 하지만 $dbAssignee 입니다."
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
                stmt.setString(2, "Change Components Auto Assign Test Project")
                stmt.executeUpdate()
            }

            // software-default 워크플로우 + 스킴 + 프로젝트 배정 (이슈 삽입에 필요)
            val wfId =
                c.prepareStatement(
                    "INSERT INTO workflows (key, name) VALUES ('software-default', '소프트웨어 개발 기본 워크플로우') " +
                        "ON CONFLICT (key) WHERE deleted_at IS NULL DO UPDATE SET name = EXCLUDED.name RETURNING id",
                ).use { stmt ->
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getObject(1) as UUID
                    }
                }

            insertWorkflowStatus(c, wfId, "open", "Open", "TODO", 0)

            c.createStatement().use { stmt ->
                stmt.execute(
                    """
                    INSERT INTO workflow_schemes (key, name, is_default)
                    VALUES ('chgcmp-scheme', 'Change Components Test Scheme', false)
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
                    WHERE s.key = 'chgcmp-scheme'
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
                      AND s.key = 'chgcmp-scheme'
                    ON CONFLICT (project_id) DO NOTHING
                    """.trimIndent(),
                )
            }

            c.commit()
        }

        compWithAliceLead = insertComponent(PROJECT_KEY, "AlphaComp", ALICE_ID)
        compNoLead = insertComponent(PROJECT_KEY, "NoLeadComp", null)
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

    private fun insertIssue(
        summary: String,
        assigneeId: UUID?,
    ): String =
        conn().use { c ->
            c.autoCommit = false

            val seq =
                c.prepareStatement(
                    "UPDATE projects SET key_sequence = key_sequence + 1 WHERE key = ? RETURNING key_sequence",
                ).use { stmt ->
                    stmt.setString(1, PROJECT_KEY)
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getLong(1)
                    }
                }
            val issueKey = "$PROJECT_KEY-$seq"
            val projectId = fetchProjectId(PROJECT_KEY)

            val taskTypeId =
                c.prepareStatement(
                    "SELECT id FROM issue_types WHERE key = 'task' AND deleted_at IS NULL LIMIT 1",
                ).use { stmt ->
                    stmt.executeQuery().use { rs ->
                        check(rs.next()) { "task 타입 없음 — V003 마이그레이션 확인 필요." }
                        rs.getLong(1)
                    }
                }

            @Suppress("MaxLineLength")
            c.prepareStatement(
                "INSERT INTO issues (key, project_id, summary, reporter_id, current_state_key, version, type_id, assignee_id) " +
                    "VALUES (?, ?, ?, ?, ?, 1, ?, ?)",
            ).use { stmt ->
                stmt.setString(1, issueKey)
                stmt.setObject(2, projectId)
                stmt.setString(3, summary)
                stmt.setObject(4, UUID.fromString("00000000-0000-0000-0000-000000000001"))
                stmt.setString(5, "open")
                stmt.setLong(6, taskTypeId)
                stmt.setObject(7, assigneeId)
                stmt.executeUpdate()
            }

            c.commit()
            issueKey
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

    @Suppress("NestedBlockDepth")
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

    private fun conn() =
        DriverManager.getConnection(
            TestConfig.postgres.jdbcUrl,
            TestConfig.postgres.username,
            TestConfig.postgres.password,
        )
}
