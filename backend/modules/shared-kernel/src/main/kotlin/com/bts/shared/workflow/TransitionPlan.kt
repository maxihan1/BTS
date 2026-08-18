// 워크플로우 전환 결과 계획 DTO — 호출자 BC 가 적용 (영속화/이벤트 발행 책임)

package com.bts.shared.workflow

/**
 * 워크플로우 전환 결과 계획.
 *
 * project-workflow 가 전환 검증을 완료한 후 호출자 BC(바운디드 컨텍스트)에 반환하는 실행 계획이다.
 * 이 DTO 를 받은 호출자 BC 는 [fieldChanges] 를 이슈에 적용하고 [emitEvents] 를 outbox 에 INSERT
 * 해야 한다. project-workflow 는 직접 어떤 상태도 변경하지 않는다.
 *
 * @param toStateKey 전환 후 도달하는 상태의 키. 예: "IN_PROGRESS", "DONE".
 * @param fieldChanges 이슈에 적용해야 할 필드 변경 목록. 변경이 없으면 빈 리스트.
 * @param emitEvents 발행을 예약한 도메인 이벤트 목록. 이벤트가 없으면 빈 리스트.
 */
data class TransitionPlan(
    val toStateKey: String,
    val fieldChanges: List<FieldChange>,
    val emitEvents: List<DomainEvent>,
)
