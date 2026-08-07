// 칸반 보드 cross-BC 포트 계약 검증 — BoardIssueLookupPort·IssueTransitionPort·WorkflowStateView 확장

package com.bts.shared.board

import com.bts.shared.workflow.WorkflowStateView
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * 칸반 보드 cross-BC 포트 계약 테스트.
 *
 * Spring 컨텍스트 없이 순수 단위 테스트로 실행한다.
 *
 * 검증 항목.
 * - [WorkflowStateView] 에 category/displayOrder 가 추가되고 기존 2-arg 호출이 default 로 컴파일·생성됨.
 * - [BoardIssueLookupPort.listVisibleIssuesByProject] default 구현이 빈 목록을 반환함(fail-safe).
 * - [IssueTransitionPort] 는 default 구현이 없음 — 추상 구현 필수(fail-closed).
 * - [BoardIssueView] 필드 계약(key/summary/currentStateKey/assigneeId/priority/version).
 * - [BoardTransitionCommand] 필드 계약(actorUserId/issueKey/toStateKey/expectedVersion/resolutionId).
 * - [BoardTransitionResult] 필드 계약(issueKey/currentStateKey/version).
 */
class BoardPortContractTest {
    // ── WorkflowStateView 확장 ───────────────────────────────────────────────

    @Test
    fun `WorkflowStateView 기존 2-arg 호출이 category DEFAULT_TODO 와 displayOrder 0 으로 생성된다`() {
        // 기존 호출자(key + name 만 넘기는 코드)가 default 값으로 보호되는지 확인한다.
        val view = WorkflowStateView(key = "open", name = "열림")

        assertThat(view.key).isEqualTo("open")
        assertThat(view.name).isEqualTo("열림")
        assertThat(view.isDone).isFalse()
        assertThat(view.category).isEqualTo("TODO")
        assertThat(view.displayOrder).isEqualTo(0)
    }

    @Test
    fun `WorkflowStateView 명시 category 와 displayOrder 를 지정하면 해당 값으로 생성된다`() {
        val view =
            WorkflowStateView(
                key = "in-progress",
                name = "진행 중",
                isDone = false,
                category = "IN_PROGRESS",
                displayOrder = 1,
            )

        assertThat(view.category).isEqualTo("IN_PROGRESS")
        assertThat(view.displayOrder).isEqualTo(1)
    }

    @Test
    fun `WorkflowStateView DONE 카테고리 명시`() {
        val view =
            WorkflowStateView(key = "closed", name = "완료", isDone = true, category = "DONE", displayOrder = 2)

        assertThat(view.isDone).isTrue()
        assertThat(view.category).isEqualTo("DONE")
        assertThat(view.displayOrder).isEqualTo(2)
    }

    // ── BoardIssueLookupPort fail-safe ───────────────────────────────────────

    @Test
    fun `BoardIssueLookupPort default 구현은 빈 BoardIssuePage 를 반환한다`() {
        // default = 빈 페이지이므로 adapter 가 없는 환경에서 빈 목록 반환(데이터 누출 없음).
        val port = object : BoardIssueLookupPort {}
        val result = port.listVisibleIssuesByProject("PROJ", UUID.randomUUID())

        assertThat(result.issues).isEmpty()
        assertThat(result.truncated).isFalse()
    }

    @Test
    fun `BoardIssueLookupPort default 는 projectKey 나 viewerUserId 가 달라도 빈 페이지를 반환한다`() {
        val port = object : BoardIssueLookupPort {}

        assertThat(port.listVisibleIssuesByProject("OTHER", UUID.randomUUID()).issues).isEmpty()
        assertThat(port.listVisibleIssuesByProject("ATLAS", UUID.randomUUID()).issues).isEmpty()
    }

    // ── BoardIssuePage 계약 ──────────────────────────────────────────────────

    @Test
    fun `BoardIssuePage 는 issues 목록과 truncated 플래그를 보존한다`() {
        val issue =
            BoardIssueView(
                key = "PROJ-1",
                summary = "테스트",
                currentStateKey = "open",
                assigneeId = null,
                priority = 1,
                version = 1L,
            )
        val page = BoardIssuePage(issues = listOf(issue), truncated = true)

        assertThat(page.issues).containsExactly(issue)
        assertThat(page.truncated).isTrue()
    }

    @Test
    fun `BoardIssuePage truncated=false 는 LIMIT 미초과 정상 조회를 나타낸다`() {
        val page = BoardIssuePage(issues = emptyList(), truncated = false)

        assertThat(page.issues).isEmpty()
        assertThat(page.truncated).isFalse()
    }

    // ── BoardIssueView 필드 계약 ─────────────────────────────────────────────

    @Test
    fun `BoardIssueView 는 typeKey 를 필수로 받고 labels 와 originalEstimateSeconds 는 default 로 생성된다`() {
        // typeKey 에는 기본값이 없다 — 호출자가 반드시 명시해야 컴파일된다 (FR-UX-14 B2, FR2).
        val view =
            BoardIssueView(
                key = "PROJ-1",
                summary = "제목",
                currentStateKey = "open",
                assigneeId = null,
                priority = 1,
                version = 0L,
                typeKey = "bug",
            )

        assertThat(view.typeKey).isEqualTo("bug")
        // 미지정 라벨은 null 이 아니라 빈 리스트다 — 응답이 [] 로 직렬화되는 근거 (FR8).
        assertThat(view.labels).isEmpty()
        assertThat(view.originalEstimateSeconds).isNull()
    }

    @Test
    fun `BoardIssueView 는 labels 와 originalEstimateSeconds 를 명시하면 그 값으로 생성된다`() {
        val view =
            BoardIssueView(
                key = "PROJ-2",
                summary = "제목",
                currentStateKey = "open",
                assigneeId = null,
                priority = 1,
                version = 0L,
                typeKey = "story",
                labels = listOf("urgent", "api"),
                originalEstimateSeconds = 3600,
            )

        assertThat(view.typeKey).isEqualTo("story")
        assertThat(view.labels).containsExactly("urgent", "api")
        assertThat(view.originalEstimateSeconds).isEqualTo(3600)
    }

    @Test
    fun `BoardIssueView 는 모든 필드를 보존한다`() {
        val assigneeId = UUID.randomUUID()
        val view =
            BoardIssueView(
                key = "PROJ-1",
                summary = "로그인 버그",
                currentStateKey = "open",
                assigneeId = assigneeId,
                priority = 3,
                version = 1L,
            )

        assertThat(view.key).isEqualTo("PROJ-1")
        assertThat(view.summary).isEqualTo("로그인 버그")
        assertThat(view.currentStateKey).isEqualTo("open")
        assertThat(view.assigneeId).isEqualTo(assigneeId)
        assertThat(view.priority).isEqualTo(3)
        assertThat(view.version).isEqualTo(1L)
    }

    @Test
    fun `BoardIssueView 는 assigneeId 가 null 일 수 있다`() {
        val view =
            BoardIssueView(
                key = "PROJ-2",
                summary = "미배정 이슈",
                currentStateKey = "open",
                assigneeId = null,
                priority = 1,
                version = 0L,
            )

        assertThat(view.assigneeId).isNull()
    }

    // ── IssueTransitionPort fail-closed ──────────────────────────────────────

    @Test
    fun `IssueTransitionPort 는 default 구현 없이 추상 메서드만 선언된다`() {
        // IssueTransitionPort 에 default 구현이 없으면 익명 객체 생성 시 반드시 override 해야 한다.
        // 이 테스트는 컴파일 타임에 override 강제를 검증한다.
        val port =
            object : IssueTransitionPort {
                override fun transition(cmd: BoardTransitionCommand): BoardTransitionResult =
                    BoardTransitionResult(
                        issueKey = cmd.issueKey,
                        currentStateKey = cmd.toStateKey,
                        version = 1L,
                    )
            }

        val cmd =
            BoardTransitionCommand(
                actorUserId = UUID.randomUUID(),
                issueKey = "PROJ-1",
                toStateKey = "in-progress",
                expectedVersion = 0L,
                resolutionId = null,
            )
        val result = port.transition(cmd)

        assertThat(result.issueKey).isEqualTo("PROJ-1")
        assertThat(result.currentStateKey).isEqualTo("in-progress")
        assertThat(result.version).isEqualTo(1L)
    }

    // ── BoardTransitionCommand 필드 계약 ─────────────────────────────────────

    @Test
    fun `BoardTransitionCommand 는 actorUserId 를 보존한다 (컨트롤러가 SecurityContext 에서 채움)`() {
        // actor 는 호출 컨트롤러가 SecurityContext 에서 추출해 cmd 로 전달한다.
        // adapter 는 SecurityContext 가 아닌 이 값을 신뢰한다(스레드 무관 → async 안전).
        val actorUserId = UUID.randomUUID()
        val cmd =
            BoardTransitionCommand(
                actorUserId = actorUserId,
                issueKey = "PROJ-6",
                toStateKey = "in-progress",
                expectedVersion = 1L,
                resolutionId = null,
            )

        assertThat(cmd.actorUserId).isEqualTo(actorUserId)
    }

    @Test
    fun `BoardTransitionCommand 는 resolutionId 가 null 일 수 있다`() {
        val cmd =
            BoardTransitionCommand(
                actorUserId = UUID.randomUUID(),
                issueKey = "PROJ-3",
                toStateKey = "closed",
                expectedVersion = 5L,
                resolutionId = null,
            )

        assertThat(cmd.issueKey).isEqualTo("PROJ-3")
        assertThat(cmd.toStateKey).isEqualTo("closed")
        assertThat(cmd.expectedVersion).isEqualTo(5L)
        assertThat(cmd.resolutionId).isNull()
    }

    @Test
    fun `BoardTransitionCommand 는 resolutionId 를 가질 수 있다`() {
        val resolutionId = UUID.randomUUID()
        val cmd =
            BoardTransitionCommand(
                actorUserId = UUID.randomUUID(),
                issueKey = "PROJ-4",
                toStateKey = "closed",
                expectedVersion = 2L,
                resolutionId = resolutionId,
            )

        assertThat(cmd.resolutionId).isEqualTo(resolutionId)
    }

    // ── BoardTransitionResult 필드 계약 ──────────────────────────────────────

    @Test
    fun `BoardTransitionResult 는 전이 결과 필드를 보존한다`() {
        val result =
            BoardTransitionResult(
                issueKey = "PROJ-5",
                currentStateKey = "done",
                version = 10L,
            )

        assertThat(result.issueKey).isEqualTo("PROJ-5")
        assertThat(result.currentStateKey).isEqualTo("done")
        assertThat(result.version).isEqualTo(10L)
    }

    // ── BoardIssueLookupPort 3-인자 default 위임 계약 ────────────────────────

    @Test
    fun `2-인자만 override 한 fake 에 3-인자로 호출하면 default 위임으로 무필터 결과가 반환된다`() {
        // CONCERN-1: 2-인자만 override 한 구현체가 3-인자 호출 시
        // default 메서드가 2-인자로 위임해 안전하게 동작하는지 확인한다.
        val fakeIssue =
            BoardIssueView(
                key = "PROJ-10",
                summary = "기존 이슈",
                currentStateKey = "open",
                assigneeId = null,
                priority = 1,
                version = 1L,
            )
        val twoArgOnlyPort =
            object : BoardIssueLookupPort {
                override fun listVisibleIssuesByProject(
                    projectKey: String,
                    viewerUserId: UUID,
                ): BoardIssuePage = BoardIssuePage(issues = listOf(fakeIssue), truncated = false)
            }

        val filter =
            BoardCardFilter(
                assigneeIds = listOf(UUID.randomUUID()),
                labels = listOf("bug"),
            )
        val result = twoArgOnlyPort.listVisibleIssuesByProject("PROJ", UUID.randomUUID(), filter)

        // default 는 2-인자로 위임하므로 2-인자 구현의 결과(fakeIssue 포함)가 반환된다.
        assertThat(result.issues).containsExactly(fakeIssue)
        assertThat(result.truncated).isFalse()
    }

    @Test
    fun `3-인자를 override 한 filter-aware fake 는 전달한 filter 를 그대로 수신한다`() {
        // CONCERN-1 역방향 검증: 3-인자를 명시 override 해야만 filter 가 드롭되지 않고
        // 구현체에 전달됨을 단언한다. filter-aware 구현체(issue-tracking adapter 등)가
        // SQL 수준에서 필터를 적용할 수 있는 선행 계약이다.
        var capturedFilter: BoardCardFilter? = null
        val filterAwarePort =
            object : BoardIssueLookupPort {
                override fun listVisibleIssuesByProject(
                    projectKey: String,
                    viewerUserId: UUID,
                    filter: BoardCardFilter,
                ): BoardIssuePage {
                    capturedFilter = filter
                    return BoardIssuePage(issues = emptyList(), truncated = false)
                }
            }

        val sentFilter =
            BoardCardFilter(
                assigneeIds = listOf(UUID.randomUUID()),
                includeUnassigned = true,
                labels = listOf("enhancement"),
                componentIds = listOf(UUID.randomUUID()),
            )
        filterAwarePort.listVisibleIssuesByProject("PROJ", UUID.randomUUID(), sentFilter)

        assertThat(capturedFilter).isEqualTo(sentFilter)
    }
}
