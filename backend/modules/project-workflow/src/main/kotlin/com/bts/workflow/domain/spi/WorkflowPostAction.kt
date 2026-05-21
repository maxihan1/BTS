// 워크플로우 PostAction SPI — 전이 후 자동 처리 계산 (실행은 호출자 BC 책임 — GAP-2)

package com.bts.workflow.domain.spi

import com.bts.workflow.domain.dto.PostActionPlan
import com.bts.workflow.domain.dto.TransitionContext

/**
 * 전이(Transition) 후 자동 처리 항목을 **계산**하는 SPI (Service Provider Interface).
 *
 * ## GAP-2 결정 — 적용은 호출자 BC 책임
 * 이 interface 는 무엇을 해야 하는지 [PostActionPlan] 으로 반환만 할 뿐,
 * 실제 필드 변경·이벤트 발행 등의 **실행**은 수행하지 않는다.
 * 실행 책임은 이 SPI 를 호출하는 BC (예. issue-tracking) 가 진다.
 *
 * 구현체는 Spring 빈으로 등록되며, WorkflowEngine 이 [type] 문자열로 조회한다.
 *
 * 참조. FR-WF-01 / docs/sdd/ §project-workflow
 */
interface WorkflowPostAction {
    /**
     * 이 PostAction 의 고유 타입 식별자.
     * YAML 워크플로우 정의의 `post_actions[].type` 값과 일치해야 한다.
     */
    val type: String

    /**
     * 주어진 전이 컨텍스트를 바탕으로 적용할 처리 계획을 계산한다.
     *
     * @param ctx 전이 시점의 컨텍스트 (이슈 상태, 액터, 커스텀 필드 등)
     * @return 필드 변경 목록 + 발행할 도메인 이벤트 목록을 담은 [PostActionPlan]
     */
    fun evaluate(ctx: TransitionContext): PostActionPlan
}
