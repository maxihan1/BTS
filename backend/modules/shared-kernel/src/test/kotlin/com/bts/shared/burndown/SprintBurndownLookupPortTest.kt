// cross-BC 번다운 조회 포트 계약 검증 — SprintBurndownLookupPort·BurndownSource·WorklogContribution

package com.bts.shared.burndown

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
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
 * - [BurndownSource]/[WorklogContribution] VO 필드 보존.
 */
class SprintBurndownLookupPortTest {
    @Test
    fun `default fetchBurndownSource returns empty fail-safe`() {
        val port = object : SprintBurndownLookupPort {}

        val result = port.fetchBurndownSource(setOf("PROJ-1", "PROJ-2"), "PROJ", UUID.randomUUID())

        assertThat(result.totalOriginalEstimateSeconds).isZero()
        assertThat(result.worklogEntries).isEmpty()
    }

    @Test
    fun `default fetchBurndownSource returns empty fail-safe for empty issueKeys`() {
        val port = object : SprintBurndownLookupPort {}

        val result = port.fetchBurndownSource(emptySet(), "PROJ", UUID.randomUUID())

        assertThat(result.totalOriginalEstimateSeconds).isZero()
        assertThat(result.worklogEntries).isEmpty()
    }

    @Test
    fun `BurndownSource 는 스코프 합계와 worklog 목록을 보존한다`() {
        val entry = WorklogContribution(startedOnUtcDate = LocalDate.of(2026, 7, 1), timeSpentSeconds = 3600L)
        val source = BurndownSource(totalOriginalEstimateSeconds = 36000L, worklogEntries = listOf(entry))

        assertThat(source.totalOriginalEstimateSeconds).isEqualTo(36000L)
        assertThat(source.worklogEntries).containsExactly(entry)
    }

    @Test
    fun `WorklogContribution 은 UTC 날짜와 초 단위 합산 시간을 보존한다`() {
        val entry = WorklogContribution(startedOnUtcDate = LocalDate.of(2026, 7, 2), timeSpentSeconds = 7200L)

        assertThat(entry.startedOnUtcDate).isEqualTo(LocalDate.of(2026, 7, 2))
        assertThat(entry.timeSpentSeconds).isEqualTo(7200L)
    }
}
