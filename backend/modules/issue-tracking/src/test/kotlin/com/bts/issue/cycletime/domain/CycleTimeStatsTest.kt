// CycleTimeStats.of() nearest-rank 백분위 계산 순수 도메인 단위테스트
package com.bts.issue.cycletime.domain

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * [CycleTimeStats] 순수 함수 단위테스트.
 *
 * 외부 의존(DB, Spring, mock) 없이 원시 타입/VO 입출력만 검증한다.
 *
 * 검증 시나리오.
 * - 빈 리스트 → count=0, 나머지 필드 전부 null
 * - n=1 → 모든 통계값이 그 단일 값과 같음
 * - 홀수 개수(n=3) nearest-rank 백분위 계산
 * - 짝수 개수(n=4) nearest-rank 백분위 계산
 * - avg 반올림(0.5 올림)
 * - 정렬 안 된 입력도 내부 정렬 후 결정적으로 동일한 결과
 */
class CycleTimeStatsTest {
    @Test
    fun `empty list yields count 0 and all null fields`() {
        val stats = CycleTimeStats.of(emptyList())

        assertThat(stats.count).isEqualTo(0)
        assertThat(stats.min).isNull()
        assertThat(stats.max).isNull()
        assertThat(stats.avg).isNull()
        assertThat(stats.p25).isNull()
        assertThat(stats.p50).isNull()
        assertThat(stats.p75).isNull()
        assertThat(stats.p90).isNull()
    }

    @Test
    fun `single value yields that value for every stat`() {
        val stats = CycleTimeStats.of(listOf(100L))

        assertThat(stats.count).isEqualTo(1)
        assertThat(stats.min).isEqualTo(100L)
        assertThat(stats.max).isEqualTo(100L)
        assertThat(stats.avg).isEqualTo(100L)
        assertThat(stats.p25).isEqualTo(100L)
        assertThat(stats.p50).isEqualTo(100L)
        assertThat(stats.p75).isEqualTo(100L)
        assertThat(stats.p90).isEqualTo(100L)
    }

    @Test
    fun `odd count nearest-rank percentiles`() {
        val stats = CycleTimeStats.of(listOf(10L, 20L, 30L))

        assertThat(stats.count).isEqualTo(3)
        assertThat(stats.p25).isEqualTo(10L)
        assertThat(stats.p50).isEqualTo(20L)
        assertThat(stats.p75).isEqualTo(30L)
        assertThat(stats.p90).isEqualTo(30L)
    }

    @Test
    fun `even count nearest-rank percentiles`() {
        val stats = CycleTimeStats.of(listOf(10L, 20L, 30L, 40L))

        assertThat(stats.count).isEqualTo(4)
        assertThat(stats.p25).isEqualTo(10L)
        assertThat(stats.p50).isEqualTo(20L)
        assertThat(stats.p75).isEqualTo(30L)
        assertThat(stats.p90).isEqualTo(40L)
    }

    @Test
    fun `avg rounds half up`() {
        val stats = CycleTimeStats.of(listOf(10L, 15L))

        assertThat(stats.avg).isEqualTo(13L)
        assertThat(stats.min).isEqualTo(10L)
        assertThat(stats.max).isEqualTo(15L)
    }

    @Test
    fun `unsorted input is sorted internally before computing stats`() {
        val stats = CycleTimeStats.of(listOf(30L, 10L, 20L))

        assertThat(stats.count).isEqualTo(3)
        assertThat(stats.min).isEqualTo(10L)
        assertThat(stats.max).isEqualTo(30L)
        assertThat(stats.p25).isEqualTo(10L)
        assertThat(stats.p50).isEqualTo(20L)
        assertThat(stats.p75).isEqualTo(30L)
        assertThat(stats.p90).isEqualTo(30L)
    }
}
