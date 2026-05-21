// 전이 응답 REST DTO — TransitionPlan → REST 응답 변환

package com.bts.workflow.web.dto

import com.bts.workflow.domain.dto.TransitionPlan

/**
 * REST 전이 응답 DTO.
 *
 * 서비스 레이어가 반환한 [TransitionPlan] 을 [TransitionPlan.toDto] extension 으로 변환하여
 * 컨트롤러가 클라이언트에 반환하는 형태.
 *
 * @property toStateKey 전이 후 도달하는 상태의 키.
 * @property fieldChanges 이슈에 적용된 필드 변경 목록.
 * @property events 발행이 예약된 도메인 이벤트 목록.
 */
data class TransitionResponseDto(
    val toStateKey: String,
    val fieldChanges: List<FieldChangeDto>,
    val events: List<DomainEventDto>,
)

/**
 * 단일 필드 변경 REST 응답 DTO.
 *
 * @property field 변경된 필드 이름. 예: "status", "assignee".
 * @property oldValue 변경 이전 값. null 허용.
 * @property newValue 변경 이후 값. null 허용.
 */
data class FieldChangeDto(
    val field: String,
    val oldValue: Any?,
    val newValue: Any?,
)

/**
 * 도메인 이벤트 발행 예약 REST 응답 DTO.
 *
 * @property type 이벤트 유형 식별자. 예: "ISSUE_TRANSITIONED".
 * @property payload 이벤트 페이로드. jsonb 호환 Map.
 */
data class DomainEventDto(
    val type: String,
    val payload: Map<String, Any?>,
)

/**
 * [TransitionPlan] 도메인 DTO 를 [TransitionResponseDto] REST 응답 형태로 변환한다.
 */
fun TransitionPlan.toDto(): TransitionResponseDto =
    TransitionResponseDto(
        toStateKey = toStateKey,
        fieldChanges =
            fieldChanges.map { fc ->
                FieldChangeDto(
                    field = fc.field,
                    oldValue = fc.oldValue,
                    newValue = fc.newValue,
                )
            },
        events =
            emitEvents.map { event ->
                DomainEventDto(
                    type = event.type,
                    payload = event.payload,
                )
            },
    )
