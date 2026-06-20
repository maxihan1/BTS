// 워크로그 수정 요청 DTO — PATCH /api/v1/issues/{key}/worklogs/{worklogId} (FR-TT-01)

package com.bts.issue.worklog.web.dto

import java.time.Instant

/**
 * 워크로그 수정 요청 DTO.
 *
 * PATCH /api/v1/issues/{key}/worklogs/{worklogId} 요청 본문.
 * 모든 필드는 선택적이다. null 이면 기존 값을 유지한다.
 *
 * @property timeSpentSeconds 새 소요 시간 (초). null 이면 기존 값 유지. 1 이상.
 * @property startedAt        새 작업 시작 시각. null 이면 기존 값 유지.
 * @property comment          새 코멘트. null 이면 기존 값 유지.
 */
data class UpdateWorklogRequest(
    val timeSpentSeconds: Int?,
    val startedAt: Instant?,
    val comment: String?,
)
