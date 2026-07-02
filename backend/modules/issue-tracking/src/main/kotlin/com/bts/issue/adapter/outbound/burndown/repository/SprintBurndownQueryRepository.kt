// 스프린트 번다운 jOOQ 조회 리포지토리 — ISSUES/WORKLOGS 스칼라 집계 조회 (FR-RP-02 Task 6 — ArchUnit 룰2 준수 위한 jOOQ 추출)

package com.bts.issue.adapter.outbound.burndown.repository

import com.bts.issue.jooq.tables.references.ISSUES
import com.bts.issue.jooq.tables.references.WORKLOGS
import com.bts.shared.burndown.WorklogContribution
import org.jooq.DSLContext
import org.jooq.Field
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * TIMESTAMPTZ 컬럼을 UTC 벽시계 날짜로 변환하는 SQL 템플릿 (jOOQ `DSL.field` 바인딩용).
 *
 * `AT TIME ZONE 'UTC'` 는 세션 TimeZone GUC 값에 무관하게 항상 UTC 로 변환하므로,
 * 이후 `::date` 캐스팅 결과도 세션 TZ 와 무관하게 결정적이다 — [utcDateField] 참조.
 */
private const val UTC_DATE_SQL_TEMPLATE = "({0} AT TIME ZONE 'UTC')::date"

/**
 * [com.bts.issue.adapter.outbound.burndown.SprintBurndownLookupAdapter] 전용 jOOQ 조회 리포지토리 (FR-RP-02 Task 6).
 *
 * ArchUnit 룰 2([com.bts.issue.architecture.IssueBcArchTest.jooqGeneratedMustOnlyBeUsedInRepositoryLayer]) —
 * jOOQ 생성 코드(`com.bts.issue.jooq..`)는 `..repository..` 패키지에서만 접촉 가능해야 하므로,
 * 어댑터에 있던 두 jOOQ 쿼리(추정 시간 합계·UTC 날짜별 worklog 집계)를 이 클래스로 추출했다
 * (동작 변경 없음, 순수 이동).
 *
 * ### 쿼리 2개 이하 — N+1 없음 (NFR3)
 * 1. [sumOriginalEstimateSeconds] — `issues` 에서 `original_estimate_seconds` 합계 1쿼리.
 * 2. [aggregateWorklogsByUtcDate] — `worklogs JOIN issues` 로 UTC 날짜별 `time_spent_seconds` 합계 1쿼리.
 * 두 쿼리 모두 소프트 삭제(`deleted_at IS NULL`) 이슈/worklog 를 제외한다.
 *
 * @param dsl jOOQ DSLContext.
 */
@Repository
class SprintBurndownQueryRepository(
    private val dsl: DSLContext,
) {
    /** 미삭제 이슈들의 `original_estimate_seconds` 합계(초). NULL 추정치는 0 으로 간주해 합산한다. */
    @Transactional(readOnly = true)
    fun sumOriginalEstimateSeconds(issueKeys: Set<String>): Long {
        val sumField = DSL.sum(ISSUES.ORIGINAL_ESTIMATE_SECONDS)
        return dsl
            .select(sumField)
            .from(ISSUES)
            .where(ISSUES.KEY.`in`(issueKeys))
            .and(ISSUES.DELETED_AT.isNull)
            .fetchOne(sumField)
            ?.toLong() ?: 0L
    }

    /** 미삭제 이슈에 속한 미삭제 worklog 를 UTC 날짜별로 합산한다. 같은 날짜당 1개 항목만 존재한다(pre-aggregate). */
    @Transactional(readOnly = true)
    fun aggregateWorklogsByUtcDate(issueKeys: Set<String>): List<WorklogContribution> {
        val startedOnUtcDate = utcDateField(WORKLOGS.STARTED_AT)
        val sumField = DSL.sum(WORKLOGS.TIME_SPENT_SECONDS)

        return dsl
            .select(startedOnUtcDate, sumField)
            .from(WORKLOGS)
            .join(ISSUES).on(WORKLOGS.ISSUE_ID.eq(ISSUES.ID))
            .where(ISSUES.KEY.`in`(issueKeys))
            .and(ISSUES.DELETED_AT.isNull)
            .and(WORKLOGS.DELETED_AT.isNull)
            .groupBy(startedOnUtcDate)
            .fetch { record ->
                val date = record.get(startedOnUtcDate) ?: return@fetch null
                WorklogContribution(
                    startedOnUtcDate = date,
                    timeSpentSeconds = record.get(sumField)?.toLong() ?: 0L,
                )
            }
            .filterNotNull()
    }
}

/**
 * TIMESTAMPTZ 컬럼을 UTC 날짜([LocalDate])로 변환하는 jOOQ 표현식을 생성한다.
 *
 * [UTC_DATE_SQL_TEMPLATE] 을 사용한다 — `date_trunc` 만 단독으로 쓸 때 발생하는
 * 세션 TZ 의존 함정과 달리, `AT TIME ZONE 'UTC'` 를 먼저 적용하므로 결과가 세션 TZ 와 무관하게 결정적이다.
 *
 * @param column worklogs.started_at 등 TIMESTAMPTZ 컬럼.
 * @return UTC 날짜 [Field] 표현식.
 */
private fun utcDateField(column: Field<OffsetDateTime?>): Field<LocalDate?> =
    DSL.field(
        UTC_DATE_SQL_TEMPLATE,
        LocalDate::class.java,
        column,
    )
