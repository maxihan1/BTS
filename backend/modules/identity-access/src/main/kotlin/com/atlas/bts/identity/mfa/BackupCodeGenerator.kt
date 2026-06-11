// MFA 분실 대비 1회용 백업 코드(xxxxx-xxxxx) 10개를 SecureRandom 으로 생성하는 컴포넌트 (FR-MF-02 Task 2)

package com.atlas.bts.identity.mfa

import org.springframework.stereotype.Component
import java.security.SecureRandom

/**
 * MFA(다단계 인증) 분실 대비 1회용 백업 코드 생성기.
 *
 * Authenticator 앱/하드웨어 키를 분실했을 때 로그인을 복구하기 위한 1회용 코드 묶음을 만든다.
 * 한 번 호출하면 [CODE_COUNT]개의 코드를 반환하며, 각 코드는 `xxxxx-xxxxx` 형식
 * (하이픈으로 구분된 5자 + 5자)이다. 영속화(해시 저장)·소진 처리는 상위 서비스 책임이고,
 * 본 컴포넌트는 순수 생성만 한다(상태 없음).
 *
 * ## 알파벳 — Crockford base32(혼동 문자 제외)
 * [ALPHABET]은 사람이 손으로 옮겨 적기 좋도록 혼동 문자(0/O, 1/I/L)를 제외한
 * 30자 집합이다. 시각적 오인으로 인한 입력 오류를 줄인다.
 *
 * ## 엔트로피 근거
 * 코드당 문자는 [CODE_DIGITS]개(하이픈 제외)이고 각 문자는 [ALPHABET] 30자 중 균등 추출이므로,
 * 코드 하나의 엔트로피는 log2(30^10) 약 49bit 로, 권고 하한 40bit 를 넘는다. 따라서 단일 코드
 * 추측 공격에 충분히 안전하다(소진·rate-limit 은 상위 계층에서 추가 방어).
 *
 * ## 보안 주의
 * - 추출에 암호학적 난수원 [SecureRandom] 을 사용한다(예측 가능한 [java.util.Random] 금지).
 * - 생성된 코드는 비밀값이므로 로깅하지 않는다(§1.1.2).
 *
 * ## 참조
 * - FR-MF-02 Task 2, SDD §19.7 (MFA — 백업 코드)
 */
@Component
class BackupCodeGenerator(
    private val secureRandom: SecureRandom = SecureRandom(),
) {
    /**
     * [CODE_COUNT]개의 상호 고유한 1회용 백업 코드를 생성한다.
     *
     * 각 코드는 `xxxxx-xxxxx` 형식이며, 모든 문자는 [ALPHABET]에서 [SecureRandom] 으로 추출된다.
     * 드물게 발생할 수 있는 중복은 집합으로 제거해 정확히 [CODE_COUNT]개의 고유 코드를 보장한다.
     *
     * @return 정확히 [CODE_COUNT]개의 고유한 백업 코드 리스트.
     */
    fun generate(): List<String> {
        val codes = LinkedHashSet<String>()
        while (codes.size < CODE_COUNT) {
            codes.add(generateOne())
        }
        return codes.toList()
    }

    /**
     * `xxxxx-xxxxx` 형식의 코드 한 개를 생성한다.
     */
    private fun generateOne(): String {
        val builder = StringBuilder(CODE_DIGITS + 1)
        for (index in 0 until CODE_DIGITS) {
            if (index == GROUP_SIZE) {
                builder.append(SEPARATOR)
            }
            builder.append(ALPHABET[secureRandom.nextInt(ALPHABET.length)])
        }
        return builder.toString()
    }

    internal companion object {
        /**
         * 혼동 문자(0/O, 1/I/L)를 제외한 Crockford base32 알파벳(30자).
         * 손으로 옮겨 적을 때 시각적 오인을 줄인다.
         */
        const val ALPHABET = "23456789ABCDEFGHJKMNPQRSTVWXYZ"

        /** 한 번 호출 시 생성하는 백업 코드 개수. */
        const val CODE_COUNT = 10

        /** 하이픈을 제외한 코드 한 개의 문자 수(엔트로피 근거 약 49bit ≥ 40bit). */
        const val CODE_DIGITS = 10

        /** 그룹 크기 — 이 위치 뒤에 [SEPARATOR] 를 삽입해 `xxxxx-xxxxx` 형식을 만든다. */
        const val GROUP_SIZE = 5

        /** 그룹 구분자. */
        const val SEPARATOR = '-'
    }
}
