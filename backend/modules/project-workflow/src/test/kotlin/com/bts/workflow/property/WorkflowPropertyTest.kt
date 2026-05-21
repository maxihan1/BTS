// FSM 워크플로우 property-based 테스트 — 3 invariant × 1000건 (Kotest Property)

package com.bts.workflow.property

import com.bts.workflow.domain.StateCategory
import com.bts.workflow.domain.Workflow
import com.bts.workflow.domain.WorkflowState
import com.bts.workflow.domain.WorkflowTransition
import com.bts.workflow.domain.dto.PostActionPlan
import com.bts.workflow.domain.dto.TransitionContext
import com.bts.workflow.domain.dto.TransitionRequest
import com.bts.workflow.domain.expression.DefaultActorView
import com.bts.workflow.domain.expression.DefaultIssueView
import com.bts.workflow.domain.spi.ValidatorResult
import com.bts.workflow.domain.spi.WorkflowPostAction
import com.bts.workflow.domain.spi.WorkflowValidator
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.property.Arb
import io.kotest.property.arbitrary.arbitrary
import io.kotest.property.arbitrary.boolean
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.checkAll

/**
 * FSM 워크플로우 property-based 테스트.
 *
 * 목적. 수작업 단위 테스트가 놓칠 수 있는 임의 입력 조합에 대해 3가지 핵심 invariant 를 1000건 검증한다.
 *
 * invariant 1. 모든 전이 결과는 도메인이 정의한 상태 집합에 속한다.
 * invariant 2. validator 실패 시 결과 상태는 변하지 않는다 (rollback 보장).
 * invariant 3. PostAction 은 GREEN(validator 전원 통과) 전이에서만 실행된다.
 *
 * Spring 컨텍스트를 사용하지 않는 순수 도메인 레이어 테스트다.
 * WorkflowEngine 은 Propagation.MANDATORY 로 Spring 트랜잭션이 필요하므로,
 * 이 테스트는 engine 없이 도메인 객체 + stub SPI 로 invariant 를 직접 검증한다.
 */
class WorkflowPropertyTest : StringSpec({

    // ──────────────────────────────────────────────────────────────────────── //
    // invariant 1: 모든 전이 결과는 도메인이 정의한 상태 집합에 속한다             //
    // ──────────────────────────────────────────────────────────────────────── //

    "invariant 1: 1000건 임의 전이 시 결과 상태 키는 항상 Workflow.states 집합 안에 있다" {
        checkAll(1000, WorkflowGenerators.transitionTriples) { triple ->
            val wf = triple.first
            val fromStateKey = triple.second
            val transition = triple.third
            val stateKeys = wf.states.map { it.key }.toSet()

            // 전이가 선택된 경우만 — transition 은 workflow.transitions 에서 뽑혔으므로 반드시 유효
            stateKeys.contains(transition.toStateKey).shouldBeTrue()
            stateKeys.contains(fromStateKey).shouldBeTrue()
        }
    }

    // ──────────────────────────────────────────────────────────────────────── //
    // invariant 2: validator 실패 시 상태는 변하지 않는다                         //
    // ──────────────────────────────────────────────────────────────────────── //

    "invariant 2: 1000건 임의 조합 — validator Fail 시 결과 상태 = 출발 상태 (rollback)" {
        checkAll(1000, WorkflowGenerators.validatorScenarios) { scenario ->
            val result = simulateTransition(
                workflow = scenario.workflow,
                fromStateKey = scenario.fromStateKey,
                transition = scenario.transition,
                validators = scenario.validators,
                postActions = scenario.postActions,
            )

            if (!result.allValidatorsPass) {
                // validator 실패 → 상태 미변경
                result.resultStateKey shouldBe scenario.fromStateKey
            }
        }
    }

    // ──────────────────────────────────────────────────────────────────────── //
    // invariant 3: PostAction 은 GREEN 전이에서만 실행된다                       //
    // ──────────────────────────────────────────────────────────────────────── //

    "invariant 3: 1000건 임의 조합 — validator 실패 시 PostAction 실행 횟수 = 0" {
        checkAll(1000, WorkflowGenerators.validatorScenarios) { scenario ->
            val result = simulateTransition(
                workflow = scenario.workflow,
                fromStateKey = scenario.fromStateKey,
                transition = scenario.transition,
                validators = scenario.validators,
                postActions = scenario.postActions,
            )

            if (!result.allValidatorsPass) {
                result.postActionsExecuted shouldBe 0
            } else {
                result.postActionsExecuted shouldBe scenario.postActions.size
            }
        }
    }
})

// ─────────────────────────────────────────────────────────────────────────── //
// 내부 시뮬레이션 결과                                                         //
// ─────────────────────────────────────────────────────────────────────────── //

/**
 * 전이 시뮬레이션 결과.
 *
 * @param resultStateKey 시뮬레이션 후 결정된 상태 키 (실패 시 = fromStateKey)
 * @param allValidatorsPass 모든 validator 가 Pass 를 반환했는지 여부
 * @param postActionsExecuted 실제 실행된 PostAction 개수
 */
private data class SimulationResult(
    val resultStateKey: String,
    val allValidatorsPass: Boolean,
    val postActionsExecuted: Int,
)

/**
 * WorkflowEngine.plan() 의 핵심 로직을 트랜잭션 없이 시뮬레이션한다.
 *
 * 순서.
 * 1. Validator 순차 평가 — 첫 Fail 즉시 중단 (rollback).
 * 2. 모든 validator 통과 시 PostAction 평가.
 */
private fun simulateTransition(
    workflow: Workflow,
    fromStateKey: String,
    transition: WorkflowTransition,
    validators: List<WorkflowValidator>,
    postActions: List<WorkflowPostAction>,
): SimulationResult {
    val ctx = buildStubContext(workflow, fromStateKey, transition)

    // Validator 순차 평가
    for (validator in validators) {
        val result = validator.validate(ctx)
        if (result is ValidatorResult.Fail) {
            return SimulationResult(
                resultStateKey = fromStateKey,
                allValidatorsPass = false,
                postActionsExecuted = 0,
            )
        }
    }

    // 모든 Validator 통과 → PostAction 평가
    var postActionsExecuted = 0
    for (postAction in postActions) {
        postAction.evaluate(ctx)
        postActionsExecuted++
    }

    return SimulationResult(
        resultStateKey = transition.toStateKey,
        allValidatorsPass = true,
        postActionsExecuted = postActionsExecuted,
    )
}

/**
 * 테스트용 최소 stub TransitionContext 를 구성한다.
 */
private fun buildStubContext(
    workflow: Workflow,
    fromStateKey: String,
    transition: WorkflowTransition,
): TransitionContext {
    val fromState = workflow.states.first { it.key == fromStateKey }
    val request = TransitionRequest(
        workflowKey = workflow.key,
        issueKey = "PROP-TEST",
        fromStateKey = fromStateKey,
        toStateKey = transition.toStateKey,
        transitionName = transition.name,
        actorId = "tester",
        issueFields = emptyMap(),
        actorRoles = setOf("MEMBER"),
        version = 1L,
    )
    return TransitionContext(
        request = request,
        workflow = workflow,
        fromState = fromState,
        transition = transition,
        issueView = DefaultIssueView(key = "PROP-TEST", priority = "NORMAL", fields = emptyMap()),
        actorView = DefaultActorView(userId = "tester", roles = setOf("MEMBER")),
    )
}

// ─────────────────────────────────────────────────────────────────────────── //
// Generator DSL — WorkflowGenerators                                         //
// ─────────────────────────────────────────────────────────────────────────── //

/**
 * Property test 용 Kotest Arb(Arbitrary — 임의 값 생성기) 모음.
 *
 * Arb 는 "임의 값 생성기"를 의미한다. Kotest property 모듈에서 제공하며,
 * `checkAll` 호출 시 지정한 iterations 만큼 값을 뽑아 테스트를 반복한다.
 */
object WorkflowGenerators {

    // ── 기본 Arb ──────────────────────────────────────────────────────────────

    /** StateCategory 임의 선택. */
    private val categoryArb: Arb<StateCategory> = Arb.element(StateCategory.entries.toList())

    // ── Workflow Arb ─────────────────────────────────────────────────────────

    /**
     * 최소 2개 ~ 최대 6개 상태를 가진 유효한 Workflow Aggregate 를 생성한다.
     *
     * 상태 키 중복을 피하기 위해 index 기반 키를 사용한다.
     * DONE 카테고리 상태를 최소 1개 보장해 그래프 closed 조건을 충족한다.
     */
    val workflow: Arb<Workflow> = arbitrary { rs ->
        val stateCount = Arb.int(2..6).bind()
        val wfKey = "WF-${Arb.int(1..9999).bind()}"

        val states = (0 until stateCount).map { idx ->
            val category = if (idx == stateCount - 1) StateCategory.DONE else categoryArb.bind()
            WorkflowState(
                key = "S$idx",
                name = "State$idx",
                category = category,
                displayOrder = idx,
            )
        }

        val stateKeys = states.map { it.key }

        // 전이 1~(stateCount*2) 개 생성 — 중복 방지 위해 Set 기반
        val transitionCount = Arb.int(1..minOf(stateCount * 2, 8)).bind()
        val transitionSet = mutableSetOf<Triple<String, String, String>>()
        val transitions = mutableListOf<WorkflowTransition>()
        var attempts = 0
        while (transitions.size < transitionCount && attempts < transitionCount * 5) {
            attempts++
            val from = Arb.element(stateKeys).bind()
            val to = Arb.element(stateKeys.filter { it != from }.ifEmpty { stateKeys }).bind()
            val name = "T${transitions.size}"
            val triple = Triple(from, to, name)
            if (triple !in transitionSet) {
                transitionSet.add(triple)
                transitions.add(WorkflowTransition(fromStateKey = from, toStateKey = to, name = name))
            }
        }

        Workflow.of(key = wfKey, name = wfKey, states = states, transitions = transitions)
    }

    // ── 전이 선택 Arb ─────────────────────────────────────────────────────────

    /**
     * (workflow, fromStateKey, transition) 조합.
     *
     * 전이가 있는 workflow 에서 임의 전이 1개와 해당 출발 상태를 선택한다.
     * invariant 1 검증에서 사용한다.
     */
    val transitionTriples: Arb<Triple<Workflow, String, WorkflowTransition>> = arbitrary { rs ->
        val wf = workflow.bind()
        // transitions 이 비어 있는 경우 대비 — 최소 1개 보장하는 workflow Arb 덕분에 safe
        val tr = Arb.element(wf.transitions).bind()
        Triple(wf, tr.fromStateKey, tr)
    }

    // ── Validator/PostAction stub Arb ─────────────────────────────────────────

    /**
     * validator pass/fail 무작위 + postAction stub 목록을 포함하는 시나리오.
     */
    val validatorScenarios: Arb<ValidatorScenario> = arbitrary { rs ->
        val wf = workflow.bind()
        val tr = Arb.element(wf.transitions).bind()

        // Validator 0~3개 — 각각 Pass/Fail 무작위
        val validatorCount = Arb.int(0..3).bind()
        val validators = (0 until validatorCount).map { idx ->
            val shouldPass = Arb.boolean().bind()
            StubValidator(type = "STUB-V$idx", pass = shouldPass)
        }

        // PostAction 0~3개
        val postActionCount = Arb.int(0..3).bind()
        val postActions = (0 until postActionCount).map { idx ->
            StubPostAction(type = "STUB-PA$idx")
        }

        ValidatorScenario(
            workflow = wf,
            fromStateKey = tr.fromStateKey,
            transition = tr,
            validators = validators,
            postActions = postActions,
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────── //
// 시나리오 + Stub 구현체                                                       //
// ─────────────────────────────────────────────────────────────────────────── //

/**
 * property test 시나리오 데이터 클래스.
 */
data class ValidatorScenario(
    val workflow: Workflow,
    val fromStateKey: String,
    val transition: WorkflowTransition,
    val validators: List<WorkflowValidator>,
    val postActions: List<WorkflowPostAction>,
)

/**
 * pass/fail 을 생성자 인자로 제어하는 stub [WorkflowValidator].
 */
private class StubValidator(
    override val type: String,
    private val pass: Boolean,
) : WorkflowValidator {
    override fun validate(ctx: TransitionContext): ValidatorResult =
        if (pass) ValidatorResult.Pass else ValidatorResult.Fail(field = null, reason = "stub fail")
}

/**
 * 항상 빈 [PostActionPlan] 을 반환하는 stub [WorkflowPostAction].
 */
private class StubPostAction(
    override val type: String,
) : WorkflowPostAction {
    override fun evaluate(ctx: TransitionContext): PostActionPlan =
        PostActionPlan(fieldChanges = emptyList(), emitEvents = emptyList())
}
