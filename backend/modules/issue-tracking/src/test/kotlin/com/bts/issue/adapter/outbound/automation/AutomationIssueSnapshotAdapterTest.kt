// AutomationIssueSnapshotAdapter Testcontainers 통합 테스트 — actor 가시성 강제(보안 등급 게이트) + 표현 매핑 pin (FR-AT-03 Task 6)

package com.bts.issue.adapter.outbound.automation

import com.bts.issue.application.IssueApplicationService
import com.bts.issue.domain.ActorId
import com.bts.issue.domain.Issue
import com.bts.issue.domain.IssueId
import com.bts.issue.domain.IssueKey
import com.bts.issue.event.IssueEventPublisher
import com.bts.issue.repository.IssueTestcontainersBase
import com.bts.issue.type.repository.IssueTypeRepository
import com.bts.shared.issue.IssueTypeId
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import com.bts.shared.user.UserLookupPort
import com.bts.shared.workflow.WorkflowKeyResolver
import com.bts.shared.workflow.WorkflowTransitionPort
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import java.sql.DriverManager
import java.util.UUID

/**
 * [AutomationIssueSnapshotAdapter] 통합 테스트 (FR-AT-03 Task 6).
 *
 * ## 왜 Testcontainers 인가
 * [AutomationIssueSnapshotAdapter.fetch] 가 재사용하는 [IssueApplicationService.findByKey] 는 실
 * jOOQ 조인 쿼리로 `issue_types.name`(타입 이름)을 해석해 반환한다. 손수 구성한 DTO 로는 이 조인
 * 결과가 실제 DB 시드([V003__issue_types.sql])와 일치하는지 검증할 수 없다 — 실 DB round-trip 으로
 * [com.bts.shared.issue.IssueSnapshot] 표현(타입 이름 문자열/상태 stateKey/우선순위 숫자)이 정확히
 * 고정되는지(C1) 를 pin 한다.
 *
 * ## 보안 등급 게이트 — 실제 identity-access 엔진이 아닌 대체 stub
 * VIEW 권한(보안 등급 멤버십 게이트 포함)의 실제 판정 로직은 identity-access BC 의
 * `IdentityAccessIssuePermissionResolver`(`@Profile("prod")`)가 담당하며 issue-tracking 컴파일
 * 범위 밖이다(BC 격리). 이 테스트는 [IssuePermissionResolver] 를 손수 구현한 stub 으로 대체해 VIEW
 * boolean 판정 결과만 제어한다 — [IssueApplicationService.findByKey] 가 그 결과를 소비하는 경로
 * (`assertViewIssueOrNotFound` → `IssueNotFoundException` → 어댑터 null 매핑)는 실제 프로덕션
 * 코드 경로 그대로 실행된다. [com.bts.issue.adapter.IssueUnfurlAdapterIntegrationTest] 의
 * `StubPermissionResolver` 동형 패턴.
 *
 * ## 검증 시나리오
 * - (a) 가시 이슈 — type/status/priority/labels 실제 표현값 pin.
 * - (b) 존재하지 않는 이슈 — null.
 * - (c) 보안 등급 제한 이슈 + VIEW 거부(비멤버 시뮬레이션) — null(§12.4 가시성 강제).
 * - (d) 같은 보안 등급 제한 이슈 + VIEW 허용(멤버 시뮬레이션) — 정상 반환.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class AutomationIssueSnapshotAdapterTest : IssueTestcontainersBase() {
    private var bugTypeId: IssueTypeId? = null

    /** V003 seed 에서 bug 타입 id 조회 — [com.bts.issue.adapter.IssueUnfurlAdapterIntegrationTest.resolveTaskTypeId] 동형. */
    @BeforeAll
    fun resolveBugTypeId() {
        DriverManager.getConnection(postgres.jdbcUrl, postgres.username, postgres.password).use { conn ->
            conn.prepareStatement("SELECT id FROM issue_types WHERE key = 'bug' AND deleted_at IS NULL LIMIT 1")
                .use { stmt ->
                    stmt.executeQuery().use { rs ->
                        check(rs.next()) { "V003 마이그레이션에서 bug 타입이 없습니다." }
                        bugTypeId = IssueTypeId(rs.getLong(1))
                    }
                }
        }
    }

    private fun requireBugTypeId(): IssueTypeId = requireNotNull(bugTypeId) { "bugTypeId 가 초기화되지 않았습니다." }

    /** VIEW 판정을 손수 제어하는 stub — [decide] 로 (actorId, permission, scope) 별 결과를 지정한다. */
    private class StubViewGate(
        private val decide: (UUID, IssuePermission, IssueScope) -> Boolean = { _, _, _ -> true },
    ) : IssuePermissionResolver {
        override fun hasPermission(
            actorId: UUID,
            permission: IssuePermission,
            scope: IssueScope,
        ): Boolean = decide(actorId, permission, scope)
    }

    /**
     * [IssueApplicationService] 를 Testcontainers 실 DB 기반 [repository] 에 연결하고, VIEW 판정만
     * [permissionResolver] stub 으로 제어한다. findByKey 경로가 쓰지 않는 협력자(워크플로우 전이/
     * 사용자 조회 등)는 relaxed mock 으로 대체한다.
     */
    private fun adapterWith(resolver: IssuePermissionResolver = StubViewGate()): AutomationIssueSnapshotAdapter {
        val service =
            IssueApplicationService(
                repo = repository,
                issueTypeRepository = mockk<IssueTypeRepository>(relaxed = true),
                resolutionRepository = mockk(relaxed = true),
                eventPublisher = mockk<IssueEventPublisher>(relaxed = true),
                permissionResolver = resolver,
                workflowPort = mockk<WorkflowTransitionPort>(relaxed = true),
                workflowKeyResolver = mockk<WorkflowKeyResolver>(relaxed = true),
                userLookupPort = mockk<UserLookupPort>(relaxed = true),
                componentRepository = mockk(relaxed = true),
                projectLeadRepository = mockk(relaxed = true),
                versionRepository = mockk(relaxed = true),
                historyRecorder = mockk(relaxed = true),
            )
        return AutomationIssueSnapshotAdapter(service)
    }

    /** 테스트용 이슈 생성 helper — [com.bts.issue.adapter.IssueUnfurlAdapterIntegrationTest.insertIssue] 동형. */
    @Suppress("LongParameterList")
    private fun insertIssue(
        seq: Long,
        reporterId: UUID = UUID.randomUUID(),
        assigneeId: UUID? = null,
        securityLevelId: UUID? = null,
        currentStateKey: String = "open",
        priority: Int = 1,
        labels: List<String> = listOf("urgent", "regression"),
        summary: String = "스냅샷 매핑 테스트 이슈",
    ): Issue =
        repository.insert(
            Issue.create(
                id = IssueId(UUID.randomUUID()),
                key = IssueKey.of("TPRJ", seq),
                projectId = testProjectId,
                typeId = requireBugTypeId(),
                summary = summary,
                reporterId = ActorId(reporterId),
                currentStateKey = currentStateKey,
                priority = priority,
                labels = labels,
                assigneeId = assigneeId?.let { ActorId(it) },
                securityLevelId = securityLevelId,
            ),
        )

    @Test
    @Order(1)
    fun `a — 가시 이슈는 type 이름_status stateKey_priority 숫자_labels 로 정확히 매핑된다`() {
        val viewer = UUID.randomUUID()
        val assignee = UUID.randomUUID()
        val reporter = UUID.randomUUID()
        insertIssue(
            seq = 1,
            reporterId = reporter,
            assigneeId = assignee,
            currentStateKey = "in_progress",
            priority = 1,
            labels = listOf("urgent", "regression"),
            summary = "결제 실패",
        )

        val snapshot = adapterWith().fetch(viewer, "TPRJ-1")

        val result = requireNotNull(snapshot) { "가시 이슈는 null 이 아니어야 한다" }
        assertThat(result.key).isEqualTo("TPRJ-1")
        assertThat(result.projectKey).isEqualTo("TPRJ")
        assertThat(result.type).isEqualTo("Bug")
        assertThat(result.status).isEqualTo("in_progress")
        assertThat(result.priority).isEqualTo(1)
        assertThat(result.labels).containsExactly("urgent", "regression")
        assertThat(result.assigneeId).isEqualTo(assignee)
        assertThat(result.reporterId).isEqualTo(reporter)
        assertThat(result.summary).isEqualTo("결제 실패")
    }

    @Test
    @Order(2)
    fun `b — 존재하지 않는 이슈는 null 을 반환한다`() {
        val result = adapterWith().fetch(UUID.randomUUID(), "TPRJ-999")

        assertThat(result).isNull()
    }

    @Test
    @Order(3)
    fun `c — 보안 등급 제한 이슈 + VIEW 거부(비멤버 시뮬레이션)는 null 을 반환한다`() {
        val nonMember = UUID.randomUUID()
        insertIssue(seq = 1, securityLevelId = UUID.randomUUID())
        val gate =
            StubViewGate { actorId, permission, scope ->
                !(actorId == nonMember && permission == IssuePermission.VIEW && scope is IssueScope.Issue)
            }

        val result = adapterWith(gate).fetch(nonMember, "TPRJ-1")

        assertThat(result).isNull()
    }

    @Test
    @Order(4)
    fun `d — 보안 등급 제한 이슈 + VIEW 허용(멤버 시뮬레이션)은 정상 반환된다`() {
        val member = UUID.randomUUID()
        val levelId = UUID.randomUUID()
        insertIssue(seq = 1, securityLevelId = levelId, currentStateKey = "open")

        val result = adapterWith(StubViewGate()).fetch(member, "TPRJ-1")

        val snapshot = requireNotNull(result) { "VIEW 허용 시 정상 반환되어야 한다" }
        assertThat(snapshot.status).isEqualTo("open")
    }
}
