// 로컬 계정 패스워드 해싱/검증 서비스 — Argon2id (OWASP 2024)

package com.atlas.bts.identity.credential

import de.mkammerer.argon2.Argon2Factory

/**
 * 로컬 인증 패스워드 해싱/검증 서비스.
 *
 * - 해싱: Argon2id, memory=65536KB, iterations=3, parallelism=4
 * - 평문 메모리 폐기: hash/verify 완료 후 [wipeArray] 호출
 */
class LocalCredentialService {
    private val argon2 = Argon2Factory.createAdvanced(Argon2Factory.Argon2Types.ARGON2id)

    /**
     * 평문 패스워드를 Argon2id 인코딩 문자열로 해싱한다.
     * 반환 후 [plain] 배열 내용은 즉시 폐기된다.
     */
    fun hash(plain: CharArray): String =
        try {
            argon2.hash(Argon2Params.ITERATIONS, Argon2Params.MEMORY_KB, Argon2Params.PARALLELISM, plain)
        } finally {
            argon2.wipeArray(plain)
        }

    /**
     * Argon2id 해시와 평문 패스워드가 일치하는지 검증한다.
     * 반환 후 [plain] 배열 내용은 즉시 폐기된다.
     */
    fun verify(
        hash: String,
        plain: CharArray,
    ): Boolean =
        try {
            argon2.verify(hash, plain)
        } finally {
            argon2.wipeArray(plain)
        }
}
