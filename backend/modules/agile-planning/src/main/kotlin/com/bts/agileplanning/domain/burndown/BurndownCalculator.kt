// 스프린트 번다운/번업 시계열을 계산하는 순수 도메인 함수 — Clock/DB/포트 의존 0

package com.bts.agileplanning.domain.burndown

import java.time.DayOfWeek
import java.time.LocalDate

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
     * @param workingCalendar 보드 「작업일」 설정. **null 이면 미설정이고 달력일 전부가 축이다**(스펙 R6).
     * @return date 오름차순으로 정렬된 [BurndownPoint] 목록. [workingCalendar] 가 null 이면 [start]~[end]
     *   각 일자 1개씩(총 일수 개), 아니면 그 구간의 **근무일** 1개씩이다.
     * @throws IllegalArgumentException [start] 가 [end] 보다 이후이거나 [scopeSeconds] 가 음수인 경우.
     */
    fun calculate(
        start: LocalDate,
        end: LocalDate,
        scopeSeconds: Long,
        worklogByUtcDate: Map<LocalDate, Long>,
        today: LocalDate,
        workingCalendar: WorkingDayCalendar? = null,
    ): List<BurndownPoint> {
        require(!start.isAfter(end)) { "start ($start) must not be after end ($end)." }
        require(scopeSeconds >= 0) { "scopeSeconds ($scopeSeconds) must not be negative." }

        val axis = buildAxis(start, end, workingCalendar)
        val asOf = minOf(end, today)
        val totalDays = (axis.size - 1).coerceAtLeast(0).toLong()
        val preStartSum = worklogByUtcDate.filterKeys { it.isBefore(start) }.values.sum()

        val points = mutableListOf<BurndownPoint>()
        var cumulative = 0L
        var day = start
        while (!day.isAfter(end)) {
            val future = day.isAfter(asOf)
            if (!future) {
                cumulative += dailyContribution(day, start, preStartSum, worklogByUtcDate)
            }
            if (day in axis) {
                // 축 위 몇 번째인가가 곧 ideal 의 x 좌표다. 아직 담기 전이라 points.size 가 그 index 다.
                val ideal = computeIdealSeconds(scopeSeconds, points.size.toLong(), totalDays)
                points +=
                    if (future) {
                        BurndownPoint(day, null, ideal, null, scopeSeconds)
                    } else {
                        val remaining = (scopeSeconds - cumulative).coerceAtLeast(0L)
                        BurndownPoint(day, remaining, ideal, cumulative, scopeSeconds)
                    }
            }
            day = day.plusDays(1)
        }
        return points
    }

    /**
     * [start]~[end] 중 실제로 차트에 그릴 날짜(x축)를 오름차순으로 만든다.
     *
     * [calendar] 가 null 이면 **미설정**이고, 그때는 달력일 전부가 축이다 — `working_days` 가 NULL 인
     * 보드의 번다운은 배포 전후로 한 점도 달라지지 않아야 한다(스펙 R6 · `WorkingDaysSettingsService` KDoc).
     *
     * 반환이 비어 있을 수 있다(스프린트 전 기간이 비근무일 — 스펙 E2). 호출부가 그 경우를 감당한다.
     */
    private fun buildAxis(
        start: LocalDate,
        end: LocalDate,
        calendar: WorkingDayCalendar?,
    ): Set<LocalDate> {
        val axis = LinkedHashSet<LocalDate>()
        var day = start
        while (!day.isAfter(end)) {
            if (calendar == null || calendar.isWorkingDay(day)) {
                axis += day
            }
            day = day.plusDays(1)
        }
        return axis
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
     * Ideal 라인의 [axisIndex] 번째 지점 값을 반올림(half-up)으로 계산한다.
     *
     * ★**달력 날짜가 아니라 축 위의 순번으로 보간한다.** 축이 근무일만 담으면 분모([totalDays])도
     * 근무일 구간 수가 되어야 한다 — x축만 좁히고 분모를 달력일로 두면 마지막 근무일의 ideal 이
     * 0 에 닿지 않아 안내선이 차트 밖에서 끝난다(스펙 R6).
     * 미설정 경로에서는 축이 달력일 전부라 `totalDays == ChronoUnit.DAYS.between(start, end)`,
     * `totalDays - axisIndex == ChronoUnit.DAYS.between(day, end)` 가 성립해 값이 종전과 같다.
     *
     * [totalDays] 가 0 이면 0 나눗셈을 피해 [scopeSeconds] 를 그대로 반환한다. 1일 스프린트뿐 아니라
     * **근무일이 하루뿐인 스프린트**도 이 경로로 합류한다.
     */
    private fun computeIdealSeconds(
        scopeSeconds: Long,
        axisIndex: Long,
        totalDays: Long,
    ): Long {
        if (totalDays == 0L) return scopeSeconds
        val stepsRemaining = totalDays - axisIndex
        val numerator = scopeSeconds * stepsRemaining
        return (numerator + totalDays / HALF_ROUNDING_DIVISOR) / totalDays
    }
}

/**
 * 보드 「작업일」 설정을 번다운 축 계산에 넘기는 값 객체 (스펙 R5·R6 · J38·J39).
 *
 * @property standardDays 표준 근무일 요일 집합.
 * @property nonWorkingDates 비근무일. 스프린트 기간 밖 날짜가 섞여 있어도 된다(스펙 E8).
 */
data class WorkingDayCalendar(
    val standardDays: Set<DayOfWeek>,
    val nonWorkingDates: Set<LocalDate> = emptySet(),
) {
    /** [date] 가 근무일인가. 표준 요일에 들고 비근무일로 등록되지 않았을 때만 참이다. */
    fun isWorkingDay(date: LocalDate): Boolean = date.dayOfWeek in standardDays && date !in nonWorkingDates
}
