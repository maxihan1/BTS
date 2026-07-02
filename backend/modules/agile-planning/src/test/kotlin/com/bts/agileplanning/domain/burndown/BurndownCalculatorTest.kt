// BurndownCalculator 순수 도메인 계산 로직 단위테스트 (S1·1일·빈·클램프·미래·pre-start 엣지)

package com.bts.agileplanning.domain.burndown

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate

/**
 * [BurndownCalculator] 순수 함수 단위테스트.
 *
 * 외부 의존(DB, Spring, mock) 없이 원시 타입 입출력만 검증한다.
 *
 * 검증 시나리오 (스펙 §계산 알고리즘 + 엣지 케이스).
 * - S1: 3일 스프린트, worklog가 2일차에 기록되어 잔여가 16h→10h로 감소, ideal 선형, completed 누적, scope 평탄
 * - 1일 스프린트(end==start): 단일 지점, 0 나눗셈 없음
 * - 빈 스코프: 전 구간 remaining=0, ideal 0 평탄
 * - 로그가 추정치 초과: remaining은 0으로 클램프되나 completed는 클램프되지 않음
 * - 미래 일자(asOf 이후): remaining/completed=null, ideal/scope는 non-null
 * - start 이전 worklog: start 버킷에 합산
 */
class BurndownCalculatorTest {
    @Test
    fun `S1 - remaining 16h to 10h, ideal linear, completed cumulative, scope flat`() {
        val start = LocalDate.of(2026, 7, 1)
        val mid = LocalDate.of(2026, 7, 2)
        val end = LocalDate.of(2026, 7, 3)
        val scope = 16 * HOUR_SECONDS
        val worklog = mapOf(mid to 6 * HOUR_SECONDS)

        val points = BurndownCalculator.calculate(start, end, scope, worklog, today = end)

        assertThat(points.map { it.date }).containsExactly(start, mid, end)

        val day0 = points[0]
        assertThat(day0.remainingSeconds).isEqualTo(scope)
        assertThat(day0.completedSeconds).isEqualTo(0L)
        assertThat(day0.idealSeconds).isEqualTo(scope)
        assertThat(day0.scopeSeconds).isEqualTo(scope)

        val day1 = points[1]
        assertThat(day1.remainingSeconds).isEqualTo(10 * HOUR_SECONDS)
        assertThat(day1.completedSeconds).isEqualTo(6 * HOUR_SECONDS)
        assertThat(day1.idealSeconds).isEqualTo(scope / 2)

        val day2 = points[2]
        assertThat(day2.remainingSeconds).isEqualTo(10 * HOUR_SECONDS)
        assertThat(day2.completedSeconds).isEqualTo(6 * HOUR_SECONDS)
        assertThat(day2.idealSeconds).isEqualTo(0L)
        assertThat(day2.scopeSeconds).isEqualTo(scope)
    }

    @Test
    fun `1-day sprint (end==start) - single point, no div-by-zero`() {
        val day = LocalDate.of(2026, 7, 1)
        val scope = 5 * HOUR_SECONDS

        val points = BurndownCalculator.calculate(day, day, scope, emptyMap(), today = day)

        assertThat(points).hasSize(1)
        val point = points.single()
        assertThat(point.date).isEqualTo(day)
        assertThat(point.idealSeconds).isEqualTo(scope)
        assertThat(point.remainingSeconds).isEqualTo(scope)
        assertThat(point.completedSeconds).isEqualTo(0L)
        assertThat(point.scopeSeconds).isEqualTo(scope)
    }

    @Test
    fun `empty scope - all remaining 0, ideal 0 flat`() {
        val start = LocalDate.of(2026, 7, 1)
        val end = LocalDate.of(2026, 7, 4)

        val points = BurndownCalculator.calculate(start, end, 0L, emptyMap(), today = end)

        assertThat(points).hasSize(4)
        assertThat(points).allSatisfy { point ->
            assertThat(point.idealSeconds).isEqualTo(0L)
            assertThat(point.scopeSeconds).isEqualTo(0L)
            assertThat(point.remainingSeconds).isEqualTo(0L)
            assertThat(point.completedSeconds).isEqualTo(0L)
        }
    }

    @Test
    fun `logged over estimate - remaining clamped at 0`() {
        val start = LocalDate.of(2026, 7, 1)
        val end = LocalDate.of(2026, 7, 2)
        val scope = 3 * HOUR_SECONDS
        val worklog = mapOf(start to 5 * HOUR_SECONDS)

        val points = BurndownCalculator.calculate(start, end, scope, worklog, today = end)

        val day0 = points[0]
        assertThat(day0.remainingSeconds).isEqualTo(0L)
        assertThat(day0.completedSeconds).isEqualTo(5 * HOUR_SECONDS)

        val day1 = points[1]
        assertThat(day1.remainingSeconds).isEqualTo(0L)
        assertThat(day1.completedSeconds).isEqualTo(5 * HOUR_SECONDS)
    }

    @Test
    fun `future days after asOf - remaining and completed null`() {
        val start = LocalDate.of(2026, 7, 1)
        val today = LocalDate.of(2026, 7, 2)
        val end = LocalDate.of(2026, 7, 4)
        val scope = 8 * HOUR_SECONDS

        val points = BurndownCalculator.calculate(start, end, scope, emptyMap(), today)

        assertThat(points).hasSize(4)
        val (actualDays, futureDays) = points.partition { !it.date.isAfter(today) }
        assertThat(actualDays).hasSize(2)
        assertThat(actualDays).allSatisfy { point ->
            assertThat(point.remainingSeconds).isNotNull()
            assertThat(point.completedSeconds).isNotNull()
        }
        assertThat(futureDays).hasSize(2)
        assertThat(futureDays).allSatisfy { point ->
            assertThat(point.remainingSeconds).isNull()
            assertThat(point.completedSeconds).isNull()
            assertThat(point.idealSeconds).isNotNull()
            assertThat(point.scopeSeconds).isEqualTo(scope)
        }
    }

    @Test
    fun `worklog before start folds into start bucket`() {
        val start = LocalDate.of(2026, 7, 5)
        val end = LocalDate.of(2026, 7, 7)
        val scope = 20 * HOUR_SECONDS
        val beforeStart = start.minusDays(1)
        val worklog = mapOf(beforeStart to 3 * HOUR_SECONDS, start to 2 * HOUR_SECONDS)

        val points = BurndownCalculator.calculate(start, end, scope, worklog, today = end)

        val day0 = points[0]
        assertThat(day0.completedSeconds).isEqualTo(5 * HOUR_SECONDS)
        assertThat(day0.remainingSeconds).isEqualTo(15 * HOUR_SECONDS)
    }

    private companion object {
        private const val HOUR_SECONDS = 3600L
    }
}
