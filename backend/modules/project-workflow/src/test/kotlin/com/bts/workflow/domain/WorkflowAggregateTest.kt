// Workflow Aggregate invariant 검증 — companion factory of(...)

package com.bts.workflow.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * Workflow.of(...) companion factory 의 invariant 검증 테스트.
 *
 * 검증 대상.
 * 1. 정상 생성 — states / transitions 조건 모두 충족 시 Workflow 반환
 * 2. states 비어 있음 → IllegalArgumentException
 * 3. state.key 중복 → IllegalArgumentException
 * 4. transition.fromStateKey 가 states 집합에 없음 → IllegalArgumentException
 * 5. transition.toStateKey 가 states 집합에 없음 → IllegalArgumentException
 * 6. transition (from, to, name) 조합 중복 → IllegalArgumentException
 */
class WorkflowAggregateTest {
    // ── 픽스처 ────────────────────────────────────────────────────────────────

    private val todoState =
        WorkflowState(
            key = "TODO",
            name = "할 일",
            category = StateCategory.TODO,
            displayOrder = 1,
        )

    private val inProgressState =
        WorkflowState(
            key = "IN_PROGRESS",
            name = "진행 중",
            category = StateCategory.IN_PROGRESS,
            displayOrder = 2,
        )

    private val doneState =
        WorkflowState(
            key = "DONE",
            name = "완료",
            category = StateCategory.DONE,
            displayOrder = 3,
        )

    private val defaultStates = listOf(todoState, inProgressState, doneState)

    private val defaultTransitions =
        listOf(
            WorkflowTransition(fromStateKey = "TODO", toStateKey = "IN_PROGRESS", name = "시작"),
            WorkflowTransition(fromStateKey = "IN_PROGRESS", toStateKey = "DONE", name = "완료"),
        )

    // ── 정상 생성 ─────────────────────────────────────────────────────────────

    @Test
    fun `정상 states 와 transitions 으로 Workflow 생성 성공`() {
        val workflow =
            Workflow.of(
                key = "WF-001",
                name = "기본 워크플로우",
                states = defaultStates,
                transitions = defaultTransitions,
            )

        assertThat(workflow.key).isEqualTo("WF-001")
        assertThat(workflow.name).isEqualTo("기본 워크플로우")
        assertThat(workflow.states).hasSize(3)
        assertThat(workflow.transitions).hasSize(2)
    }

    // ── states 비어 있음 ──────────────────────────────────────────────────────

    @Test
    fun `states 가 비어 있으면 IllegalArgumentException`() {
        assertThatThrownBy {
            Workflow.of(
                key = "WF-002",
                name = "빈 워크플로우",
                states = emptyList(),
                transitions = emptyList(),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("states")
    }

    // ── state key 중복 ────────────────────────────────────────────────────────

    @Test
    fun `state key 중복 시 IllegalArgumentException`() {
        val duplicateStates =
            listOf(
                WorkflowState(key = "TODO", name = "할 일1", category = StateCategory.TODO, displayOrder = 1),
                WorkflowState(key = "TODO", name = "할 일2", category = StateCategory.TODO, displayOrder = 2),
            )

        assertThatThrownBy {
            Workflow.of(
                key = "WF-003",
                name = "중복 워크플로우",
                states = duplicateStates,
                transitions = emptyList(),
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("duplicate")
    }

    // ── transition fromStateKey 누락 ──────────────────────────────────────────

    @Test
    fun `transition fromStateKey 가 states 집합에 없으면 IllegalArgumentException`() {
        val invalidTransitions =
            listOf(
                WorkflowTransition(fromStateKey = "UNKNOWN", toStateKey = "IN_PROGRESS", name = "잘못된 전이"),
            )

        assertThatThrownBy {
            Workflow.of(
                key = "WF-004",
                name = "잘못된 from 워크플로우",
                states = defaultStates,
                transitions = invalidTransitions,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("fromStateKey")
    }

    // ── transition toStateKey 누락 ────────────────────────────────────────────

    @Test
    fun `transition toStateKey 가 states 집합에 없으면 IllegalArgumentException`() {
        val invalidTransitions =
            listOf(
                WorkflowTransition(fromStateKey = "TODO", toStateKey = "MISSING", name = "잘못된 전이"),
            )

        assertThatThrownBy {
            Workflow.of(
                key = "WF-005",
                name = "잘못된 to 워크플로우",
                states = defaultStates,
                transitions = invalidTransitions,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("toStateKey")
    }

    // ── transition (from, to, name) 조합 중복 ─────────────────────────────────

    @Test
    fun `transition from-to-name 조합 중복 시 IllegalArgumentException`() {
        val duplicateTransitions =
            listOf(
                WorkflowTransition(fromStateKey = "TODO", toStateKey = "IN_PROGRESS", name = "시작"),
                WorkflowTransition(fromStateKey = "TODO", toStateKey = "IN_PROGRESS", name = "시작"),
            )

        assertThatThrownBy {
            Workflow.of(
                key = "WF-006",
                name = "중복 전이 워크플로우",
                states = defaultStates,
                transitions = duplicateTransitions,
            )
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("duplicate")
    }
}
