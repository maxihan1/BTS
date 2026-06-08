// 선택형 커스텀 필드의 개별 선택지를 나타내는 불변 값 객체
package com.bts.issue.customfield.domain

/**
 * 선택형 커스텀 필드([FieldType.isSelectType] = true)의 선택지.
 *
 * [CustomFieldDefinition] 에 종속되며, 정의가 소프트 삭제되면 DB 에서 CASCADE 삭제된다.
 * 모든 속성이 val 로 선언되어 불변이다.
 *
 * @property value DB 저장값 · JSONB 조회 키. URL-safe 소문자 권장(제약은 정의 key 와 달리 강제 안 함).
 * @property label 사용자에게 노출되는 표시 이름.
 * @property displayOrder 선택지 정렬 순서. 낮을수록 먼저 표시.
 */
data class CustomFieldOption(
    val value: String,
    val label: String,
    val displayOrder: Int,
)
