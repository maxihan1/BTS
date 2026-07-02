// 스프린트 번다운/번업 시계열을 계산하는 순수 도메인 함수 — Clock/DB/포트 의존 0

package com.bts.agileplanning.domain.burndown

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 스프린트 번다운(Burndown) / 번업(Burnup) 시계열을 계산하는 순수 함수.
 *
 * 원시 타입만 입출력하며 Clock·DB·shared-kernel 포트를 참조하지 않는다.
 * "오늘" 날짜는 [calculate] 의 today 파라미터로 주입받는다(시각 의존 로직을 테스트 가능하게 만들기 위함 —
 * AuthController time-bomb 회귀 학습 반영).
 */
object BurndownCalculator {
    /** ideal 반올림(half-up) 계산의 반내림 보정 상수. */
    private const val HALF_ROUNDING_DIVISOR = 2L

    /**
     * [start] ~ [end] (inclusive) 각 캘린더 일자의 번다운/번업 지점을 계산한다.
     *
     * ### 알고리즘 (스펙 §계산 알고리즘)
     * - asOf = min(end, today). Actual/Completed 는 asOf 까지만 산출하고, 이후 미래 일자는 null.
     * - Ideal 은 start 에서 [scopeSeconds], end 에서 0 으로 선형 보간(반올림 half-up, d==start 는 정확히
     *   scopeSeconds, d==end 는 정확히 0 을 보장). end == start(1일 스프린트)면 0 나눗셈을 피해 단일
     *   지점(ideal = scopeSeconds)으로 처리한다.
     * - Scope 라인은 전 구간 [scopeSeconds] 로 평탄하다(스코프 변경 이력 미재구성, ADR D4).
     * - [worklogByUtcDate] 의 키가 [start] 이전인 항목은 start 버킷에 선합산한다(스프린트 이전 로그는
     *   이미 소진된 것으로 간주). [end] 이후 키는 계산 범위 밖이라 무시한다.
     * - remainingSeconds 는 음수가 되지 않도록 0 으로 클램프한다. completedSeconds 는 클램프하지 않아
     *   [scopeSeconds] 를 초과할 수 있다(로그가 추정치보다 많은 경우, 문서화된 한계).
     *
     * @param start 스프린트 시작일. [end] 이하여야 한다.
     * @param end 스프린트 종료일. [start] 이상이어야 한다.
     * @param scopeSeconds 총 스코프(초). 음수 불가(Σ original_estimate_seconds, NULL=0 합산 결과).
     * @param worklogByUtcDate UTC 날짜별 worklog 시간 합(초). [start] 이전 키는 이 함수 안에서
     *   start 버킷에 합산된다.
     * @param today "오늘" 날짜(UTC). asOf = min(end, today) 산출에 사용한다.
     * @return date 오름차순으로 정렬된 [BurndownPoint] 목록. [start]~[end] 각 일자 1개씩, 총 (일수) 개.
     * @throws IllegalArgumentException [start] 가 [end] 보다 이후이거나 [scopeSeconds] 가 음수인 경우.
     */
    fun calculate(
        start: LocalDate,
        end: LocalDate,
        scopeSeconds: Long,
        worklogByUtcDate: Map<LocalDate, Long>,
        today: LocalDate,
    ): List<BurndownPoint> {
        require(!start.isAfter(end)) { "start ($start) must not be after end ($end)." }
        require(scopeSeconds >= 0) { "scopeSeconds ($scopeSeconds) must not be negative." }

        val asOf = minOf(end, today)
        val totalDays = ChronoUnit.DAYS.between(start, end)
        val preStartSum = worklogByUtcDate.filterKeys { it.isBefore(start) }.values.sum()

        val points = mutableListOf<BurndownPoint>()
        var cumulative = 0L
        var day = start
        while (!day.isAfter(end)) {
            val ideal = computeIdealSeconds(scopeSeconds, day, end, totalDays)
            if (day.isAfter(asOf)) {
                points += BurndownPoint(day, null, ideal, null, scopeSeconds)
            } else {
                cumulative += dailyContribution(day, start, preStartSum, worklogByUtcDate)
                val remaining = (scopeSeconds - cumulative).coerceAtLeast(0L)
                points += BurndownPoint(day, remaining, ideal, cumulative, scopeSeconds)
            }
            day = day.plusDays(1)
        }
        return points
    }

    /**
     * [day] 의 worklog 기여량(초)을 반환한다.
     *
     * [day] 가 [start] 와 같으면 [start] 이전 일자의 누적([preStartSum])까지 합산한다(선반영).
     */
    private fun dailyContribution(
        day: LocalDate,
        start: LocalDate,
        preStartSum: Long,
        worklogByUtcDate: Map<LocalDate, Long>,
    ): Long {
        val own = worklogByUtcDate[day] ?: 0L
        return if (day == start) own + preStartSum else own
    }

    /**
     * Ideal 라인의 [day] 지점 값을 반올림(half-up)으로 계산한다.
     *
     * [totalDays] 가 0(1일 스프린트, end == start)이면 0 나눗셈을 피해 [scopeSeconds] 를 그대로 반환한다.
     * 그 외에는 [day]==[end] 에서 정확히 0, [day]==start 에서 정확히 [scopeSeconds] 가 나오도록
     * `(numerator + totalDays/2) / totalDays` 형태의 정수 반올림 공식을 사용한다.
     */
    private fun computeIdealSeconds(
        scopeSeconds: Long,
        day: LocalDate,
        end: LocalDate,
        totalDays: Long,
    ): Long {
        if (totalDays == 0L) return scopeSeconds
        val daysRemaining = ChronoUnit.DAYS.between(day, end)
        val numerator = scopeSeconds * daysRemaining
        return (numerator + totalDays / HALF_ROUNDING_DIVISOR) / totalDays
    }
}
