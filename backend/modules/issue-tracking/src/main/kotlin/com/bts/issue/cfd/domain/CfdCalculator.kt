// CFD 시계열을 상태 타임라인에서 계산하는 순수 도메인 계산기 — Clock/DB/포트 의존 0

package com.bts.issue.cfd.domain

import com.bts.issue.statushistory.StatusCategory
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * CFD(Cumulative Flow Diagram, 누적 흐름 다이어그램) 시계열을 계산하는 순수 함수.
 *
 * DB·Clock·Spring 의존이 전혀 없는 순수 도메인 계산기다. 이슈별 상태 타임라인([CfdIssueTimeline])
 * 목록을 받아 창(window) 내 각 날짜의 카테고리별(TODO/IN_PROGRESS/DONE) 누적 이슈 개수를 계산한다.
 *
 * ### 알고리즘 — 델타-누적 (복잡도: O(이슈 수 × 이슈당 segment 수 + 창 일수 × 3))
 * 1. 카테고리별 정수 델타 배열(길이 = 창 일수)을 준비한다.
 * 2. 이슈별로 `effectiveStart = max(createdDate, from)`을 구하고, `createdDate > to`이면 그 이슈는
 *    창 내에 존재하지 않으므로 건너뛴다.
 * 3. effectiveStart 시점의 카테고리(= `startDate ≤ effectiveStart`인 마지막 segment)를 찾아 해당
 *    카테고리 델타[effectiveStart 인덱스]에 +1 한다. effectiveStart **이전** segment들은 이 초기
 *    카테고리 계산에 이미 접혀 들어가므로 별도 처리하지 않는다(창 이전 전이 폴딩).
 * 4. effectiveStart 이후 segment(그 startDate가 `effectiveStart < startDate ≤ to`)마다 이전
 *    카테고리 델타를 -1, 새 카테고리 델타를 +1 한다.
 * 5. 모든 이슈 처리 후 각 카테고리 배열을 prefix-sum 하면 날짜별 누적 카운트가 된다.
 */
object CfdCalculator {
    /**
     * [from]~[to](inclusive) 각 캘린더 일자의 CFD 지점을 계산한다.
     *
     * @param from 창 시작일(inclusive).
     * @param to 창 종료일(inclusive). [from] 이상이어야 한다.
     * @param issues 이슈별 상태 타임라인 목록.
     * @return [from]~[to] 각 날짜 1개씩, 날짜 오름차순으로 정렬된 [CfdPoint] 목록.
     * @throws IllegalArgumentException [from]이 [to]보다 이후인 경우.
     */
    fun calculate(
        from: LocalDate,
        to: LocalDate,
        issues: List<CfdIssueTimeline>,
    ): List<CfdPoint> {
        require(!from.isAfter(to)) { "from ($from) must not be after to ($to)." }

        val windowDays = dayIndex(from, to) + 1
        val deltas: Map<StatusCategory, IntArray> = StatusCategory.entries.associateWith { IntArray(windowDays) }

        issues.forEach { issue -> applyIssueDeltas(issue, from, to, deltas) }
        deltas.values.forEach { accumulate(it) }

        return (0 until windowDays).map { i -> pointAt(from, i, deltas) }
    }

    /**
     * 단일 이슈의 상태 타임라인을 창 [from]~[to] 기준 [deltas]에 반영한다.
     *
     * [CfdIssueTimeline.createdDate]가 [to] 이후이면 그 이슈는 창 내 존재하지 않으므로 건너뛴다.
     */
    private fun applyIssueDeltas(
        issue: CfdIssueTimeline,
        from: LocalDate,
        to: LocalDate,
        deltas: Map<StatusCategory, IntArray>,
    ) {
        if (issue.createdDate.isAfter(to)) return

        val effectiveStart = maxOf(issue.createdDate, from)
        var previousCategory = categoryAt(issue, effectiveStart)
        addDelta(deltas, previousCategory, dayIndex(from, effectiveStart), amount = 1)

        issue.segments
            .filter { it.startDate.isAfter(effectiveStart) && !it.startDate.isAfter(to) }
            .forEach { segment ->
                val idx = dayIndex(from, segment.startDate)
                addDelta(deltas, previousCategory, idx, amount = -1)
                addDelta(deltas, segment.category, idx, amount = 1)
                previousCategory = segment.category
            }
    }

    /**
     * [issue]의 [asOf] 시점 카테고리를 구한다 — `startDate ≤ asOf`인 마지막 segment의 category.
     *
     * [asOf] 이전(또는 같은 날) segment들은 이 하나의 초기 카테고리로 접혀 들어간다(창 이전 전이 폴딩).
     * [CfdIssueTimeline.segments]는 `segments[0].startDate == createdDate`를 만족하므로
     * (호출자가 보장하는 불변식), [asOf]가 `createdDate` 이상이면 이 검색은 항상 결과를 찾는다.
     */
    private fun categoryAt(
        issue: CfdIssueTimeline,
        asOf: LocalDate,
    ): StatusCategory = issue.segments.last { !it.startDate.isAfter(asOf) }.category

    /** [category] 배열의 [index] 위치에 [amount]를 더한다(델타 인덱싱 헬퍼). */
    private fun addDelta(
        deltas: Map<StatusCategory, IntArray>,
        category: StatusCategory,
        index: Int,
        amount: Int,
    ) {
        deltas.getValue(category)[index] += amount
    }

    /** [array]를 제자리에서 prefix-sum 한다(델타 배열 → 날짜별 누적 카운트). */
    private fun accumulate(array: IntArray) {
        for (i in 1 until array.size) {
            array[i] += array[i - 1]
        }
    }

    /** [from] 기준 [date]의 배열 인덱스(0-based 일수 차이)를 계산한다. */
    private fun dayIndex(
        from: LocalDate,
        date: LocalDate,
    ): Int = ChronoUnit.DAYS.between(from, date).toInt()

    /** [deltas]의 [index] 위치 값들로 하루치 [CfdPoint]를 조립한다. */
    private fun pointAt(
        from: LocalDate,
        index: Int,
        deltas: Map<StatusCategory, IntArray>,
    ): CfdPoint =
        CfdPoint(
            date = from.plusDays(index.toLong()),
            todoCount = deltas.getValue(StatusCategory.TODO)[index],
            inProgressCount = deltas.getValue(StatusCategory.IN_PROGRESS)[index],
            doneCount = deltas.getValue(StatusCategory.DONE)[index],
        )
}
