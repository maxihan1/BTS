// Cycle/Lead Time 조회 응답 DTO — GET /api/v1/projects/{projectKey}/cycle-time 응답 구조 (FR-RP-04 Task 6)

package com.bts.issue.cycletime.web.dto

import com.bts.issue.cycletime.domain.CycleTimeMetric
import com.bts.issue.cycletime.domain.CycleTimeResult
import com.bts.issue.cycletime.domain.CycleTimeSample
import java.time.LocalDate

/**
 * Cycle/Lead Time 지표의 단일 이슈 표본 응답 DTO.
 *
 * @property issueKey 이슈 키.
 * @property seconds 소요 시간(초).
 */
data class SampleResponse(
    val issueKey: String,
    val seconds: Long,
)

/**
 * Cycle/Lead Time 지표 하나(요약 통계 평탄화 + 개별 표본) 응답 DTO.
 *
 * [com.bts.issue.cycletime.domain.CycleTimeStats] 의 필드를 평탄화해 담는다. [count] 가 0 이면
 * min/max/avg/백분위 전 필드가 `null` 이다(표본 없음 — 값이 0 인 것과 구분).
 *
 * @property count 표본 개수.
 * @property min 최솟값(초).
 * @property max 최댓값(초).
 * @property avg 평균값(초).
 * @property p25 25번째 백분위수(초).
 * @property p50 50번째 백분위수(초, 중앙값).
 * @property p75 75번째 백분위수(초).
 * @property p90 90번째 백분위수(초).
 * @property samples 소요 시간(초) 오름차순 개별 이슈 표본 목록.
 */
data class MetricResponse(
    val count: Int,
    val min: Long?,
    val max: Long?,
    val avg: Long?,
    val p25: Long?,
    val p50: Long?,
    val p75: Long?,
    val p90: Long?,
    val samples: List<SampleResponse>,
)

/**
 * GET /api/v1/projects/{projectKey}/cycle-time 최종 응답 DTO.
 *
 * [from]/[to] 는 컨트롤러가 해석한 실제 조회 창을 그대로 echo 한다(요청에서 생략되었어도 실제
 * 적용된 값을 프론트가 알 수 있도록 한다).
 *
 * @property projectKey 대상 프로젝트 키.
 * @property from 실제 적용된 창 시작일(inclusive).
 * @property to 실제 적용된 창 종료일(inclusive).
 * @property cycleTime Cycle Time(첫 IN_PROGRESS → 마지막 DONE) 지표.
 * @property leadTime Lead Time(생성 → 마지막 DONE) 지표.
 */
data class CycleTimeResponse(
    val projectKey: String,
    val from: LocalDate,
    val to: LocalDate,
    val cycleTime: MetricResponse,
    val leadTime: MetricResponse,
) {
    companion object {
        /**
         * [CycleTimeResult] 도메인 read-model 을 응답 DTO 로 변환한다.
         *
         * @param result 서비스 계층의 Cycle/Lead Time 계산 결과.
         * @return 응답 DTO.
         */
        fun from(result: CycleTimeResult): CycleTimeResponse =
            CycleTimeResponse(
                projectKey = result.projectKey,
                from = result.from,
                to = result.to,
                cycleTime = toMetricResponse(result.cycleTime),
                leadTime = toMetricResponse(result.leadTime),
            )

        /**
         * [CycleTimeMetric] 을 [stats] 필드 평탄화 + [samples] 매핑으로 변환한다.
         */
        private fun toMetricResponse(metric: CycleTimeMetric): MetricResponse =
            MetricResponse(
                count = metric.stats.count,
                min = metric.stats.min,
                max = metric.stats.max,
                avg = metric.stats.avg,
                p25 = metric.stats.p25,
                p50 = metric.stats.p50,
                p75 = metric.stats.p75,
                p90 = metric.stats.p90,
                samples = metric.samples.map { toSampleResponse(it) },
            )

        /**
         * [CycleTimeSample] 을 응답 DTO 로 변환한다.
         */
        private fun toSampleResponse(sample: CycleTimeSample): SampleResponse =
            SampleResponse(issueKey = sample.issueKey, seconds = sample.seconds)
    }
}
