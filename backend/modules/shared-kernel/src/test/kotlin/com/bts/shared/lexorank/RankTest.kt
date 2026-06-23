// LexoRank Rank VO 단위 테스트 — between/initial/고갈/검증/Comparable 전수 커버

package com.bts.shared.lexorank

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Rank VO 단위 테스트.
 *
 * Spring 컨텍스트 없이 순수 단위 테스트로 실행한다.
 *
 * 검증 범위.
 * - 유효/무효 문자열 생성 검증 (끝문자 a 포함)
 * - initial() 반환값 불변식
 * - initial() == between(null, null) 동치 (plan N3)
 * - between 일반 케이스 (prev < result < next, 끝문자 a 아님)
 * - between 인접 케이스 (b,c — 길이 증가, 끝문자 a 아님)
 * - between prefix 케이스 (b,bc — trailing-a 회피 B2)
 * - between 경계 케이스 (null 하한/상한)
 * - 50자 고갈 시 RankSpaceExhaustedException
 * - Comparable 정렬 일관성 (사전순 == Rank 순서)
 */
class RankTest {

    // ── 생성 검증 ───────────────────────────────────────────────────────────────

    @ParameterizedTest(name = "정상 — {0} 은 유효한 Rank 이다")
    @ValueSource(strings = ["b", "n", "z", "bb", "nm", "bz", "mn"])
    fun `정상 — 유효한 알파벳 소문자 문자열은 Rank 를 생성한다`(value: String) {
        val rank = Rank.of(value)
        assertThat(rank.value).isEqualTo(value)
    }

    @Test
    fun `정상 — 정확히 50자 길이는 허용된다`() {
        // b 로 끝나므로 trailing-a 아님
        val value = "b".repeat(50)
        val rank = Rank.of(value)
        assertThat(rank.value).isEqualTo(value)
    }

    @Test
    fun `거부 — 빈 문자열은 IllegalArgumentException 을 던진다`() {
        assertThatThrownBy { Rank.of("") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @ParameterizedTest(name = "거부 — {0} 은 대문자를 포함해 거부된다")
    @ValueSource(strings = ["A", "Ab", "bA", "N"])
    fun `거부 — 대문자 포함 값은 IllegalArgumentException 을 던진다`(value: String) {
        assertThatThrownBy { Rank.of(value) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `거부 — 51자 초과는 IllegalArgumentException 을 던진다`() {
        val value = "b".repeat(51)
        assertThatThrownBy { Rank.of(value) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `거부 — 끝 문자가 a 인 값은 IllegalArgumentException 을 던진다`() {
        assertThatThrownBy { Rank.of("ba") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `거부 — 단일 문자 a 는 IllegalArgumentException 을 던진다`() {
        assertThatThrownBy { Rank.of("a") }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @ParameterizedTest(name = "거부 — {0} 은 비알파벳 문자로 거부된다")
    @ValueSource(strings = ["1", "b1", "b-c", " b", "b "])
    fun `거부 — 비알파벳 문자 포함 값은 IllegalArgumentException 을 던진다`(value: String) {
        assertThatThrownBy { Rank.of(value) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    // ── initial ─────────────────────────────────────────────────────────────────

    @Test
    fun `initial — 반환값은 유효한 Rank 이다`() {
        val r = Rank.initial()
        // 유효성 검증 (끝문자 a 아님, 1~50자, 소문자 알파벳)
        assertThat(r.value).isNotEmpty
        assertThat(r.value).matches("[a-z]+")
        assertThat(r.value).doesNotEndWith("a")
    }

    @Test
    fun `initial — between(null, null) 과 동치이다 (N3)`() {
        assertThat(Rank.initial()).isEqualTo(Rank.between(null, null))
    }

    // ── between 일반 케이스 ─────────────────────────────────────────────────────

    @Test
    fun `between — b 와 z 사이 결과는 b 보다 크고 z 보다 작다`() {
        val prev = Rank.of("b")
        val next = Rank.of("z")
        val result = Rank.between(prev, next)

        assertThat(result).isGreaterThan(prev)
        assertThat(result).isLessThan(next)
        assertThat(result.value).doesNotEndWith("a")
    }

    @Test
    fun `between — 결과는 prev 보다 크고 next 보다 작다 (사전순)`() {
        val prev = Rank.of("g")
        val next = Rank.of("n")
        val result = Rank.between(prev, next)

        assertThat(result.value).isGreaterThan(prev.value)
        assertThat(result.value).isLessThan(next.value)
        assertThat(result.value).doesNotEndWith("a")
    }

    // ── between 인접 케이스 — 길이 증가, 끝문자 a 아님 ────────────────────────

    @Test
    fun `between — b 와 c 인접 시 길이가 증가하고 끝문자가 a 가 아니다`() {
        val prev = Rank.of("b")
        val next = Rank.of("c")
        val result = Rank.between(prev, next)

        // prev < result < next 불변식
        assertThat(result).isGreaterThan(prev)
        assertThat(result).isLessThan(next)
        // 길이가 1자 이상 증가해야 한다
        assertThat(result.value.length).isGreaterThan(prev.value.length)
        // trailing-a 금지
        assertThat(result.value).doesNotEndWith("a")
    }

    // ── between prefix 케이스 — trailing-a 회피 B2 ───────────────────────────

    @Test
    fun `between — b 와 bc prefix 케이스에서 결과 끝문자가 a 가 아니다 (B2)`() {
        val prev = Rank.of("b")
        val next = Rank.of("bc")
        val result = Rank.between(prev, next)

        assertThat(result).isGreaterThan(prev)
        assertThat(result).isLessThan(next)
        assertThat(result.value).doesNotEndWith("a")
        // "ba" 또는 "aa" 로 끝나지 않아야 한다
        assertThat(result.value).doesNotEndWith("a")
    }

    @Test
    fun `between — m 과 mb prefix 케이스에서 결과 끝문자가 a 가 아니다`() {
        val prev = Rank.of("m")
        val next = Rank.of("mb")
        val result = Rank.between(prev, next)

        assertThat(result).isGreaterThan(prev)
        assertThat(result).isLessThan(next)
        assertThat(result.value).doesNotEndWith("a")
    }

    // ── between 경계 케이스 (null 하한/상한) ────────────────────────────────────

    @Test
    fun `between — prev null 은 next 보다 작은 rank 를 반환한다`() {
        val next = Rank.of("g")
        val result = Rank.between(null, next)

        assertThat(result).isLessThan(next)
        assertThat(result.value).doesNotEndWith("a")
    }

    @Test
    fun `between — next null 은 prev 보다 큰 rank 를 반환한다`() {
        val prev = Rank.of("g")
        val result = Rank.between(prev, null)

        assertThat(result).isGreaterThan(prev)
        assertThat(result.value).doesNotEndWith("a")
    }

    @Test
    fun `between — 둘 다 null 은 유효한 중간값을 반환한다`() {
        val result = Rank.between(null, null)

        assertThat(result.value).isNotEmpty
        assertThat(result.value).doesNotEndWith("a")
    }

    @Test
    fun `between — n 과 null 결과 끝문자가 a 가 아니다`() {
        val prev = Rank.of("n")
        val result = Rank.between(prev, null)

        assertThat(result).isGreaterThan(prev)
        assertThat(result.value).doesNotEndWith("a")
    }

    @Test
    fun `between — null 과 b 결과 끝문자가 a 가 아니다`() {
        val next = Rank.of("b")
        val result = Rank.between(null, next)

        assertThat(result).isLessThan(next)
        assertThat(result.value).doesNotEndWith("a")
    }

    // ── 50자 고갈 ────────────────────────────────────────────────────────────────

    @Test
    fun `고갈 — 50자 내 중간값 생성 불가 시 RankSpaceExhaustedException 을 던진다`() {
        // 인접한 50자짜리 두 키 사이에는 49자 + 한 자리 여유도 없어 고갈 발생
        // y + 49자 b 와 y + 49자 c: 사이는 50자 한계를 넘어야 표현 가능
        val prev = Rank.of("y" + "b".repeat(49))
        val next = Rank.of("y" + "c".repeat(49))

        assertThatThrownBy { Rank.between(prev, next) }
            .isInstanceOf(RankSpaceExhaustedException::class.java)
    }

    // ── Comparable 정렬 일관성 ──────────────────────────────────────────────────

    @Test
    fun `Comparable — Rank 정렬은 문자열 사전순과 일치한다`() {
        val ranks = listOf(
            Rank.of("z"),
            Rank.of("b"),
            Rank.of("n"),
            Rank.of("g"),
            Rank.of("bb"),
        ).sorted()

        val expected = listOf("b", "bb", "g", "n", "z")
        assertThat(ranks.map { it.value }).isEqualTo(expected)
    }

    @Test
    fun `Comparable — 같은 값 두 Rank 는 동등하다`() {
        val r1 = Rank.of("n")
        val r2 = Rank.of("n")

        assertThat(r1).isEqualByComparingTo(r2)
        assertThat(r1).isEqualTo(r2)
    }

    @Test
    fun `Comparable — b 는 n 보다 작다`() {
        assertThat(Rank.of("b")).isLessThan(Rank.of("n"))
    }

    // ── between 연속 삽입 불변식 ────────────────────────────────────────────────

    @Test
    fun `between — 연속 삽입 시 prev lt result lt next 불변식이 유지된다`() {
        var prev: Rank? = null
        val next = Rank.of("z")
        repeat(10) {
            val result = Rank.between(prev, next)
            val currentPrev = prev
            if (currentPrev != null) {
                assertThat(result).isGreaterThan(currentPrev)
            }
            assertThat(result).isLessThan(next)
            assertThat(result.value).doesNotEndWith("a")
            prev = result
        }
    }
}
