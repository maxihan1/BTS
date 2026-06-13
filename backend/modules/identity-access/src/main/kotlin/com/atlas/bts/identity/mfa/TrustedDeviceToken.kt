// 신뢰 디바이스 식별용 불투명 토큰 생성/해시 — rawToken hex 64자 + SHA-256 hex 해시 (FR-MF-05)

package com.atlas.bts.identity.mfa

import java.security.MessageDigest
import java.security.SecureRandom

data class TrustedDeviceToken(
    val rawToken: String,
    val hash: String,
) {
    companion object {
        private const val TOKEN_BYTES = 32

        fun generate(): TrustedDeviceToken {
            val bytes = ByteArray(TOKEN_BYTES)
            SecureRandom().nextBytes(bytes)
            val rawToken = bytes.joinToString("") { "%02x".format(it) }
            return TrustedDeviceToken(rawToken = rawToken, hash = hash(rawToken))
        }

        fun hash(rawToken: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(rawToken.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }
}
