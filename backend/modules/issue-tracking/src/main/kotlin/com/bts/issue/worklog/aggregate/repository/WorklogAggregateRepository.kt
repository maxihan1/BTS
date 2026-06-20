// 워크로그 집계 리포지토리 — 이슈/사용자/기간 차원 SQL 집계 (FR-TT-02)

package com.bts.issue.worklog.aggregate.repository

import com.bts.issue.worklog.aggregate.domain.AggregateGranularity
import com.bts.issue.worklog.aggregate.domain.WorklogAggregateDimension
import com.bts.issue.worklog.aggregate.domain.WorklogAggregateRow
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * 워크로그 집계 리포지토리.
 *
 * `worklogs JOIN issues JOIN projects` 경로로 SQL SUM/COUNT 를 수행한다.
 * 차원(issue/user/period)별 GROUP BY 와 선택적 날짜 필터를 지원한다.
 *
 * ## 중요 제약 (FR-TT-02 plan B2)
 * - `date_trunc` 첫 인자는 bind 파라미터 불가 — [AggregateGranularity.sqlLiteral] 을
 *   `DSL.inline(...)` 으로 삽입한다.
 * - [WorklogAggregateRow.timeSpentSeconds] 는 `SUM(time_spent_seconds)` 의 nullable 결과를
 *   `?.toLong() ?: 0L` 로 받는다. `!!` 사용 금지.
 *
 * @param dsl jOOQ DSLContext.
 */
@Repository
class WorklogAggregateRepository(
    private val dsl: DSLContext,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 프로젝트 키와 차원을 기준으로 워크로그를 집계한다.
     *
     * @param projectKey 집계 대상 프로젝트 키 (예: "TPRJ").
     * @param dimension  집계 차원 ([WorklogAggregateDimension.ISSUE] / [WorklogAggregateDimension.USER] /
     *                   [WorklogAggregateDimension.PERIOD]).
     * @param granularity PERIOD 차원일 때 필요한 버킷 단위 (DAY/WEEK/MONTH). 나머지 차원에서는 무시.
     * @param from       시작 시각 (포함, null 이면 필터 없음).
     * @param to         종료 시각 — 당일 포함 시맨틱, 구현은 `to+1일 00:00 UTC exclusive` (null 이면 필터 없음).
     * @return 집계 행 목록. 워크로그 없으면 빈 리스트.
     */
    @Transactional(readOnly = true)
    fun aggregate(
        projectKey: String,
        dimension: WorklogAggregateDimension,
        granularity: AggregateGranularity?,
        from: Instant?,
        to: Instant?,
    ): List<WorklogAggregateRow> {
        log.debug(
            "aggregate projectKey={} dimension={} granularity={} from={} to={}",
            projectKey,
            dimension,
            granularity,
            from,
            to,
        )
        TODO("GREEN 단계에서 구현 예정")
    }
}
