// 워크로그 추가 요청 DTO — POST /api/v1/issues/{key}/worklogs (FR-TT-01)

package com.bts.issue.worklog.web.dto

import java.time.Instant

/**
 * 워크로그 추가 요청 DTO.
 *
 * POST /api/v1/issues/{key}/worklogs 요청 본문.
 *
 * @property timeSpentSeconds        작업 소요 시간 (초). 필수. 1 이상.
 * @property startedAt               작업 시작 시각 (ISO-8601). 필수.
 * @property comment                 선택적 코멘트.
 * @property newRemainingEstimateSeconds 잔여 추정 시간 직접 지정 (초). null 이면 자동 차감. 0 이상.
 */
data class AddWorklogRequest(
    val timeSpentSeconds: Int?,
    val startedAt: Instant?,
    val comment: String?,
    val newRemainingEstimateSeconds: Int?,
)
