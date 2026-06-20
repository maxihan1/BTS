// IssueTransitionAdapter 통합 테스트 — 보드 카드 이동이 기존 전이 경로에 위임됨을 검증

package com.bts.issue.adapter.outbound.board

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
import org.flywaydb.core.api.configuration.FluentConfiguration
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
import java.sql.Connection
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
 * [com.bts.issue.adapter.inbound.rest.IssueControllerTransitionIntegrationTest] 와 동형 구조.
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
@Suppress("LongMethod")
class IssueTransitionAdapterTest : IssueTestcontainersBase() {
    private lateinit var adapter: IssueTransitionAdapter
    private val actorId: UUID = UUID.fromString("11111111-1111-4111-8111-111111111111")

    companion object {
        private const val NORMAL_PROJECT_KEY = "BDTRANS"
        private const val NOSCHEME_PROJECT_KEY = "BDNOSCH"
    }

    /** issue-tracking + project-workflow 마이그레이션 공동 적용 */
    override fun configureFlyway(builder: FluentConfiguration): FluentConfiguration =
        super.configureFlyway(builder).locations(
            "classpath:db/migration/issue-tracking",
            "classpath:db/migration/project-workflow",
        )

    @BeforeAll
    fun setUpAll() {
        val dataSource = DriverManagerDataSource(postgres.jdbcUrl, postgres.username, postgres.password)
        val workflowDsl = DSL.using(dataSource, SQLDialect.POSTGRES)
        val txManager = DataSourceTransactionManager(dataSource)
        val txTemplate = TransactionTemplate(txManager)
        val objectMapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())

        // ── project-workflow 빈 (IssueControllerTransitionIntegrationTest.TestConfig 동형) ──
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

    /**
     * 테스트용 이슈를 DB 에 직접 삽입하고 이슈 키 문자열을 반환한다.
     *
     * Service layer 를 우회하므로 [currentStateKey] 를 그대로 삽입한다.
     * [IssueControllerTransitionIntegrationTest.insertIssue] 와 동형 패턴.
     */
    private fun insertIssue(
        projectKey: String = NORMAL_PROJECT_KEY,
        summary: String = "전이 테스트 이슈",
        currentStateKey: String = "open",
    ): String {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.autoCommit = false

            val seq =
                conn.prepareStatement(
                    "UPDATE projects SET key_sequence = key_sequence + 1 WHERE key = ? RETURNING key_sequence",
                ).use { stmt ->
                    stmt.setString(1, projectKey)
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getLong(1)
                    }
                }

            val issueKey = "$projectKey-$seq"

            val projectId =
                conn.prepareStatement("SELECT id FROM projects WHERE key = ?").use { stmt ->
                    stmt.setString(1, projectKey)
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getObject(1) as UUID
                    }
                }

            val taskTypeId =
                conn.prepareStatement(
                    "SELECT id FROM issue_types WHERE key = 'task' AND deleted_at IS NULL LIMIT 1",
                ).use { stmt ->
                    stmt.executeQuery().use { rs ->
                        check(rs.next()) { "task 타입이 없습니다. V003 마이그레이션 확인 필요." }
                        rs.getLong(1)
                    }
                }

            conn.prepareStatement(
                "INSERT INTO issues (key, project_id, summary, reporter_id, current_state_key, version, type_id) " +
                    "VALUES (?, ?, ?, ?, ?, 1, ?)",
            ).use { stmt ->
                stmt.setString(1, issueKey)
                stmt.setObject(2, projectId)
                stmt.setString(3, summary)
                stmt.setObject(4, actorId)
                stmt.setString(5, currentStateKey)
                stmt.setLong(6, taskTypeId)
                stmt.executeUpdate()
            }

            conn.commit()
            return issueKey
        }
    }

    /**
     * 워크플로우 · 스킴 · 프로젝트 픽스처 시드.
     *
     * [com.bts.issue.adapter.inbound.rest.IssueControllerTransitionIntegrationTest.seedWorkflowsAndSchemes]
     * 와 동형 패턴 — 컬럼/테이블명은 실제 마이그레이션 스키마(V200~V202) 기준.
     *
     * - BDTRANS 프로젝트 — software-scheme(software-default 워크플로우) 배정.
     * - BDNOSCH 프로젝트 — 스킴 미배정 (S4 시나리오).
     * - software-default 워크플로우 (open/in_progress/done 상태 + "Start Work" 전이).
     * - no-default-scheme — default mapping 없음.
     */
    private fun seedWorkflowsAndSchemes() {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.autoCommit = false

            insertProject(conn, NORMAL_PROJECT_KEY, "Board Transition Test Project")
            insertProject(conn, NOSCHEME_PROJECT_KEY, "No Scheme Project")

            // software-default 워크플로우 (already seeded by V200 YamlSeedService 시뮬레이션)
            val wfId =
                conn.prepareStatement(
                    "INSERT INTO workflows (key, name) VALUES ('software-default', '소프트웨어 개발 기본 워크플로우') " +
                        "ON CONFLICT (key) DO UPDATE SET name = EXCLUDED.name RETURNING id",
                ).use { stmt ->
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getObject(1) as UUID
                    }
                }

            val openId = insertState(conn, wfId, "open", "Open", "TODO", 0)
            val inProgressId = insertState(conn, wfId, "in_progress", "In Progress", "IN_PROGRESS", 1)
            insertState(conn, wfId, "done", "Done", "DONE", 2)

            // open → in_progress 만 정의 (open → done 은 의도적 미정의 — S2 시나리오)
            insertTransition(conn, wfId, openId, inProgressId, "Start Work")

            // software-scheme 은 V201 seed 에서 이미 삽입됨 — default mapping 만 추가
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    INSERT INTO workflow_scheme_issue_type_mappings (scheme_id, issue_type_id, workflow_id)
                    SELECT s.id, NULL, '$wfId'
                    FROM workflow_schemes s
                    WHERE s.key = 'software-scheme'
                      AND NOT EXISTS (
                        SELECT 1 FROM workflow_scheme_issue_type_mappings m
                        WHERE m.scheme_id = s.id AND m.issue_type_id IS NULL
                      )
                    """.trimIndent(),
                )
            }

            // BDTRANS ← software-scheme 배정 (project_id = UUID, V202 정정)
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    INSERT INTO project_workflow_scheme_assignments (project_id, workflow_scheme_id, assigned_by)
                    SELECT p.id, s.id, '00000000-0000-0000-0000-000000000000'::uuid
                    FROM projects p, workflow_schemes s
                    WHERE p.key = '$NORMAL_PROJECT_KEY'
                      AND s.key = 'software-scheme'
                    ON CONFLICT (project_id) DO NOTHING
                    """.trimIndent(),
                )
            }
            // no-default-scheme 생성 (default mapping 없음 — IssueControllerTransitionIntegrationTest 동형)
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    INSERT INTO workflow_schemes (key, name, is_default)
                    VALUES ('no-default-scheme-bd', 'No Default Scheme BD', false)
                    ON CONFLICT (key) DO NOTHING
                    """.trimIndent(),
                )
            }

            // BDNOSCH ← no-default-scheme-bd 배정 (default mapping 없으므로 전이 시 WorkflowSchemeNoDefaultException)
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    INSERT INTO project_workflow_scheme_assignments (project_id, workflow_scheme_id, assigned_by)
                    SELECT p.id, s.id, '00000000-0000-0000-0000-000000000000'::uuid
                    FROM projects p, workflow_schemes s
                    WHERE p.key = '$NOSCHEME_PROJECT_KEY'
                      AND s.key = 'no-default-scheme-bd'
                    ON CONFLICT (project_id) DO NOTHING
                    """.trimIndent(),
                )
            }

            conn.commit()
        }
    }

    private fun insertProject(
        conn: Connection,
        key: String,
        name: String,
    ) {
        conn.prepareStatement(
            "INSERT INTO projects (key, name) VALUES (?, ?) ON CONFLICT (key) DO NOTHING",
        ).use { stmt ->
            stmt.setString(1, key)
            stmt.setString(2, name)
            stmt.executeUpdate()
        }
    }

    @Suppress("LongParameterList")
    private fun insertState(
        conn: Connection,
        wfId: UUID,
        key: String,
        name: String,
        category: String,
        displayOrder: Int,
    ): UUID =
        conn.prepareStatement(
            "INSERT INTO workflow_states (workflow_id, key, name, category, display_order) " +
                "VALUES (?, ?, ?, ?, ?) ON CONFLICT (workflow_id, key) " +
                "DO UPDATE SET display_order = EXCLUDED.display_order RETURNING id",
        ).use { stmt ->
            stmt.setObject(1, wfId)
            stmt.setString(2, key)
            stmt.setString(3, name)
            stmt.setString(4, category)
            stmt.setInt(5, displayOrder)
            stmt.executeQuery().use { rs ->
                rs.next()
                rs.getObject(1) as UUID
            }
        }

    private fun insertTransition(
        conn: Connection,
        wfId: UUID,
        fromId: UUID,
        toId: UUID,
        transitionName: String,
    ) {
        conn.prepareStatement(
            "INSERT INTO workflow_transitions (workflow_id, from_state_id, to_state_id, name) " +
                "VALUES (?, ?, ?, ?) ON CONFLICT (workflow_id, from_state_id, to_state_id) DO NOTHING",
        ).use { stmt ->
            stmt.setObject(1, wfId)
            stmt.setObject(2, fromId)
            stmt.setObject(3, toId)
            stmt.setString(4, transitionName)
            stmt.executeUpdate()
        }
    }

    // ── S1. 정상 전이 — open → in_progress ──────────────────────────────────────

    /**
     * S1. 보드 카드 이동이 기존 전이 경로에 위임되어 상태 갱신 + version 증가를 반환한다.
     *
     * Given  BDTRANS-N 이슈 (currentStateKey="open", version=1)
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
     * Given  BDTRANS-N 이슈 (currentStateKey="open")
     * When   adapter.transition(cmd(toStateKey="done"))
     *        — software-default 에 open→done 미정의
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
     * Given  BDTRANS-N 이슈 (version=1)
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
     * Given  BDNOSCH-N 이슈 (no scheme assigned)
     * When   adapter.transition(cmd)
     * Then   IssueWorkflowNotConfiguredException
     */
    @Test
    fun `transition propagates IssueWorkflowNotConfiguredException when no scheme`() {
        setActor()
        val issueKey = insertIssue(projectKey = NOSCHEME_PROJECT_KEY, currentStateKey = "open")

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
    fun `transition throws 401 when SecurityContext is not authenticated`() {
        // SecurityContext clear — setActor() 호출 없음
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
            .matches { ex ->
                (ex as org.springframework.web.server.ResponseStatusException).statusCode.value() == 401
            }
    }
}
