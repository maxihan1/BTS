// Workflow.of() 의 전환 ID 기반 invariant 검증 — 다중 전환 · 전역 전환 · 최초 전환

package com.bts.workflow.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * FR-WF-05 — 전환 identity 가 (from, to) 2튜플에서 전환 ID(UUID) 로 옮겨간 뒤의 `Workflow.of()` invariant.
 *
 * 검증 대상.
 * 1. 같은 (from, to) 에 이름이 다른 전환 2개를 받는다 (구 invariant 5번 삭제 — C1 도메인 층)
 * 2. GLOBAL 전환의 fromStateKey 는 null 이어야 한다 (값이 있으면 거부)
 * 3. INITIAL 전환은 워크플로우당 최대 1개
 * 4. NORMAL 전환의 fromStateKey 가 null 이면 거부
 *
 * 근거. ADR `docs/adr/2026-08-18-workflow-transition-id-identity.md` §D1 · §D2 · §D4.
 */
class WorkflowTest {
    // ── 픽스처 ────────────────────────────────────────────────────────────────

    private val todoState =
        WorkflowState(key = "TODO", name = "할 일", category = StateCategory.TODO, displayOrder = 1)

    private val inProgressState =
        WorkflowState(key = "IN_PROGRESS", name = "진행 중", category = StateCategory.IN_PROGRESS, displayOrder = 2)

    private val doneState =
        WorkflowState(key = "DONE", name = "완료", category = StateCategory.DONE, displayOrder = 3)

    private val defaultStates = listOf(todoState, inProgressState, doneState)

    private fun transition(
        from: String?,
        to: String,
        name: String,
        kind: TransitionKind = TransitionKind.NORMAL,
    ) = WorkflowTransition(
        id = UUID.randomUUID(),
        fromStateKey = from,
        toStateKey = to,
        name = name,
        kind = kind,
    )

    // ── C1. 다중 전환 ─────────────────────────────────────────────────────────

    @Test
    fun `같은 (from,to) 에 이름이 다른 전환 2개를 of() 가 받는다`() {
        val transitions =
            listOf(
                transition(from = "TODO", to = "IN_PROGRESS", name = "시작"),
                transition(from = "TODO", to = "IN_PROGRESS", name = "긴급 시작"),
            )

        val workflow =
            Workflow.of(
                key = "WF-MULTI",
                name = "다중 전환 워크플로우",
                states = defaultStates,
                transitions = transitions,
            )

        assertThat(workflow.transitions).hasSize(2)
        assertThat(workflow.transitions.map { it.name }).containsExactly("시작", "긴급 시작")
        assertThat(workflow.transitions.map { it.id }.toSet()).hasSize(2)
    }

    // ── 전역 전환 ─────────────────────────────────────────────────────────────

    @Test
    fun `GLOBAL 전환은 fromStateKey 가 null 이어야 한다 — 값이 있으면 거부`() {
        val accepted =
            Workflow.of(
                key = "WF-GLOBAL-OK",
                name = "전역 전환 워크플로우",
                states = defaultStates,
                transitions = listOf(transition(from = null, to = "DONE", name = "긴급 완료", kind = TransitionKind.GLOBAL)),
            )
        assertThat(accepted.transitions).hasSize(1)
        assertThat(accepted.transitions.first().fromStateKey).isNull()

        assertThatThrownBy {
            Workflow.of(
                key = "WF-GLOBAL-NG",
                name = "잘못된 전역 전환 워크플로우",
                states = defaultStates,
                transitions =
                    listOf(
                        transition(from = "TODO", to = "DONE", name = "긴급 완료", kind = TransitionKind.GLOBAL),
                    ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("GLOBAL")
            .hasMessageContaining("fromStateKey")
    }

    // ── 최초 전환 ─────────────────────────────────────────────────────────────

    @Test
    fun `INITIAL 전환은 워크플로우당 1개 — 2개면 거부`() {
        val accepted =
            Workflow.of(
                key = "WF-INITIAL-OK",
                name = "최초 전환 워크플로우",
                states = defaultStates,
                transitions = listOf(transition(from = null, to = "TODO", name = "생성", kind = TransitionKind.INITIAL)),
            )
        assertThat(accepted.transitions).hasSize(1)

        assertThatThrownBy {
            Workflow.of(
                key = "WF-INITIAL-NG",
                name = "최초 전환 2개 워크플로우",
                states = defaultStates,
                transitions =
                    listOf(
                        transition(from = null, to = "TODO", name = "생성", kind = TransitionKind.INITIAL),
                        transition(from = null, to = "IN_PROGRESS", name = "바로 진행", kind = TransitionKind.INITIAL),
                    ),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("INITIAL")
    }

    // ── NORMAL 전환의 from 은 필수 ────────────────────────────────────────────

    @Test
    fun `NORMAL 전환의 fromStateKey 가 null 이면 거부`() {
        assertThatThrownBy {
            Workflow.of(
                key = "WF-NORMAL-NG",
                name = "from 없는 NORMAL 워크플로우",
                states = defaultStates,
                transitions = listOf(transition(from = null, to = "DONE", name = "완료", kind = TransitionKind.NORMAL)),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("NORMAL")
            .hasMessageContaining("fromStateKey")
    }
}
