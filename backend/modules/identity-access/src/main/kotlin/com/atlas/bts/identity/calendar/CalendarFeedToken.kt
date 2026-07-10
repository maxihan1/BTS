// 캘린더 피드 익명 구독용 불투명 토큰 생성/해시 — rawToken hex 64자 + SHA-256 hex 해시 (FR-CA-02)

package com.atlas.bts.identity.calendar

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * 캘린더 피드(`GET /ical/feed/{token}.ics`) 익명 구독용 불투명 토큰의 생성/해시 값 객체.
 *
 * [rawToken] 은 [TOKEN_BYTES]바이트 CSPRNG 난수를 소문자 hex 로 인코딩한 평문이며, 토큰 발급 응답에
 * 단 한 번만 내려간다. [hash] 는 `SHA-256(rawToken)` 소문자 hex [HASH_LENGTH]자로,
 * DB(`user_calendar_tokens.token_hash`)에는 이 해시만 저장한다([TrustedDeviceToken]·PAT 선례와
 * **동일한 hex 인코딩** — base64url 혼용 금지).
 *
 * ## 보안 정책
 * - **[rawToken] 평문은 DB·로그에 절대 저장/기록 금지**(DEVELOPMENT.md §1.1.1 — 비밀값 미저장).
 *   해시는 단방향이라 DB 탈취 시에도 rawToken 을 역산할 수 없다(refresh_token·PAT 선례).
 * - 난수원은 [SecureRandom](CSPRNG)만 사용한다(예측 가능한 `java.util.Random` 금지).
 */
data class CalendarFeedToken(
    val rawToken: String,
    val hash: String,
) {
    companion object {
        /** rawToken 난수 바이트 수. 32바이트 → hex 인코딩 시 [HASH_LENGTH]자. */
        private const val TOKEN_BYTES = 32

        /** SHA-256 / rawToken hex 문자열 길이(소문자 64자). */
        private const val HASH_LENGTH = 64

        /** [TOKEN_BYTES]바이트 CSPRNG 난수 rawToken + 그 SHA-256 hex 해시를 생성한다. */
        fun generate(): CalendarFeedToken {
            val bytes = ByteArray(TOKEN_BYTES)
            SecureRandom().nextBytes(bytes)
            val rawToken = toHex(bytes)
            return CalendarFeedToken(rawToken = rawToken, hash = hash(rawToken))
        }

        /** [rawToken] 의 SHA-256 hex 소문자 [HASH_LENGTH]자 해시. 동일 입력은 동일 해시(결정적). */
        fun hash(rawToken: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hex = toHex(digest.digest(rawToken.toByteArray(Charsets.UTF_8)))
            check(hex.length == HASH_LENGTH) { "SHA-256 hex 길이는 ${HASH_LENGTH}자여야 한다. 실제: ${hex.length}" }
            return hex
        }

        /** 바이트 배열을 소문자 hex 문자열로 인코딩한다(rawToken·해시 공통, base64url 혼용 방지). */
        private fun toHex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }
    }
}
