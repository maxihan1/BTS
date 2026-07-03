// Cycle/Lead Time 지표 하나(요약 통계 + 개별 표본) read-model VO

package com.bts.issue.cycletime.domain

/**
 * Cycle Time 또는 Lead Time 지표 하나 — 요약 통계와 개별 표본을 함께 담는다.
 *
 * @property stats [samples]의 [CycleTimeSample.seconds] 목록으로 계산된 요약 통계.
 * @property samples 소요 시간(초) 오름차순으로 정렬된 개별 이슈 표본 목록.
 */
data class CycleTimeMetric(
    val stats: CycleTimeStats,
    val samples: List<CycleTimeSample>,
)
