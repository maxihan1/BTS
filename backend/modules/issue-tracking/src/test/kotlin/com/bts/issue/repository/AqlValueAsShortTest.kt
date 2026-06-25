// asShort() 확장함수 — Short 범위 가드 단위 테스트 (defense-in-depth characterization)

package com.bts.issue.repository

import com.bts.shared.search.AqlValue
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/**
 * [AqlValue.asShort] 확장함수의 Short 범위 가드 단위 테스트.
 *
 * 파서([com.bts.search.aql.AqlParser])가 Short 범위 밖 값을 1차 차단하므로
 * 정상 경로에서는 이 함수에 범위 위반 값이 도달하지 않는다.
 * 그러나 파서를 우회한 직접 AST 조립·미래 파서 변경 등 방어 목적으로
 * [asShort]가 silent wrap 대신 [IllegalArgumentException]을 던짐을 검증한다(defense-in-depth).
 *
 * ### 검증 항목
 * - [AqlValue.Num] Short 최대값 초과 → [IllegalArgumentException].
 * - [AqlValue.Num] Short 최솟값 미만 → [IllegalArgumentException].
 * - [AqlValue.Str] 숫자 문자열이 Short 초과 → [IllegalArgumentException].
 * - [AqlValue.Num] Short 경계값(32767, -32768) → 정상 변환.
 * - [AqlValue.Num] 일반값(1, 3, 5) → 정상 변환.
 * - [AqlValue.Str] 비숫자 → [IllegalArgumentException].
 * - **회귀 보장**: 범위 밖 값이 wrap되지 않음 — silent overflow 부재 단언.
 */
class AqlValueAsShortTest {
    // ── Short 초과 → IllegalArgumentException ──────────────────────────────

    @Test
    fun `AqlValue Num Short 최대값 초과는 IllegalArgumentException을 던진다`() {
        // 40000은 Int 범위 내이지만 Short 최대값(32767) 초과.
        // 가드 전에는 40000.toShort() = -25536 (silent wrap) 으로 오염됐다.
        assertThatThrownBy { AqlValue.Num(40000).asShort() }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `AqlValue Num Short 최솟값 미만은 IllegalArgumentException을 던진다`() {
        // -40000은 Short 최솟값(-32768) 미만.
        assertThatThrownBy { AqlValue.Num(-40000).asShort() }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `AqlValue Num Int 최대값은 IllegalArgumentException을 던진다`() {
        // 2147483647은 Short 범위 훨씬 초과.
        assertThatThrownBy { AqlValue.Num(Int.MAX_VALUE).asShort() }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `AqlValue Str Short 초과 숫자 문자열은 IllegalArgumentException을 던진다`() {
        // Str 경로는 toIntOrNull()?.toShort() 로 처리되므로 동일 가드 필요.
        assertThatThrownBy { AqlValue.Str("40000").asShort() }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    // ── 경계값 — 정상 변환 ─────────────────────────────────────────────────

    @Test
    fun `AqlValue Num Short 최대값 32767은 정상 변환된다`() {
        val result = AqlValue.Num(32767).asShort()
        assertThat(result).isEqualTo(32767.toShort())
    }

    @Test
    fun `AqlValue Num Short 최솟값 마이너스32768은 정상 변환된다`() {
        val result = AqlValue.Num(-32768).asShort()
        assertThat(result).isEqualTo((-32768).toShort())
    }

    @Test
    fun `AqlValue Num 일반 우선순위 값은 정상 변환된다`() {
        assertThat(AqlValue.Num(1).asShort()).isEqualTo(1.toShort())
        assertThat(AqlValue.Num(3).asShort()).isEqualTo(3.toShort())
        assertThat(AqlValue.Num(5).asShort()).isEqualTo(5.toShort())
    }

    // ── Str 비숫자 → IllegalArgumentException ──────────────────────────────

    @Test
    fun `AqlValue Str 비숫자 문자열은 IllegalArgumentException을 던진다`() {
        assertThatThrownBy { AqlValue.Str("high").asShort() }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    // ── 회귀 보장 — silent wrap 부재 단언 ─────────────────────────────────

    @Test
    fun `asShort 가드가 없으면 40000이 마이너스25536로 wrap되는 것을 방지한다`() {
        // 40000.toShort() = -25536 (Kotlin 기본 동작 — silent overflow).
        // 가드 추가 후 이 경로는 IllegalArgumentException으로 대체된다.
        // 이 테스트는 wrap 이전 값(-25536)이 실제로 반환되지 않음을 단언한다.
        val wrappedValue = 40000.toShort() // -25536 — 이 값이 반환되면 안 된다
        val thrownEx =
            runCatching { AqlValue.Num(40000).asShort() }
                .exceptionOrNull()
        assertThat(thrownEx)
            .isInstanceOf(IllegalArgumentException::class.java)
            .withFailMessage("asShort()가 silent wrap으로 $wrappedValue 를 반환해서는 안 됩니다.")
    }
}
