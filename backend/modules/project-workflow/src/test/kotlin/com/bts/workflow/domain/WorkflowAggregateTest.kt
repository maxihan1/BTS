// Workflow Aggregate invariant 검증 — companion factory of(...)

package com.bts.workflow.domain

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * Workflow.of(...) companion factory 의 invariant 검증 테스트.
 *
 * 검증 대상.
 * 1. 정상 생성 — states / transitions 조건 모두 충족 시 Workflow 반환
 * 2. states 비어 있음 → IllegalArgumentException
 * 3. state.key 중복 → IllegalArgumentException
 * 4. transition.fromStateKey 가 states 집합에 없음 → IllegalArgumentException
 * 5. transition.toStateKey 가 states 집합에 없음 → IllegalArgumentException
 * 6. transition.id 기본값 — 명시하지 않으면 새 UUID 가 부여된다
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
                WorkflowTransition(fromStateKey = "UNKNOWN", toStateKey = "IN_PROGRESS", name = "잘못된 전환"),
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
                WorkflowTransition(fromStateKey = "TODO", toStateKey = "MISSING", name = "잘못된 전환"),
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

    // ── transition (from, to) 조합 중복 ──────────────────────────────────────
    //
    // 「(from, to) 조합 중복 금지」invariant 를 단언하던 테스트 2건을 FR-WF-05 에서 삭제했다 —
    // 전환 identity 가 (from, to) 에서 id 로 바뀌면서 그 규칙 자체가 없어져 단언이 거짓이 됐다
    // (ADR docs/adr/2026-08-18-workflow-transition-id-identity.md §D1). 대체 커버리지는
    // WorkflowTest.kt 의 `같은 (from,to) 에 이름이 다른 전환 2개를 of() 가 받는다` 가 제공한다.

    @Test
    fun `다른 (from, to) 가 같은 name 인 두 transition 은 정상 생성`() {
        val validTransitions =
            listOf(
                WorkflowTransition(fromStateKey = "TODO", toStateKey = "IN_PROGRESS", name = "Move"),
                WorkflowTransition(fromStateKey = "IN_PROGRESS", toStateKey = "DONE", name = "Move"),
            )

        val workflow =
            Workflow.of(
                key = "WF-008",
                name = "같은 name 다른 경로 워크플로우",
                states = defaultStates,
                transitions = validTransitions,
            )

        assertThat(workflow.transitions).hasSize(2)
    }

    // ── transition id 기본값 ───────────────────────────────────────────────────

    @Test
    fun `WorkflowTransition 을 id 없이 만들면 새 UUID 가 자동 부여된다`() {
        val first = WorkflowTransition(fromStateKey = "TODO", toStateKey = "IN_PROGRESS", name = "Start Work")
        val second = WorkflowTransition(fromStateKey = "TODO", toStateKey = "IN_PROGRESS", name = "시작")

        assertThat(first.id).isNotEqualTo(second.id)

        val explicitId = UUID.randomUUID()
        val given =
            WorkflowTransition(
                id = explicitId,
                fromStateKey = "TODO",
                toStateKey = "DONE",
                name = "Close",
            )

        assertThat(given.id).isEqualTo(explicitId)
    }
}
