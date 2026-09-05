// 번다운 x축·ideal 분모를 근무일로 좁히는 규칙의 단위테스트 (부채 177 Task 11 · 스펙 R6·E2·E8)

package com.bts.agileplanning.domain.burndown

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.SoftAssertions.assertSoftly
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * [BurndownCalculator] 의 **근무일 축** 단위테스트 (스펙 R6 · 완료 기준 5·6·7).
 *
 * 순수 함수 테스트다 — Spring·DB·mock 을 쓰지 않는다(NFR N2. 서비스로 올리면 Testcontainers 를 탄다).
 *
 * ## ★ 대조군 한 쌍이 이 파일의 최상위 요구다
 * 「미설정이면 달력일 14개」와 「월~금이면 근무일 10개」 두 단언은 **한 쌍으로만 옳다.**
 * 뒤엣것만 두면 「미설정을 월~금으로 채우는」 구현(= `working_days` 를 `NOT NULL DEFAULT '{MON..FRI}'`
 * 로 두는 것과 같은 결과)도 통과하고, 그 순간 **아무도 설정을 바꾸지 않았는데 기존 모든 스프린트의
 * 차트가 배포 시점에 바뀐다**(스펙 R6). 앞엣것만 두면 현행 구현이 그대로 통과한다.
 *
 * ## 뮤테이션 검증 — 무엇을 걸었고 어느 테스트가 잡았는가
 * 각 행은 **느슨한 구현과 올바른 구현이 갈리는 입력**이다. 「깨면 red」가 아니라 「어느 쪽 구현이
 * 살아남는가」로 읽어라.
 *
 * | # | 뮤테이션(느슨한 구현) | 잡는 테스트 | 갈리는 관측값 |
 * |---|---|---|---|
 * | M1 | `workingCalendar` 를 무시한다(Task 11 이전의 현행 구현) | 대조군 **뒤**쪽 | 월~금 point 수 10 ↔ 14 |
 * | M2 | 미설정을 월~금으로 채운다(`?: WEEKDAYS`) | 대조군 **앞**쪽 | 미설정 point 수 14 ↔ 10 |
 * | M3 | x축만 좁히고 ideal 분모는 달력일(13)로 둔다 | ideal 분모 | 7/17 ideal 0 ↔ 49,846 |
 * | M4 | `nonWorkingDates` 를 무시한다 | 비근무일 한 칸 | point 수 9 ↔ 10 |
 * | M5 | 기간 **밖** 비근무일까지 축에서 뺀다 | 비근무일 한 칸(E8) | point 수 9 ↔ 8 |
 * | M6 | `totalDays == 0` 가드를 지운다 | 근무일 하루 | ideal ↔ `ArithmeticException` |
 * | M7 | 빈 축을 특수 처리하지 않는다 | 전 기간 비근무일(E2) | 빈 목록 ↔ 예외/500 |
 * | M8 | 축의 첫 점을 `start` 로 고정한다 | 주말 시작·종료 | 첫 point 7/6 ↔ 7/4 |
 * | M9 | 비근무일에 적힌 worklog 를 버린다 | 비근무일 worklog 2건 | completed 포함 ↔ 누락 |
 *
 * **M1·M2 가 서로를 가리지 않는다**(실측). 대조군은 `assertSoftly` 로 묶어 두 판정이 **항상 둘 다**
 * 실행되게 했다 — 그래서 한쪽만 깨는 뮤테이션에서 반대쪽이 초록으로 남는 것을 기계가 보여준다.
 * - M1 → 뒤쪽만 red. `Expected size: 10 but was: 14`. 미설정 판정과 [BurndownCalculatorTest] 6건은 초록.
 * - M2 → 앞쪽만 red. `Expected size: 14 but was: 10`. 월~금 판정은 초록
 *   (덤으로 [BurndownCalculatorTest] 의 7/1~7/4 스프린트 2건이 함께 깨진다 — 미설정 경로를 건드린 증거).
 *
 * **M3 은 개수 판정으로는 절대 안 잡힌다**(실측). M3 에서 대조군 한 쌍은 그대로 초록이고 ideal 값
 * 판정 3건만 red 다 — 「point 개수」만 재는 테스트로는 분모 버그가 통과한다.
 */
class BurndownWorkingDaysTest {
    @Test
    fun `control pair - unset keeps all 14 calendar days while MON-FRI narrows to 10 working days`() {
        // 픽스처 자체 검증 — 날짜 상수가 틀어지면 이 테스트는 조용히 공허해진다.
        assertThat(SPRINT_START.dayOfWeek).isEqualTo(DayOfWeek.MONDAY)
        assertThat(SPRINT_END.dayOfWeek).isEqualTo(DayOfWeek.SUNDAY)
        assertThat(ChronoUnit.DAYS.between(SPRINT_START, SPRINT_END) + 1).isEqualTo(14L)

        val unset = calculate(workingCalendar = null)
        val monToFri = calculate(workingCalendar = WorkingDayCalendar(WEEKDAYS))

        // ★ soft 로 묶는다 — 앞쪽이 먼저 죽으면 뒤쪽 판정이 아예 실행되지 않아
        //   「한쪽만 깨는 뮤테이션이 반대쪽을 초록으로 남긴다」를 기계가 보여주지 못한다.
        assertSoftly { softly ->
            softly.assertThat(unset.map { it.date })
                .describedAs("미설정(NULL) = 달력일 전부 — 배포 순간 기존 차트가 바뀌면 안 된다(R6)")
                .hasSize(14)
                .containsExactlyElementsOf(SPRINT_START.datesUntil(SPRINT_END.plusDays(1)).toList())

            softly.assertThat(monToFri.map { it.date })
                .describedAs("월~금 = 근무일 10개. 주말 4일이 축에서 빠진다(R6)")
                .hasSize(10)
                .containsExactly(
                    JUL_06, JUL_07, JUL_08, JUL_09, JUL_10,
                    JUL_13, JUL_14, JUL_15, JUL_16, JUL_17,
                )
        }
    }

    @Test
    fun `ideal denominator is the working day count, not the calendar day count`() {
        val unset = calculate(workingCalendar = null)
        val monToFri = calculate(workingCalendar = WorkingDayCalendar(WEEKDAYS))

        // 근무일 축의 분모는 9 구간(=근무일 10개-1)이라 마지막 근무일에서 정확히 0 이다.
        assertThat(monToFri.map { it.idealSeconds }).containsExactly(
            324_000L, 288_000L, 252_000L, 216_000L, 180_000L,
            144_000L, 108_000L, 72_000L, 36_000L, 0L,
        )

        // ★ 같은 날짜(7/17)가 두 경로에서 다른 ideal 을 갖는다 — 분모가 갈린다는 직접 증거.
        //   x축만 좁히고 분모를 안 고치면 마지막 근무일의 ideal 이 0 이 아니라 49,846 으로 남는다(M3).
        assertThat(idealOn(monToFri, JUL_17)).describedAs("근무일 분모 9").isEqualTo(0L)
        assertThat(idealOn(unset, JUL_17)).describedAs("달력일 분모 13 — 현행 유지").isEqualTo(49_846L)
    }

    @Test
    fun `a non-working date removes one more slot while an out-of-range one changes nothing`() {
        val holidayOnly = WorkingDayCalendar(WEEKDAYS, nonWorkingDates = setOf(JUL_08))
        val withOutOfRange =
            WorkingDayCalendar(WEEKDAYS, nonWorkingDates = setOf(JUL_08, LocalDate.of(2026, 8, 1)))

        val points = calculate(workingCalendar = holidayOnly)

        assertThat(points.map { it.date })
            .describedAs("근무일 10개 - 비근무일 1개 = 9개")
            .hasSize(9)
            .doesNotContain(JUL_08)

        // 분모도 8 구간으로 함께 줄어든다.
        assertThat(points.map { it.idealSeconds }).containsExactly(
            324_000L, 283_500L, 243_000L, 202_500L, 162_000L, 121_500L, 81_000L, 40_500L, 0L,
        )

        // E8 — 스프린트 기간 밖 비근무일은 저장되지만 계산에서 자연히 무시된다.
        assertThat(calculate(workingCalendar = withOutOfRange)).isEqualTo(points)
    }

    @Test
    fun `E2 - a sprint whose every day is non-working yields no points instead of dividing by zero`() {
        val allOff =
            WorkingDayCalendar(
                WEEKDAYS,
                nonWorkingDates =
                    setOf(JUL_06, JUL_07, JUL_08, JUL_09, JUL_10, JUL_13, JUL_14, JUL_15, JUL_16, JUL_17),
            )

        assertThatCode { calculate(workingCalendar = allOff) }.doesNotThrowAnyException()
        assertThat(calculate(workingCalendar = allOff))
            .describedAs("전원 휴가 2주 — 그릴 점이 없다. 500 이 아니다(E2)")
            .isEmpty()
    }

    @Test
    fun `a single working day joins the totalDays == 0 path`() {
        val mondayOnly = WorkingDayCalendar(setOf(DayOfWeek.MONDAY))

        val points =
            BurndownCalculator.calculate(
                start = SPRINT_START,
                end = JUL_12,
                scopeSeconds = SCOPE_SECONDS,
                worklogByUtcDate = emptyMap(),
                today = JUL_12,
                workingCalendar = mondayOnly,
            )

        assertThat(points).hasSize(1)
        val only = points.single()
        assertThat(only.date).isEqualTo(JUL_06)
        assertThat(only.idealSeconds).describedAs("분모 0 — 1일 스프린트와 같은 경로").isEqualTo(SCOPE_SECONDS)
        assertThat(only.remainingSeconds).isEqualTo(SCOPE_SECONDS)
        assertThat(only.completedSeconds).isEqualTo(0L)
    }

    @Test
    fun `a sprint starting on a weekend puts the first point on the first working day`() {
        val saturdayStart = LocalDate.of(2026, 7, 4)
        assertThat(saturdayStart.dayOfWeek).isEqualTo(DayOfWeek.SATURDAY)

        val points =
            BurndownCalculator.calculate(
                start = saturdayStart,
                end = JUL_17,
                scopeSeconds = SCOPE_SECONDS,
                worklogByUtcDate = mapOf(saturdayStart to 2 * HOUR_SECONDS),
                today = JUL_17,
                workingCalendar = WorkingDayCalendar(WEEKDAYS),
            )

        assertThat(points.first().date).describedAs("7/4(토)가 아니라 7/6(월)").isEqualTo(JUL_06)
        assertThat(points.first().idealSeconds).isEqualTo(SCOPE_SECONDS)
        assertThat(points.last().date).isEqualTo(JUL_17)
        assertThat(points.last().idealSeconds).isEqualTo(0L)

        // 시작일(토)에 적은 worklog 는 사라지지 않고 첫 근무일에 합류한다.
        assertThat(points.first().completedSeconds).isEqualTo(2 * HOUR_SECONDS)
    }

    @Test
    fun `worklog logged on a non-working day is carried to the next working day, not dropped`() {
        val saturday = JUL_11
        assertThat(saturday.dayOfWeek).isEqualTo(DayOfWeek.SATURDAY)

        val points =
            calculate(
                workingCalendar = WorkingDayCalendar(WEEKDAYS),
                worklog = mapOf(JUL_10 to 3 * HOUR_SECONDS, saturday to 4 * HOUR_SECONDS),
            )

        assertThat(points.map { it.date }).doesNotContain(saturday)

        val friday = points.single { it.date == JUL_10 }
        assertThat(friday.completedSeconds).describedAs("토요일 로그는 아직 아니다").isEqualTo(3 * HOUR_SECONDS)

        val nextWorkingDay = points.single { it.date == JUL_13 }
        assertThat(nextWorkingDay.completedSeconds)
            .describedAs("비근무일 로그를 버리면 잔여가 영원히 안 줄어든다")
            .isEqualTo(7 * HOUR_SECONDS)
        assertThat(nextWorkingDay.remainingSeconds).isEqualTo(SCOPE_SECONDS - 7 * HOUR_SECONDS)
    }

    // ── fixture ───────────────────────────────────────────────────────────────

    /** 2주 스프린트(달력 14일 · 주말 4일) 고정 픽스처. 기본 worklog 는 비어 있다. */
    private fun calculate(
        workingCalendar: WorkingDayCalendar?,
        worklog: Map<LocalDate, Long> = emptyMap(),
    ): List<BurndownPoint> =
        BurndownCalculator.calculate(
            start = SPRINT_START,
            end = SPRINT_END,
            scopeSeconds = SCOPE_SECONDS,
            worklogByUtcDate = worklog,
            today = SPRINT_END,
            workingCalendar = workingCalendar,
        )

    private fun idealOn(
        points: List<BurndownPoint>,
        date: LocalDate,
    ): Long = points.single { it.date == date }.idealSeconds

    private companion object {
        private const val HOUR_SECONDS = 3600L

        /** 90h. 근무일 축의 분모 9 로도, 비근무일 1개를 뺀 8 로도 나누어떨어져 반올림이 판정을 흐리지 않는다. */
        private const val SCOPE_SECONDS = 324_000L

        private val WEEKDAYS =
            setOf(
                DayOfWeek.MONDAY,
                DayOfWeek.TUESDAY,
                DayOfWeek.WEDNESDAY,
                DayOfWeek.THURSDAY,
                DayOfWeek.FRIDAY,
            )

        private val SPRINT_START = LocalDate.of(2026, 7, 6)
        private val SPRINT_END = LocalDate.of(2026, 7, 19)

        private val JUL_06 = LocalDate.of(2026, 7, 6)
        private val JUL_07 = LocalDate.of(2026, 7, 7)
        private val JUL_08 = LocalDate.of(2026, 7, 8)
        private val JUL_09 = LocalDate.of(2026, 7, 9)
        private val JUL_10 = LocalDate.of(2026, 7, 10)
        private val JUL_11 = LocalDate.of(2026, 7, 11)
        private val JUL_12 = LocalDate.of(2026, 7, 12)
        private val JUL_13 = LocalDate.of(2026, 7, 13)
        private val JUL_14 = LocalDate.of(2026, 7, 14)
        private val JUL_15 = LocalDate.of(2026, 7, 15)
        private val JUL_16 = LocalDate.of(2026, 7, 16)
        private val JUL_17 = LocalDate.of(2026, 7, 17)
    }
}
