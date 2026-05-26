// 워크플로우 전이 inbound port — issue-tracking / automation BC 호출 (Propagation.MANDATORY)

package com.bts.workflow.port.inbound

import com.bts.workflow.domain.dto.TransitionRequest
import com.bts.workflow.domain.dto.TransitionResult
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
 * ### 반환 계약
 *
 * 구현체는 내부 예외를 [TransitionResult] 케이스로 매핑하여 반환해야 한다.
 * 호출자 BC 는 `when` 식으로 exhaustive 하게 처리해야 하며 `else` 브랜치는 금지한다.
 *
 * - [TransitionResult.Success] — 검증 통과. [com.bts.workflow.domain.dto.TransitionPlan] 을 포함.
 * - [TransitionResult.ValidatorFailure] — 등록된 [com.bts.workflow.domain.spi.WorkflowValidator] 중
 *   하나 이상이 전이를 거부했을 때. [TransitionResult.ValidatorFailure.message] 에 사유 포함.
 * - [TransitionResult.WorkflowNotFound] — [TransitionRequest.workflowKey] 에 해당하는 워크플로우가
 *   DB 에 없을 때. [TransitionResult.WorkflowNotFound.key] 에 조회 실패한 키 포함.
 * - [TransitionResult.ExpressionTimeout] — SpEL 조건식 평가가 제한 시간을 초과했을 때.
 *   [TransitionResult.ExpressionTimeout.message] 에 상세 메시지 포함.
 */
interface WorkflowTransitionPort {
    /**
     * 전이 요청을 검증하고 결과를 반환한다.
     *
     * project-workflow 는 이 메서드에서 상태를 직접 변경하지 않는다.
     * [TransitionResult.Success.plan] 을 호출자 BC 가 자신의 트랜잭션 안에서 적용해야 한다.
     *
     * @param req 전이 요청 DTO. [TransitionRequest.validate] 를 통과한 상태여야 한다.
     * @return 전이 검증 결과. 4 케이스 반환 계약은 [WorkflowTransitionPort] KDoc 참조.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun plan(req: TransitionRequest): TransitionResult
}
