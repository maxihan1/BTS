// outbox 발행 예약 — 호출자 BC 가 outbox INSERT

package com.bts.shared.workflow

/**
 * 도메인 이벤트 발행 예약 DTO.
 *
 * project-workflow 는 이 DTO 를 [TransitionPlan.emitEvents] 목록으로 반환하기만 한다.
 * 실제 pgmq(PostgreSQL 기반 메시지 큐) outbox INSERT 는 이 값을 받은 호출자 BC 가 수행한다.
 * 이를 통해 워크플로우 BC 가 직접 이벤트 인프라에 의존하지 않도록 격리한다.
 *
 * @param type 이벤트 유형 식별자. 예: "ISSUE_TRANSITIONED", "ISSUE_DONE".
 * @param payload 이벤트 페이로드. Map 형태로 jsonb 호환이며 Any? 값을 허용한다.
 */
data class DomainEvent(
    val type: String,
    val payload: Map<String, Any?>,
)
