// 시작 상태 해석이 INITIAL 전환을 따르는지 검증 — 상태 displayOrder 를 바꿔도 흔들리면 안 된다 (C4)

package com.bts.workflow.scheme

import com.bts.shared.workflow.ProjectKey
import com.bts.workflow.domain.StateCategory
import com.bts.workflow.domain.TransitionKind
import com.bts.workflow.domain.Workflow
import com.bts.workflow.domain.WorkflowState
import com.bts.workflow.domain.WorkflowTransition
import com.bts.workflow.scheme.adapter.inbound.WorkflowKeyResolverImpl
import com.bts.workflow.scheme.port.outbound.WorkflowResolver
import io.mockk.every
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * [WorkflowKeyResolverImpl] 의 시작 상태 해석 규칙 단위 테스트.
 *
 * ## 이 테스트가 닫는 결함 (C4)
 * 종전 구현은 `workflow.states.minByOrNull { it.displayOrder }` 로 시작 상태를 정했다.
 * 즉 관리자가 편집기에서 **상태 표시 순서를 바꾸는 순간 이슈 생성 상태가 조용히 바뀐다.**
 * 읽기 전용이던 시절에는 드러나지 않던 결함이 편집(FR-WF-04~07)을 열면 바로 사고가 된다.
 * 새 규칙은 [TransitionKind.INITIAL] 전환의 도착 상태가 시작 상태이며,
 * INITIAL 이 없을 때만 종전 `minByOrNull` 로 폴백한다 (V207 백필 미도달 워크플로우 방어).
 *
 * ## 픽스처 설계 — 「INITIAL 이 가리키는 상태가 나온다」만으로는 부족하다
 * 그 단언은 폴백 구현으로도 우연히 통과할 수 있다. 그래서 **같은 상태 집합에 displayOrder 만
 * 정반대로 뒤집은 픽스처 2개**를 만들어 두 결과가 동일한지 본다. 폴백 구현이면 두 결과가 갈린다.
 *
 * ## 두 메서드 모두 본다
 * 시작 상태 해석은 [WorkflowKeyResolverImpl.resolveStart] (쓰기 경로) 와
 * [WorkflowKeyResolverImpl.resolveExisting] (읽기 경로) 두 곳에 있으므로 매 케이스마다 둘 다 단언한다.
 *
 * 결정 근거. ADR `docs/adr/2026-08-18-workflow-transition-id-identity.md` §맥락 · §D2.
 */
class WorkflowStartStateInitialTest {
    private val workflowResolver = mockk<WorkflowResolver>()
    private val sut = WorkflowKeyResolverImpl(workflowResolver)
    private val projectKey = ProjectKey("ATLAS")

    // ── 1. C4 — 순서를 뒤집어도 시작 상태는 그대로여야 한다 ────────────────────────

    @Test
    fun `상태 display_order 를 바꿔도 시작 상태가 안 바뀐다`() {
        val initial = initialTransitionTo("open")
        val ascending =
            workflowOf(
                states = statesOrderedBy(openOrder = 1, inProgressOrder = 2, doneOrder = 3),
                transitions = listOf(initial),
            )
        val descending =
            workflowOf(
                states = statesOrderedBy(openOrder = 3, inProgressOrder = 2, doneOrder = 1),
                transitions = listOf(initial),
            )

        assertThat(resolveStartKey(descending))
            .describedAs("resolveStart — displayOrder 를 뒤집어도 INITIAL 도착 상태가 시작 상태다")
            .isEqualTo(resolveStartKey(ascending))
            .isEqualTo("open")

        assertThat(resolveExistingKey(descending))
            .describedAs("resolveExisting — displayOrder 를 뒤집어도 INITIAL 도착 상태가 시작 상태다")
            .isEqualTo(resolveExistingKey(ascending))
            .isEqualTo("open")
    }

    // ── 2. INITIAL 전환이 시작 상태를 정한다 ──────────────────────────────────────

    @Test
    fun `INITIAL 전환이 가리키는 상태가 시작 상태다`() {
        // displayOrder 최소는 open 이지만 INITIAL 은 in_progress 를 가리킨다.
        val workflow =
            workflowOf(
                states = statesOrderedBy(openOrder = 1, inProgressOrder = 2, doneOrder = 3),
                transitions =
                    listOf(
                        initialTransitionTo("in_progress"),
                        WorkflowTransition("open", "done", "Resolve"),
                    ),
            )

        assertThat(resolveStartKey(workflow)).isEqualTo("in_progress")
        assertThat(resolveExistingKey(workflow)).isEqualTo("in_progress")
    }

    // ── 3. INITIAL 부재 시 종전 폴백 (V207 백필 미도달 방어) ──────────────────────

    @Test
    fun `INITIAL 전환이 없으면 종전 minByOrNull 폴백`() {
        // displayOrder 최소는 done 이고 INITIAL 이 없으므로 종전 규칙대로 done 이 시작 상태다.
        val workflow =
            workflowOf(
                states = statesOrderedBy(openOrder = 3, inProgressOrder = 2, doneOrder = 1),
                transitions = listOf(WorkflowTransition("open", "done", "Resolve")),
            )

        assertThat(resolveStartKey(workflow)).isEqualTo("done")
        assertThat(resolveExistingKey(workflow)).isEqualTo("done")
    }

    // ── 픽스처 ────────────────────────────────────────────────────────────────────

    private fun resolveStartKey(workflow: Workflow): String {
        every { workflowResolver.resolveFor(any(), any()) } returns workflow
        return sut.resolveStart(projectKey, null).startStateKey
    }

    private fun resolveExistingKey(workflow: Workflow): String? {
        every { workflowResolver.resolveExistingFor(any(), any()) } returns workflow
        return sut.resolveExisting(projectKey, null)?.startStateKey
    }

    private fun statesOrderedBy(
        openOrder: Int,
        inProgressOrder: Int,
        doneOrder: Int,
    ): List<WorkflowState> =
        listOf(
            WorkflowState("open", "Open", StateCategory.TODO, openOrder),
            WorkflowState("in_progress", "In Progress", StateCategory.IN_PROGRESS, inProgressOrder),
            WorkflowState("done", "Done", StateCategory.DONE, doneOrder),
        )

    private fun initialTransitionTo(stateKey: String): WorkflowTransition =
        WorkflowTransition(
            fromStateKey = null,
            toStateKey = stateKey,
            name = "Create",
            kind = TransitionKind.INITIAL,
        )

    private fun workflowOf(
        states: List<WorkflowState>,
        transitions: List<WorkflowTransition>,
    ): Workflow =
        Workflow.of(
            key = "software-default",
            name = "Software Default",
            states = states,
            transitions = transitions,
        )
}
