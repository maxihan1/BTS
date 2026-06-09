// OidcAuthenticationSuccessHandler 단위 테스트 — JIT + 세션/JWT 발급 + 복귀경로 화이트리스트 (FR-AU-04)

package com.atlas.bts.identity.provider.oidc

import com.atlas.bts.identity.account.SsoLinkingCallbackProcessor
import com.atlas.bts.identity.account.SsoLinkingIntent
import com.atlas.bts.identity.account.SsoLinkingIntentStore
import com.atlas.bts.identity.jwt.JwtIssuer
import com.atlas.bts.identity.provider.ldap.AutoProvisionService
import com.atlas.bts.identity.provider.ldap.ExternalAccount
import com.atlas.bts.identity.provider.ldap.LdapProvisionAttrs
import com.atlas.bts.identity.session.RefreshToken
import com.atlas.bts.identity.session.RefreshTokenRepository
import com.atlas.bts.identity.session.Session
import com.atlas.bts.identity.session.SessionService
import com.atlas.bts.identity.spi.ProviderType
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.core.oidc.user.OidcUser
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * [OidcAuthenticationSuccessHandler] 단위 테스트 (MockK).
 *
 * 검증 범위 (Saml2AuthenticationSuccessHandlerTest 동형):
 * - [OAuth2AuthenticationToken] → sub/registrationId 추출
 * - registrationId → authn_provider_id 해소
 * - [AutoProvisionService.provision] JIT 호출 (sub 가 externalSubject 로)
 * - 세션/JWT 발급 컴포넌트 호출 (SessionService.create / RefreshTokenRepository.save / JwtIssuer.issue)
 * - 복귀 경로 open-redirect 차단 (N5 — 상대경로 화이트리스트만)
 *
 * 시각 의존(refresh TTL)은 [Clock.fixed] 로 고정 (time-bomb 회귀 방지).
 */
class OidcSuccessHandlerTest {
    private lateinit var configRepo: OidcProviderConfigReader
    private lateinit var autoProvisionService: AutoProvisionService
    private lateinit var sessionService: SessionService
    private lateinit var refreshTokenRepository: RefreshTokenRepository
    private lateinit var jwtIssuer: JwtIssuer
    private lateinit var callbackProcessor: SsoLinkingCallbackProcessor
    private lateinit var intentStore: SsoLinkingIntentStore
    private lateinit var handler: OidcAuthenticationSuccessHandler

    private val fixedNow = Instant.parse("2026-06-01T10:00:00Z")
    private val clock = Clock.fixed(fixedNow, ZoneOffset.UTC)

    private val registrationId = "corp-oidc"
    private val sub = "oidc|alice-subject-123"
    private val email = "alice@corp.example.com"
    private val displayName = "Alice Kim"
    private val providerId = UUID.fromString("00000000-0000-4a04-8000-000000000004")
    private val userId = UUID.fromString("a1b2c3d4-0000-4000-8000-000000000001")
    private val sessionId = UUID.fromString("b1b2c3d4-0000-4000-8000-000000000002")
    private val accessTokenValue = "header.payload.sig"

    private val oidcConfig =
        OidcProviderConfig(
            id = UUID.fromString("c1b2c3d4-0000-4000-8000-000000000003"),
            registrationId = registrationId,
            displayName = "Corp OIDC",
            issuerUri = "https://idp.corp.example.com",
            clientId = "bts-client",
            clientSecretEncrypted = "enc:...",
            scopes = "openid,profile,email",
            authnProviderId = providerId,
            enabled = true,
        )

    private val provisionedAccount =
        ExternalAccount(
            id = UUID.fromString("d1b2c3d4-0000-4000-8000-000000000004"),
            providerId = providerId,
            externalSubject = sub,
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
            providerId = "oidc",
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
        callbackProcessor = mockk()
        // 실 store — C3 비활성 경로의 명시 consume(intent 제거)을 실증한다(고정 Clock).
        intentStore = SsoLinkingIntentStore(clock)

        every { configRepo.findByRegistrationId(registrationId) } returns oidcConfig
        // 연결 모드 enabled 재해소(B1/EC16) — 기본은 활성. 비활성 테스트가 개별로 override 한다.
        every { configRepo.findEnabledByRegistrationId(registrationId) } returns oidcConfig
        every { autoProvisionService.provision(providerId, any()) } returns provisionedAccount
        every { sessionService.create(userId, any(), any(), any()) } returns session
        every {
            jwtIssuer.issue(userId, sessionId, any(), any())
        } returns accessTokenValue

        handler =
            OidcAuthenticationSuccessHandler(
                configRepo = configRepo,
                autoProvisionService = autoProvisionService,
                sessionService = sessionService,
                refreshTokenRepository = refreshTokenRepository,
                jwtIssuer = jwtIssuer,
                callbackProcessor = callbackProcessor,
                intentStore = intentStore,
                clock = clock,
            )
    }

    private fun oidcAuthentication(): OAuth2AuthenticationToken {
        val principal = mockk<OidcUser>()
        every { principal.subject } returns sub
        every { principal.email } returns email
        every { principal.fullName } returns displayName
        every { principal.preferredUsername } returns email
        every { principal.name } returns sub
        val token = mockk<OAuth2AuthenticationToken>()
        every { token.principal } returns principal
        every { token.authorizedClientRegistrationId } returns registrationId
        return token
    }

    private fun request(returnPath: String?): HttpServletRequest {
        val req = mockk<HttpServletRequest>(relaxed = true)
        every { req.getParameter(RETURN_PARAM) } returns returnPath
        every { req.remoteAddr } returns "10.0.0.1"
        every { req.getHeader("User-Agent") } returns "test-agent"
        return req
    }

    @Test
    fun `registrationId 로 providerId 를 해소해 provision 을 호출한다`() {
        val response = mockk<HttpServletResponse>(relaxed = true)

        handler.onAuthenticationSuccess(request("/projects"), response, oidcAuthentication())

        verify(exactly = 1) { configRepo.findByRegistrationId(registrationId) }
        val attrsSlot = slot<LdapProvisionAttrs>()
        verify(exactly = 1) { autoProvisionService.provision(providerId, capture(attrsSlot)) }
        // sub 가 externalSubject 로 매핑되어야 한다 (user_external_accounts.external_subject)
        assertThat(attrsSlot.captured.externalSubject).isEqualTo(sub)
        assertThat(attrsSlot.captured.email).isEqualTo(email)
        assertThat(attrsSlot.captured.displayName).isEqualTo(displayName)
    }

    @Test
    fun `세션 생성 + refresh 토큰 저장 + JWT 발급을 호출한다`() {
        val response = mockk<HttpServletResponse>(relaxed = true)

        handler.onAuthenticationSuccess(request("/projects"), response, oidcAuthentication())

        verify(exactly = 1) { sessionService.create(userId, any(), any(), any()) }
        val tokenSlot = slot<RefreshToken>()
        verify(exactly = 1) { refreshTokenRepository.save(capture(tokenSlot)) }
        assertThat(tokenSlot.captured.sessionId).isEqualTo(sessionId)
        // refresh TTL 은 고정 Clock 기준으로 미래여야 한다 (Clock 주입 확인)
        assertThat(tokenSlot.captured.expiresAt).isAfter(fixedNow)
        verify(exactly = 1) { jwtIssuer.issue(userId, sessionId, any(), any()) }
    }

    @Test
    fun `상대경로 복귀 파라미터는 그 경로로 리다이렉트한다`() {
        val response = mockk<HttpServletResponse>(relaxed = true)
        val locationSlot = slot<String>()
        every { response.sendRedirect(capture(locationSlot)) } returns Unit

        handler.onAuthenticationSuccess(request("/projects/PROJ-1"), response, oidcAuthentication())

        assertThat(locationSlot.captured).isEqualTo("/projects/PROJ-1")
    }

    @Test
    fun `절대 URL 복귀 파라미터는 open-redirect 차단으로 기본 dashboard 로 보낸다`() {
        val response = mockk<HttpServletResponse>(relaxed = true)
        val locationSlot = slot<String>()
        every { response.sendRedirect(capture(locationSlot)) } returns Unit

        handler.onAuthenticationSuccess(
            request("https://evil.example.com/phish"),
            response,
            oidcAuthentication(),
        )

        assertThat(locationSlot.captured).isEqualTo(DEFAULT_REDIRECT)
    }

    @Test
    fun `프로토콜 상대 복귀 파라미터(슬래시 두개)도 차단한다`() {
        val response = mockk<HttpServletResponse>(relaxed = true)
        val locationSlot = slot<String>()
        every { response.sendRedirect(capture(locationSlot)) } returns Unit

        handler.onAuthenticationSuccess(request("//evil.example.com"), response, oidcAuthentication())

        assertThat(locationSlot.captured).isEqualTo(DEFAULT_REDIRECT)
    }

    @Test
    fun `백슬래시 우회 복귀 파라미터(슬래시 백슬래시)도 차단한다`() {
        val response = mockk<HttpServletResponse>(relaxed = true)
        val locationSlot = slot<String>()
        every { response.sendRedirect(capture(locationSlot)) } returns Unit

        handler.onAuthenticationSuccess(request("/\\evil.example.com"), response, oidcAuthentication())

        assertThat(locationSlot.captured).isEqualTo(DEFAULT_REDIRECT)
    }

    @Test
    fun `복귀 파라미터가 없으면 기본 dashboard 로 보낸다`() {
        val response = mockk<HttpServletResponse>(relaxed = true)
        val locationSlot = slot<String>()
        every { response.sendRedirect(capture(locationSlot)) } returns Unit

        handler.onAuthenticationSuccess(request(null), response, oidcAuthentication())

        assertThat(locationSlot.captured).isEqualTo(DEFAULT_REDIRECT)
    }

    // --- FR-AU-08b 연결 모드 분기 (fail-closed, EC1/EC12/EC16) -----------------------

    /**
     * 세션에 SSO 연결 인텐트가 심어진 [MockHttpServletRequest] 를 만든다(start XHR 가 만든 세션 재사용 모사).
     * 핸들러는 `getSession(false)` 로 이 세션을 보고 비소비 peek 한다.
     */
    private fun requestWithIntent(intent: SsoLinkingIntent): HttpServletRequest {
        val req = MockHttpServletRequest("GET", "/login/oauth2/code/$registrationId")
        req.remoteAddr = "10.0.0.1"
        req.addHeader("User-Agent", "test-agent")
        requireNotNull(req.getSession(true)).setAttribute(SsoLinkingIntentStore.ATTRIBUTE_KEY, intent)
        return req
    }

    private fun linkIntent(reg: String = registrationId): SsoLinkingIntent =
        SsoLinkingIntent(
            mode = SsoLinkingIntent.Mode.LINK,
            userId = userId,
            sid = null,
            registrationId = reg,
            providerType = ProviderType.OIDC,
            expiresAt = fixedNow.plusSeconds(INTENT_TTL_SECONDS),
        )

    @Test
    fun `연결 의도가 있으면 processor 로 위임하고 세션 JWT provision 을 발급하지 않는다 (fail-closed EC12)`() {
        val response = mockk<HttpServletResponse>(relaxed = true)
        every {
            callbackProcessor.process(any(), ProviderType.OIDC, registrationId, providerId, sub, emptyList(), response)
        } returns true

        handler.onAuthenticationSuccess(requestWithIntent(linkIntent()), response, oidcAuthentication())

        verify(exactly = 1) {
            callbackProcessor.process(any(), ProviderType.OIDC, registrationId, providerId, sub, emptyList(), response)
        }
        // enabled 재해소 경로(B1)로만 providerId 를 얻어야 한다(start↔콜백 TOCTOU 차단).
        verify(exactly = 1) { configRepo.findEnabledByRegistrationId(registrationId) }
        // fail-closed — 발급 경로 물리적 진입 불가
        verify(exactly = 0) { autoProvisionService.provision(any(), any()) }
        verify(exactly = 0) { sessionService.create(any(), any(), any(), any()) }
        verify(exactly = 0) { refreshTokenRepository.save(any()) }
        verify(exactly = 0) { jwtIssuer.issue(any(), any(), any(), any()) }
    }

    @Test
    fun `연결 의도가 있고 provider 가 비활성이면 link error 로 보내고 발급하지 않는다 (B1 EC16)`() {
        // OIDC findByRegistrationId 비대칭 보정 — 콜백 enabled 재해소가 비활성을 거른다.
        every { configRepo.findEnabledByRegistrationId(registrationId) } returns null
        val response = mockk<HttpServletResponse>(relaxed = true)
        val locationSlot = slot<String>()
        every { response.sendRedirect(capture(locationSlot)) } returns Unit

        handler.onAuthenticationSuccess(requestWithIntent(linkIntent()), response, oidcAuthentication())

        assertThat(locationSlot.captured).isEqualTo("$SETTINGS_PATH?link=error")
        verify(exactly = 0) {
            callbackProcessor.process(any(), any(), any(), any(), any(), any(), any())
        }
        verify(exactly = 0) { autoProvisionService.provision(any(), any()) }
        verify(exactly = 0) { sessionService.create(any(), any(), any(), any()) }
        verify(exactly = 0) { jwtIssuer.issue(any(), any(), any(), any()) }
    }

    @Test
    fun `연결 의도가 있고 provider 가 비활성이면 intent 를 세션에서 제거한다 (C3 1회용 대칭)`() {
        // processor 를 안 타는 비활성 경로(EC16)에서도 intent 가 세션에 잔류하지 않아야 한다(1회용 계약 대칭, SAML 동형).
        every { configRepo.findEnabledByRegistrationId(registrationId) } returns null
        val request = requestWithIntent(linkIntent())
        val response = mockk<HttpServletResponse>(relaxed = true)
        every { response.sendRedirect(any()) } returns Unit

        handler.onAuthenticationSuccess(request, response, oidcAuthentication())

        // 처리 후 세션에서 intent 속성이 제거돼야 한다(재소비 차단).
        assertThat(request.getSession(false)?.getAttribute(SsoLinkingIntentStore.ATTRIBUTE_KEY)).isNull()
        // fail-closed 유지 — 발급 0.
        verify(exactly = 0) { autoProvisionService.provision(any(), any()) }
        verify(exactly = 0) { sessionService.create(any(), any(), any(), any()) }
        verify(exactly = 0) { jwtIssuer.issue(any(), any(), any(), any()) }
    }

    @Test
    fun `재인증 의도가 있고 provider 가 비활성이면 reauth failed 로 보내고 발급하지 않는다 (B1 EC16)`() {
        every { configRepo.findEnabledByRegistrationId(registrationId) } returns null
        val reauthIntent =
            SsoLinkingIntent(
                mode = SsoLinkingIntent.Mode.REAUTH,
                userId = userId,
                sid = sessionId,
                registrationId = registrationId,
                providerType = ProviderType.OIDC,
                expiresAt = fixedNow.plusSeconds(INTENT_TTL_SECONDS),
            )
        val response = mockk<HttpServletResponse>(relaxed = true)
        val locationSlot = slot<String>()
        every { response.sendRedirect(capture(locationSlot)) } returns Unit

        handler.onAuthenticationSuccess(requestWithIntent(reauthIntent), response, oidcAuthentication())

        assertThat(locationSlot.captured).isEqualTo("$SETTINGS_PATH?reauth=failed")
        verify(exactly = 0) { jwtIssuer.issue(any(), any(), any(), any()) }
    }

    @Test
    fun `연결 의도가 있지만 만료돼 processor 가 위임 거부하면 발급하지 않고 error 로 보낸다 (EC5 fail-closed)`() {
        val response = mockk<HttpServletResponse>(relaxed = true)
        val locationSlot = slot<String>()
        every { response.sendRedirect(capture(locationSlot)) } returns Unit
        every {
            callbackProcessor.process(any(), ProviderType.OIDC, registrationId, providerId, sub, emptyList(), response)
        } returns false

        handler.onAuthenticationSuccess(requestWithIntent(linkIntent()), response, oidcAuthentication())

        assertThat(locationSlot.captured).isEqualTo("$SETTINGS_PATH?link=error")
        verify(exactly = 0) { autoProvisionService.provision(any(), any()) }
        verify(exactly = 0) { sessionService.create(any(), any(), any(), any()) }
        verify(exactly = 0) { jwtIssuer.issue(any(), any(), any(), any()) }
    }

    @Test
    fun `연결 의도가 없으면 기존 JIT 로그인 흐름이 무변경이다 (EC1 회귀)`() {
        val req = MockHttpServletRequest("GET", "/login/oauth2/code/$registrationId")
        req.remoteAddr = "10.0.0.1"
        req.addHeader("User-Agent", "test-agent")
        req.getSession(true)
        val response = mockk<HttpServletResponse>(relaxed = true)
        val locationSlot = slot<String>()
        every { response.sendRedirect(capture(locationSlot)) } returns Unit

        handler.onAuthenticationSuccess(req, response, oidcAuthentication())

        // 일반 로그인은 기존 findByRegistrationId(enabled 무관) 경로 그대로.
        verify(exactly = 1) { configRepo.findByRegistrationId(registrationId) }
        verify(exactly = 1) { autoProvisionService.provision(providerId, any()) }
        verify(exactly = 1) { sessionService.create(userId, any(), any(), any()) }
        verify(exactly = 1) { jwtIssuer.issue(userId, sessionId, any(), any()) }
        verify(exactly = 0) { callbackProcessor.process(any(), any(), any(), any(), any(), any(), any()) }
        assertThat(locationSlot.captured).isEqualTo(DEFAULT_REDIRECT)
    }

    private companion object {
        const val DEFAULT_REDIRECT = "/dashboard"
        const val SESSION_TTL_SECONDS = 1_209_600L
        const val RETURN_PARAM = "returnTo"
        const val INTENT_TTL_SECONDS = 300L
        const val SETTINGS_PATH = "/settings/account-links"
    }
}
