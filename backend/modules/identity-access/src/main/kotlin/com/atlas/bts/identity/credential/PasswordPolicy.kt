// 비밀번호 정책 VO — 길이·복잡도 위반을 열거해 반환 (SDD 19장 §3.2)

package com.atlas.bts.identity.credential

/**
 * 비밀번호 정책 위반 종류.
 *
 * SDD 19장 §3.2: 최소 12자 + 영문 대소문자 / 숫자 / 특수문자 중 3종 이상.
 */
enum class PasswordPolicyViolation {
    /** 비밀번호 길이가 최소 기준(12자) 미달. */
    MIN_LENGTH,

    /** 사용된 문자 종류(대문자·소문자·숫자·특수문자)가 3종 미만. */
    COMPLEXITY,
}

/**
 * 비밀번호 정책 검증기.
 *
 * SDD 19장 §3.2 규칙.
 * - 길이 12자 미만 → [PasswordPolicyViolation.MIN_LENGTH]
 * - 문자 종류(대문자·소문자·숫자·특수문자) 3종 미만 → [PasswordPolicyViolation.COMPLEXITY]
 * - 두 조건 모두 위반 시 두 항목 모두 반환.
 *
 * ## 보안 주의사항
 * [validate]는 [plain]을 **읽기만** 한다. 배열 내용을 수정하거나 wipe하지 않는다.
 * wipe(0으로 덮어쓰기)는 **호출자 책임**이다.
 * [plain] 내용은 어떠한 형태로도 로그·저장하지 않는다.
 */
object PasswordPolicy {
    fun validate(plain: CharArray): List<PasswordPolicyViolation> {
        val violations = mutableListOf<PasswordPolicyViolation>()

        if (plain.size < 12) {
            violations += PasswordPolicyViolation.MIN_LENGTH
        }

        val hasUpper = plain.any { it.isUpperCase() }
        val hasLower = plain.any { it.isLowerCase() }
        val hasDigit = plain.any { it.isDigit() }
        val hasSpecial = plain.any { !it.isLetterOrDigit() }

        val classCount = listOf(hasUpper, hasLower, hasDigit, hasSpecial).count { it }
        if (classCount < 3) {
            violations += PasswordPolicyViolation.COMPLEXITY
        }

        return violations
    }
}
