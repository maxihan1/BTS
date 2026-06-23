// 이슈 rank 변경 요청 DTO — PATCH /api/v1/issues/{key}/rank (FR-BL-01 Task 5)

package com.bts.issue.adapter.inbound.rest.dto

/**
 * 이슈 rank 변경 요청 DTO.
 *
 * 두 이웃 이슈 키를 받아 대상 이슈를 그 사이에 배치한다.
 * 서버가 이웃 rank 를 조회하고 between 을 계산한다(클라이언트가 rank 미계산).
 *
 * 제약.
 * - previousIssueKey 와 nextIssueKey 둘 다 null 이면 400 (BacklogRankService 검증).
 * - null 허용: null 이면 해당 방향 경계 없음(맨 앞/맨 뒤로 이동).
 *
 * @property previousIssueKey 앞에 위치할 이슈 키. null 이면 맨 앞으로 이동.
 * @property nextIssueKey 뒤에 위치할 이슈 키. null 이면 맨 뒤로 이동.
 */
data class RerankIssueRequest(
    val previousIssueKey: String? = null,
    val nextIssueKey: String? = null,
)
