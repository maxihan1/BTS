// 로컬 계정 패스워드 해싱/검증 서비스 — Argon2id (OWASP 2024)

package com.atlas.bts.identity.credential

import de.mkammerer.argon2.Argon2Factory

/**
 * 로컬 인증 패스워드 해싱/검증 서비스.
 *
 * Argon2id 파라미터 출처: OWASP Password Storage Cheat Sheet 2024
 * - memory: 65536 KB (64 MiB)
 * - iterations: 3
 * - parallelism: 4
 */
class LocalCredentialService {

    private val argon2 = Argon2Factory.createAdvanced(Argon2Factory.Argon2Types.ARGON2id)

    /**
     * 평문 패스워드를 Argon2id 인코딩 문자열로 해싱한다.
     * 호출 후 [plain] 배열 내용은 즉시 폐기된다.
     */
    fun hash(plain: CharArray): String =
        argon2.hash(3, 65536, 4, plain)

    /**
     * Argon2id 해시와 평문 패스워드가 일치하는지 검증한다.
     * 호출 후 [plain] 배열 내용은 즉시 폐기된다.
     */
    fun verify(hash: String, plain: CharArray): Boolean =
        argon2.verify(hash, plain)
}
