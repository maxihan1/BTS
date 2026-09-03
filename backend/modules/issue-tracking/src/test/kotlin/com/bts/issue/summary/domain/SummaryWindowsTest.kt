// SummaryWindows 창 경계 계산 단위테스트 — UTC 자정 절단 · 인접 두 창 · 월/연 경계

package com.bts.issue.summary.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit

/**
 * [SummaryWindows] 순수 계산 단위테스트.
 *
 * 외부 의존 없이 [Clock.fixed] 만 주입해 결정적으로 검증한다.
 *
 * 검증 시나리오.
 * - 창 경계가 **UTC 자정**으로 절단된다(KST 호스트에서 날짜가 밀리지 않음)
 * - 최근 7일 = 오늘 포함 7일, 직전 7일 = 8~14일 전 — 두 창은 인접하고 겹치지 않는다
 * - [SummaryWindows.historySince] 가 상태 개요 DONE 2주 특례까지 한 번에 덮는다
 * - 향후 7일 마감 = 오늘 포함 7일
 * - 월 경계·연 경계에서 날짜가 바르게 역산된다
 */
class SummaryWindowsTest {
    @Test
    fun `창 경계를 UTC 자정으로 절단한다`() {
        val windows = SummaryWindows.of(fixedAt("2026-09-03T15:30:00Z"))

        assertThat(windows.today).isEqualTo(LocalDate.of(2026, 9, 3))
        assertThat(windows.recentFrom).isEqualTo(utcMidnight(2026, 8, 28))
        assertThat(windows.recentTo).isEqualTo(utcMidnight(2026, 9, 4))
    }

    @Test
    fun `KST 자정을 넘긴 UTC 전날 밤에도 UTC 날짜로 자른다`() {
        // KST 로는 2026-09-04 08:30 이지만 UTC 로는 아직 2026-09-03 이다.
        val windows = SummaryWindows.of(fixedAt("2026-09-03T23:30:00Z"))

        assertThat(windows.today).isEqualTo(LocalDate.of(2026, 9, 3))
    }

    @Test
    fun `호스트 타임존이 KST 여도 UTC 기준으로 계산한다`() {
        val kstClock = Clock.fixed(Instant.parse("2026-09-03T15:30:00Z"), ZoneId.of("Asia/Seoul"))

        val windows = SummaryWindows.of(kstClock)

        assertThat(windows.today).isEqualTo(LocalDate.of(2026, 9, 3))
        assertThat(windows.recentFrom).isEqualTo(utcMidnight(2026, 8, 28))
    }

    @Test
    fun `최근 7일과 직전 7일은 각각 7일이고 서로 인접하며 겹치지 않는다`() {
        val windows = SummaryWindows.of(fixedAt("2026-09-03T00:00:00Z"))

        assertThat(ChronoUnit.DAYS.between(windows.recentFrom, windows.recentTo)).isEqualTo(7)
        assertThat(ChronoUnit.DAYS.between(windows.previousFrom, windows.previousTo)).isEqualTo(7)
        assertThat(windows.previousTo).isEqualTo(windows.recentFrom)
    }

    @Test
    fun `직전 7일은 8일 전부터 14일 전까지다`() {
        val windows = SummaryWindows.of(fixedAt("2026-09-03T09:00:00Z"))

        assertThat(windows.previousFrom).isEqualTo(utcMidnight(2026, 8, 21))
        assertThat(windows.previousTo).isEqualTo(utcMidnight(2026, 8, 28))
    }

    @Test
    fun `historySince 는 2주 전 자정이고 직전 창 시작과 같다`() {
        val windows = SummaryWindows.of(fixedAt("2026-09-03T09:00:00Z"))

        // 이력을 한 번만 읽어 카드 완료(7일)·직전 7일·상태 개요 DONE(2주)을 모두 덮는다.
        assertThat(windows.historySince).isEqualTo(utcMidnight(2026, 8, 21))
        assertThat(windows.historySince).isEqualTo(windows.previousFrom)
        assertThat(ChronoUnit.DAYS.between(windows.historySince, windows.recentTo)).isEqualTo(14)
    }

    @Test
    fun `향후 7일 마감은 오늘 포함 7일이다`() {
        val windows = SummaryWindows.of(fixedAt("2026-09-03T09:00:00Z"))

        assertThat(windows.dueFrom).isEqualTo(LocalDate.of(2026, 9, 3))
        assertThat(windows.dueTo).isEqualTo(LocalDate.of(2026, 9, 9))
    }

    @Test
    fun `연 경계를 넘어 역산한다`() {
        val windows = SummaryWindows.of(fixedAt("2026-01-03T09:00:00Z"))

        assertThat(windows.recentFrom).isEqualTo(utcMidnight(2025, 12, 28))
        assertThat(windows.previousFrom).isEqualTo(utcMidnight(2025, 12, 21))
    }

    @Test
    fun `윤년 2월 말을 넘어 역산한다`() {
        val windows = SummaryWindows.of(fixedAt("2028-03-02T09:00:00Z"))

        // 2028 은 윤년이므로 2월 29일이 존재한다.
        assertThat(windows.recentFrom).isEqualTo(utcMidnight(2028, 2, 25))
        assertThat(windows.previousFrom).isEqualTo(utcMidnight(2028, 2, 18))
    }

    private fun fixedAt(instant: String): Clock = Clock.fixed(Instant.parse(instant), ZoneOffset.UTC)

    private fun utcMidnight(
        year: Int,
        month: Int,
        day: Int,
    ): OffsetDateTime = LocalDate.of(year, month, day).atStartOfDay().atOffset(ZoneOffset.UTC)
}
