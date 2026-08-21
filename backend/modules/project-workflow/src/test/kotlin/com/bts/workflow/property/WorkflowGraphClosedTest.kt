// 워크플로우 그래프 closed 검증 + 인라인 픽스처↔YAML 무drift 대조 (Spring 컨텍스트 없음)

package com.bts.workflow.property

import com.bts.workflow.domain.StateCategory
import com.bts.workflow.domain.TransitionKind
import com.bts.workflow.domain.Workflow
import com.bts.workflow.domain.WorkflowState
import com.bts.workflow.domain.WorkflowTransition
import com.bts.workflow.seed.WorkflowYamlDto
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe

// ─────────────────────────────────────────────────────────────────────────── //
// 1. 표준 4 워크플로우 픽스처 — classpath:workflows/<key>.yaml 을 그대로 옮긴 것       //
// ─────────────────────────────────────────────────────────────────────────── //

/**
 * software-default 픽스처 (5 상태 + 7 전환 — INITIAL 1 포함).
 *
 * `workflows/software-default.yaml` 의 states·transitions 와 순서까지 같아야 하며,
 * 어긋나면 「YAML 과 같다」 검증(검증 4)이 red 를 낸다.
 */
private fun softwareDefaultFixture(): Workflow =
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
                WorkflowTransition(null, "open", "이슈 생성", kind = TransitionKind.INITIAL),
                WorkflowTransition("open", "in_progress", "Start Work"),
                WorkflowTransition("in_progress", "in_review", "Submit for Review"),
                WorkflowTransition("in_review", "done", "Approve"),
                WorkflowTransition("in_review", "in_progress", "Request Changes"),
                WorkflowTransition("done", "closed", "Close"),
                WorkflowTransition("open", "closed", "Cancel"),
            ),
    )

/** bug-tracking 픽스처 (5 상태 + 6 전환 — INITIAL 1 포함). YAML 대조 대상. */
private fun bugTrackingFixture(): Workflow =
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
                WorkflowTransition(null, "reported", "이슈 생성", kind = TransitionKind.INITIAL),
                WorkflowTransition("reported", "triaged", "Triage"),
                WorkflowTransition("triaged", "in_progress", "Start Fix"),
                WorkflowTransition("in_progress", "resolved", "Resolve"),
                WorkflowTransition("resolved", "closed", "Close"),
                WorkflowTransition("resolved", "in_progress", "Reopen"),
            ),
    )

/** simple 픽스처 (3 상태 + 4 전환 — INITIAL 1 포함). YAML 대조 대상. */
private fun simpleFixture(): Workflow =
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
                WorkflowTransition(null, "todo", "이슈 생성", kind = TransitionKind.INITIAL),
                WorkflowTransition("todo", "doing", "Start"),
                WorkflowTransition("doing", "done", "Complete"),
                WorkflowTransition("done", "doing", "Reopen"),
            ),
    )

/** kanban-basic 픽스처 (4 상태 + 4 전환 — INITIAL 1 포함). YAML 대조 대상. */
private fun kanbanBasicFixture(): Workflow =
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
                WorkflowTransition(null, "backlog", "이슈 생성", kind = TransitionKind.INITIAL),
                WorkflowTransition("backlog", "ready", "Refine"),
                WorkflowTransition("ready", "in_progress", "Pull"),
                WorkflowTransition("in_progress", "done", "Finish"),
            ),
    )

/** 표준 4 워크플로우 픽스처. 전량이 YAML 대조(검증 4) 대상이다. */
private val standardWorkflows: List<Workflow> =
    listOf(
        softwareDefaultFixture(),
        bugTrackingFixture(),
        simpleFixture(),
        kanbanBasicFixture(),
    )

// ─────────────────────────────────────────────────────────────────────────── //
// 2. 판별용 합성 픽스처 — YAML 대응물이 없다                                       //
// ─────────────────────────────────────────────────────────────────────────── //

/**
 * GLOBAL 전환으로만 닿는 상태를 가진 워크플로우.
 *
 * ### 왜 필요한가 — 표준 4개만으로는 GLOBAL 분기가 공허하다
 * 표준 4 YAML 에는 GLOBAL 전환이 **한 건도 없다**. 그래서 표준 픽스처만 돌리면
 * [GraphVerifier.buildAdjacency] 의 GLOBAL 확장이 항상 빈 리스트를 더하고, 그 분기를 통째로
 * 지워도 초록이 난다 — 태어나자마자 죽는 검사가 된다.
 *
 * `cancelled` 로 들어오는 NORMAL 간선을 일부러 두지 않았다. GLOBAL 확장이 없으면 `cancelled` 가
 * 고아로 남아 「고아 상태 0건」이 red 가 된다. 그 비대칭이 이 픽스처의 존재 이유다.
 */
private fun globalOnlyReachableFixture(): Workflow =
    Workflow.of(
        key = "global-only-reachable",
        name = "GLOBAL 전환으로만 닿는 상태를 가진 판별용 워크플로우",
        states =
            listOf(
                WorkflowState("open", "Open", StateCategory.TODO, 1),
                WorkflowState("in_progress", "In Progress", StateCategory.IN_PROGRESS, 2),
                WorkflowState("done", "Done", StateCategory.DONE, 3),
                WorkflowState("cancelled", "Cancelled", StateCategory.DONE, 4),
            ),
        transitions =
            listOf(
                WorkflowTransition(null, "open", "이슈 생성", kind = TransitionKind.INITIAL),
                WorkflowTransition("open", "in_progress", "Start Work"),
                WorkflowTransition("in_progress", "done", "Finish"),
                WorkflowTransition(null, "cancelled", "Cancel", kind = TransitionKind.GLOBAL),
            ),
    )

/**
 * INITIAL 도착지가 displayOrder 최소 상태가 **아닌** 워크플로우.
 *
 * ### 왜 필요한가 — 표준 4개만으로는 INITIAL 분기가 공허하다
 * 표준 4 YAML 의 INITIAL 도착지는 모두 displayOrder 가 가장 작은 상태다(V207 ⑨ 백필과 같은 선택).
 * 그래서 [GraphVerifier.resolveInitialState] 의 INITIAL 분기를 지우고 displayOrder 폴백만 남겨도
 * **답이 항상 같다** — 구조적으로 판별이 불가능하다.
 *
 * 여기서는 이슈 생성이 `triage` 로 들어가고 `backlog` 는 displayOrder 만 앞선다. `backlog` 에서
 * 나가는 간선을 일부러 두지 않아, 폴백 시작점(`backlog`)에서는 그래프가 닫히지 않는다.
 * 그 비대칭 덕에 INITIAL 분기를 지우면 「고아 상태 0건」이 red 가 된다.
 */
private fun initialNotLowestOrderFixture(): Workflow =
    Workflow.of(
        key = "initial-not-lowest-order",
        name = "INITIAL 도착지가 displayOrder 최소가 아닌 판별용 워크플로우",
        states =
            listOf(
                WorkflowState("backlog", "Backlog", StateCategory.TODO, 1),
                WorkflowState("triage", "Triage", StateCategory.TODO, 2),
                WorkflowState("in_progress", "In Progress", StateCategory.IN_PROGRESS, 3),
                WorkflowState("done", "Done", StateCategory.DONE, 4),
            ),
        transitions =
            listOf(
                WorkflowTransition(null, "triage", "이슈 생성", kind = TransitionKind.INITIAL),
                WorkflowTransition("triage", "backlog", "Defer"),
                WorkflowTransition("triage", "in_progress", "Accept"),
                WorkflowTransition("in_progress", "done", "Finish"),
            ),
    )

/** 판별용 합성 픽스처. YAML 대응물이 없어 검증 4(YAML 대조)에서는 제외한다. */
private val discriminatorWorkflows: List<Workflow> =
    listOf(
        globalOnlyReachableFixture(),
        initialNotLowestOrderFixture(),
    )

/**
 * 워크플로우 그래프 closed 검증.
 *
 * 검증 항목.
 * 1. 시작점 도달 가능 — initial state (INITIAL 전환의 도착지, 없으면 displayOrder 가장 낮은 상태)
 *    가 그래프에 존재한다.
 * 2. DONE 종결 상태 ≥ 1 — DONE 카테고리 상태가 1개 이상 존재한다.
 * 3. 고아 상태 0건 — BFS 로 시작점에서 모든 상태에 도달 가능하다.
 * 4. 픽스처 ↔ YAML 무drift — 표준 4 픽스처의 states·transitions 가 `classpath:workflows/<key>.yaml`
 *    과 순서까지 같다.
 *
 * ### 픽스처는 두 종류다 — 헤더가 정본 노릇을 하지 않게 한다
 * - **표준 4** ([standardWorkflows]) — YAML 을 그대로 옮긴 것이고, 그 주장을 사람의 주석이 아니라
 *   검증 4 가 매 실행 기계로 대조한다. 「YAML 을 고쳤는데 픽스처만 안 고쳤다」가 red 로 잡힌다
 *   (Task 19 가 YAML 을 6/5/3/3 → 7/6/4/4 로 바꿨을 때 이 파일만 갈라졌던 사고의 재발 방지).
 *   `description`·`validators`·`postActions` 는 도달성과 무관해 픽스처에 옮기지도, 대조하지도 않는다.
 * - **판별용 합성 2** ([discriminatorWorkflows]) — YAML 대응물이 **없다**. 표준 4 로는 공허해지는
 *   [GraphVerifier] 의 GLOBAL·INITIAL 분기를 판별 가능하게 만드는 것이 유일한 목적이다.
 *
 * Spring 컨텍스트·DB 없이 순수 도메인 객체 + classpath YAML 읽기만으로 돈다.
 */
class WorkflowGraphClosedTest : FunSpec({

    val allWorkflows: List<Workflow> = standardWorkflows + discriminatorWorkflows

    // ── 검증 1: 시작점(initial state)이 그래프에 존재한다 ────────────────────────

    allWorkflows.forEach { workflow ->
        test("${workflow.key}: initial state 가 그래프에 존재한다") {
            val initialState = GraphVerifier.resolveInitialState(workflow)
            workflow.states.map { it.key }.contains(initialState.key) shouldBe true
        }
    }

    // ── 검증 2: DONE 카테고리 상태가 1개 이상 존재한다 ───────────────────────────

    allWorkflows.forEach { workflow ->
        test("${workflow.key}: DONE 카테고리 종결 상태 ≥ 1") {
            val doneStates = workflow.states.filter { it.category == StateCategory.DONE }
            doneStates.shouldNotBeEmpty()
            doneStates.size shouldBeGreaterThanOrEqual 1
        }
    }

    // ── 검증 3: 고아 상태 0건 — BFS 도달성 ────────────────────────────────────

    allWorkflows.forEach { workflow ->
        test("${workflow.key}: 고아 상태 0건 — 모든 상태가 initial state 에서 BFS 도달 가능") {
            val reachable = GraphVerifier.bfsReachable(workflow)
            val allStateKeys = workflow.states.map { it.key }.toSet()
            val orphans = allStateKeys - reachable

            orphans shouldBe emptySet()
        }
    }

    // ── 검증 4: 표준 4 픽스처가 YAML 과 갈라지지 않았다 ─────────────────────────

    standardWorkflows.forEach { workflow ->
        test("${workflow.key}: 픽스처 states 가 YAML 과 같다") {
            workflow.states shouldBe StandardWorkflowYaml.statesOf(workflow.key)
        }
    }

    standardWorkflows.forEach { workflow ->
        test("${workflow.key}: 픽스처 transitions 가 YAML 과 같다") {
            transitionShapes(workflow.transitions) shouldBe StandardWorkflowYaml.transitionShapesOf(workflow.key)
        }
    }

    // ── 판별용 합성 픽스처의 전제 자체를 지키는 검사 ───────────────────────────
    // 아래 둘이 깨지면 위 검증 3 이 다시 공허해진다. 「왜 이런 모양인가」를 기계가 붙잡는다.

    test("global-only-reachable: 'cancelled' 는 GLOBAL 전환으로만 닿는다 — NORMAL 진입 간선 0건") {
        val workflow = globalOnlyReachableFixture()
        val normalInbound =
            workflow.transitions.filter {
                it.kind == TransitionKind.NORMAL && it.toStateKey == "cancelled"
            }

        normalInbound shouldBe emptyList()
    }

    test("initial-not-lowest-order: 시작점은 INITIAL 도착지이지 displayOrder 최소 상태가 아니다") {
        val workflow = initialNotLowestOrderFixture()
        val lowestOrderKey = workflow.states.minByOrNull { it.displayOrder }?.key

        GraphVerifier.resolveInitialState(workflow).key shouldBe "triage"
        lowestOrderKey shouldBe "backlog"
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
     * 표준 4 워크플로우는 둘의 답이 같아 이 분기를 판별하지 못한다.
     * [initialNotLowestOrderFixture] 가 그 자리를 메운다 — 지우면 red 가 난다.
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
     * 표준 4 YAML 에는 GLOBAL 전환이 없어 이 확장을 지워도 표준 픽스처는 초록이다.
     * [globalOnlyReachableFixture] 가 그 자리를 메운다 — 지우면 red 가 난다.
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

// ─────────────────────────────────────────────────────────────────────────── //
// 픽스처 ↔ YAML 대조 유틸리티                                                   //
// ─────────────────────────────────────────────────────────────────────────── //

/**
 * 전환에서 그래프 형태만 뽑은 값.
 *
 * [WorkflowTransition.id] 는 DB 가 정하는 UUID 라 픽스처와 YAML 이 같을 수가 없다. 도달성이 보는
 * 것은 (from, to, kind) 이고 name 은 사람이 대조할 라벨이라 그 넷만 남긴다.
 */
private data class TransitionShape(
    val from: String?,
    val to: String,
    val name: String,
    val kind: TransitionKind,
)

/** 도메인 전환 목록을 대조용 형태로 바꾼다. */
private fun transitionShapes(transitions: List<WorkflowTransition>): List<TransitionShape> =
    transitions.map { TransitionShape(it.fromStateKey, it.toStateKey, it.name, it.kind) }

/**
 * `classpath:workflows/<key>.yaml` 을 읽어 대조용 형태로 내주는 로더.
 *
 * Spring 컨텍스트 없이 Jackson 만 쓴다 — 이 테스트가 컨텍스트를 띄우지 않는다는 성질을 지키면서
 * 「인라인 픽스처가 YAML 과 같다」는 주장만 기계가 확인하게 한다. 시드 서비스가 쓰는 것과 같은
 * [WorkflowYamlDto] 로 읽으므로 파싱 규칙이 프로덕션과 어긋날 수 없다.
 */
private object StandardWorkflowYaml {
    private val mapper = ObjectMapper(YAMLFactory()).registerKotlinModule()

    /** YAML 의 states 를 도메인 [WorkflowState] 목록으로 바꿔 반환한다. */
    fun statesOf(workflowKey: String): List<WorkflowState> =
        load(workflowKey).states.map {
            WorkflowState(it.key, it.name, StateCategory.valueOf(it.category), it.displayOrder)
        }

    /** YAML 의 transitions 를 대조용 [TransitionShape] 목록으로 바꿔 반환한다. */
    fun transitionShapesOf(workflowKey: String): List<TransitionShape> =
        load(workflowKey).transitions.map {
            TransitionShape(it.from, it.to, it.name, TransitionKind.valueOf(it.kind))
        }

    private fun load(workflowKey: String): WorkflowYamlDto {
        val path = "/workflows/$workflowKey.yaml"
        val stream =
            StandardWorkflowYaml::class.java.getResourceAsStream(path)
                ?: error("$path 를 test classpath 에서 찾지 못했다 — src/main/resources 배치를 확인하라")
        return stream.use { mapper.readValue(it, WorkflowYamlDto::class.java) }
    }
}
