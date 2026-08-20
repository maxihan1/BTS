// 표준 4 워크플로우 그래프 closed 검증 — BFS 도달성 알고리즘 (Spring 컨텍스트 없음)

package com.bts.workflow.property

import com.bts.workflow.domain.StateCategory
import com.bts.workflow.domain.TransitionKind
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
 * 1. 시작점 도달 가능 — initial state (INITIAL 전환의 도착지, 없으면 displayOrder 가장 낮은 상태)
 *    가 그래프에 존재한다.
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
     * 해석 순서.
     * 1. [TransitionKind.INITIAL] 전환이 있으면 그 전환의 도착 상태가 시작점이다 — 이슈 생성이
     *    실제로 진입하는 상태라 표시 순서보다 우선한다.
     * 2. INITIAL 전환이 없으면 종전대로 displayOrder 가 가장 낮은 상태로 폴백한다.
     *
     * @param workflow 시작점을 구할 워크플로우
     * @return 시작 상태
     */
    fun resolveInitialState(workflow: Workflow): WorkflowState {
        val initialTargetKey =
            workflow.transitions.firstOrNull { it.kind == TransitionKind.INITIAL }?.toStateKey
        if (initialTargetKey != null) {
            return workflow.states.firstOrNull { it.key == initialTargetKey }
                ?: error("${workflow.key}: INITIAL 전환의 도착 상태 '$initialTargetKey' 가 states 에 없음")
        }
        return workflow.states.minByOrNull { it.displayOrder }
            ?: error("${workflow.key}: states 가 비어 있음 — Workflow.of 에서 이미 검증되므로 도달 불가")
    }

    /**
     * BFS 로 initial state 에서 도달 가능한 모든 상태 키 집합을 반환한다.
     *
     * @param workflow 검증 대상 워크플로우
     * @return initial state 에서 도달 가능한 상태 키 Set
     */
    fun bfsReachable(workflow: Workflow): Set<String> {
        val initial = resolveInitialState(workflow)
        val adjacency = buildAdjacency(workflow)

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

    /**
     * 상태 key → 그 상태에서 한 걸음에 갈 수 있는 상태 key 목록.
     *
     * ### 전환 종류마다 간선의 뜻이 다르다 — 되돌리지 말 것
     * [WorkflowTransition.fromStateKey] 가 nullable 이라고 해서 **null 을 그냥 걸러내면 안 된다**.
     * 그러면 GLOBAL 전환이 도달성 계산에서 통째로 빠져 「고아 상태 0건」 속성 자체가 거짓이 된다 —
     * 오직 GLOBAL 전환으로만 닿는 상태가 고아로 오판된다.
     *
     * - [TransitionKind.NORMAL] — `from → to` 간선 1개.
     * - [TransitionKind.GLOBAL] — 출발지가 없는 것이 「아무 데서나」라는 뜻이므로
     *   **모든 상태 → to** 간선. 자기 자신으로 가는 간선은 도달성에 아무것도 보태지 않아 제외한다.
     * - [TransitionKind.INITIAL] — 그래프 바깥(이슈 생성)에서 들어오는 진입점이라 간선이 아니다.
     *   [resolveInitialState] 가 시작점을 정하는 데만 쓴다. 간선으로 세면 어떤 상태에서든
     *   시작 상태로 되돌아갈 수 있는 것처럼 보여 도달성이 부풀려진다.
     *
     * @param workflow 인접 리스트를 만들 워크플로우
     * @return 모든 상태 key 를 키로 갖는 인접 리스트
     */
    private fun buildAdjacency(workflow: Workflow): Map<String, List<String>> {
        val normalEdges: Map<String, List<String>> =
            workflow.transitions
                .filter { it.kind == TransitionKind.NORMAL }
                .mapNotNull { transition -> transition.fromStateKey?.let { it to transition.toStateKey } }
                .groupBy({ (from, _) -> from }, { (_, to) -> to })

        val globalTargets: List<String> =
            workflow.transitions
                .filter { it.kind == TransitionKind.GLOBAL }
                .map { it.toStateKey }

        return workflow.states.associate { state ->
            state.key to (normalEdges[state.key].orEmpty() + globalTargets.filter { it != state.key })
        }
    }
}
