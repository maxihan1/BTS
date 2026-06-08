// 커스텀 필드 정의 단건 응답 DTO — CustomFieldDefinition 도메인 객체를 REST 응답으로 매핑 (FR-IS-10 Task 8)

package com.bts.issue.customfield.web.dto

import com.bts.issue.customfield.domain.CustomFieldDefinition
import com.bts.issue.customfield.domain.CustomFieldOption
import com.bts.issue.customfield.domain.FieldType
import java.util.UUID

/**
 * 커스텀 필드 정의 단건 응답 DTO.
 *
 * [CustomFieldDefinition] 도메인 객체를 REST 응답용으로 매핑한다.
 * DB 저장 후에만 응답에 포함되므로 [id] 는 null 불가.
 *
 * @property id 필드 정의 UUID.
 * @property projectId 소속 프로젝트 UUID.
 * @property key 필드 식별자. URL-safe 소문자.
 * @property name 사용자에게 노출되는 필드 이름.
 * @property description 선택적 설명. null 허용.
 * @property fieldType 필드 데이터 타입.
 * @property required 이슈 저장 시 필수 여부.
 * @property displayOrder 표시 순서.
 * @property options 선택형 타입의 선택지 목록.
 */
data class CustomFieldResponse(
    val id: UUID,
    val projectId: UUID,
    val key: String,
    val name: String,
    val description: String?,
    val fieldType: FieldType,
    val required: Boolean,
    val displayOrder: Int,
    val options: List<OptionResponse>,
) {
    /**
     * 선택지 응답 DTO.
     *
     * @property value 저장값.
     * @property label 표시 이름.
     * @property displayOrder 정렬 순서.
     */
    data class OptionResponse(
        val value: String,
        val label: String,
        val displayOrder: Int,
    ) {
        companion object {
            /** [CustomFieldOption] 을 [OptionResponse] 로 변환한다. */
            fun from(option: CustomFieldOption): OptionResponse =
                OptionResponse(
                    value = option.value,
                    label = option.label,
                    displayOrder = option.displayOrder,
                )
        }
    }

    companion object {
        /**
         * [CustomFieldDefinition] 도메인 객체를 [CustomFieldResponse] 로 변환한다.
         *
         * @param definition 변환 대상 도메인 객체. [CustomFieldDefinition.id] 는 non-null 이어야 한다.
         * @throws IllegalArgumentException [definition.id] 가 null 인 경우.
         */
        fun from(definition: CustomFieldDefinition): CustomFieldResponse {
            val id =
                requireNotNull(definition.id) {
                    "CustomFieldDefinition.id must not be null for response mapping"
                }
            return CustomFieldResponse(
                id = id,
                projectId = definition.projectId,
                key = definition.key,
                name = definition.name,
                description = null,
                fieldType = definition.fieldType,
                required = definition.required,
                displayOrder = definition.displayOrder,
                options = definition.options.map(OptionResponse::from),
            )
        }
    }
}
