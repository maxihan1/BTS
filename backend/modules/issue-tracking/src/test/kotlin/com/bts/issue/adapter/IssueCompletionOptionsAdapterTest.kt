// IssueCompletionOptionsAdapter 통합 테스트 — BROWSE 게이트 + DONE 전환 필터 + resolution 목록 결합 fail-closed 검증 (FR-SL-05 Task 3)

package com.bts.issue.adapter

import com.bts.issue.adapter.outbound.AlwaysAllowIssuePermissionResolver
import com.bts.issue.application.IssueApplicationService
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.event.IssueEventPublisher
import com.bts.issue.repository.IssueTestcontainersBase
import com.bts.issue.resolution.repository.ResolutionRepository
import com.bts.issue.testsupport.insertWorkflowStatus
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.bts.shared.permission.IssueSecurityAccess
import com.bts.shared.permission.IssueSecurityDirectory
import com.bts.shared.user.UserLookupPort
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
import org.flywaydb.core.api.configuration.FluentConfiguration
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.sql.Connection
import java.sql.DriverManager
import java.time.Clock
import java.util.UUID

/**
 * [IssueCompletionOptionsAdapter] 통합 테스트 (FR-SL-05 Task 3).
 *
 * [IssueTestcontainersBase] 의 JVM 단위 singleton PostgreSQL 컨테이너를 그대로 사용하되,
 * 워크플로우 시드는 전용 프로젝트 키(`SLCOMP`)에만 배정한다 — `TPRJ` 는 여러 테스트 클래스가
 * 공유하므로([IssueTestcontainersBase] KDoc "JVM 단위 singleton container" 참조) 워크플로우 스킴을
 * 배정하면 다른 테스트와 충돌할 수 있다([IssueTransitionAdapterTest] 의 `BDTRANS`/`BDNOSCH` 전용
 * 프로젝트 키 선례와 동일한 이유).
 *
 * [IssueApplicationService] 는 project-workflow 모듈 전체 스택을 실제로 wire 한다
 * ([IssueTransitionAdapterTest.setUpAll] 동형) — `availableTransitions` 가 실제 워크플로우 상태
 * 카테고리를 반환해야 DONE 필터 검증이 의미가 있기 때문이다.
 *
 * ## 시나리오
 * - 가시 이슈 → (version, DONE 카테고리 전환 후보만, resolution 목록) 반환.
 * - 프로젝트 BROWSE 권한 없음 → null(fail-closed) — [IssueApplicationService.availableTransitions] 는
 *   호출되지 않는다(게이트가 먼저 차단).
 * - 활성 resolution 이 전혀 없으면(전역 soft-delete) resolutions 는 빈 목록 — `@Order` 로 마지막에
 *   실행해 다른 시나리오의 resolution 시드에 영향을 주지 않는다.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class IssueCompletionOptionsAdapterTest : IssueTestcontainersBase() {
    private lateinit var issueApplicationService: IssueApplicationService
    private lateinit var resolutionRepository: ResolutionRepository
    private lateinit var projectId: UUID

    companion object {
        private const val PROJECT_KEY = "SLCOMP"
    }

    /** issue-tracking + project-workflow 마이그레이션 공동 적용 ([IssueTransitionAdapterTest] 동형). */
    override fun configureFlyway(builder: FluentConfiguration): FluentConfiguration =
        super.configureFlyway(builder).locations(
            "classpath:db/migration/issue-tracking",
            "classpath:db/migration/project-workflow",
        )

    @BeforeAll
    fun setUpAll() {
        val objectMapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())

        // ── project-workflow 빈 ([IssueTransitionAdapterTest] 동형) ─────────────
        val workflowRepo = WorkflowRepository(dsl)
        val workflowCache = WorkflowCache(workflowRepo, dsl)
        val workflowDefRepo =
            mockk<WorkflowDefinitionRepository> {
                every { findValidators(any(), any()) } returns emptyList()
                every { findPostActions(any(), any()) } returns emptyList()
            }
        val validatorFactory = mockk<WorkflowValidatorFactory>(relaxed = true)
        val postActionFactory = mockk<WorkflowPostActionFactory>(relaxed = true)
        val workflowEngine = WorkflowEngine(workflowCache, validatorFactory, postActionFactory, workflowDefRepo)
        val workflowTransitionAdapter = WorkflowTransitionAdapter(workflowEngine)

        val schemeRepo = WorkflowSchemeRepository(dsl)
        val assignmentRepo = ProjectWorkflowSchemeAssignmentRepository(dsl)
        val mappingRepo = SchemeIssueTypeMappingRepository(dsl)
        val schemeEventPublisher = WorkflowSchemeEventPublisher(dsl, objectMapper)
        val schemePermResolver = AlwaysAllowWorkflowSchemePermissionResolver()
        val projectLookup = JdbcProjectLookupAdapter(dsl)
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
        val issueTypeRepo = IssueTypeRepository(dsl)
        resolutionRepository = ResolutionRepository(dsl)
        val issueEventPublisher = IssueEventPublisher(dsl, objectMapper)

        issueApplicationService =
            IssueApplicationService(
                repo = repository,
                issueTypeRepository = issueTypeRepo,
                resolutionRepository = resolutionRepository,
                eventPublisher = issueEventPublisher,
                // IssueApplicationService 자체 VIEW 게이트 — adapter 의 BROWSE 게이트와 다른 차원이므로
                // 여기서는 항상 허용해, 시나리오별 가시성 제어는 adapter 에 주입하는 StubPermissionResolver 만 담당한다.
                permissionResolver = AlwaysAllowIssuePermissionResolver(),
                workflowPort = workflowTransitionAdapter,
                workflowKeyResolver = workflowKeyResolver,
                userLookupPort =
                    object : UserLookupPort {
                        override fun exists(userId: UUID): Boolean = true
                    },
                componentRepository = mockk(relaxed = true),
                projectLeadRepository = mockk(relaxed = true),
                versionRepository = mockk(relaxed = true),
                clock = Clock.systemUTC(),
                historyRecorder = mockk(relaxed = true),
            )

        seedProjectAndWorkflow()
    }

    // ── 워크플로우 시드 ────────────────────────────────────────────────────────

    /**
     * 전용 프로젝트(`SLCOMP`) + 워크플로우 시드.
     *
     * open → in_progress (category IN_PROGRESS, 비-DONE) 과 open → done (category DONE) 을 함께
     * 정의해, `availableTransitions` 가 반환하는 두 후보 중 DONE 카테고리만 필터링됨을 검증할 수 있게 한다.
     */
    private fun seedProjectAndWorkflow() {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.autoCommit = false

            insertProject(conn, PROJECT_KEY, "Slack Completion Test Project")
            projectId =
                conn.prepareStatement("SELECT id FROM projects WHERE key = ?").use { stmt ->
                    stmt.setString(1, PROJECT_KEY)
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getObject(1) as UUID
                    }
                }

            val wfId =
                conn.prepareStatement(
                    "INSERT INTO workflows (key, name) VALUES ('slcomp-wf', 'Slack 완료 테스트 워크플로우') " +
                        "ON CONFLICT (key) WHERE deleted_at IS NULL DO UPDATE SET name = EXCLUDED.name RETURNING id",
                ).use { stmt ->
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getObject(1) as UUID
                    }
                }

            val openId = insertState(conn, wfId, "open", "Open", "TODO", 0)
            val inProgressId = insertState(conn, wfId, "in_progress", "In Progress", "IN_PROGRESS", 1)
            val doneId = insertState(conn, wfId, "done", "Done", "DONE", 2)

            insertTransition(conn, wfId, openId, inProgressId, "Start Work")
            insertTransition(conn, wfId, openId, doneId, "Complete Directly")

            val schemeId =
                conn.prepareStatement(
                    "INSERT INTO workflow_schemes (key, name, is_default) " +
                        "VALUES ('slcomp-scheme', 'Slack 완료 테스트 스킴', false) " +
                        "ON CONFLICT (key) DO UPDATE SET name = EXCLUDED.name RETURNING id",
                ).use { stmt ->
                    stmt.executeQuery().use { rs ->
                        rs.next()
                        rs.getLong(1)
                    }
                }

            conn.prepareStatement(
                "INSERT INTO workflow_scheme_issue_type_mappings (scheme_id, issue_type_id, workflow_id) " +
                    "VALUES (?, NULL, ?) ON CONFLICT ON CONSTRAINT uq_scheme_issue_type DO NOTHING",
            ).use { stmt ->
                stmt.setLong(1, schemeId)
                stmt.setObject(2, wfId)
                stmt.executeUpdate()
            }

            conn.prepareStatement(
                "INSERT INTO project_workflow_scheme_assignments " +
                    "(project_id, workflow_scheme_id, assigned_at, assigned_by) " +
                    "VALUES (?, ?, NOW(), '00000000-0000-4000-8000-000000000000'::uuid) " +
                    "ON CONFLICT (project_id) DO NOTHING",
            ).use { stmt ->
                stmt.setObject(1, projectId)
                stmt.setLong(2, schemeId)
                stmt.executeUpdate()
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
    ): UUID = insertWorkflowStatus(conn, wfId, key, name, category, displayOrder)

    private fun insertTransition(
        conn: Connection,
        wfId: UUID,
        fromId: UUID,
        toId: UUID,
        name: String,
    ) {
        conn.prepareStatement(
            "INSERT INTO workflow_transitions (workflow_id, from_state_id, to_state_id, name) " +
                "VALUES (?, ?, ?, ?) ON CONFLICT (workflow_id, from_state_id, to_state_id) DO NOTHING",
        ).use { stmt ->
            stmt.setObject(1, wfId)
            stmt.setObject(2, fromId)
            stmt.setObject(3, toId)
            stmt.setString(4, name)
            stmt.executeUpdate()
        }
    }

    // ── stub 협력자 (adapter 자체 게이트 전용, [IssueUnfurlAdapterIntegrationTest] 동형) ──────

    /** BROWSE(Project) 게이트만 제어하는 stub — 시나리오별로 단일 boolean 결과를 반환한다. */
    private class StubPermissionResolver(
        private val browseAllowed: Boolean,
    ) : IssuePermissionResolver {
        override fun hasPermission(
            actorId: UUID,
            permission: IssuePermission,
            scope: IssueScope,
        ): Boolean = browseAllowed
    }

    /** 손수 만든 [IssueSecurityAccess] 를 반환하는 stub — [IssueUnfurlAdapterIntegrationTest] 동형. */
    private class StubSecurityDirectory(
        private val next: IssueSecurityAccess,
    ) : IssueSecurityDirectory {
        override fun levelBelongsToProjectScheme(
            levelId: UUID,
            projectKey: String,
        ): Boolean = true

        override fun accessibleLevels(
            actorId: UUID,
            projectKey: String,
        ): IssueSecurityAccess = next
    }

    private fun unrestricted() =
        IssueSecurityAccess(
            unrestricted = true,
            staticLevelIds = emptySet(),
            reporterLevelIds = emptySet(),
            assigneeLevelIds = emptySet(),
        )

    private fun adapterWith(
        browseAllowed: Boolean = true,
        access: IssueSecurityAccess = unrestricted(),
    ): IssueCompletionOptionsAdapter =
        IssueCompletionOptionsAdapter(
            issueRepository = repository,
            permissionResolver = StubPermissionResolver(browseAllowed),
            securityDirectory = StubSecurityDirectory(access),
            issueApplicationService = issueApplicationService,
            resolutionRepository = resolutionRepository,
        )

    /** 테스트용 이슈 생성 helper — [IssueUnfurlAdapterIntegrationTest.insertIssue] 동형. */
    private fun insertIssue(
        seq: Long,
        currentStateKey: String = "open",
    ): Issue =
        repository.insert(
            Issue.create(
                id = IssueId(UUID.randomUUID()),
                key = IssueKey.of(PROJECT_KEY, seq),
                projectId = projectId,
                typeId = requireTaskTypeId(),
                summary = "completion options issue $seq",
                reporterId = ActorId(UUID.randomUUID()),
                currentStateKey = currentStateKey,
            ),
        )

    private fun requireTaskTypeId(): IssueTypeId {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement("SELECT id FROM issue_types WHERE key = 'task' AND deleted_at IS NULL LIMIT 1")
                .use { stmt ->
                    stmt.executeQuery().use { rs ->
                        check(rs.next()) { "V003 마이그레이션에서 task 타입이 없습니다." }
                        return IssueTypeId(rs.getLong(1))
                    }
                }
        }
    }

    // ── 시나리오 ───────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    fun `가시 이슈는 버전 DONE 전환 후보 resolution 목록을 반환한다`() {
        val viewer = UUID.randomUUID()
        insertIssue(seq = 1, currentStateKey = "open")

        val adapter = adapterWith(browseAllowed = true, access = unrestricted())

        val result = adapter.getCompletionOptions("$PROJECT_KEY-1", viewer)

        val options = requireNotNull(result) { "가시 이슈는 null 이 아니어야 한다" }
        assertThat(options.version).isEqualTo(1L)
        assertThat(options.doneTransitions).hasSize(1)
        assertThat(options.doneTransitions.single().toStateKey).isEqualTo("done")
        assertThat(options.resolutions).isNotEmpty
        assertThat(options.resolutions.map { it.label }).contains("Fixed")
    }

    @Test
    @Order(2)
    fun `프로젝트 BROWSE 권한이 없으면 null 을 반환한다`() {
        val viewer = UUID.randomUUID()
        insertIssue(seq = 1, currentStateKey = "open")

        val adapter = adapterWith(browseAllowed = false)

        val result = adapter.getCompletionOptions("$PROJECT_KEY-1", viewer)

        assertThat(result).isNull()
    }

    @Test
    @Order(3)
    fun `활성 resolution 이 없으면 resolutions 는 빈 목록이다`() {
        val viewer = UUID.randomUUID()
        insertIssue(seq = 1, currentStateKey = "open")
        // 전역 활성 resolution 을 전부 soft-delete — 마지막 순서로 실행되어 앞선 시나리오의 시드에 영향 없음.
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("UPDATE resolutions SET deleted_at = NOW() WHERE deleted_at IS NULL")
            }
        }

        val adapter = adapterWith(browseAllowed = true, access = unrestricted())

        val result = adapter.getCompletionOptions("$PROJECT_KEY-1", viewer)

        val options = requireNotNull(result) { "가시 이슈는 null 이 아니어야 한다" }
        assertThat(options.resolutions).isEmpty()
    }
}
