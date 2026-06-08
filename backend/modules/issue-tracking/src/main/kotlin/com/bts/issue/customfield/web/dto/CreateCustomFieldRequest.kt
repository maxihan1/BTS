// POST /api/v1/projects/{projectIdOrKey}/custom-fields 요청 바디 DTO — Jakarta Validation (FR-IS-10 Task 8)

package com.bts.issue.customfield.web.dto

import com.bts.issue.customfield.domain.FieldType
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size

/**
 * 커스텀 필드 정의 생성 REST 요청 바디.
 *
 * Jakarta Bean Validation 으로 입력값을 검증한다.
 * 선택형 타입(SINGLE_SELECT/MULTI_SELECT/RADIO)의 경우 [options] 에 최소 1건이 있어야 하며,
 * 이는 도메인 [com.bts.issue.customfield.domain.CustomFieldDefinition.create] 에서 검증된다.
 *
 * @property key 필드 식별자. URL-safe 소문자. JSONB 키로 사용. 필수.
 * @property name 사용자에게 노출되는 필드 이름. 필수, 공백 불가.
 * @property description 선택적 설명. null 허용.
 * @property fieldType 필드 데이터 타입. 생성 후 불변.
 * @property required 필수 여부. 기본값 false.
 * @property displayOrder 표시 순서. 기본값 0.
 * @property options 선택지 목록. 선택형 타입이면 반드시 1건 이상.
 */
data class CreateCustomFieldRequest(
    @field:NotBlank(message = "key는 비어 있을 수 없습니다.")
    @field:Size(max = 64, message = "key는 64자 이하이어야 합니다.")
    val key: String,
    @field:NotBlank(message = "name은 비어 있을 수 없습니다.")
    @field:Size(max = 255, message = "name은 255자 이하이어야 합니다.")
    val name: String,
    @field:Size(max = 1000, message = "description은 1000자 이하이어야 합니다.")
    val description: String? = null,
    @field:NotNull(message = "fieldType은 필수입니다.")
    val fieldType: FieldType,
    val required: Boolean = false,
    val displayOrder: Int = 0,
    @field:Valid
    val options: List<OptionRequest> = emptyList(),
) {
    /**
     * 선택지 요청 DTO.
     *
     * @property value 저장값(JSONB 키). 필수.
     * @property label 표시 이름. 필수.
     * @property displayOrder 정렬 순서. 기본값 0.
     */
    data class OptionRequest(
        @field:NotBlank(message = "option.value는 비어 있을 수 없습니다.")
        val value: String,
        @field:NotBlank(message = "option.label은 비어 있을 수 없습니다.")
        val label: String,
        val displayOrder: Int = 0,
    )
}
