// 정책 충족 랜덤 임시 비밀번호 생성기 — CSPRNG 기반 (FR-AU-05)

package com.atlas.bts.identity.credential

import org.springframework.stereotype.Component
import java.security.SecureRandom

/**
 * 관리자가 로컬 계정을 생성할 때 발급하는 임시 비밀번호 생성기.
 *
 * [PasswordPolicy] (최소 12자 + 문자 종류 3종 이상)를 **항상** 충족하도록
 * 4종 문자군(소문자·대문자·숫자·특수문자)에서 각 1자 이상을 보장한 뒤
 * 나머지를 무작위로 채우고 셔플한다.
 *
 * ## 무작위성
 * [java.security.SecureRandom] (CSPRNG — 암호학적으로 안전한 난수 발생기)만 사용한다.
 * `Math.random` / `Random` 등 예측 가능한 난수는 사용하지 않는다.
 *
 * ## 평문 수명 (다운스트림 가이드 — Task 3)
 * - [generate]가 반환한 [CharArray]는 **호출측이 사용 직후 wipe(0/공백으로 덮어쓰기)** 해야 한다.
 * - store(영속 계층)에 비밀번호를 넘기기 전, **응답용으로 별도 `copyOf()`를 만들어야 한다.**
 *   store는 해시 후 전달받은 배열을 wipe하므로, 사용자 응답에 보여줄 평문이 함께 지워진다.
 * - 평문은 어떠한 형태로도 로그·저장하지 않는다 (DEVELOPMENT.md §1.1).
 *
 * 상태가 없으므로 싱글톤 [Component]로 안전하게 공유된다.
 */
@Component
class TemporaryPasswordGenerator {
    /**
     * 정책을 충족하는 임시 비밀번호를 생성한다.
     *
     * 4종 문자군에서 각 1자 이상을 포함하고 [LENGTH]자까지 무작위로 채운 뒤 셔플한다.
     * 따라서 반환값은 항상 [PasswordPolicy.validate]를 통과한다.
     *
     * @return 생성된 평문 비밀번호. **호출측이 사용 직후 wipe할 책임이 있다** (KDoc 평문 수명 참조).
     */
    fun generate(): CharArray {
        val password = CharArray(LENGTH)

        // 각 문자군에서 최소 1자 보장 — 정책의 "3종 이상" 조건을 항상 충족시킨다.
        for (i in CHARACTER_GROUPS.indices) {
            password[i] = randomChar(CHARACTER_GROUPS[i])
        }

        // 나머지 자리는 전체 문자 집합에서 무작위로 채운다.
        for (i in CHARACTER_GROUPS.size until LENGTH) {
            password[i] = randomChar(ALL_CHARACTERS)
        }

        shuffleInPlace(password)
        return password
    }

    /** [pool]에서 CSPRNG로 한 글자를 균등 추출한다. */
    private fun randomChar(pool: CharArray): Char = pool[secureRandom.nextInt(pool.size)]

    /** Fisher-Yates 셔플 — 보장 문자의 위치 편향을 제거한다. */
    private fun shuffleInPlace(chars: CharArray) {
        for (i in chars.size - 1 downTo 1) {
            val j = secureRandom.nextInt(i + 1)
            val tmp = chars[i]
            chars[i] = chars[j]
            chars[j] = tmp
        }
    }

    private companion object {
        /** 생성 길이. 정책 최소(12)보다 넉넉하게 잡아 안전 마진 확보. */
        const val LENGTH = 16

        val LOWERCASE = "abcdefghijklmnopqrstuvwxyz".toCharArray()
        val UPPERCASE = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray()
        val DIGITS = "0123456789".toCharArray()

        /** 혼동 가능 문자를 피한 특수문자 집합. */
        val SPECIALS = "!@#\$%^&*()-_=+".toCharArray()

        /** 각 문자군에서 1자 이상 보장하기 위한 그룹 목록 (4종). */
        val CHARACTER_GROUPS = arrayOf(LOWERCASE, UPPERCASE, DIGITS, SPECIALS)

        /** 나머지 자리를 채울 전체 문자 집합. */
        val ALL_CHARACTERS: CharArray = LOWERCASE + UPPERCASE + DIGITS + SPECIALS

        /** 스레드 안전한 CSPRNG. 인스턴스 재사용으로 시드 재초기화 비용 회피. */
        val secureRandom = SecureRandom()
    }
}
