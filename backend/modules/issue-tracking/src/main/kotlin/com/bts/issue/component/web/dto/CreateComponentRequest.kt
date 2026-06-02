// POST /api/v1/projects/{projectIdOrKey}/components 요청 바디 DTO — Jakarta Validation

package com.bts.issue.component.web.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.util.UUID

/**
 * 컴포넌트 생성 REST 요청 바디.
 *
 * Jakarta Bean Validation 으로 입력값을 검증한다.
 *
 * @property name 컴포넌트 이름. 필수, 공백 불가, 최대 255자.
 * @property description 선택적 설명. null 허용, 최대 1000자.
 * @property leadUserId 리드 사용자 UUID. null 이면 미지정.
 */
data class CreateComponentRequest(
    @field:NotBlank(message = "name은 비어 있을 수 없습니다.")
    @field:Size(max = 255, message = "name은 255자 이하이어야 합니다.")
    val name: String,
    @field:Size(max = 1000, message = "description은 1000자 이하이어야 합니다.")
    val description: String? = null,
    val leadUserId: UUID? = null,
)
