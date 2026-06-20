// 워크로그 집계 응답 DTO — GET /api/v1/worklogs/aggregate 응답 구조 (FR-TT-02)

package com.bts.issue.worklog.aggregate.web.dto

import com.bts.issue.worklog.aggregate.domain.AggregateGranularity
import com.bts.issue.worklog.aggregate.domain.WorklogAggregateDimension
import com.bts.issue.worklog.aggregate.domain.WorklogAggregateResult
import com.fasterxml.jackson.annotation.JsonInclude
import java.time.LocalDate

/**
 * 워크로그 집계 단일 버킷 응답 DTO.
 *
 * @property key              차원 식별자 (이슈 키 / 사용자 UUID 문자열 / 날짜 문자열).
 * @property label            사람이 읽을 수 있는 레이블.
 * @property timeSpentSeconds 이 버킷의 총 소요 시간(초).
 * @property worklogCount     이 버킷의 워크로그 건수.
 */
data class WorklogAggregateBucketResponse(
    val key: String,
    val label: String,
    val timeSpentSeconds: Long,
    val worklogCount: Int,
)

/**
 * GET /api/v1/worklogs/aggregate 최종 응답 DTO.
 *
 * ### @JsonInclude(NON_NULL)
 * [granularity], [from], [to] 는 null 일 때 응답 JSON 에 포함하지 않는다.
 * - by≠period 일 때 [granularity] 는 null → 키 자체 제거.
 * - from/to 파라미터 미전달 시 null → 키 자체 제거.
 *
 * @property by                    집계 차원 (issue / user / period).
 * @property granularity           기간 버킷 단위. by=period 일 때만 포함.
 * @property from                  집계 시작일 (YYYY-MM-DD). 미전달 시 null.
 * @property to                    집계 종료일 (YYYY-MM-DD). 미전달 시 null.
 * @property buckets               집계 버킷 목록.
 * @property totalTimeSpentSeconds 전체 총 소요 시간(초).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class WorklogAggregateResponse(
    val by: String,
    val granularity: String?,
    val from: LocalDate?,
    val to: LocalDate?,
    val buckets: List<WorklogAggregateBucketResponse>,
    val totalTimeSpentSeconds: Long,
) {
    companion object {
        /**
         * [WorklogAggregateResult] 도메인 객체를 응답 DTO 로 변환한다.
         *
         * by≠period 이면 [granularity] 는 null(응답에서 키 제거).
         *
         * @param result      서비스 계층의 집계 결과.
         * @param dimension   집계 차원.
         * @param granularity by=period 일 때 버킷 단위.
         * @param from        시작일. 파라미터 미전달 시 null.
         * @param to          종료일. 파라미터 미전달 시 null.
         * @return 응답 DTO.
         */
        fun from(
            result: WorklogAggregateResult,
            dimension: WorklogAggregateDimension,
            granularity: AggregateGranularity?,
            from: LocalDate?,
            to: LocalDate?,
        ): WorklogAggregateResponse =
            WorklogAggregateResponse(
                by = dimension.name.lowercase(),
                granularity = if (dimension == WorklogAggregateDimension.PERIOD) granularity?.name?.lowercase() else null,
                from = from,
                to = to,
                buckets =
                    result.buckets.map { bucket ->
                        WorklogAggregateBucketResponse(
                            key = bucket.key,
                            label = bucket.label,
                            timeSpentSeconds = bucket.timeSpentSeconds,
                            worklogCount = bucket.worklogCount,
                        )
                    },
                totalTimeSpentSeconds = result.totalTimeSpentSeconds,
            )
    }
}
