// 워크로그 집계 도메인 타입 — 이슈/사용자/기간 차원 집계 VO 및 열거형 (FR-TT-02)

package com.bts.issue.worklog.aggregate.domain

/**
 * 워크로그 집계 차원.
 *
 * - [ISSUE]  — 이슈 키 기준 집계.
 * - [USER]   — 작성자 UUID 기준 집계.
 * - [PERIOD] — 시간 버킷(day/week/month) 기준 집계. [AggregateGranularity] 와 함께 사용.
 */
enum class WorklogAggregateDimension {
    ISSUE,
    USER,
    PERIOD,
}

/**
 * 기간 버킷 단위.
 *
 * [sqlLiteral] 은 PostgreSQL `date_trunc` 에 전달될 리터럴 문자열이다.
 * 화이트리스트(enum 값)로만 삽입 가능하므로 SQL 인젝션 불가.
 * `DSL.inline(granularity.sqlLiteral)` 형태로만 사용하며 bind 파라미터 경로를 절대 사용하지 않는다.
 *
 * @property sqlLiteral PostgreSQL date_trunc 에 전달할 리터럴 (예: "day", "week", "month").
 */
enum class AggregateGranularity(val sqlLiteral: String) {
    DAY("day"),
    WEEK("week"),
    MONTH("month"),
}

/**
 * 집계 쿼리 결과 단일 행.
 *
 * @property groupKey        차원 식별자 — 이슈 키(ISSUE), author_id::text(USER), date_trunc 버킷 날짜 문자열(PERIOD).
 * @property timeSpentSeconds 해당 그룹의 총 소요 시간(초).
 * @property worklogCount    해당 그룹의 워크로그 건수.
 */
data class WorklogAggregateRow(
    val groupKey: String,
    val timeSpentSeconds: Long,
    val worklogCount: Int,
)

/**
 * 집계 결과 버킷 — 서비스/컨트롤러 레이어에서 사용하는 표현 타입.
 *
 * @property key              차원 식별자 (groupKey 와 동일).
 * @property label            사람이 읽을 수 있는 레이블 (예: 이슈 키, 날짜 문자열).
 * @property timeSpentSeconds 해당 버킷의 총 소요 시간(초).
 * @property worklogCount     해당 버킷의 워크로그 건수.
 */
data class WorklogAggregateBucket(
    val key: String,
    val label: String,
    val timeSpentSeconds: Long,
    val worklogCount: Int,
)

/**
 * 집계 최종 결과.
 *
 * @property buckets               버킷 목록 (차원별 그룹).
 * @property totalTimeSpentSeconds 전체 총 소요 시간(초).
 */
data class WorklogAggregateResult(
    val buckets: List<WorklogAggregateBucket>,
    val totalTimeSpentSeconds: Long,
)
