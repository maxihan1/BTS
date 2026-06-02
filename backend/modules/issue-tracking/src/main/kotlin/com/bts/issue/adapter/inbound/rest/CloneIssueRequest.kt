// POST /api/v1/issues/{key}/clone 요청 바디 DTO — Jakarta Validation 으로 입력값 검증 (FR-IS-06)

package com.bts.issue.adapter.inbound.rest

import jakarta.validation.constraints.Size

/**
 * 이슈 클론 REST 요청 바디.
 *
 * 모든 필드가 선택적이며, body 자체를 생략할 수도 있다 (컨트롤러가 기본값 적용).
 *
 * @property includeAssignee true(기본) 면 원본 담당자를 클론본에 복사. false 면 미할당으로 클론.
 * @property summaryOverride 클론본 제목 덮어쓰기. null/공백이면 원본 summary 사용. 최대 255자.
 */
data class CloneIssueRequest(
    val includeAssignee: Boolean = true,
    @field:Size(max = 255, message = "summaryOverride는 255자 이하여야 합니다.")
    val summaryOverride: String? = null,
)
