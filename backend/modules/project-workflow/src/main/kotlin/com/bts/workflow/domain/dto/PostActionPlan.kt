// PostAction 계산 결과 — fieldChanges + emitEvents (실행은 호출자 BC)

package com.bts.workflow.domain.dto

import com.bts.shared.workflow.DomainEvent
import com.bts.shared.workflow.FieldChange

/**
 * [com.bts.workflow.domain.spi.WorkflowPostAction.evaluate] 의 반환값.
 *
 * PostAction 구현체는 전이 컨텍스트를 분석해 무엇을 해야 하는지를 이 객체로 반환한다.
 * 실제 필드 변경 적용 및 이벤트 발행(outbox INSERT)은 이 값을 수신한 호출자 BC 가 수행한다.
 * 이로써 WorkflowPostAction 이 인프라(DB / 메시지 큐)에 직접 의존하지 않는다.
 *
 * @param fieldChanges 이슈에 적용할 필드 변경 목록. 변경이 없으면 빈 리스트.
 * @param emitEvents 발행을 예약한 도메인 이벤트 목록. 이벤트가 없으면 빈 리스트.
 */
data class PostActionPlan(
    val fieldChanges: List<FieldChange>,
    val emitEvents: List<DomainEvent>,
)
