// POST /api/v1/projects/{projectIdOrKey}/issue-templates 요청 바디 DTO — Jakarta Validation (FR-TM-01 Task 6)

package com.bts.issue.template.web.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Positive
import jakarta.validation.constraints.Size

/**
 * 이슈 템플릿 생성 REST 요청 바디.
 *
 * Jakarta Bean Validation 으로 입력값을 1차 검증한다.
 * 불변식(name 공백, content 공백)은 도메인 [com.bts.issue.template.domain.IssueTemplate.create] 에서
 * 2차 검증한다.
 *
 * @property issueTypeId 연결할 이슈 타입 BIGINT. 양수 필수.
 * @property name 템플릿 이름. 비어 있을 수 없으며, 최대 100자.
 * @property content 이슈 본문 내용(Markdown). 비어 있을 수 없음.
 */
data class CreateIssueTemplateRequest(
    @field:NotNull(message = "issueTypeId 는 필수입니다.")
    @field:Positive(message = "issueTypeId 는 양수이어야 합니다.")
    val issueTypeId: Long,
    @field:NotBlank(message = "name 은 비어 있을 수 없습니다.")
    @field:Size(max = 100, message = "name 은 100자 이하이어야 합니다.")
    val name: String,
    @field:NotBlank(message = "content 는 비어 있을 수 없습니다.")
    val content: String,
)
