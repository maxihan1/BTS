// CycleTimeCalculator 순수 도메인 계산 로직 단위테스트 (모집단 판정·미경유·재오픈·음수·창 경계 엣지)

package com.bts.issue.cycletime.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/**
 * [CycleTimeCalculator] 순수 함수 단위테스트.
 *
 * 외부 의존(DB, Spring, mock) 없이 원시 타입/VO 입출력만 검증한다.
 *
 * 검증 시나리오.
 * - 정상 완료 이슈 — lead/cycle 초 계산 + [CycleTimeStats] 위임 확인
 * - IN_PROGRESS 미경유 이슈는 lead 표본에는 포함, cycle 표본에서는 제외
 * - 재오픈 이력이 있어도 cycle은 첫 IN_PROGRESS ~ 마지막 DONE으로 계산
 * - lastDoneAt의 UTC 날짜가 창 밖이면 모집단에서 완전히 제외
 * - lastDoneAt=null(미완료)이면 모집단에서 제외
 * - firstInProgressAt이 lastDoneAt보다 나중(시각 역전)이면 cycle만 제외, lead는 유지
 * - created==firstInProgress==lastDone(동시각)이면 0초 표본을 제외하지 않고 유지
 * - 빈 입력은 count 0 + 빈 samples, from/to/projectKey는 그대로 echo
 * - 여러 이슈의 samples는 seconds 오름차순 정렬
 */
class CycleTimeCalculatorTest {
    @Test
    fun `completed issue with cycle produces lead and cycle seconds delegating to CycleTimeStats`() {
        val from = LocalDate.of(2026, 7, 1)
        val to = LocalDate.of(2026, 7, 10)
        val created = Instant.parse("2026-07-01T00:00:00Z")
        val firstInProgress = created.plus(Duration.ofDays(1))
        val lastDone = created.plus(Duration.ofDays(3))
        val input = IssueDurationInput("PROJ-1", created, firstInProgress, lastDone)

        val result = CycleTimeCalculator.calculate("PROJ", from, to, listOf(input))

        val expectedLeadSeconds = Duration.ofDays(3).seconds
        val expectedCycleSeconds = Duration.ofDays(2).seconds

        assertThat(result.projectKey).isEqualTo("PROJ")
        assertThat(result.from).isEqualTo(from)
        assertThat(result.to).isEqualTo(to)

        assertThat(result.leadTime.samples).containsExactly(CycleTimeSample("PROJ-1", expectedLeadSeconds))
        assertThat(result.leadTime.stats).isEqualTo(CycleTimeStats.of(listOf(expectedLeadSeconds)))

        assertThat(result.cycleTime.samples).containsExactly(CycleTimeSample("PROJ-1", expectedCycleSeconds))
        assertThat(result.cycleTime.stats).isEqualTo(CycleTimeStats.of(listOf(expectedCycleSeconds)))
    }

    @Test
    fun `issue that never entered IN_PROGRESS is excluded from cycle but included in lead`() {
        val from = LocalDate.of(2026, 7, 1)
        val to = LocalDate.of(2026, 7, 10)
        val created = Instant.parse("2026-07-01T00:00:00Z")
        val lastDone = created.plus(Duration.ofDays(2))
        val input = IssueDurationInput("PROJ-2", created, firstInProgressAt = null, lastDoneAt = lastDone)

        val result = CycleTimeCalculator.calculate("PROJ", from, to, listOf(input))

        assertThat(result.leadTime.stats.count).isEqualTo(1)
        assertThat(result.cycleTime.stats.count).isEqualTo(0)
        assertThat(result.leadTime.stats.count).isGreaterThan(result.cycleTime.stats.count)
        assertThat(result.cycleTime.samples).isEmpty()
    }

    @Test
    fun `cycle time uses first IN_PROGRESS entry through final DONE regardless of reopen history`() {
        val from = LocalDate.of(2026, 7, 1)
        val to = LocalDate.of(2026, 7, 10)
        val created = Instant.parse("2026-07-01T00:00:00Z")
        val firstInProgress = created.plus(Duration.ofHours(6))
        val lastDone = created.plus(Duration.ofDays(5))
        val input = IssueDurationInput("PROJ-3", created, firstInProgress, lastDone)

        val result = CycleTimeCalculator.calculate("PROJ", from, to, listOf(input))

        val expectedCycleSeconds = Duration.between(firstInProgress, lastDone).seconds
        assertThat(result.cycleTime.samples).containsExactly(CycleTimeSample("PROJ-3", expectedCycleSeconds))
    }

    @Test
    fun `issue completed outside window is excluded from population entirely`() {
        val from = LocalDate.of(2026, 7, 1)
        val to = LocalDate.of(2026, 7, 5)
        val created = Instant.parse("2026-07-01T00:00:00Z")
        val firstInProgress = created.plus(Duration.ofDays(1))
        val lastDone = Instant.parse("2026-07-10T00:00:00Z")
        val input = IssueDurationInput("PROJ-4", created, firstInProgress, lastDone)

        val result = CycleTimeCalculator.calculate("PROJ", from, to, listOf(input))

        assertThat(result.leadTime.stats.count).isEqualTo(0)
        assertThat(result.cycleTime.stats.count).isEqualTo(0)
        assertThat(result.leadTime.samples).isEmpty()
        assertThat(result.cycleTime.samples).isEmpty()
    }

    @Test
    fun `unfinished issue with null lastDoneAt is excluded from population`() {
        val from = LocalDate.of(2026, 7, 1)
        val to = LocalDate.of(2026, 7, 10)
        val created = Instant.parse("2026-07-01T00:00:00Z")
        val firstInProgress = created.plus(Duration.ofDays(1))
        val input = IssueDurationInput("PROJ-5", created, firstInProgress, lastDoneAt = null)

        val result = CycleTimeCalculator.calculate("PROJ", from, to, listOf(input))

        assertThat(result.leadTime.stats.count).isEqualTo(0)
        assertThat(result.cycleTime.stats.count).isEqualTo(0)
    }

    @Test
    fun `firstInProgressAt after lastDoneAt excludes cycle sample but keeps lead sample`() {
        val from = LocalDate.of(2026, 7, 1)
        val to = LocalDate.of(2026, 7, 10)
        val created = Instant.parse("2026-07-01T00:00:00Z")
        val lastDone = created.plus(Duration.ofDays(3))
        // lastDone 이후로 역전된 firstInProgress — 데이터 이상/시각 역전 방어 케이스
        val firstInProgress = created.plus(Duration.ofDays(5))
        val input = IssueDurationInput("PROJ-6", created, firstInProgress, lastDone)

        val result = CycleTimeCalculator.calculate("PROJ", from, to, listOf(input))

        assertThat(result.leadTime.stats.count).isEqualTo(1)
        assertThat(result.cycleTime.stats.count).isEqualTo(0)
        assertThat(result.cycleTime.samples).isEmpty()
    }

    @Test
    fun `zero-duration issue where created, started, and done coincide keeps a zero-second sample`() {
        val from = LocalDate.of(2026, 7, 1)
        val to = LocalDate.of(2026, 7, 10)
        val instant = Instant.parse("2026-07-01T00:00:00Z")
        val input = IssueDurationInput("PROJ-7", instant, instant, instant)

        val result = CycleTimeCalculator.calculate("PROJ", from, to, listOf(input))

        assertThat(result.leadTime.stats.count).isEqualTo(1)
        assertThat(result.leadTime.samples).containsExactly(CycleTimeSample("PROJ-7", 0L))
        assertThat(result.cycleTime.stats.count).isEqualTo(1)
        assertThat(result.cycleTime.samples).containsExactly(CycleTimeSample("PROJ-7", 0L))
    }

    @Test
    fun `empty input yields zero counts and echoes window and project key`() {
        val from = LocalDate.of(2026, 7, 1)
        val to = LocalDate.of(2026, 7, 10)

        val result = CycleTimeCalculator.calculate("PROJ", from, to, emptyList())

        assertThat(result.projectKey).isEqualTo("PROJ")
        assertThat(result.from).isEqualTo(from)
        assertThat(result.to).isEqualTo(to)
        assertThat(result.cycleTime.stats.count).isEqualTo(0)
        assertThat(result.cycleTime.samples).isEmpty()
        assertThat(result.leadTime.stats.count).isEqualTo(0)
        assertThat(result.leadTime.samples).isEmpty()
    }

    @Test
    fun `samples are sorted ascending by seconds regardless of input order`() {
        val from = LocalDate.of(2026, 7, 1)
        val to = LocalDate.of(2026, 7, 10)
        val created = Instant.parse("2026-07-01T00:00:00Z")

        val longest = IssueDurationInput("PROJ-LONG", created, created, created.plus(Duration.ofDays(5)))
        val shortest = IssueDurationInput("PROJ-SHORT", created, created, created.plus(Duration.ofDays(1)))
        val middle = IssueDurationInput("PROJ-MID", created, created, created.plus(Duration.ofDays(3)))

        val result = CycleTimeCalculator.calculate("PROJ", from, to, listOf(longest, shortest, middle))

        assertThat(result.leadTime.samples.map { it.issueKey })
            .containsExactly("PROJ-SHORT", "PROJ-MID", "PROJ-LONG")
        assertThat(result.cycleTime.samples.map { it.issueKey })
            .containsExactly("PROJ-SHORT", "PROJ-MID", "PROJ-LONG")
        assertThat(result.leadTime.samples.map { it.seconds }).isSorted()
        assertThat(result.cycleTime.samples.map { it.seconds }).isSorted()
    }
}
