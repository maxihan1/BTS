// WorkflowTransitionAdapter — WorkflowTransitionPort 구현체. WorkflowEngine 호출 + try/catch 후 TransitionResult 매핑.

package com.bts.workflow.adapter.inbound

import com.bts.shared.workflow.AvailableTransitionsRequest
import com.bts.shared.workflow.AvailableTransitionsResult
import com.bts.shared.workflow.TransitionRequest
import com.bts.shared.workflow.TransitionResult
import com.bts.shared.workflow.WorkflowTransitionPort
import com.bts.workflow.domain.exception.WorkflowExpressionTimeoutException
import com.bts.workflow.domain.exception.WorkflowNotFoundException
import com.bts.workflow.domain.exception.WorkflowValidatorFailureException
import com.bts.workflow.engine.WorkflowEngine
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * [WorkflowTransitionPort] 구현체.
 *
 * [WorkflowEngine] 을 호출하고 내부 예외를 [TransitionResult] 케이스로 매핑한다.
 * 호출자 BC (바운디드 컨텍스트 — 책임 범위로 나눈 도메인 단위) 인 issue-tracking / automation 은
 * 이 adapter 를 통해 전이를 요청하며,
 * `com.bts.workflow.domain.exception.*` 를 직접 import 하지 않아도 된다 (BC 격리 보장).
 *
 * ### 트랜잭션 계약
 * - [plan]: [Propagation.MANDATORY] — 쓰기 트랜잭션 컨텍스트에서 호출해야 한다.
 * - [availableTransitions]: [Propagation.MANDATORY] + readOnly=true — 읽기 전용 트랜잭션.
 *
 * ### post-action 미실행 보장
 * [availableTransitions] 는 [WorkflowEngine.availableTransitions] 에 위임하며,
 * 해당 메서드는 Validator 평가만 수행한다. SetField/Notify 등 PostAction 은 절대 실행되지 않는다.
 *
 * ### 관련 ADR
 * docs/adr/2026-05-26-workflow-transition-port-result-sealed.md
 */
@Component
class WorkflowTransitionAdapter(
    private val workflowEngine: WorkflowEngine,
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
     * 현재 상태에서 validator 를 통과하는 가용 전이 목록 조회를 [WorkflowEngine] 에 위임한다.
     *
     * post-action (SetField/Notify 등) 은 절대 실행하지 않는다.
     * 실질 로직은 [WorkflowEngine.availableTransitions] 가 담당한다.
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
        return workflowEngine.availableTransitions(req)
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
