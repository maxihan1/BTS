// 이슈 필드 변경 명세 (old → new)

package com.bts.workflow.domain.dto

/**
 * 이슈의 단일 필드 변경 명세.
 *
 * [oldValue] → [newValue] 방향으로 변경이 발생했음을 기술한다.
 * 두 값 모두 `Any?` 타입이므로 문자열·숫자·null 등 jsonb 호환 값을 자유롭게 담을 수 있다.
 * 실제 영속화(저장)는 이 DTO 를 수신한 호출자 BC(바운디드 컨텍스트)가 수행한다.
 *
 * @param field 변경된 필드 이름. 예: "status", "assignee", "priority".
 * @param oldValue 변경 이전 값. null 허용 (기존 값이 없던 경우).
 * @param newValue 변경 이후 값. null 허용 (값 제거 시).
 */
data class FieldChange(
    val field: String,
    val oldValue: Any?,
    val newValue: Any?,
)
