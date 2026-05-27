// Validator/PostAction 평가 컨텍스트 (request + workflow + state + transition + sealed root view)

package com.bts.workflow.domain.dto

import com.bts.shared.workflow.TransitionRequest
import com.bts.workflow.domain.Workflow
import com.bts.workflow.domain.WorkflowState
import com.bts.workflow.domain.WorkflowTransition
import com.bts.workflow.domain.expression.ActorView
import com.bts.workflow.domain.expression.IssueView

/**
 * 워크플로우 전이(Transition) 실행 중 Validator 와 PostAction 이 공유하는 읽기 전용 컨텍스트.
 *
 * [WorkflowValidator][com.bts.workflow.domain.spi.WorkflowValidator] 와
 * [WorkflowPostAction][com.bts.workflow.domain.spi.WorkflowPostAction] 모두 이 컨텍스트를
 * 파라미터로 받아 평가를 수행한다.
 * 어떤 구현체도 이 컨텍스트를 변경해서는 안 된다 — 모든 프로퍼티가 val 이다.
 *
 * @param request 원본 전이 요청 DTO (호출자 BC 가 제공).
 * @param workflow 현재 적용 중인 워크플로우 aggregate.
 * @param fromState 전이 출발 상태.
 * @param transition 실행되는 전이 정의.
 * @param issueView 이슈 필드를 SpEL(Spring Expression Language) 평가용으로 래핑한 읽기 전용 뷰.
 * @param actorView 실행자 정보를 SpEL 평가용으로 래핑한 읽기 전용 뷰.
 */
data class TransitionContext(
    val request: TransitionRequest,
    val workflow: Workflow,
    val fromState: WorkflowState,
    val transition: WorkflowTransition,
    val issueView: IssueView,
    val actorView: ActorView,
)
