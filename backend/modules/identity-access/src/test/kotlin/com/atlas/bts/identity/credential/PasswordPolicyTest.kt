// PasswordPolicy VO 단위 테스트 — 길이·복잡도 위반 열거 검증 (SDD 19장 §3.2)

package com.atlas.bts.identity.credential

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PasswordPolicyTest {

    @Test
    fun `11자는 MIN_LENGTH 위반`() {
        val violations = PasswordPolicy.validate("Abcdefg1234".toCharArray())
        assertEquals(listOf(PasswordPolicyViolation.MIN_LENGTH), violations)
    }

    @Test
    fun `12자 3종이상은 통과`() {
        val violations = PasswordPolicy.validate("Abcdefgh1234".toCharArray())
        assertTrue(violations.isEmpty(), "위반 없이 통과해야 한다. 실제 위반: $violations")
    }

    @Test
    fun `12자 2종만은 COMPLEXITY 위반`() {
        // 소문자 + 숫자만 — 대문자·특수문자 없음 (2종)
        val violations = PasswordPolicy.validate("abcdefgh1234".toCharArray())
        assertEquals(listOf(PasswordPolicyViolation.COMPLEXITY), violations)
    }

    @Test
    fun `짧고 단순하면 두 위반 모두 열거`() {
        // 11자 + 소문자만 (1종)
        val violations = PasswordPolicy.validate("abcdefghijk".toCharArray())
        assertTrue(
            violations.containsAll(
                listOf(PasswordPolicyViolation.MIN_LENGTH, PasswordPolicyViolation.COMPLEXITY),
            ),
            "MIN_LENGTH + COMPLEXITY 둘 다 포함되어야 한다. 실제 위반: $violations",
        )
        assertEquals(2, violations.size, "위반이 정확히 2개여야 한다. 실제: $violations")
    }

    @Test
    fun `특수문자 포함 3종 통과`() {
        // 소문자 + 숫자 + 특수문자 — 3종, 12자
        val violations = PasswordPolicy.validate("abcdefgh12!@".toCharArray())
        assertTrue(violations.isEmpty(), "위반 없이 통과해야 한다. 실제 위반: $violations")
    }

    @Test
    fun `검증은 CharArray 입력, 호출 후 wipe 안 함(읽기 전용)`() {
        val input = "Abcdefgh1234".toCharArray()
        val originalCopy = input.copyOf()

        PasswordPolicy.validate(input)

        // policy 는 입력 배열을 수정하지 않아야 한다 — wipe 는 호출자 책임
        assertTrue(input.contentEquals(originalCopy), "policy.validate 는 입력 CharArray 를 변경하지 않아야 한다")
    }
}
