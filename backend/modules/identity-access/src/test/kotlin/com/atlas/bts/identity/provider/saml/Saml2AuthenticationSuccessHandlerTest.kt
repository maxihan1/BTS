// Saml2AuthenticationSuccessHandler 단위 테스트 — JIT + 세션/JWT 발급 + RelayState 화이트리스트 (FR-AU-03)

package com.atlas.bts.identity.provider.saml

import com.atlas.bts.identity.jwt.JwtIssuer
import com.atlas.bts.identity.provider.ldap.AutoProvisionService
import com.atlas.bts.identity.provider.ldap.ExternalAccount
import com.atlas.bts.identity.provider.ldap.LdapProvisionAttrs
import com.atlas.bts.identity.session.RefreshToken
import com.atlas.bts.identity.session.RefreshTokenRepository
import com.atlas.bts.identity.session.Session
import com.atlas.bts.identity.session.SessionService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.saml2.provider.service.authentication.Saml2AuthenticatedPrincipal
import org.springframework.security.saml2.provider.service.authentication.Saml2Authentication
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * [Saml2AuthenticationSuccessHandler] 단위 테스트 (MockK).
 *
 * 검증 범위:
 * - [Saml2Authentication] → nameId/registrationId 추출
 * - registrationId → authn_provider_id 해소 (C9)
 * - [AutoProvisionService.provision] JIT 호출 (nameId 가 externalSubject 로)
 * - 세션/JWT 발급 컴포넌트 호출 (SessionService.create / RefreshTokenRepository.save / JwtIssuer.issue)
 * - RelayState open-redirect 차단 (N2 — 상대경로 화이트리스트만)
 *
 * 시각 의존(refresh TTL)은 [Clock.fixed] 로 고정 (N3, time-bomb 회귀 방지).
 */
class Saml2AuthenticationSuccessHandlerTest {
    private lateinit var configRepo: SamlIdpConfigRepository
    private lateinit var autoProvisionService: AutoProvisionService
    private lateinit var sessionService: SessionService
    private lateinit var refreshTokenRepository: RefreshTokenRepository
    private lateinit var jwtIssuer: JwtIssuer
    private lateinit var handler: Saml2AuthenticationSuccessHandler

    private val fixedNow = Instant.parse("2026-06-01T10:00:00Z")
    private val clock = Clock.fixed(fixedNow, ZoneOffset.UTC)

    private val registrationId = "corp-saml"
    private val nameId = "alice@corp.example.com"
    private val providerId = UUID.fromString("00000000-0000-4a03-8000-000000000003")
    private val userId = UUID.fromString("a1b2c3d4-0000-4000-8000-000000000001")
    private val sessionId = UUID.fromString("b1b2c3d4-0000-4000-8000-000000000002")
    private val accessTokenValue = "header.payload.sig"

    private val idpConfig =
        SamlIdpConfig(
            id = UUID.fromString("c1b2c3d4-0000-4000-8000-000000000003"),
            registrationId = registrationId,
            displayName = "Corp SAML",
            idpEntityId = "https://idp.corp.example.com",
            idpSsoUrl = "https://idp.corp.example.com/sso",
            idpX509Cert = "-----BEGIN CERTIFICATE-----\nMII...\n-----END CERTIFICATE-----",
            authnProviderId = providerId,
            enabled = true,
        )

    private val provisionedAccount =
        ExternalAccount(
            id = UUID.fromString("d1b2c3d4-0000-4000-8000-000000000004"),
            providerId = providerId,
            externalSubject = nameId,
            userId = userId,
            groups = emptyList(),
            failedAttempts = 0,
            lockedUntil = null,
            lastLoginAt = null,
            createdAt = fixedNow,
            updatedAt = fixedNow,
        )

    private val session =
        Session(
            id = sessionId,
            userId = userId,
            providerId = "saml",
            deviceFingerprint = null,
            ipAddress = "10.0.0.1",
            userAgent = "test-agent",
            createdAt = fixedNow,
            expiresAt = fixedNow.plusSeconds(SESSION_TTL_SECONDS),
            lastSeenAt = fixedNow,
            revokedAt = null,
            revokeReason = null,
        )

    @BeforeEach
    fun setUp() {
        configRepo = mockk()
        autoProvisionService = mockk()
        sessionService = mockk()
        refreshTokenRepository = mockk(relaxed = true)
        jwtIssuer = mockk()

        every { configRepo.findEnabledByRegistrationId(registrationId) } returns idpConfig
        every { autoProvisionService.provision(providerId, any()) } returns provisionedAccount
        every { sessionService.create(userId, any(), any(), any()) } returns session
        every {
            jwtIssuer.issue(userId, sessionId, any(), any())
        } returns accessTokenValue

        handler =
            Saml2AuthenticationSuccessHandler(
                configRepo = configRepo,
                autoProvisionService = autoProvisionService,
                sessionService = sessionService,
                refreshTokenRepository = refreshTokenRepository,
                jwtIssuer = jwtIssuer,
                clock = clock,
            )
    }

    private fun samlAuthentication(): Saml2Authentication {
        val principal = mockk<Saml2AuthenticatedPrincipal>()
        every { principal.name } returns nameId
        every { principal.relyingPartyRegistrationId } returns registrationId
        every { principal.attributes } returns emptyMap()
        return Saml2Authentication(principal, "<saml-response/>", emptyList())
    }

    private fun request(relayState: String?): HttpServletRequest {
        val req = mockk<HttpServletRequest>(relaxed = true)
        every { req.getParameter("RelayState") } returns relayState
        every { req.remoteAddr } returns "10.0.0.1"
        every { req.getHeader("User-Agent") } returns "test-agent"
        return req
    }

    @Test
    fun `registrationId 로 providerId 를 해소해 provision 을 호출한다`() {
        val response = mockk<HttpServletResponse>(relaxed = true)

        handler.onAuthenticationSuccess(request("/projects"), response, samlAuthentication())

        verify(exactly = 1) { configRepo.findEnabledByRegistrationId(registrationId) }
        val attrsSlot = slot<LdapProvisionAttrs>()
        verify(exactly = 1) { autoProvisionService.provision(providerId, capture(attrsSlot)) }
        // nameId 가 externalSubject 로 매핑되어야 한다 (user_external_accounts.external_subject)
        assertThat(attrsSlot.captured.externalSubject).isEqualTo(nameId)
    }

    @Test
    fun `세션 생성 + refresh 토큰 저장 + JWT 발급을 호출한다`() {
        val response = mockk<HttpServletResponse>(relaxed = true)

        handler.onAuthenticationSuccess(request("/projects"), response, samlAuthentication())

        verify(exactly = 1) { sessionService.create(userId, any(), any(), any()) }
        val tokenSlot = slot<RefreshToken>()
        verify(exactly = 1) { refreshTokenRepository.save(capture(tokenSlot)) }
        assertThat(tokenSlot.captured.sessionId).isEqualTo(sessionId)
        // refresh TTL 은 고정 Clock 기준으로 미래여야 한다 (Clock 주입 확인)
        assertThat(tokenSlot.captured.expiresAt).isAfter(fixedNow)
        verify(exactly = 1) { jwtIssuer.issue(userId, sessionId, any(), any()) }
    }

    @Test
    fun `상대경로 RelayState 는 그 경로로 리다이렉트한다`() {
        val response = mockk<HttpServletResponse>(relaxed = true)
        val locationSlot = slot<String>()
        every { response.sendRedirect(capture(locationSlot)) } returns Unit

        handler.onAuthenticationSuccess(request("/projects/PROJ-1"), response, samlAuthentication())

        assertThat(locationSlot.captured).isEqualTo("/projects/PROJ-1")
    }

    @Test
    fun `절대 URL RelayState 는 open-redirect 차단으로 기본 dashboard 로 보낸다`() {
        val response = mockk<HttpServletResponse>(relaxed = true)
        val locationSlot = slot<String>()
        every { response.sendRedirect(capture(locationSlot)) } returns Unit

        handler.onAuthenticationSuccess(
            request("https://evil.example.com/phish"),
            response,
            samlAuthentication(),
        )

        assertThat(locationSlot.captured).isEqualTo(DEFAULT_REDIRECT)
    }

    @Test
    fun `프로토콜 상대 RelayState(슬래시 두개)도 차단한다`() {
        val response = mockk<HttpServletResponse>(relaxed = true)
        val locationSlot = slot<String>()
        every { response.sendRedirect(capture(locationSlot)) } returns Unit

        handler.onAuthenticationSuccess(request("//evil.example.com"), response, samlAuthentication())

        assertThat(locationSlot.captured).isEqualTo(DEFAULT_REDIRECT)
    }

    @Test
    fun `RelayState 가 없으면 기본 dashboard 로 보낸다`() {
        val response = mockk<HttpServletResponse>(relaxed = true)
        val locationSlot = slot<String>()
        every { response.sendRedirect(capture(locationSlot)) } returns Unit

        handler.onAuthenticationSuccess(request(null), response, samlAuthentication())

        assertThat(locationSlot.captured).isEqualTo(DEFAULT_REDIRECT)
    }

    private companion object {
        const val DEFAULT_REDIRECT = "/dashboard"
        const val SESSION_TTL_SECONDS = 1_209_600L
    }
}
