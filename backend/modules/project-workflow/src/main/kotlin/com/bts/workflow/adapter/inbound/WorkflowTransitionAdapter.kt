// WorkflowTransitionAdapter — WorkflowTransitionPort 구현체. WorkflowEngine 호출 + try/catch 후 TransitionResult 매핑. availableTransitions는 validator-only 평가.

package com.bts.workflow.adapter.inbound

import com.bts.shared.workflow.AvailableTransitionView
import com.bts.shared.workflow.AvailableTransitionsRequest
import com.bts.shared.workflow.AvailableTransitionsResult
import com.bts.shared.workflow.TransitionRequest
import com.bts.shared.workflow.TransitionResult
import com.bts.shared.workflow.WorkflowTransitionPort
import com.bts.workflow.cache.WorkflowCache
import com.bts.workflow.domain.WorkflowTransition
import com.bts.workflow.domain.dto.TransitionContext
import com.bts.workflow.domain.exception.WorkflowExpressionTimeoutException
import com.bts.workflow.domain.exception.WorkflowNotFoundException
import com.bts.workflow.domain.exception.WorkflowValidatorFailureException
import com.bts.workflow.domain.expression.DefaultActorView
import com.bts.workflow.domain.expression.DefaultIssueView
import com.bts.workflow.domain.spi.ValidatorResult
import com.bts.workflow.engine.WorkflowDefinitionRepository
import com.bts.workflow.engine.WorkflowEngine
import com.bts.workflow.engine.WorkflowValidatorFactory
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * [WorkflowTransitionPort] 구현체.
 *
 * [WorkflowEngine] 을 호출하고 내부 예외를 [TransitionResult] 케이스로 매핑한다.
 * 호출자 BC (issue-tracking / automation) 는 이 adapter 를 통해 전이를 요청하며,
 * `com.bts.workflow.domain.exception.*` 를 직접 import 하지 않아도 된다 (BC 격리 보장).
 *
 * ### 트랜잭션 계약
 * - [plan]: [Propagation.MANDATORY] — 쓰기 트랜잭션 컨텍스트에서 호출해야 한다.
 * - [availableTransitions]: [Propagation.MANDATORY] + readOnly=true — 읽기 전용 트랜잭션.
 *
 * ### post-action 미실행 보장
 * [availableTransitions] 는 [WorkflowEngine.plan] 을 호출하지 않는다.
 * WorkflowCache → validator 평가만 수행하므로 SetField/Notify 등 부수 효과는 실행되지 않는다.
 *
 * ### 관련 ADR
 * docs/adr/2026-05-26-workflow-transition-port-result-sealed.md
 */
@Component
class WorkflowTransitionAdapter(
    private val workflowEngine: WorkflowEngine,
    private val workflowCache: WorkflowCache,
    private val definitionRepo: WorkflowDefinitionRepository,
    private val validatorFactory: WorkflowValidatorFactory,
) : WorkflowTransitionPort {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 전이 요청을 [WorkflowEngine] 에 위임하고 결과를 [TransitionResult] 로 매핑한다.
     *
     * 내부 예외는 [mapException] 에서 [TransitionResult] 케이스로 변환한다.
     * 인식되지 않은 예외는 그대로 re-throw 한다.
     *
     * @param req 전이 요청 DTO
     * @return [TransitionResult] — 4 케이스 반환 계약은 [WorkflowTransitionPort] KDoc 참조
     */
    @Transactional(propagation = Propagation.MANDATORY)
    @Suppress("TooGenericExceptionCaught")
    override fun plan(req: TransitionRequest): TransitionResult =
        try {
            TransitionResult.Success(workflowEngine.plan(req))
        } catch (e: Exception) {
            // workflow domain exception 4종을 sealed Result 로 매핑하는 책임이 본 adapter — 의도된 generic catch.
            // 알 수 없는 RuntimeException 은 mapException 에서 재throw → fallback 동작.
            mapException(req, e)
        }

    /**
     * 현재 상태에서 validator를 통과하는 가용 전이 목록을 반환한다.
     *
     * post-action (SetField/Notify 등) 은 절대 실행하지 않는다.
     * [WorkflowEngine.plan] 을 호출하지 않으므로 부수 효과 없이 validator 평가만 수행한다.
     *
     * @param req 가용 전이 열거 요청 DTO
     * @return 2 케이스 반환 계약은 [WorkflowTransitionPort] KDoc 참조
     */
    @Transactional(readOnly = true, propagation = Propagation.MANDATORY)
    override fun availableTransitions(req: AvailableTransitionsRequest): AvailableTransitionsResult {
        log.debug(
            "WorkflowTransitionAdapter.availableTransitions: workflowKey={} fromStateKey={}",
            req.workflowKey,
            req.fromStateKey,
        )

        val workflow = workflowCache.findByKey(req.workflowKey)
            ?: run {
                log.info(
                    "WorkflowTransitionAdapter.availableTransitions: workflow not found key={}",
                    req.workflowKey,
                )
                return AvailableTransitionsResult.WorkflowNotFound(req.workflowKey)
            }

        val candidates = workflow.transitions.filter { it.fromStateKey == req.fromStateKey }
        val passed = candidates.filter { transition -> passesValidators(req, workflow, transition) }

        return AvailableTransitionsResult.Success(
            passed.map { AvailableTransitionView(it.fromStateKey, it.toStateKey, it.name) },
        )
    }

    // ── private helper ────────────────────────────────────────────────────────

    /**
     * 단일 전이에 대해 validator 평가만 수행하고 통과 여부를 반환한다.
     *
     * post-action 은 평가하지 않는다 — [availableTransitions] 의 읽기 전용 계약을 유지한다.
     */
    private fun passesValidators(
        req: AvailableTransitionsRequest,
        workflow: com.bts.workflow.domain.Workflow,
        transition: WorkflowTransition,
    ): Boolean {
        val fromState = workflow.states.find { it.key == req.fromStateKey } ?: return false
        val issueView = DefaultIssueView(
            key = req.actorId,
            priority = req.issueFields["priority"] as? String ?: "",
            fields = req.issueFields,
        )
        val actorView = DefaultActorView(userId = req.actorId, roles = req.actorRoles)
        // availableTransitions용 최소 TransitionRequest — validator 평가에만 사용
        val syntheticRequest = com.bts.shared.workflow.TransitionRequest(
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

        for (cfg in definitionRepo.findValidators(transition)) {
            val validator = validatorFactory.create(cfg.type, cfg.config)
            val result = validator.validate(ctx)
            if (result is ValidatorResult.Fail) {
                log.debug(
                    "WorkflowTransitionAdapter.availableTransitions: validator='{}' rejected {}→{} reason='{}'",
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

    private fun mapException(
        req: TransitionRequest,
        e: Exception,
    ): TransitionResult =
        when (e) {
            is WorkflowValidatorFailureException -> {
                log.info(
                    "WorkflowTransitionAdapter.plan: validator failure workflowKey={} issueKey={} message={}",
                    req.workflowKey,
                    req.issueKey,
                    e.message,
                )
                TransitionResult.ValidatorFailure(
                    message = e.message ?: "워크플로우 유효성 검증에 실패했습니다.",
                )
            }
            is WorkflowNotFoundException -> {
                log.info(
                    "WorkflowTransitionAdapter.plan: workflow not found key={}",
                    e.workflowKey,
                )
                TransitionResult.WorkflowNotFound(key = e.workflowKey)
            }
            is WorkflowExpressionTimeoutException -> {
                log.warn(
                    "WorkflowTransitionAdapter.plan: expression timeout workflowKey={} expression={}",
                    req.workflowKey,
                    e.expression,
                )
                TransitionResult.ExpressionTimeout(
                    message = e.message ?: "워크플로우 표현식 평가 시간을 초과했습니다.",
                )
            }
            else -> throw e
        }
}
