// Cycle/Lead Time 단일 이슈 표본 — 소요 시간(초) read-model VO

package com.bts.issue.cycletime.domain

/**
 * Cycle Time 또는 Lead Time 분포의 단일 이슈 표본.
 *
 * @property issueKey 이슈 키.
 * @property seconds 소요 시간(초). 0 이상.
 */
data class CycleTimeSample(
    val issueKey: String,
    val seconds: Long,
)
