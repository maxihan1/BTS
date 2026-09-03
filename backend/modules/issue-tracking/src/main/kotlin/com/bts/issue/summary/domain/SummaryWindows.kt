// 프로젝트 요약 화면의 집계 창 경계를 UTC 자정 기준으로 계산하는 순수 값 객체

package com.bts.issue.summary.domain

import java.time.Clock
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * 프로젝트 요약 집계에 쓰이는 창 경계 모음.
 *
 * ## 왜 UTC 로 자르나
 * 호스트 타임존(KST)으로 자르면 날짜 버킷이 9시간 밀린다. CFD([com.bts.issue.cfd])와
 * Cycle/Lead Time 이 이미 UTC 기준이므로 요약도 같은 기준을 쓴다 — 화면끼리 숫자가
 * 어긋나지 않는 것이 사용자에게 더 중요하다.
 *
 * ## 창 배치 (오늘 = D)
 * ```
 *  D-13         D-7          D-6           D          D+1        D+6
 *   │            │            │            │           │          │
 *   ├─ previous ─┤            │            │           │          │
 *   │  (7일)     ├──────── recent (7일) ───┼───────────┤          │
 *   ├──────── historySince (14일) ─────────────────────┤          │
 *   │                                      ├─── due (7일, 날짜) ──┤
 * ```
 * - [recentFrom]~[recentTo] — 카드 「최근 7일」. from inclusive, to **exclusive**.
 * - [previousFrom]~[previousTo] — 카드 델타의 비교 대상. [previousTo] == [recentFrom] 로 인접한다.
 * - [historySince] — 상태 이력을 **한 번만** 읽어 완료(7일)·직전 7일·상태 개요 DONE 특례(2주)를
 *   모두 덮는 하한. [previousFrom] 과 같은 시각이다.
 * - [dueFrom]~[dueTo] — 카드 「향후 7일 마감」. `issues.due_date` 가 `DATE` 라 날짜로 비교한다.
 *
 * @property today UTC 기준 오늘 날짜.
 * @property recentFrom 최근 7일 창 시작(inclusive).
 * @property recentTo 최근 7일 창 끝(exclusive) — 내일 자정.
 * @property previousFrom 직전 7일 창 시작(inclusive).
 * @property previousTo 직전 7일 창 끝(exclusive) — [recentFrom] 과 같다.
 * @property dueFrom 마감 예정 하한 날짜(inclusive) — 오늘.
 * @property dueTo 마감 예정 상한 날짜(inclusive) — 오늘 포함 7일째.
 */
data class SummaryWindows(
    val today: LocalDate,
    val recentFrom: OffsetDateTime,
    val recentTo: OffsetDateTime,
    val previousFrom: OffsetDateTime,
    val previousTo: OffsetDateTime,
    val dueFrom: LocalDate,
    val dueTo: LocalDate,
) {
    /**
     * 상태 전환 이력을 조회할 하한 시각.
     *
     * [previousFrom] 과 같다 — 직전 7일 창의 시작이 곧 2주 창의 시작이기 때문이다.
     * 이력을 이 한 시각으로 한 번만 읽으면 카드 완료(최근 7일)·완료 델타(직전 7일)·
     * 상태 개요 DONE 특례(최근 2주)가 모두 채워진다.
     */
    val historySince: OffsetDateTime get() = previousFrom

    companion object {
        /** 카드 「최근 7일」·「향후 7일」 창 길이(일, 오늘 포함). */
        const val RECENT_WINDOW_DAYS = 7L

        /**
         * 상태 개요의 DONE 버킷에만 적용되는 창 길이(일).
         *
         * Jira 클라우드 요약 화면 원문 — "Only items that have been completed in the last
         * two weeks will appear in Done". 우선순위·유형·담당자 분포에는 적용되지 **않는다**.
         */
        const val DONE_WINDOW_DAYS = 14L

        /**
         * [clock] 이 가리키는 순간의 UTC 날짜를 기준으로 모든 창을 계산한다.
         *
         * [clock] 의 존(zone)은 무시하고 항상 UTC 로 해석한다 — KST 호스트에서도 같은 결과가 나온다.
         *
         * @param clock 기준 시각을 제공하는 시계. 테스트는 [Clock.fixed] 를 주입한다.
         * @return 계산된 창 경계 모음.
         */
        fun of(clock: Clock): SummaryWindows {
            val today = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
            val recentTo = utcMidnight(today.plusDays(1))
            val recentFrom = utcMidnight(today.minusDays(RECENT_WINDOW_DAYS - 1))
            val previousFrom = utcMidnight(today.minusDays(DONE_WINDOW_DAYS - 1))
            return SummaryWindows(
                today = today,
                recentFrom = recentFrom,
                recentTo = recentTo,
                previousFrom = previousFrom,
                previousTo = recentFrom,
                dueFrom = today,
                dueTo = today.plusDays(RECENT_WINDOW_DAYS - 1),
            )
        }

        private fun utcMidnight(date: LocalDate): OffsetDateTime = date.atStartOfDay().atOffset(ZoneOffset.UTC)
    }
}
