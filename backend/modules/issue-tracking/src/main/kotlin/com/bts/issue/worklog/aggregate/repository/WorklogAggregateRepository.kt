// 워크로그 집계 리포지토리 — 이슈/사용자/기간 차원 SQL 집계 (FR-TT-02)

package com.bts.issue.worklog.aggregate.repository

import com.bts.issue.jooq.tables.references.ISSUES
import com.bts.issue.jooq.tables.references.PROJECTS
import com.bts.issue.jooq.tables.references.WORKLOGS
import com.bts.issue.worklog.aggregate.domain.AggregateGranularity
import com.bts.issue.worklog.aggregate.domain.WorklogAggregateDimension
import com.bts.issue.worklog.aggregate.domain.WorklogAggregateRow
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.impl.DSL
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * 워크로그 집계 리포지토리.
 *
 * `worklogs JOIN issues JOIN projects` 경로로 SQL SUM/COUNT 를 수행한다.
 * 차원(issue/user/period)별 GROUP BY 와 선택적 날짜 필터를 지원한다.
 *
 * ## 중요 제약 (FR-TT-02 plan B2)
 * - `date_trunc` 첫 인자는 bind 파라미터 불가 — [AggregateGranularity.sqlLiteral] 을
 *   `DSL.inline(...)` 으로 삽입한다. `DSL.param` / `DSL.val` 경로 절대 금지.
 * - SUM(time_spent_seconds) 결과는 nullable 이므로 `?.toLong() ?: 0L` 로 받는다. `!!` 금지.
 * - worklogs → issues → projects 는 각각 N:1 이므로 cartesian product 발생하지 않는다.
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
     * @param projectKey  집계 대상 프로젝트 키 (예: "TPRJ").
     * @param dimension   집계 차원 (ISSUE / USER / PERIOD).
     * @param granularity PERIOD 차원일 때 필요한 버킷 단위. 나머지 차원에서는 무시.
     * @param from        시작 시각 (포함). null 이면 필터 없음.
     * @param to          종료 시각 — 당일 포함 시맨틱. 구현은 to+1일 00:00 UTC exclusive.
     *                    null 이면 필터 없음.
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

        val groupKeyExpr: Field<String?> = buildGroupKeyExpression(dimension, granularity)
        val sumField = DSL.sum(WORKLOGS.TIME_SPENT_SECONDS)
        val countField = DSL.count(WORKLOGS.ID)

        // to 당일 포함 — to+1일 00:00 UTC exclusive 로 변환
        val toExcl: OffsetDateTime? =
            to?.atOffset(ZoneOffset.UTC)?.truncatedTo(ChronoUnit.DAYS)?.plusDays(1)
        val fromOdt: OffsetDateTime? =
            from?.atOffset(ZoneOffset.UTC)

        val query =
            dsl
                .select(groupKeyExpr, sumField, countField)
                .from(WORKLOGS)
                .join(ISSUES).on(
                    WORKLOGS.ISSUE_ID.eq(ISSUES.ID)
                        .and(ISSUES.DELETED_AT.isNull),
                )
                .join(PROJECTS).on(
                    ISSUES.PROJECT_ID.eq(PROJECTS.ID)
                        .and(PROJECTS.DELETED_AT.isNull),
                )
                .where(PROJECTS.KEY.eq(projectKey))
                .and(WORKLOGS.DELETED_AT.isNull)
                .apply {
                    if (fromOdt != null) {
                        and(WORKLOGS.STARTED_AT.greaterOrEqual(fromOdt))
                    }
                    if (toExcl != null) {
                        and(WORKLOGS.STARTED_AT.lessThan(toExcl))
                    }
                }
                .groupBy(groupKeyExpr)

        return query
            .fetch { record ->
                val key = record.get(groupKeyExpr) ?: return@fetch null
                // C1: SUM nullable 처리 — BigDecimal 반환 가능, !! 금지
                val totalSeconds = record.get(sumField)?.toLong() ?: 0L
                val count = record.get(countField) ?: 0
                WorklogAggregateRow(
                    groupKey = key,
                    timeSpentSeconds = totalSeconds,
                    worklogCount = count,
                )
            }
            .filterNotNull()
    }

    // ── private helpers ────────────────────────────────────────────────────────

    /**
     * 차원과 granularity 에 따른 GROUP BY 표현식을 반환한다.
     *
     * @param dimension   집계 차원.
     * @param granularity PERIOD 차원일 때 버킷 단위 (null 이면 ISSUE/USER 차원).
     * @return jOOQ [Field] 표현식.
     *
     * ### B2 — date_trunc 리터럴 처리
     * PostgreSQL `date_trunc($1, col)` 의 첫 인자는 bind 파라미터를 받지 않는다.
     * [AggregateGranularity.sqlLiteral] 은 enum 화이트리스트이므로 [DSL.inline] 으로 안전하게 삽입한다.
     * `DSL.param` / `DSL.val` 경로 절대 금지.
     *
     * ### E6 — 세션 TZ 무관 UTC 버킷
     * PgJDBC 는 JVM 기본 타임존을 세션 TimeZone GUC 로 전파한다.
     * 한국 호스트(KST=UTC+9)에서 `date_trunc('day', started_at)` 를 그냥 쓰면
     * 경계 시각(예: UTC 23시 = KST 다음날 08시)이 다음 날 버킷으로 들어간다.
     * PERIOD 차원에서는 `started_at AT TIME ZONE 'UTC'` 로 UTC 벽시계로 먼저 변환한 뒤
     * date_trunc 를 적용해 세션 TZ 에 무관한 UTC 기준 버킷을 보장한다.
     */
    private fun buildGroupKeyExpression(
        dimension: WorklogAggregateDimension,
        granularity: AggregateGranularity?,
    ): Field<String?> =
        when (dimension) {
            WorklogAggregateDimension.ISSUE ->
                ISSUES.KEY.cast(String::class.java)

            WorklogAggregateDimension.USER ->
                DSL.field(
                    "CAST({0} AS TEXT)",
                    String::class.java,
                    WORKLOGS.AUTHOR_ID,
                )

            WorklogAggregateDimension.PERIOD -> {
                val gran =
                    requireNotNull(granularity) {
                        "PERIOD 차원에서는 granularity 가 필수입니다."
                    }
                // date_trunc 첫 인자는 DSL.inline 으로 삽입 (B2 — bind 파라미터 금지).
                // AT TIME ZONE 'UTC' 로 started_at 을 UTC 벽시계로 변환한 뒤 자른다.
                // 이렇게 해야 세션 TimeZone GUC 설정(PgJDBC 가 JVM TZ 로 전파)에 무관하게
                // 항상 UTC 기준 버킷을 생성한다 (spec E6 — "UTC 기준 해석" 보장).
                // 'UTC' 는 고정 문자열 리터럴이므로 SQL 인젝션 위험 없음.
                val startedAtUtc =
                    DSL.field(
                        "{0} AT TIME ZONE 'UTC'",
                        OffsetDateTime::class.java,
                        WORKLOGS.STARTED_AT,
                    )
                val truncated =
                    DSL.field(
                        "date_trunc({0}, {1})",
                        OffsetDateTime::class.java,
                        DSL.inline(gran.sqlLiteral),
                        startedAtUtc,
                    )
                DSL.field(
                    "to_char({0}, 'YYYY-MM-DD')",
                    String::class.java,
                    truncated,
                )
            }
        }
}
