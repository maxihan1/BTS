// 워크플로우 엔진 — plan(req) 으로 전이 계획 계산 (Validator 4종 순차 + PostAction 5종 누적, Propagation.MANDATORY)

package com.bts.workflow.engine

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
 * 전이 정의에 연결된 Validator/PostAction 설정을 조회하는 outbound port.
 *
 * 실제 구현은 jOOQ 로 workflow_validators / workflow_post_actions 테이블을 조회한다.
 * 본 PR 범위에서는 인터페이스만 정의하며, 구현체는 후속 task 에서 등록한다.
 */
interface WorkflowDefinitionRepository {
    /**
     * 주어진 전이에 설정된 Validator 설정 목록을 반환한다.
     * 순서는 YAML 정의 순서를 따른다 (순차 평가를 위해 보존해야 한다).
     *
     * @param transition 조회 대상 전이 정의
     */
    fun findValidators(transition: WorkflowTransition): List<ValidatorConfig>

    /**
     * 주어진 전이에 설정된 PostAction 설정 목록을 반환한다.
     *
     * @param transition 조회 대상 전이 정의
     */
    fun findPostActions(transition: WorkflowTransition): List<PostActionConfig>
}

/** Validator 한 건의 type + config 쌍. */
data class ValidatorConfig(val type: String, val config: Map<String, Any?>)

/** PostAction 한 건의 type + config 쌍. */
data class PostActionConfig(val type: String, val config: Map<String, Any?>)

// ──────────────────────────────────────────────────────────────────────── //
// WorkflowEngine                                                           //
// ──────────────────────────────────────────────────────────────────────── //

/**
 * 워크플로우 전이 엔진.
 *
 * [plan] 을 통해 다음 두 단계를 순서대로 수행한다.
 *
 * 1. **Validator 순차 평가** — 첫 Fail 즉시 [WorkflowValidatorFailureException].
 * 2. **PostAction 누적** — 모든 PostAction 의 [FieldChange] + [DomainEvent] 를 합산해 [TransitionPlan] 반환.
 *
 * 이 클래스는 상태를 직접 변경하지 않는다.
 * 반환된 [TransitionPlan] 을 호출자 ([com.bts.workflow.adapter.inbound.WorkflowTransitionAdapter]) 가
 * [com.bts.shared.workflow.TransitionResult] 로 래핑하여 상위 BC 에 전달한다.
 *
 * @param cache 워크플로우 메모리 캐시
 * @param validatorFactory Validator 인스턴스 팩토리
 * @param postActionFactory PostAction 인스턴스 팩토리
 * @param definitionRepo 전이별 Validator/PostAction 설정 조회 repository
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
     * 전이 요청을 검증하고 실행 계획을 반환한다.
     *
     * 반드시 활성 트랜잭션 안에서 호출해야 한다 ([Propagation.MANDATORY]).
     * 트랜잭션 없이 호출하면 Spring 이 [org.springframework.transaction.IllegalTransactionStateException] 을 던진다.
     *
     * 호출자인 [com.bts.workflow.adapter.inbound.WorkflowTransitionAdapter] 가 아래 예외를
     * [com.bts.shared.workflow.TransitionResult] 케이스로 매핑한다.
     *
     * @throws WorkflowNotFoundException 워크플로우·전이 정의를 찾을 수 없을 때
     * @throws WorkflowValidatorFailureException Validator 가 전이를 거부할 때
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

    // ── private helpers ── //

    private fun resolveWorkflow(req: TransitionRequest): Workflow =
        cache.findByKey(req.workflowKey)
            ?: throw WorkflowNotFoundException(req.workflowKey)

    private fun resolveTransition(
        req: TransitionRequest,
        workflow: Workflow,
    ): WorkflowTransition =
        workflow.transitions.find {
            it.fromStateKey == req.fromStateKey &&
                it.toStateKey == req.toStateKey &&
                it.name == req.transitionName
        } ?: throw WorkflowNotFoundException(
            "${req.workflowKey}::${req.transitionName}(${req.fromStateKey}→${req.toStateKey})",
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

    /** Validator 를 순차 평가한다. 첫 Fail 즉시 예외를 던진다. */
    private fun runValidators(
        ctx: TransitionContext,
        transition: WorkflowTransition,
    ) {
        for (cfg in definitionRepo.findValidators(transition)) {
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

    /** PostAction 을 모두 평가하고 fieldChanges 와 emitEvents 를 누적해 반환한다. */
    private fun runPostActions(
        ctx: TransitionContext,
        transition: WorkflowTransition,
    ): Pair<List<FieldChange>, List<DomainEvent>> {
        val fieldChanges = mutableListOf<FieldChange>()
        val emitEvents = mutableListOf<DomainEvent>()
        for (cfg in definitionRepo.findPostActions(transition)) {
            val postAction = postActionFactory.create(cfg.type, cfg.config)
            val plan = postAction.evaluate(ctx)
            fieldChanges += plan.fieldChanges
            emitEvents += plan.emitEvents
        }
        return fieldChanges to emitEvents
    }
}
