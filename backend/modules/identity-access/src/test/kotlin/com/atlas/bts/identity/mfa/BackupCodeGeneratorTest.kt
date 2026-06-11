// BackupCodeGenerator 단위 테스트 — 백업 코드 10개 생성 형식·알파벳·고유성·랜덤성 검증 (FR-MF-02 Task 2)

package com.atlas.bts.identity.mfa

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.RepeatedTest
import org.junit.jupiter.api.Test

/**
 * [BackupCodeGenerator] 단위 테스트.
 *
 * MFA(다단계 인증) 분실 대비 1회용 백업 코드 생성기를 검증한다. 코드는 Crockford base32
 * 알파벳(혼동 문자 0/O/1/I/L 제외)에서 `SecureRandom` 으로 추출하며, `xxxxx-xxxxx` 형식이다.
 *
 * ## 검증 시나리오
 * - 항상 정확히 10개 반환
 * - 각 코드 `xxxxx-xxxxx`(5자-하이픈-5자) 형식
 * - 각 문자는 혼동 문자 제외 알파벳에 속함
 * - 한 번의 호출 안에서 10개가 상호 고유
 * - 반복 호출 시 코드 집합이 매번 달라짐(랜덤성 — `SecureRandom` 근거)
 */
class BackupCodeGeneratorTest {
    private val generator = BackupCodeGenerator()

    // 혼동 문자(0/O/1/I/L) 제외 Crockford base32 알파벳.
    private val allowedAlphabet = "23456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val codeFormat = Regex("^[$allowedAlphabet]{5}-[$allowedAlphabet]{5}$")

    @Test
    fun `정확히 10개의 코드를 반환한다`() {
        val codes = generator.generate()

        assertThat(codes).hasSize(10)
    }

    @Test
    fun `각 코드는 xxxxx-xxxxx 형식이다`() {
        val codes = generator.generate()

        assertThat(codes).allMatch { codeFormat.matches(it) }
    }

    @Test
    fun `각 코드의 문자는 혼동 문자 제외 알파벳에만 속한다`() {
        val codes = generator.generate()

        val usedChars = codes.joinToString("").replace("-", "").toSet()
        assertThat(allowedAlphabet.toSet()).containsAll(usedChars)
    }

    @Test
    fun `혼동 문자(0,O,1,I,L)는 코드에 포함되지 않는다`() {
        val codes = generator.generate()

        val joined = codes.joinToString("")
        assertThat(joined).doesNotContain("0", "O", "1", "I", "L")
    }

    @Test
    fun `한 번의 호출에서 10개 코드는 상호 고유하다`() {
        val codes = generator.generate()

        assertThat(codes.toSet()).hasSize(10)
    }

    @RepeatedTest(5)
    fun `반복 호출 시 코드 집합이 매번 달라진다`() {
        val first = generator.generate().toSet()
        val second = generator.generate().toSet()

        // SecureRandom 사용 시 두 호출의 전체 일치 확률은 무시 가능(엔트로피 ~50bit/코드).
        assertThat(first == second).isFalse()
    }
}
