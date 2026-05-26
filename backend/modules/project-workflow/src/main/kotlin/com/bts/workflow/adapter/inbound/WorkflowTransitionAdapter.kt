// WorkflowTransitionAdapter — WorkflowTransitionPort 구현체. WorkflowEngine 호출 + try/catch 후 TransitionResult 매핑.

package com.bts.workflow.adapter.inbound

import com.bts.workflow.domain.dto.TransitionRequest
import com.bts.workflow.domain.dto.TransitionResult
import com.bts.workflow.domain.exception.WorkflowExpressionTimeoutException
import com.bts.workflow.domain.exception.WorkflowNotFoundException
import com.bts.workflow.domain.exception.WorkflowValidatorFailureException
import com.bts.workflow.engine.WorkflowEngine
import com.bts.workflow.port.inbound.WorkflowTransitionPort
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
 * [Propagation.MANDATORY] — 호출자가 반드시 활성 트랜잭션 안에서 이 포트를 호출해야 한다.
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
    override fun plan(req: TransitionRequest): TransitionResult =
        try {
            TransitionResult.Success(workflowEngine.plan(req))
        } catch (e: Exception) {
            mapException(req, e)
        }

    // ── private helper ────────────────────────────────────────────────────────

    private fun mapException(req: TransitionRequest, e: Exception): TransitionResult =
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
