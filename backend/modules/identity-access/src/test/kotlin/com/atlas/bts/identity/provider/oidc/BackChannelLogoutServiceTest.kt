// BackChannelLogoutService 단위 테스트 — sid 로 · sub 의 iat 전 oidc 세션으로 폐기, refresh chain 짝 (S24)

package com.atlas.bts.identity.provider.oidc

import com.atlas.bts.identity.provider.ldap.ExternalAccount
import com.atlas.bts.identity.provider.ldap.ExternalAccountRepository
import com.atlas.bts.identity.session.RefreshTokenRepository
import com.atlas.bts.identity.session.SessionRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class BackChannelLogoutServiceTest {
    private val sessions = mockk<SessionRepository>()
    private val refreshTokens = mockk<RefreshTokenRepository>(relaxed = true)
    private val externalAccounts = mockk<ExternalAccountRepository>()
    private val configs = mockk<OidcProviderConfigReader>()
    private val service = BackChannelLogoutService(sessions, refreshTokens, externalAccounts, configs)

    private val iat = Instant.parse("2026-11-03T01:00:00Z")
    private val authProviderId = UUID.randomUUID()
    private val userId = UUID.randomUUID()
    private val s1 = UUID.randomUUID()
    private val s2 = UUID.randomUUID()

    private val authConfig =
        OidcProviderConfig(
            id = UUID.randomUUID(),
            registrationId = "auth",
            displayName = "QA Hub 통합계정",
            issuerUri = "https://auth.example.com",
            clientId = "bts",
            clientSecretEncrypted = "enc",
            scopes = "openid",
            authnProviderId = authProviderId,
            enabled = true,
        )

    private fun account() =
        ExternalAccount(
            id = UUID.randomUUID(),
            providerId = authProviderId,
            externalSubject = "auth-user",
            userId = userId,
            groups = emptyList(),
            failedAttempts = 0,
            lockedUntil = null,
            lastLoginAt = null,
            createdAt = iat,
            updatedAt = iat,
        )

    @Test
    fun `sid 가 있으면 그 sid 세션만 끊고 refresh chain 도 같이 끊는다`() {
        every { sessions.revokeActiveByAuthSid("sid-a", "backchannel_logout") } returns listOf(s1, s2)

        val n = service.logout(LogoutClaims(sid = "sid-a", sub = "auth-user", issuedAt = iat))

        assertThat(n).isEqualTo(2)
        verify { refreshTokens.revokeChainFromSession(s1) }
        verify { refreshTokens.revokeChainFromSession(s2) }
        verify(exactly = 0) { sessions.revokeActiveByUserCreatedBefore(any(), any(), any(), any()) }
    }

    @Test
    fun `sid 가 없으면 Auth provider 의 sub 사용자 oidc 세션 중 iat + 1초 전 것을 끊는다 (iat 는 초 단위)`() {
        every { configs.findByRegistrationId("auth") } returns authConfig
        every { externalAccounts.findByProviderIdAndExternalSubject(authProviderId, "auth-user") } returns account()
        every {
            sessions.revokeActiveByUserCreatedBefore(userId, "oidc", iat.plusSeconds(1), "backchannel_logout")
        } returns listOf(s1)

        val n = service.logout(LogoutClaims(sid = null, sub = "auth-user", issuedAt = iat))

        assertThat(n).isEqualTo(1)
        verify { refreshTokens.revokeChainFromSession(s1) }
    }

    @Test
    fun `BTS 에 로그인한 적 없는 sub 이면 아무것도 끊지 않는다`() {
        every { configs.findByRegistrationId("auth") } returns authConfig
        every { externalAccounts.findByProviderIdAndExternalSubject(authProviderId, "stranger") } returns null

        assertThat(service.logout(LogoutClaims(sid = null, sub = "stranger", issuedAt = iat))).isZero()
        verify(exactly = 0) { sessions.revokeActiveByUserCreatedBefore(any(), any(), any(), any()) }
        verify(exactly = 0) { refreshTokens.revokeChainFromSession(any()) }
    }
}
