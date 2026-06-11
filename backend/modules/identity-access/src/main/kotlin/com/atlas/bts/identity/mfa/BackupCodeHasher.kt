// MFA 1회용 백업 코드를 SHA-256 단방향 해시로 변환(입력 정규화 후)하는 컴포넌트 (FR-MF-02 Task 3)

package com.atlas.bts.identity.mfa

import org.springframework.security.crypto.codec.Hex
import org.springframework.stereotype.Component
import java.security.MessageDigest

/**
 * MFA(다단계 인증) 1회용 백업 코드 해시 유틸.
 *
 * 백업 코드는 비밀값이므로 DB 에 평문 저장하지 않고(DEVELOPMENT.md §1.1.1) SHA-256 단방향 해시로
 * 변환해 저장한다. 검증 시에는 사용자가 입력한 코드를 같은 방식으로 해시해 저장된 해시 집합과 대조한다.
 *
 * ## 왜 Argon2 가 아니라 SHA-256 인가
 * 패스워드(`StoredPasswordCredential`)는 사람이 고른 저엔트로피 비밀이라 무차별 대입 지연이 핵심이라
 * Argon2 를 쓴다. 반면 백업 코드는 [BackupCodeGenerator] 가 [SecureRandom] 으로 만든 고엔트로피
 * (코드당 약 49bit) 비밀이므로, 무차별 대입 자체가 비현실적이다. 따라서 빠른 SHA-256 으로 충분하며,
 * 코드 1개 검증마다 해시 1회만 계산하면 된다.
 *
 * ## 입력 정규화 — 사용자 입력 형식 변형 흡수
 * 사용자는 백업 코드를 손으로 옮겨 적으며 형식이 흔들린다. 대소문자가 바뀌거나, 가독성을 위해 넣은
 * 하이픈(`xxxxx-xxxxx`)을 빼거나, 앞뒤/중간 공백이 섞일 수 있다. 같은 코드가 형식만 달라 검증에 실패하면
 * 안 되므로, 해시 전에 항상 **대문자화 + 하이픈/공백 제거**로 정규화한다. 그 결과 `a3k9f-2m7qx` 와
 * `A3K9F2M7QX`, `  A3K9F 2M7QX  ` 가 모두 동일한 해시로 수렴한다. 저장과 검증이 같은 정규화를 거치므로
 * 대조가 일관된다.
 *
 * ## 보안 주의 (DEVELOPMENT.md §1.1.2)
 * 입력 평문 코드와 출력 해시는 **절대 로깅하지 않는다**(println/log 금지).
 *
 * ## 참조
 * - FR-MF-02 Task 3, SDD §19.7 (MFA — 백업 코드)
 */
@Component
class BackupCodeHasher {
    /**
     * 백업 코드 한 개를 정규화한 뒤 SHA-256 해시의 64자 hex 문자열로 변환한다.
     *
     * 정규화 규칙은 대문자화 + 하이픈/공백 제거이므로, 같은 코드는 입력 형식과 무관하게 같은 해시를 낸다.
     * 입력/출력은 비밀값이라 로깅하지 않는다(§1.1.2).
     *
     * @param plain 사용자/생성기가 제공한 백업 코드 평문(형식 변형 허용).
     * @return 정규화 후 SHA-256 해시의 소문자 hex 문자열(64자, 32바이트).
     */
    fun hash(plain: String): String {
        val normalized = normalize(plain)
        val digest = MessageDigest.getInstance(HASH_ALGORITHM)
        val bytes = digest.digest(normalized.toByteArray(Charsets.UTF_8))
        return String(Hex.encode(bytes))
    }

    /**
     * 백업 코드를 대문자화 + 하이픈/공백 제거로 정규화한다.
     * 사용자 입력 형식 변형(대소문자/하이픈/공백)을 흡수해 같은 코드를 같은 문자열로 수렴시킨다.
     */
    private fun normalize(plain: String): String =
        plain.uppercase().replace(STRIP_PATTERN, "")

    internal companion object {
        /** 단방향 해시 알고리즘 — 32바이트 출력(64자 hex). */
        const val HASH_ALGORITHM = "SHA-256"

        /** 정규화 시 제거할 문자(하이픈 + 모든 공백류). */
        val STRIP_PATTERN = Regex("[\\s-]")
    }
}
