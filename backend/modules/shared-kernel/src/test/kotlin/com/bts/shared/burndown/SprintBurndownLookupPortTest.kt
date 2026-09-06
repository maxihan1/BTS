// cross-BC 번다운 조회 포트 계약 검증 — SprintBurndownLookupPort·BurndownSource·WorklogContribution

package com.bts.shared.burndown

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/**
 * cross-BC 번다운 원천 데이터 조회 포트 계약 테스트.
 *
 * Spring 컨텍스트 없이 순수 단위 테스트로 실행한다.
 *
 * 검증 항목.
 * - [SprintBurndownLookupPort.fetchBurndownSource] default 구현이 빈 [BurndownSource] 를 반환함(fail-safe).
 * - adapter 가 미등록된 환경에서도 스코프 0 · worklog 없음으로 안전하게 계산이 진행된다.
 * - [BurndownSource]/[WorklogContribution] VO 필드 보존 — **[WorklogContribution.startedAt] 원본 포함**.
 * - ★**계약 반전**(부채 177 Task 30) — 이전 계약은 「한 [WorklogContribution.startedOnUtcDate] 당 최대
 *   1개(구현체가 사전 집계)」였다. 지금은 **worklog 1건당 1항목**이고 같은 UTC 날짜가 여러 번 나올 수 있다.
 *   사전 집계는 BC 경계 앞에서 시각을 버려(비단사) 소비측의 보드 timezone 일 귀속을 불가능하게 만들었다.
 * - ★**개수 축 입력**(부채 177 task-35) — [BurndownSource.visibleIssueCount] 와
 *   [BurndownSource.issueCompletions] 를 함께 나른다. `time_tracking = NONE` 보드의 번다운은
 *   세로축이 시간이 아니라 **이슈 개수**이고, 그 축은 추정 시간·worklog 로는 만들 수 없다.
 *   [IssueCompletion.completedAt] 도 날짜가 아니라 **시각**이다 — worklog 와 같은 이유다.
 */
class SprintBurndownLookupPortTest {
    @Test
    fun `default fetchBurndownSource returns empty fail-safe`() {
        val port = object : SprintBurndownLookupPort {}

        val result = port.fetchBurndownSource(setOf("PROJ-1", "PROJ-2"), "PROJ", UUID.randomUUID())

        assertThat(result.totalOriginalEstimateSeconds).isZero()
        assertThat(result.worklogEntries).isEmpty()
        // 개수 축도 같은 fail-safe 방향이다 — adapter 부재 시 「이슈 0개·완료 없음」으로 그린다.
        assertThat(result.visibleIssueCount).isZero()
        assertThat(result.issueCompletions).isEmpty()
    }

    @Test
    fun `default fetchBurndownSource returns empty fail-safe for empty issueKeys`() {
        val port = object : SprintBurndownLookupPort {}

        val result = port.fetchBurndownSource(emptySet(), "PROJ", UUID.randomUUID())

        assertThat(result.totalOriginalEstimateSeconds).isZero()
        assertThat(result.worklogEntries).isEmpty()
        assertThat(result.visibleIssueCount).isZero()
        assertThat(result.issueCompletions).isEmpty()
    }

    @Test
    fun `BurndownSource 는 스코프 합계와 worklog 목록을 보존한다`() {
        val entry =
            WorklogContribution(
                startedOnUtcDate = LocalDate.of(2026, 7, 1),
                timeSpentSeconds = 3600L,
                startedAt = Instant.parse("2026-07-01T09:00:00Z"),
            )
        val source =
            BurndownSource(
                totalOriginalEstimateSeconds = 36000L,
                worklogEntries = listOf(entry),
                visibleIssueCount = 2L,
                issueCompletions = emptyList(),
            )

        assertThat(source.totalOriginalEstimateSeconds).isEqualTo(36000L)
        assertThat(source.worklogEntries).containsExactly(entry)
        assertThat(source.visibleIssueCount).isEqualTo(2L)
        assertThat(source.issueCompletions).isEmpty()
    }

    @Test
    fun `WorklogContribution 은 worklog 단건의 시각과 초를 보존한다`() {
        val startedAt = Instant.parse("2026-07-02T23:30:00Z")
        val entry =
            WorklogContribution(
                startedOnUtcDate = LocalDate.of(2026, 7, 2),
                timeSpentSeconds = 7200L,
                startedAt = startedAt,
            )

        assertThat(entry.startedOnUtcDate).isEqualTo(LocalDate.of(2026, 7, 2))
        assertThat(entry.timeSpentSeconds).isEqualTo(7200L)
        // ★시각 원본이 그대로 실린다 — 로컬 날짜 칸 배치는 소비측(agile-planning) 책임이다.
        assertThat(entry.startedAt).isEqualTo(startedAt)
    }

    @Test
    fun `계약 반전 - 같은 UTC 날짜의 항목이 여럿일 수 있고 시각으로 갈린다`() {
        val morning =
            WorklogContribution(
                startedOnUtcDate = LocalDate.of(2026, 7, 2),
                timeSpentSeconds = 3600L,
                startedAt = Instant.parse("2026-07-02T10:00:00Z"),
            )
        val lateNight = morning.copy(startedAt = Instant.parse("2026-07-02T23:30:00Z"))

        val source =
            BurndownSource(
                totalOriginalEstimateSeconds = 0L,
                worklogEntries = listOf(morning, lateNight),
                visibleIssueCount = 1L,
                issueCompletions = emptyList(),
            )

        // 이전 계약(날짜당 최대 1개)이었다면 이 목록은 애초에 만들어질 수 없었다.
        assertThat(source.worklogEntries).hasSize(2)
        assertThat(source.worklogEntries.map { it.startedOnUtcDate }).containsExactly(
            LocalDate.of(2026, 7, 2),
            LocalDate.of(2026, 7, 2),
        )
        // 날짜만으로는 두 건이 구별되지 않는다 — 그래서 시각이 필요하다(비단사 손실).
        assertThat(morning).isNotEqualTo(lateNight)
    }

    @Test
    fun `BurndownSource 는 개수 축 입력도 함께 나른다`() {
        val completedAt = Instant.parse("2026-07-02T12:00:00Z")
        val source =
            BurndownSource(
                totalOriginalEstimateSeconds = 0L,
                worklogEntries = emptyList(),
                visibleIssueCount = 3L,
                issueCompletions = listOf(IssueCompletion(issueKey = "PROJ-1", completedAt = completedAt)),
            )

        // 개수 축은 「몇 개인가」와 「언제 완료됐는가」 둘 다 있어야 그려진다 — 하나만으로는 시계열이 안 된다.
        assertThat(source.visibleIssueCount).isEqualTo(3L)
        assertThat(source.issueCompletions).hasSize(1)
        assertThat(source.issueCompletions.single().issueKey).isEqualTo("PROJ-1")
        assertThat(source.issueCompletions.single().completedAt).isEqualTo(completedAt)
    }

    @Test
    fun `IssueCompletion 은 시각 원본을 그대로 보존한다`() {
        // worklog 와 같은 이유로 날짜가 아니라 시각을 나른다 — 로컬 날짜 칸 배치는 소비측 책임이다.
        val lateNight = Instant.parse("2026-07-02T23:30:00Z")

        val completion = IssueCompletion(issueKey = "PROJ-9", completedAt = lateNight)

        assertThat(completion.completedAt).isEqualTo(lateNight)
        assertThat(completion.completedAt).isNotEqualTo(Instant.parse("2026-07-02T00:00:00Z"))
    }
}
