// POST /api/v1/projects/{projectIdOrKey}/versions 요청 바디 DTO — Jakarta Validation

package com.bts.issue.version.web.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import java.time.LocalDate

/**
 * 버전 생성 REST 요청 바디.
 *
 * Jakarta Bean Validation 으로 입력값을 검증한다.
 *
 * @property name 버전 이름. 필수, 공백 불가, 최대 255자.
 * @property description 선택적 설명. null 허용, 최대 1000자.
 * @property startDate 버전 시작일. null 이면 미지정.
 * @property releaseDate 버전 릴리스 예정일. null 이면 미지정.
 */
data class CreateVersionRequest(
    @field:NotBlank(message = "name은 비어 있을 수 없습니다.")
    @field:Size(max = 255, message = "name은 255자 이하이어야 합니다.")
    val name: String,
    @field:Size(max = 1000, message = "description은 1000자 이하이어야 합니다.")
    val description: String? = null,
    val startDate: LocalDate? = null,
    val releaseDate: LocalDate? = null,
)
