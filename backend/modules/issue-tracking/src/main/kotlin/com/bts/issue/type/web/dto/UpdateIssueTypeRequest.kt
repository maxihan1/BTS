// PATCH /api/v1/issue-types/{id} 요청 바디 DTO — Jakarta Validation 으로 입력값 검증

package com.bts.issue.type.web.dto

import jakarta.validation.constraints.NotBlank

/**
 * 이슈 타입 수정 REST 요청 바디.
 *
 * key / isStandard 는 불변이므로 포함하지 않는다.
 * [com.bts.issue.type.application.UpdateIssueTypeRequest] (Application DTO) 로 변환하여 서비스에 위임한다.
 *
 * @property name 새 표시 이름. 공백 불가.
 * @property description 새 설명. null 허용.
 * @property iconName 새 아이콘 식별자. null 허용.
 * @property hierarchyLevel 새 계층 깊이. {-1, 0, 1} 허용.
 */
data class UpdateIssueTypeRequest(
    @field:NotBlank(message = "name은 비어 있을 수 없습니다.")
    val name: String,
    val description: String? = null,
    val iconName: String? = null,
    val hierarchyLevel: Int = 0,
) {
    /**
     * Web DTO 를 Application DTO 로 변환한다.
     *
     * @return [com.bts.issue.type.application.UpdateIssueTypeRequest]
     */
    fun toAppRequest(): com.bts.issue.type.application.UpdateIssueTypeRequest =
        com.bts.issue.type.application.UpdateIssueTypeRequest(
            name = name,
            description = description,
            iconName = iconName,
            hierarchyLevel = hierarchyLevel,
        )
}
