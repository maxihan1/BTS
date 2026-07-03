// Cycle/Lead Time 분포의 요약 통계(nearest-rank 백분위) read-model VO
package com.bts.issue.cycletime.domain

/**
 * 초 단위 소요 시간 분포의 요약 통계.
 *
 * 영속되지 않는 순수 read-model VO다. [of]로만 생성한다.
 *
 * ### count=0(빈 표본) 정책
 * 표본이 하나도 없으면 [count]만 0이고 min/max/avg/백분위 전 필드가 `null`이다. 0을 반환하지
 * 않는 이유는 "값이 0"과 "표본이 없어 정의 불가"를 호출부가 구분할 수 있어야 하기 때문이다
 * (예: 차트에서 빈 구간을 0으로 그리면 실제 0초 완료를 의미하는 것처럼 오독된다).
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
        private const val PERCENTILE_25 = 25.0
        private const val PERCENTILE_50 = 50.0
        private const val PERCENTILE_75 = 75.0
        private const val PERCENTILE_90 = 90.0
        private const val PERCENTILE_100 = 100.0

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
                p25 = percentile(sorted, PERCENTILE_25),
                p50 = percentile(sorted, PERCENTILE_50),
                p75 = percentile(sorted, PERCENTILE_75),
                p90 = percentile(sorted, PERCENTILE_90),
            )
        }

        /**
         * 정렬된 [sorted] 목록에서 [p]번째 백분위수를 nearest-rank 방식으로 구한다.
         *
         * 공식. `idx = clamp(ceil(p / 100 * n) - 1, 0, n - 1)` (n = [sorted].size).
         *
         * 선형보간이 아닌 nearest-rank를 쓰는 이유는 결과가 항상 실제 표본 값 중 하나여서
         * (보간값처럼 존재하지 않는 소요 시간을 만들어내지 않고) 해석이 직관적이기 때문이다.
         * `- 1`은 순위(1-based)를 배열 인덱스(0-based)로 바꾸기 위함이고, `coerceIn`은 `p=100`
         * 근처에서 `ceil` 결과가 `n`을 넘는 경계 케이스를 마지막 인덱스로 안전하게 고정한다.
         *
         * @param sorted 오름차순 정렬된 값 목록. 비어 있지 않아야 한다(호출자 [of]가 보장).
         * @param p 0~100 사이의 백분위(예: 50.0 = 중앙값).
         */
        private fun percentile(
            sorted: List<Long>,
            p: Double,
        ): Long {
            val n = sorted.size
            val rank = Math.ceil(p / PERCENTILE_100 * n).toInt()
            val idx = (rank - 1).coerceIn(0, n - 1)
            return sorted[idx]
        }
    }
}
