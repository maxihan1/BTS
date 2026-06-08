// PATCH /api/v1/projects/{projectIdOrKey}/custom-fields/{fieldId} 요청 바디 DTO (FR-IS-10 Task 8)

package com.bts.issue.customfield.web.dto

import com.bts.issue.customfield.domain.FieldType
import jakarta.validation.Valid
import jakarta.validation.constraints.Size

/**
 * 커스텀 필드 정의 수정 REST 요청 바디.
 *
 * [fieldType] 과 [key] 는 생성 후 불변이다.
 * 전달 시 기존 값과 동일하면 허용, 다르면 서비스 레이어에서 422 로 거부된다.
 *
 * **null sentinel 정책** — null/생략 = 무변경.
 *
 * @property name 새 이름. null 이면 기존 유지.
 * @property description 새 설명. null 이면 기존 유지.
 * @property fieldType 필드 타입. 기존 값과 달라지면 서비스에서 예외.
 * @property key 필드 키. 기존 값과 달라지면 서비스에서 예외.
 * @property required 필수 여부. null 이면 기존 유지.
 * @property displayOrder 표시 순서. null 이면 기존 유지.
 * @property options 선택지 목록. null 이면 기존 유지. 전달 시 전체 교체.
 */
data class UpdateCustomFieldRequest(
    @field:Size(max = 255, message = "name은 255자 이하이어야 합니다.")
    val name: String? = null,
    @field:Size(max = 1000, message = "description은 1000자 이하이어야 합니다.")
    val description: String? = null,
    val fieldType: FieldType? = null,
    @field:Size(max = 64, message = "key는 64자 이하이어야 합니다.")
    val key: String? = null,
    val required: Boolean? = null,
    val displayOrder: Int? = null,
    @field:Valid
    val options: List<CreateCustomFieldRequest.OptionRequest>? = null,
)
