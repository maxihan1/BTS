// PATCH /api/v1/projects/{projectIdOrKey}/issue-templates/{templateId} 요청 바디 DTO (FR-TM-01 Task 6)

package com.bts.issue.template.web.dto

import jakarta.validation.constraints.Size

/**
 * 이슈 템플릿 수정 REST 요청 바디.
 *
 * **null sentinel 정책** — null/생략 = 무변경.
 * 수정 가능 필드: [name], [content]. issueTypeId 는 생성 후 불변.
 *
 * @property name 새 템플릿 이름. null 이면 기존 유지. 전달 시 비어 있을 수 없음.
 * @property content 새 이슈 본문 내용. null 이면 기존 유지.
 */
data class UpdateIssueTemplateRequest(
    @field:Size(max = 100, message = "name 은 100자 이하이어야 합니다.")
    val name: String? = null,
    val content: String? = null,
)
