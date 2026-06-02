// PATCH /api/v1/projects/{projectIdOrKey}/components/{id} 요청 바디 DTO — name/description 수정

package com.bts.issue.component.web.dto

import jakarta.validation.constraints.Size

/**
 * 컴포넌트 수정 REST 요청 바디.
 *
 * name / description 만 변경 가능하다. 리드 변경은 [ChangeComponentLeadRequest] 전용 엔드포인트를 사용한다.
 *
 * **문자열 sentinel 정책.** null / 생략 = 무변경. 빈 문자열은 @NotBlank 위반이 아니라
 * 도메인 rename 검증에서 거부된다 (500 으로 노출되지 않도록 도메인 불변식 설명 참조).
 *
 * @property name 새 이름. null 이면 기존 값 유지.
 * @property description 새 설명. null 이면 기존 값 유지.
 */
data class UpdateComponentRequest(
    @field:Size(max = 255, message = "name은 255자 이하이어야 합니다.")
    val name: String? = null,
    @field:Size(max = 1000, message = "description은 1000자 이하이어야 합니다.")
    val description: String? = null,
)
