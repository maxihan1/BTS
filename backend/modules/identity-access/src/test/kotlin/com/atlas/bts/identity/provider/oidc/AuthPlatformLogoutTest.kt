// AuthPlatformLogout 단위 테스트 — Auth /connect/logout 주소 · id_token 없는 세션 · 풀 수 없는 암호문 (S24)

package com.atlas.bts.identity.provider.oidc

import com.atlas.bts.identity.session.Session
import com.bts.shared.crypto.SecretEncryptor
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.web.util.UriComponentsBuilder
import java.time.Instant
import java.util.UUID

class AuthPlatformLogoutTest {
    private val encryptor = SecretEncryptor("test-app-encryption-key-for-oidc", "deadbeefcafef00d")
    private val logout = AuthPlatformLogout(encryptor, "https://auth.example.com/")

    private val request =
        MockHttpServletRequest("POST", "/api/v1/auth/logout").apply {
            scheme = "https"
            serverName = "bunker.example.com"
            serverPort = 443
        }

    private fun session(authIdTokenEnc: String?) =
        Session(
            id = UUID.randomUUID(),
            userId = UUID.randomUUID(),
            providerId = "oidc",
            deviceFingerprint = null,
            ipAddress = null,
            userAgent = null,
            createdAt = Instant.EPOCH,
            expiresAt = Instant.EPOCH,
            lastSeenAt = Instant.EPOCH,
            revokedAt = null,
            revokeReason = null,
            authIdTokenEnc = authIdTokenEnc,
        )

    @Test
    fun `id token 을 가진 세션은 issuer connect-logout 에 hint 와 이 앱 login 주소를 싣는다`() {
        val url = logout.endSessionUrl(session(encryptor.encrypt("h.p.s")), request)

        val parsed = UriComponentsBuilder.fromUriString(requireNotNull(url)).build()
        assertThat(parsed.scheme + "://" + parsed.host + parsed.path)
            .isEqualTo("https://auth.example.com/connect/logout")
        assertThat(parsed.queryParams["id_token_hint"]).containsExactly("h.p.s")
        // Auth 는 등록 문자열과 정확히 비교한다 — 기본 포트 · query 를 붙이지 않는다
        assertThat(parsed.queryParams["post_logout_redirect_uri"])
            .containsExactly("https://bunker.example.com/login")
        assertThat(parsed.queryParams.keys).containsExactlyInAnyOrder("id_token_hint", "post_logout_redirect_uri")
    }

    @Test
    fun `id token 이 없는 세션 · issuer 미설정 · 풀 수 없는 암호문은 null 이라 BTS 만 끊는다`() {
        assertThat(logout.endSessionUrl(session(null), request)).isNull()
        assertThat(AuthPlatformLogout(encryptor, "").endSessionUrl(session(encryptor.encrypt("t")), request)).isNull()
        assertThat(logout.endSessionUrl(session("not-a-cipher"), request)).isNull()
    }
}
