// IssueUnfurlAdapter 통합 테스트 — BROWSE 게이트 + accessibleLevels 보안등급 게이트 결합 fail-closed 검증 (FR-SL-03 Task 6)

package com.bts.issue.adapter

import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.domain.IssuePriority
import com.bts.issue.repository.IssueTestcontainersBase
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.issue.IssueTypeKey
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.bts.shared.permission.IssueSecurityAccess
import com.bts.shared.permission.IssueSecurityDirectory
import com.bts.shared.user.UserLookupPort
import com.bts.shared.workflow.ProjectKey
import com.bts.shared.workflow.WorkflowStateCatalog
import com.bts.shared.workflow.WorkflowStateView
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.sql.DriverManager
import java.util.UUID

/**
 * [IssueUnfurlAdapter] 통합 테스트 (FR-SL-03 Task 6).
 *
 * 어댑터를 Spring 컨텍스트 없이 직접 생성해([BoardIssueLookupAdapterTest] 선례),
 * `IssueRepository`/`IssueTypeRepository` 는 Testcontainers 실 DB에, 나머지 협력자
 * ([IssuePermissionResolver]/[IssueSecurityDirectory]/[WorkflowStateCatalog]/[UserLookupPort])는
 * 시나리오별로 제어 가능한 stub 에 연결한다.
 *
 * ## 왜 BROWSE(Project) 게이트가 추가로 필요한가
 * `BoardIssueLookupAdapter`의 `accessibleLevels`+`existsVisibleIssue` 조합은 보안등급(security level)
 * 만 판정하며, 프로젝트 BROWSE 권한(멤버십)은 **호출자(보드 컨트롤러)가 별도로 강제한다**는 전제다.
 * Slack unfurl 흐름에는 그런 별도 호출자가 없다 — ADR D1이 "권한 확인 + 상세 조회를 어댑터 한 곳에서
 * 원자적으로 수행한다"고 명시한다. accessibleLevels+existsVisibleIssue 만 재사용하면, 보안 스킴이
 * 적용되지 않은(빠른경로 unrestricted=true) 대다수 프로젝트에서 **프로젝트 멤버가 아닌 임의의 Atlas
 * 사용자도 이슈를 열람**할 수 있는 fail-open이 발생한다. 이를 막기 위해 `IssueEpicService.progress()`의
 * "BROWSE(Project) 진입 게이트" 패턴을 그대로 재사용해 accessibleLevels 앞에 추가한다.
 *
 * ## 시나리오
 * - 가시 이슈 → View(상태/우선순위/담당자 라벨 해석 확인)
 * - 무권한(BROWSE 거부) → null
 * - 없는 키(형식 오류·미존재) → null
 * - accessibleLevels 빈/미상(보안등급 미충족) → null(거부)
 * - WorkflowStateCatalog 예외 전파(캐치 금지, rollback-only 오염 회귀 가드)
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class IssueUnfurlAdapterIntegrationTest : IssueTestcontainersBase() {
    private var taskTypeId: IssueTypeId? = null

    private val typeRepository: IssueTypeRepository by lazy { IssueTypeRepository(dsl) }

    /** resolveTaskTypeId — V003 seed 에서 task 타입 id 조회 ([BoardIssueLookupAdapterTest] 선례). */
    @BeforeAll
    fun resolveTaskTypeId() {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement("SELECT id FROM issue_types WHERE key = 'task' AND deleted_at IS NULL LIMIT 1")
                .use { stmt ->
                    stmt.executeQuery().use { rs ->
                        check(rs.next()) { "V003 마이그레이션에서 task 타입이 없습니다." }
                        taskTypeId = IssueTypeId(rs.getLong(1))
                    }
                }
        }
    }

    private fun requireTaskTypeId(): IssueTypeId = requireNotNull(taskTypeId) { "taskTypeId 가 초기화되지 않았습니다." }

    // ── stub 협력자 ────────────────────────────────────────────────────────────

    /** BROWSE(Project) 게이트만 제어하는 stub — 시나리오별로 단일 boolean 결과를 반환한다. */
    private class StubPermissionResolver(
        var browseAllowed: Boolean,
    ) : IssuePermissionResolver {
        override fun hasPermission(
            actorId: UUID,
            permission: IssuePermission,
            scope: IssueScope,
        ): Boolean = browseAllowed
    }

    /** [BoardIssueLookupAdapterTest.StubSecurityDirectory] 동형 — 손수 만든 [IssueSecurityAccess] 를 반환한다. */
    private class StubSecurityDirectory(
        var next: IssueSecurityAccess,
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

    private class StubWorkflowStateCatalog(
        private val states: List<WorkflowStateView>,
    ) : WorkflowStateCatalog {
        override fun listStates(
            projectKey: ProjectKey,
            issueTypeKey: IssueTypeKey?,
        ): List<WorkflowStateView> = states
    }

    /** WorkflowStateCatalog.listStates(MANDATORY) 예외 전파 검증용 — 항상 [error] 를 던진다. */
    private class ThrowingWorkflowStateCatalog(
        private val error: RuntimeException,
    ) : WorkflowStateCatalog {
        override fun listStates(
            projectKey: ProjectKey,
            issueTypeKey: IssueTypeKey?,
        ): List<WorkflowStateView> = throw error
    }

    private class StubUserLookupPort(
        private val names: Map<UUID, String>,
    ) : UserLookupPort {
        override fun exists(userId: UUID): Boolean = names.containsKey(userId)

        override fun findDisplayNamesByIds(ids: Set<UUID>): Map<UUID, String> = names.filterKeys { it in ids }
    }

    private fun unrestricted() =
        IssueSecurityAccess(
            unrestricted = true,
            staticLevelIds = emptySet(),
            reporterLevelIds = emptySet(),
            assigneeLevelIds = emptySet(),
        )

    private fun restricted(staticLevelIds: Set<UUID> = emptySet()) =
        IssueSecurityAccess(
            unrestricted = false,
            staticLevelIds = staticLevelIds,
            reporterLevelIds = emptySet(),
            assigneeLevelIds = emptySet(),
        )

    @Suppress("LongParameterList")
    private fun adapterWith(
        browseAllowed: Boolean = true,
        access: IssueSecurityAccess = unrestricted(),
        states: List<WorkflowStateView> = listOf(WorkflowStateView(key = "open", name = "열림")),
        names: Map<UUID, String> = emptyMap(),
        catalog: WorkflowStateCatalog = StubWorkflowStateCatalog(states),
    ): IssueUnfurlAdapter =
        IssueUnfurlAdapter(
            issueRepository = repository,
            issueTypeRepository = typeRepository,
            permissionResolver = StubPermissionResolver(browseAllowed),
            securityDirectory = StubSecurityDirectory(access),
            workflowStateCatalog = catalog,
            userLookupPort = StubUserLookupPort(names),
        )

    /** 테스트용 이슈 생성 helper — [BoardIssueLookupAdapterTest.insertIssue] 동형. */
    @Suppress("LongParameterList")
    private fun insertIssue(
        seq: Long,
        reporterId: UUID = UUID.randomUUID(),
        assigneeId: UUID? = null,
        securityLevelId: UUID? = null,
        currentStateKey: String = "open",
        priority: Int = 3,
        summary: String = "unfurl issue $seq",
    ): Issue =
        repository.insert(
            Issue.create(
                id = IssueId(UUID.randomUUID()),
                key = IssueKey.of("TPRJ", seq),
                projectId = testProjectId,
                typeId = requireTaskTypeId(),
                summary = summary,
                reporterId = ActorId(reporterId),
                currentStateKey = currentStateKey,
                priority = priority,
                assigneeId = assigneeId?.let { ActorId(it) },
                securityLevelId = securityLevelId,
            ),
        )

    // ── 시나리오 ───────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    fun `가시 이슈는 상태·우선순위·담당자 라벨이 해석된 View 를 반환한다`() {
        val viewer = UUID.randomUUID()
        val assignee = UUID.randomUUID()
        insertIssue(
            seq = 1,
            assigneeId = assignee,
            securityLevelId = null,
            currentStateKey = "open",
            priority = IssuePriority.HIGH.number,
            summary = "unfurl target",
        )

        val adapter =
            adapterWith(
                browseAllowed = true,
                access = unrestricted(),
                states = listOf(WorkflowStateView(key = "open", name = "열림")),
                names = mapOf(assignee to "홍길동"),
            )

        val result = adapter.getVisibleIssueCard("TPRJ-1", viewer)

        val view = requireNotNull(result) { "가시 이슈는 null 이 아니어야 한다" }
        assertThat(view.issueKey).isEqualTo("TPRJ-1")
        assertThat(view.summary).isEqualTo("unfurl target")
        assertThat(view.statusLabel).isEqualTo("열림")
        assertThat(view.priorityLabel).isEqualTo(IssuePriority.HIGH.displayName)
        assertThat(view.assigneeDisplayName).isEqualTo("홍길동")
    }

    @Test
    @Order(2)
    fun `담당자가 없는 이슈는 assigneeDisplayName 이 null 이다`() {
        val viewer = UUID.randomUUID()
        insertIssue(seq = 1, assigneeId = null, securityLevelId = null)

        val adapter = adapterWith()

        val result = adapter.getVisibleIssueCard("TPRJ-1", viewer)

        val view = requireNotNull(result) { "가시 이슈는 null 이 아니어야 한다" }
        assertThat(view.assigneeDisplayName).isNull()
    }

    @Test
    @Order(3)
    fun `프로젝트 BROWSE 권한이 없으면 null 을 반환한다`() {
        val viewer = UUID.randomUUID()
        insertIssue(seq = 1, securityLevelId = null)

        val adapter = adapterWith(browseAllowed = false)

        val result = adapter.getVisibleIssueCard("TPRJ-1", viewer)

        assertThat(result).isNull()
    }

    @Test
    @Order(4)
    fun `형식이 잘못된 이슈 키는 null 을 반환한다`() {
        val adapter = adapterWith()

        val result = adapter.getVisibleIssueCard("not-a-valid-key", UUID.randomUUID())

        assertThat(result).isNull()
    }

    @Test
    @Order(5)
    fun `존재하지 않는 이슈 키는 null 을 반환한다`() {
        val adapter = adapterWith()

        val result = adapter.getVisibleIssueCard("TPRJ-999", UUID.randomUUID())

        assertThat(result).isNull()
    }

    @Test
    @Order(6)
    fun `accessibleLevels 가 빈 등급 집합이면(미상) 보안등급 이슈를 거부한다`() {
        val viewer = UUID.randomUUID()
        val secretLevel = UUID.randomUUID()
        insertIssue(seq = 1, securityLevelId = secretLevel)

        // BROWSE 는 통과(프로젝트 멤버)하지만 accessibleLevels 는 빈 집합(어떤 등급도 접근 불가) → 거부.
        val adapter = adapterWith(browseAllowed = true, access = restricted())

        val result = adapter.getVisibleIssueCard("TPRJ-1", viewer)

        assertThat(result).isNull()
    }

    @Test
    @Order(7)
    fun `WorkflowStateCatalog 가 예외를 던지면 캐치하지 않고 그대로 전파한다`() {
        val viewer = UUID.randomUUID()
        insertIssue(seq = 1, securityLevelId = null)

        val boom = IllegalStateException("workflow scheme not configured")
        val adapter = adapterWith(catalog = ThrowingWorkflowStateCatalog(boom))

        assertThatThrownBy { adapter.getVisibleIssueCard("TPRJ-1", viewer) }
            .isSameAs(boom)
    }
}
