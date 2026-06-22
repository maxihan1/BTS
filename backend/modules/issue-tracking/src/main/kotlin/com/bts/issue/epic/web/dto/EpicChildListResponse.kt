// 에픽 자식 이슈 목록 응답 DTO

package com.bts.issue.epic.web.dto

/**
 * 에픽 자식 이슈 목록 응답 DTO (FR-EP-01 Task 6).
 *
 * GET `/api/v1/issues/{epicKey}/epic-children` 응답.
 *
 * @property children 에픽에 속한 활성 자식 이슈 요약 목록. created_at 오름차순 정렬.
 */
data class EpicChildListResponse(
    val children: List<EpicChildSummaryResponse>,
)
