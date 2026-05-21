// 워크플로우 전이 inbound port — issue-tracking / automation BC 호출 (Propagation.MANDATORY)

package com.bts.workflow.port.inbound

import com.bts.workflow.domain.dto.TransitionPlan
import com.bts.workflow.domain.dto.TransitionRequest
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * 워크플로우 전이 inbound port.
 *
 * issue-tracking BC 와 automation BC 가 전이를 요청할 때 이 인터페이스를 호출한다.
 * 호출자는 반드시 활성 트랜잭션 안에서 이 포트를 호출해야 한다 ([Propagation.MANDATORY]).
 * 트랜잭션 없이 호출하면 Spring 이 [org.springframework.transaction.IllegalTransactionStateException]
 * 을 던진다.
 *
 * ### 예외 계약
 *
 * @throws com.bts.workflow.domain.exception.WorkflowNotFoundException
 *   [TransitionRequest.workflowKey] 에 해당하는 워크플로우가 DB 에 없을 때.
 * @throws com.bts.workflow.domain.exception.WorkflowValidatorFailureException
 *   등록된 [com.bts.workflow.domain.spi.WorkflowValidator] 중 하나 이상이 전이를 거부할 때.
 * @throws com.bts.workflow.domain.exception.WorkflowExpressionTimeoutException
 *   SpEL(Spring Expression Language) 조건식 평가가 제한 시간을 초과할 때.
 */
interface WorkflowTransitionPort {
    /**
     * 전이 요청을 검증하고 실행 계획을 반환한다.
     *
     * project-workflow 는 이 메서드에서 상태를 직접 변경하지 않는다.
     * 반환된 [TransitionPlan] 을 호출자 BC 가 자신의 트랜잭션 안에서 적용해야 한다.
     *
     * @param req 전이 요청 DTO. [TransitionRequest.validate] 를 통과한 상태여야 한다.
     * @return 호출자 BC 가 적용해야 할 전이 실행 계획.
     * @throws com.bts.workflow.domain.exception.WorkflowNotFoundException
     * @throws com.bts.workflow.domain.exception.WorkflowValidatorFailureException
     * @throws com.bts.workflow.domain.exception.WorkflowExpressionTimeoutException
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun plan(req: TransitionRequest): TransitionPlan
}
