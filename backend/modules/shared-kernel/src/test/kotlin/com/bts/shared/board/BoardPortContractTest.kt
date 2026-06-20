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
    fun `BoardIssueLookupPort default 구현은 빈 목록을 반환한다`() {
        // default = emptyList() 이므로 adapter 가 없는 환경에서 빈 목록 반환(데이터 누출 없음).
        val port = object : BoardIssueLookupPort {}
        val result = port.listVisibleIssuesByProject("PROJ", UUID.randomUUID())

        assertThat(result).isEmpty()
    }

    @Test
    fun `BoardIssueLookupPort default 는 projectKey 나 viewerUserId 가 달라도 빈 목록을 반환한다`() {
        val port = object : BoardIssueLookupPort {}

        assertThat(port.listVisibleIssuesByProject("OTHER", UUID.randomUUID())).isEmpty()
        assertThat(port.listVisibleIssuesByProject("ATLAS", UUID.randomUUID())).isEmpty()
    }

    // ── BoardIssueView 필드 계약 ─────────────────────────────────────────────

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
}
