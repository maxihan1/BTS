// Cycle/Lead Time 분포의 요약 통계(nearest-rank 백분위) read-model VO
package com.bts.issue.cycletime.domain

/**
 * 초 단위 소요 시간 분포의 요약 통계.
 *
 * 영속되지 않는 순수 read-model VO다. [of]로만 생성한다.
 *
 * @property count 표본 개수.
 * @property min 최솟값(초). [count]가 0이면 `null`.
 * @property max 최댓값(초). [count]가 0이면 `null`.
 * @property avg 평균값(초, 반올림). [count]가 0이면 `null`.
 * @property p25 25번째 백분위수(초). [count]가 0이면 `null`.
 * @property p50 50번째 백분위수(초, 중앙값). [count]가 0이면 `null`.
 * @property p75 75번째 백분위수(초). [count]가 0이면 `null`.
 * @property p90 90번째 백분위수(초). [count]가 0이면 `null`.
 */
data class CycleTimeStats(
    val count: Int,
    val min: Long?,
    val max: Long?,
    val avg: Long?,
    val p25: Long?,
    val p50: Long?,
    val p75: Long?,
    val p90: Long?,
) {
    companion object {
        /**
         * [seconds] 목록으로부터 요약 통계를 계산한다.
         *
         * @param seconds 초 단위 값 목록. 정렬 여부와 무관하게 내부에서 오름차순 정렬 후 계산한다.
         * @return [seconds]가 비어 있으면 count=0에 나머지 필드는 전부 `null`. 아니면 계산된 통계.
         */
        fun of(seconds: List<Long>): CycleTimeStats {
            if (seconds.isEmpty()) {
                return CycleTimeStats(0, null, null, null, null, null, null, null)
            }

            val sorted = seconds.sorted()
            return CycleTimeStats(
                count = sorted.size,
                min = sorted.first(),
                max = sorted.last(),
                avg = Math.round(sorted.sum().toDouble() / sorted.size),
                p25 = percentile(sorted, 25.0),
                p50 = percentile(sorted, 50.0),
                p75 = percentile(sorted, 75.0),
                p90 = percentile(sorted, 90.0),
            )
        }

        private fun percentile(
            sorted: List<Long>,
            p: Double,
        ): Long {
            val n = sorted.size
            val idx = (Math.ceil(p / 100.0 * n) - 1).toInt().coerceIn(0, n - 1)
            return sorted[idx]
        }
    }
}
