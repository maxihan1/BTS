// POST /api/v1/issue-types 요청 바디 DTO — Jakarta Validation 으로 입력값 검증

package com.bts.issue.type.web.dto

import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.NotBlank

/**
 * 이슈 타입 생성 REST 요청 바디.
 *
 * Jakarta Bean Validation 으로 입력값을 검증한다.
 * [com.bts.issue.type.application.CreateIssueTypeRequest] (Application DTO) 로 변환하여 서비스에 위임한다.
 *
 * @property key URL-safe 소문자 슬러그. 공백 불가. 형식 위반 시 서비스 계층에서 [com.bts.issue.type.domain.IssueTypeKeyInvalidException].
 * @property name 표시 이름. 공백 불가.
 * @property description 선택적 설명. null 허용.
 * @property iconName 아이콘 식별자. null 허용.
 * @property hierarchyLevel 계층 깊이. {-1, 0, 1} 허용. 기본값 0.
 */
data class CreateIssueTypeRequest(
    @field:NotBlank(message = "key는 비어 있을 수 없습니다.")
    val key: String,
    @field:NotBlank(message = "name은 비어 있을 수 없습니다.")
    val name: String,
    val description: String? = null,
    val iconName: String? = null,
    @field:Min(-1, message = "hierarchyLevel은 -1 이상이어야 합니다.")
    @field:Max(1, message = "hierarchyLevel은 1 이하이어야 합니다.")
    val hierarchyLevel: Int = 0,
) {
    /**
     * Web DTO 를 Application DTO 로 변환한다.
     *
     * @return [com.bts.issue.type.application.CreateIssueTypeRequest]
     */
    fun toAppRequest(): com.bts.issue.type.application.CreateIssueTypeRequest =
        com.bts.issue.type.application.CreateIssueTypeRequest(
            key = key,
            name = name,
            description = description,
            iconName = iconName,
            hierarchyLevel = hierarchyLevel,
        )
}
