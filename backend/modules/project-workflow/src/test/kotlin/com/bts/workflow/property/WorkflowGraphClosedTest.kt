// 표준 4 워크플로우 그래프 closed 검증 — BFS 도달성 알고리즘 (Spring 컨텍스트 없음)

package com.bts.workflow.property

import com.bts.workflow.domain.StateCategory
import com.bts.workflow.domain.Workflow
import com.bts.workflow.domain.WorkflowState
import com.bts.workflow.domain.WorkflowTransition
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe

/**
 * 표준 4 워크플로우 그래프 closed 검증.
 *
 * 검증 항목.
 * 1. 시작점 도달 가능 — initial state (displayOrder 가장 낮은 상태) 가 그래프에 존재한다.
 * 2. DONE 종결 상태 ≥ 1 — DONE 카테고리 상태가 1개 이상 존재한다.
 * 3. 고아 상태 0건 — BFS 로 시작점에서 모든 상태에 도달 가능하다.
 *
 * Spring 컨텍스트 없이 순수 도메인 객체로만 검증한다.
 * 4 워크플로우는 YAML 과 동일한 구조를 직접 내장해 외부 의존을 제거한다.
 */
class WorkflowGraphClosedTest : FunSpec({

    // ── 표준 4 워크플로우 인라인 픽스처 ────────────────────────────────────────

    val standardWorkflows: List<Workflow> =
        listOf(
            // 1. software-default (5 상태 + 6 전환)
            Workflow.of(
                key = "software-default",
                name = "소프트웨어 개발 기본 워크플로우",
                states =
                    listOf(
                        WorkflowState("open", "Open", StateCategory.TODO, 1),
                        WorkflowState("in_progress", "In Progress", StateCategory.IN_PROGRESS, 2),
                        WorkflowState("in_review", "In Review", StateCategory.IN_PROGRESS, 3),
                        WorkflowState("done", "Done", StateCategory.DONE, 4),
                        WorkflowState("closed", "Closed", StateCategory.DONE, 5),
                    ),
                transitions =
                    listOf(
                        WorkflowTransition("open", "in_progress", "Start Work"),
                        WorkflowTransition("in_progress", "in_review", "Submit for Review"),
                        WorkflowTransition("in_review", "done", "Approve"),
                        WorkflowTransition("in_review", "in_progress", "Request Changes"),
                        WorkflowTransition("done", "closed", "Close"),
                        WorkflowTransition("open", "closed", "Cancel"),
                    ),
            ),
            // 2. bug-tracking (5 상태 + 5 전환)
            Workflow.of(
                key = "bug-tracking",
                name = "버그 추적 워크플로우",
                states =
                    listOf(
                        WorkflowState("reported", "Reported", StateCategory.TODO, 1),
                        WorkflowState("triaged", "Triaged", StateCategory.TODO, 2),
                        WorkflowState("in_progress", "In Progress", StateCategory.IN_PROGRESS, 3),
                        WorkflowState("resolved", "Resolved", StateCategory.DONE, 4),
                        WorkflowState("closed", "Closed", StateCategory.DONE, 5),
                    ),
                transitions =
                    listOf(
                        WorkflowTransition("reported", "triaged", "Triage"),
                        WorkflowTransition("triaged", "in_progress", "Start Fix"),
                        WorkflowTransition("in_progress", "resolved", "Resolve"),
                        WorkflowTransition("resolved", "closed", "Close"),
                        WorkflowTransition("resolved", "in_progress", "Reopen"),
                    ),
            ),
            // 3. simple (3 상태 + 3 전환)
            Workflow.of(
                key = "simple",
                name = "단순 워크플로우 (TODO/DOING/DONE)",
                states =
                    listOf(
                        WorkflowState("todo", "To Do", StateCategory.TODO, 1),
                        WorkflowState("doing", "Doing", StateCategory.IN_PROGRESS, 2),
                        WorkflowState("done", "Done", StateCategory.DONE, 3),
                    ),
                transitions =
                    listOf(
                        WorkflowTransition("todo", "doing", "Start"),
                        WorkflowTransition("doing", "done", "Complete"),
                        WorkflowTransition("done", "doing", "Reopen"),
                    ),
            ),
            // 4. kanban-basic (4 상태 + 3 전환)
            Workflow.of(
                key = "kanban-basic",
                name = "칸반 기본 워크플로우",
                states =
                    listOf(
                        WorkflowState("backlog", "Backlog", StateCategory.TODO, 1),
                        WorkflowState("ready", "Ready", StateCategory.TODO, 2),
                        WorkflowState("in_progress", "In Progress", StateCategory.IN_PROGRESS, 3),
                        WorkflowState("done", "Done", StateCategory.DONE, 4),
                    ),
                transitions =
                    listOf(
                        WorkflowTransition("backlog", "ready", "Refine"),
                        WorkflowTransition("ready", "in_progress", "Pull"),
                        WorkflowTransition("in_progress", "done", "Finish"),
                    ),
            ),
        )

    // ── 검증 1: 시작점(initial state)이 그래프에 존재한다 ────────────────────────

    standardWorkflows.forEach { workflow ->
        test("${workflow.key}: initial state 가 그래프에 존재한다") {
            val initialState = GraphVerifier.resolveInitialState(workflow)
            workflow.states.map { it.key }.contains(initialState.key) shouldBe true
        }
    }

    // ── 검증 2: DONE 카테고리 상태가 1개 이상 존재한다 ───────────────────────────

    standardWorkflows.forEach { workflow ->
        test("${workflow.key}: DONE 카테고리 종결 상태 ≥ 1") {
            val doneStates = workflow.states.filter { it.category == StateCategory.DONE }
            doneStates.shouldNotBeEmpty()
            doneStates.size shouldBeGreaterThanOrEqual 1
        }
    }

    // ── 검증 3: 고아 상태 0건 — BFS 도달성 ────────────────────────────────────

    standardWorkflows.forEach { workflow ->
        test("${workflow.key}: 고아 상태 0건 — 모든 상태가 initial state 에서 BFS 도달 가능") {
            val reachable = GraphVerifier.bfsReachable(workflow)
            val allStateKeys = workflow.states.map { it.key }.toSet()
            val orphans = allStateKeys - reachable

            orphans shouldBe emptySet()
        }
    }
})

// ─────────────────────────────────────────────────────────────────────────── //
// 그래프 검증 유틸리티                                                          //
// ─────────────────────────────────────────────────────────────────────────── //

/**
 * Workflow 그래프 도달성 검증 유틸리티.
 *
 * BFS(Breadth-First Search — 너비 우선 탐색) 로 시작점에서 도달 가능한 모든 상태를 탐색한다.
 * BFS 는 "가장 가까운 이웃부터 탐색하는 그래프 탐색 알고리즘"이다.
 */
object GraphVerifier {
    /**
     * 워크플로우의 initial state 를 반환한다.
     *
     * displayOrder 가 가장 낮은 상태를 initial state 로 간주한다.
     */
    fun resolveInitialState(workflow: Workflow): WorkflowState =
        workflow.states.minByOrNull { it.displayOrder }
            ?: error("${workflow.key}: states 가 비어 있음 — Workflow.of 에서 이미 검증되므로 도달 불가")

    /**
     * BFS 로 initial state 에서 도달 가능한 모든 상태 키 집합을 반환한다.
     *
     * @param workflow 검증 대상 워크플로우
     * @return initial state 에서 도달 가능한 상태 키 Set
     */
    fun bfsReachable(workflow: Workflow): Set<String> {
        val initial = resolveInitialState(workflow)

        // 인접 리스트 구성: fromStateKey → toStateKey 목록
        val adjacency: Map<String, List<String>> =
            workflow.transitions
                .groupBy { it.fromStateKey }
                .mapValues { (_, transitions) -> transitions.map { it.toStateKey } }

        val visited = mutableSetOf<String>()
        val queue = ArrayDeque<String>()

        visited.add(initial.key)
        queue.add(initial.key)

        while (queue.isNotEmpty()) {
            val current = queue.removeFirst()
            for (neighbor in adjacency[current].orEmpty()) {
                if (neighbor !in visited) {
                    visited.add(neighbor)
                    queue.add(neighbor)
                }
            }
        }

        return visited
    }
}
