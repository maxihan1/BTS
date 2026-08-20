// 워크플로우 엔진 — plan(req) 전환 계획 계산 + availableTransitions(req) 가용 전환 열거 (Propagation.MANDATORY)

package com.bts.workflow.engine

import com.bts.shared.workflow.AvailableTransitionView
import com.bts.shared.workflow.AvailableTransitionsRequest
import com.bts.shared.workflow.AvailableTransitionsResult
import com.bts.shared.workflow.DomainEvent
import com.bts.shared.workflow.FieldChange
import com.bts.shared.workflow.TransitionPlan
import com.bts.shared.workflow.TransitionRequest
import com.bts.workflow.cache.WorkflowCache
import com.bts.workflow.domain.Workflow
import com.bts.workflow.domain.WorkflowState
import com.bts.workflow.domain.WorkflowTransition
import com.bts.workflow.domain.dto.TransitionContext
import com.bts.workflow.domain.exception.WorkflowNotFoundException
import com.bts.workflow.domain.exception.WorkflowValidatorFailureException
import com.bts.workflow.domain.expression.DefaultActorView
import com.bts.workflow.domain.expression.DefaultIssueView
import com.bts.workflow.domain.spi.ValidatorPhase
import com.bts.workflow.domain.spi.ValidatorResult
import com.bts.workflow.domain.spi.WorkflowPostAction
import com.bts.workflow.domain.spi.WorkflowValidator
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

// ──────────────────────────────────────────────────────────────────────── //
// SPI factory / repository 인터페이스 — 실제 구현은 후속 task 에서 등록      //
// ──────────────────────────────────────────────────────────────────────── //

/**
 * type 식별자와 YAML config 를 받아 [WorkflowValidator] 인스턴스를 반환하는 팩토리.
 *
 * 지원 type. "RequiredField" / "Permission" / "NotStatusCategory" / "CustomExpression".
 * 구현체는 Spring Bean 으로 등록되어 WorkflowEngine 에 주입된다.
 */
interface WorkflowValidatorFactory {
    /**
     * @param type YAML 워크플로우 정의의 `validators[].type` 값
     * @param config YAML 워크플로우 정의의 `validators[].config` 값
     * @throws IllegalArgumentException 지원하지 않는 type 일 때
     */
    fun create(
        type: String,
        config: Map<String, Any?>,
    ): WorkflowValidator
}

/**
 * type 식별자와 YAML config 를 받아 [WorkflowPostAction] 인스턴스를 반환하는 팩토리.
 *
 * 지원 type. "SetField" / "Notify" / "AddWatcher" / "RunAutomation" / "CallWebhook".
 * 구현체는 Spring Bean 으로 등록되어 WorkflowEngine 에 주입된다.
 */
interface WorkflowPostActionFactory {
    /**
     * @param type YAML 워크플로우 정의의 `post_actions[].type` 값
     * @param config YAML 워크플로우 정의의 `post_actions[].config` 값
     * @throws IllegalArgumentException 지원하지 않는 type 일 때
     */
    fun create(
        type: String,
        config: Map<String, Any?>,
    ): WorkflowPostAction
}

/**
 * 전환 정의에 연결된 Validator/PostAction 설정을 조회하는 outbound port.
 *
 * 실제 구현은 jOOQ 로 workflow_validators / workflow_post_actions 테이블을 조회한다.
 * 본 PR 범위에서는 인터페이스만 정의하며, 구현체는 후속 task 에서 등록한다.
 */
interface WorkflowDefinitionRepository {
    /**
     * 주어진 전환에 설정된 Validator 설정 목록을 반환한다.
     * 순서는 YAML 정의 순서를 따른다 (순차 평가를 위해 보존해야 한다).
     *
     * [workflowKey] 는 workflow_id 해석을 위한 1급 식별자로, 같은 (from, to) 쌍을 사용하는
     * 여러 워크플로우가 존재할 때 올바른 validator 행을 선택하기 위해 필수로 전달해야 한다.
     * 이 인자 없이 transition 만으로 조회하면 오매칭(silent 결함)이 발생한다.
     *
     * @param workflowKey 워크플로우 식별자 (workflow 테이블 key 컬럼 값)
     * @param transition 조회 대상 전환 정의
     */
    fun findValidators(
        workflowKey: String,
        transition: WorkflowTransition,
    ): List<ValidatorConfig>

    /**
     * 주어진 전환에 설정된 PostAction 설정 목록을 반환한다.
     *
     * [workflowKey] 는 workflow_id 해석을 위한 1급 식별자로, 같은 (from, to) 쌍을 사용하는
     * 여러 워크플로우가 존재할 때 올바른 post_action 행을 선택하기 위해 필수로 전달해야 한다.
     *
     * @param workflowKey 워크플로우 식별자 (workflow 테이블 key 컬럼 값)
     * @param transition 조회 대상 전환 정의
     */
    fun findPostActions(
        workflowKey: String,
        transition: WorkflowTransition,
    ): List<PostActionConfig>
}

/** Validator 한 건의 type + config 쌍. phase 는 validator 인스턴스에서 읽는다(단일 출처). */
data class ValidatorConfig(
    val type: String,
    val config: Map<String, Any?>,
)

/** PostAction 한 건의 type + config 쌍. */
data class PostActionConfig(val type: String, val config: Map<String, Any?>)

// ──────────────────────────────────────────────────────────────────────── //
// WorkflowEngine                                                           //
// ──────────────────────────────────────────────────────────────────────── //

/**
 * 워크플로우 전환 엔진.
 *
 * [plan] 을 통해 다음 두 단계를 순서대로 수행한다.
 *
 * 1. **Validator 순차 평가** — 첫 Fail 즉시 [WorkflowValidatorFailureException].
 * 2. **PostAction 누적** — 모든 PostAction 의 [FieldChange] + [DomainEvent] 를 합산해 [TransitionPlan] 반환.
 *
 * [availableTransitions] 는 Validator 평가만 수행하며 PostAction 을 절대 실행하지 않는다 (GET 읽기 경로 — 부수 효과 없음).
 *
 * 이 클래스는 상태를 직접 변경하지 않는다.
 * 반환된 [TransitionPlan] 을 호출자 ([com.bts.workflow.adapter.inbound.WorkflowTransitionAdapter]) 가
 * [com.bts.shared.workflow.TransitionResult] 로 래핑하여 상위 BC 에 전달한다.
 *
 * @param cache 워크플로우 메모리 캐시
 * @param validatorFactory Validator 인스턴스 팩토리
 * @param postActionFactory PostAction 인스턴스 팩토리
 * @param definitionRepo 전환별 Validator/PostAction 설정 조회 repository
 */
@Service
class WorkflowEngine(
    private val cache: WorkflowCache,
    private val validatorFactory: WorkflowValidatorFactory,
    private val postActionFactory: WorkflowPostActionFactory,
    private val definitionRepo: WorkflowDefinitionRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 전환 요청을 검증하고 실행 계획을 반환한다.
     *
     * 반드시 활성 트랜잭션 안에서 호출해야 한다 ([Propagation.MANDATORY]).
     * 트랜잭션 없이 호출하면 Spring 이 [org.springframework.transaction.IllegalTransactionStateException] 을 던진다.
     *
     * 호출자인 [com.bts.workflow.adapter.inbound.WorkflowTransitionAdapter] 가 아래 예외를
     * [com.bts.shared.workflow.TransitionResult] 케이스로 매핑한다.
     *
     * @throws WorkflowNotFoundException 워크플로우·전환 정의를 찾을 수 없을 때
     * @throws WorkflowValidatorFailureException Validator 가 전환을 거부할 때
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun plan(req: TransitionRequest): TransitionPlan {
        log.debug(
            "WorkflowEngine.plan: workflowKey={}, issueKey={}, transition={}→{}",
            req.workflowKey,
            req.issueKey,
            req.fromStateKey,
            req.toStateKey,
        )

        val workflow = resolveWorkflow(req)
        val transition = resolveTransition(req, workflow)
        val ctx = buildContext(req, workflow, transition)

        runValidators(ctx, transition)
        val (fieldChanges, emitEvents) = runPostActions(ctx, transition)

        return TransitionPlan(transition.toStateKey, fieldChanges, emitEvents)
    }

    /**
     * 현재 상태에서 Validator 를 통과하는 가용 전환 목록을 반환한다.
     *
     * PostAction 은 절대 실행하지 않는다. GET 읽기 경로이므로 부수 효과 없음.
     * 반드시 활성 읽기 전용 트랜잭션 안에서 호출해야 한다 ([Propagation.MANDATORY], readOnly=true).
     *
     * @param req 가용 전환 열거 요청 DTO
     * @return [AvailableTransitionsResult.Success] 또는 [AvailableTransitionsResult.WorkflowNotFound]
     */
    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    fun availableTransitions(req: AvailableTransitionsRequest): AvailableTransitionsResult {
        log.debug(
            "WorkflowEngine.availableTransitions: workflowKey={} fromStateKey={}",
            req.workflowKey,
            req.fromStateKey,
        )

        val workflow =
            cache.findByKey(req.workflowKey)
                ?: run {
                    log.info(
                        "WorkflowEngine.availableTransitions: workflow not found key={}",
                        req.workflowKey,
                    )
                    return AvailableTransitionsResult.WorkflowNotFound(req.workflowKey)
                }

        val candidates = workflow.transitions.filter { it.fromStateKey == req.fromStateKey }
        val passed = candidates.filter { transition -> passesValidators(req, workflow, transition) }

        return AvailableTransitionsResult.Success(
            passed.map { transition ->
                AvailableTransitionView(
                    // 후보 필터가 fromStateKey == req.fromStateKey 인 전환만 남기므로 여기서는 항상 같은 값이다.
                    fromStateKey = transition.fromStateKey ?: req.fromStateKey,
                    toStateKey = transition.toStateKey,
                    name = transition.name,
                    toCategory = workflow.states.find { it.key == transition.toStateKey }?.category?.name,
                )
            },
        )
    }

    // ── private helpers ── //

    private fun resolveWorkflow(req: TransitionRequest): Workflow =
        cache.findByKey(req.workflowKey)
            ?: throw WorkflowNotFoundException(req.workflowKey)

    /**
     * transition identity = (from, to) — ADR 2026-05-28-workflow-transition-identity-policy 참조.
     */
    private fun resolveTransition(
        req: TransitionRequest,
        workflow: Workflow,
    ): WorkflowTransition =
        workflow.transitions.find {
            it.fromStateKey == req.fromStateKey &&
                it.toStateKey == req.toStateKey
        } ?: throw WorkflowNotFoundException(
            "${req.workflowKey}::${req.fromStateKey}→${req.toStateKey}",
        )

    private fun buildContext(
        req: TransitionRequest,
        workflow: Workflow,
        transition: WorkflowTransition,
    ): TransitionContext {
        val fromState: WorkflowState =
            workflow.states.find { it.key == req.fromStateKey }
                ?: throw WorkflowNotFoundException("${req.workflowKey}::state::${req.fromStateKey}")
        val issueView =
            DefaultIssueView(
                key = req.issueKey,
                priority = req.issueFields["priority"] as? String ?: "",
                fields = req.issueFields,
            )
        val actorView = DefaultActorView(userId = req.actorId, roles = req.actorRoles)
        return TransitionContext(req, workflow, fromState, transition, issueView, actorView)
    }

    /**
     * Validator 를 순차 평가한다. 첫 Fail 즉시 예외를 던진다.
     *
     * plan 경로(전환 실행)에서만 호출된다. AVAILABILITY / EXECUTION 구분 없이 모든 phase 의
     * validator 를 평가한다. EXECUTION 페이즈 게이트(RequiredField 등)도 이 경로에서 차단한다.
     * availableTransitions 경로에서는 이 함수를 호출하지 않고 passesValidators 를 사용한다.
     *
     * ctx.request.workflowKey 를 definitionRepo 에 전달해 같은 (from, to) 를 공유하는
     * 다른 워크플로우의 validator 가 오매칭되지 않도록 한다.
     */
    private fun runValidators(
        ctx: TransitionContext,
        transition: WorkflowTransition,
    ) {
        for (cfg in definitionRepo.findValidators(ctx.request.workflowKey, transition)) {
            val validator = validatorFactory.create(cfg.type, cfg.config)
            val result = validator.validate(ctx)
            if (result is ValidatorResult.Fail) {
                log.info(
                    "WorkflowEngine.plan: validator='{}' failed field='{}' reason='{}'",
                    validator.type,
                    result.field,
                    result.reason,
                )
                throw WorkflowValidatorFailureException(validator.type, result.field, result.reason)
            }
        }
    }

    /**
     * PostAction 을 모두 평가하고 fieldChanges 와 emitEvents 를 누적해 반환한다.
     *
     * ctx.request.workflowKey 를 definitionRepo 에 전달해 같은 (from, to) 를 공유하는
     * 다른 워크플로우의 post_action 이 오매칭되지 않도록 한다.
     */
    private fun runPostActions(
        ctx: TransitionContext,
        transition: WorkflowTransition,
    ): Pair<List<FieldChange>, List<DomainEvent>> {
        val fieldChanges = mutableListOf<FieldChange>()
        val emitEvents = mutableListOf<DomainEvent>()
        for (cfg in definitionRepo.findPostActions(ctx.request.workflowKey, transition)) {
            val postAction = postActionFactory.create(cfg.type, cfg.config)
            val plan = postAction.evaluate(ctx)
            fieldChanges += plan.fieldChanges
            emitEvents += plan.emitEvents
        }
        return fieldChanges to emitEvents
    }

    /**
     * 단일 전환에 대해 AVAILABILITY 페이즈 Validator 만 평가하고 통과 여부를 반환한다.
     *
     * availableTransitions 경로(읽기, 버튼 노출 결정)에서만 호출된다.
     * EXECUTION 페이즈 validator(RequiredField 등)는 건너뛴다 — 버튼 노출과 실행 차단이
     * 분리되어야 하기 때문이다(Jira transition screen 시맨틱). 실행 차단은 runValidators 에서 담당.
     *
     * PostAction 은 평가하지 않는다 — [availableTransitions] 의 읽기 전용 계약을 유지한다.
     * fromState 가 워크플로우에 존재하지 않으면 false 를 반환한다.
     *
     * req.workflowKey 를 definitionRepo 에 전달해 같은 (from, to) 를 공유하는
     * 다른 워크플로우의 validator 가 오매칭되지 않도록 한다.
     */
    private fun passesValidators(
        req: AvailableTransitionsRequest,
        workflow: Workflow,
        transition: WorkflowTransition,
    ): Boolean {
        val fromState = workflow.states.find { it.key == req.fromStateKey } ?: return false
        val issueView =
            DefaultIssueView(
                key = req.issueKey,
                priority = req.issueFields["priority"] as? String ?: "",
                fields = req.issueFields,
            )
        val actorView = DefaultActorView(userId = req.actorId, roles = req.actorRoles)
        val syntheticRequest =
            TransitionRequest(
                workflowKey = req.workflowKey,
                issueKey = "",
                fromStateKey = req.fromStateKey,
                toStateKey = transition.toStateKey,
                actorId = req.actorId,
                actorRoles = req.actorRoles,
                issueFields = req.issueFields,
                version = 0L,
            )
        val ctx = TransitionContext(syntheticRequest, workflow, fromState, transition, issueView, actorView)

        // availableTransitions 는 AVAILABILITY 페이즈 validator 만 평가한다.
        // EXECUTION 페이즈(RequiredField 등)는 전환 실행 시(plan 경로)에만 평가되므로 건너뛴다.
        // phase 의 진실 출처는 validator 인스턴스이므로, 인스턴스 생성 후 phase 를 확인한다.
        // 이렇게 해야 "입력이 필요한 전환"도 목록에는 노출되고(버튼 보임), 실행 시점에만 차단된다.
        for (cfg in definitionRepo.findValidators(req.workflowKey, transition)) {
            val validator = validatorFactory.create(cfg.type, cfg.config)
            if (validator.phase == ValidatorPhase.EXECUTION) continue
            val result = validator.validate(ctx)
            if (result is ValidatorResult.Fail) {
                log.debug(
                    "WorkflowEngine.availableTransitions: validator='{}' rejected {}→{} reason='{}'",
                    validator.type,
                    transition.fromStateKey,
                    transition.toStateKey,
                    result.reason,
                )
                return false
            }
        }
        return true
    }
}
