// PATCH /api/v1/projects/{projectIdOrKey}/versions/{id} 요청 바디 DTO — name/description 수정

package com.bts.issue.version.web.dto

import jakarta.validation.constraints.Size

/**
 * 버전 수정 REST 요청 바디.
 *
 * name / description 만 변경 가능하다. 날짜 변경은 [ChangeVersionDatesRequest] 전용 엔드포인트를 사용한다.
 *
 * **문자열 sentinel 정책.** null / 생략 = 무변경. non-null 값은 그대로 반영된다(빈 문자열이면 빈 설명으로 저장).
 *
 * @property name 새 이름. null 이면 기존 값 유지.
 * @property description 새 설명. null 이면 기존 값 유지. non-null 이면 해당 값으로 저장.
 */
data class UpdateVersionRequest(
    @field:Size(max = 255, message = "name은 255자 이하이어야 합니다.")
    val name: String? = null,
    @field:Size(max = 1000, message = "description은 1000자 이하이어야 합니다.")
    val description: String? = null,
)
