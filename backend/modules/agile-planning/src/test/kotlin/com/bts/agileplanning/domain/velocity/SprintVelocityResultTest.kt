// SprintVelocityResult.of() 산술평균 계산 단위 테스트 — 외부 의존 0
package com.bts.agileplanning.domain.velocity

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

class SprintVelocityResultTest {
    private fun point(
        name: String,
        startDate: LocalDate?,
        endDate: LocalDate?,
        commitmentSeconds: Long,
        completedSeconds: Long,
    ) = VelocityPoint(
        sprintId = UUID.randomUUID(),
        name = name,
        startDate = startDate,
        endDate = endDate,
        commitmentSeconds = commitmentSeconds,
        completedSeconds = completedSeconds,
    )

    @Test
    fun `빈 points 는 평균 0 과 빈 리스트를 반환한다`() {
        val result = SprintVelocityResult.of("BTS", emptyList())

        assertThat(result.projectKey).isEqualTo("BTS")
        assertThat(result.averageCommitmentSeconds).isEqualTo(0L)
        assertThat(result.averageCompletedSeconds).isEqualTo(0L)
        assertThat(result.points).isEmpty()
    }

    @Test
    fun `여러 스프린트의 평균을 정수 나눗셈 반내림으로 계산한다`() {
        val points =
            listOf(
                point("Sprint 1", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 14), 300_000L, 250_000L),
                point("Sprint 2", LocalDate.of(2026, 1, 15), LocalDate.of(2026, 1, 28), 200_000L, 150_000L),
                point("Sprint 3", LocalDate.of(2026, 1, 29), LocalDate.of(2026, 2, 11), 100_000L, 100_000L),
            )

        val result = SprintVelocityResult.of("BTS", points)

        assertThat(result.averageCommitmentSeconds).isEqualTo(200_000L)
        assertThat(result.averageCompletedSeconds).isEqualTo(166_666L)
    }

    @Test
    fun `points 는 입력 순서를 그대로 보존한다`() {
        val points =
            listOf(
                point("Sprint 1", null, null, 100L, 90L),
                point("Sprint 2", null, null, 200L, 180L),
            )

        val result = SprintVelocityResult.of("BTS", points)

        assertThat(result.points).containsExactly(points[0], points[1])
    }

    @Test
    fun `정수 나눗셈은 반올림 없이 내림 처리한다`() {
        val points =
            listOf(
                point("Sprint 1", null, null, 10L, 10L),
                point("Sprint 2", null, null, 10L, 10L),
                point("Sprint 3", null, null, 11L, 11L),
            )

        val result = SprintVelocityResult.of("BTS", points)

        // (10+10+11)/3 = 31/3 = 10 (반내림, 반올림 아님 — 11이었다면 반올림 규칙 위반 검증)
        assertThat(result.averageCommitmentSeconds).isEqualTo(10L)
        assertThat(result.averageCompletedSeconds).isEqualTo(10L)
    }
}
