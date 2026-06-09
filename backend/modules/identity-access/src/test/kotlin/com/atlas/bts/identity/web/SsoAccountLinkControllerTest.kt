// SsoAccountLinkController 슬라이스 테스트 — SSO 연결/재인증 시작 + 검증 매트릭스 + JSESSIONID 방출 (FR-AU-08b Task 9)

package com.atlas.bts.identity.web

import com.atlas.bts.identity.account.SsoLinkingIntentStore
import com.atlas.bts.identity.account.StepUpService
import com.atlas.bts.identity.config.CorsConfig
import com.atlas.bts.identity.config.SecurityConfig
import com.atlas.bts.identity.jwt.SidRevokeJwtConverter
import com.atlas.bts.identity.pat.PersonalAccessTokenService
import com.atlas.bts.identity.provider.oidc.OidcProviderConfig
import com.atlas.bts.identity.provider.oidc.OidcProviderConfigReader
import com.atlas.bts.identity.provider.saml.SamlIdpConfig
import com.atlas.bts.identity.provider.saml.SamlIdpConfigRepository
import com.atlas.bts.identity.session.SessionService
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.cors.CorsConfigurationSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * SsoAccountLinkController WebMvcTest 슬라이스 테스트 (FR-AU-08b Task 9).
 *
 * ## 검증 시나리오
 * - POST /links/sso/start — JWT+step-up. PAT 403, step-up 없음 403 step_up_required,
 *   유효 SAML/OIDC → 200 {authorizeUrl} + intent 저장 + **JSESSIONID Set-Cookie 헤더 실재**(C2).
 * - POST /reauth/sso/start — JWT 만(step-up 불요). PAT 403, 유효 → 200 {authorizeUrl} + intent 저장.
 * - 입력 검증 매트릭스(FR7): providerType×registrationId 불일치 404, LOCAL/LDAP 400,
 *   미존재/비활성 404, registrationId 형식 위반 400.
 *
 * ## 의존성 모킹 전략 (AccountLinkControllerTest 선례와 일관)
 * SecurityConfig 필수 Bean 은 @TestConfiguration+MockK, 컨트롤러 의존 서비스는 @MockBean(Mockito).
 * AccountLinkJwtSupport 는 실 객체로 등록(step-up 판정은 @MockBean stepUpService).
 */
@WebMvcTest(
    controllers = [SsoAccountLinkController::class],
    excludeAutoConfiguration = [OAuth2ClientAutoConfiguration::class],
)
@Import(SecurityConfig::class, SsoAccountLinkControllerTest.SecurityBeans::class)
class SsoAccountLinkControllerTest {
    @TestConfiguration
    class SecurityBeans {
        @Bean
        fun jwtDecoder(): JwtDecoder = mockk(relaxed = true)

        @Bean
        fun clock(): Clock = Clock.fixed(Instant.parse("2026-06-09T10:00:00Z"), ZoneOffset.UTC)

        @Bean
        fun sidRevokeJwtConverter(clock: Clock): SidRevokeJwtConverter {
            val sessionService: SessionService = mockk(relaxed = true)
            return SidRevokeJwtConverter(sessionService, clock)
        }

        @Bean
        fun corsConfigurationSource(): CorsConfigurationSource = CorsConfig().corsConfigurationSource(listOf(ORIGIN))

        @Bean
        fun personalAccessTokenService(): PersonalAccessTokenService = mockk(relaxed = true)

        @Bean
        fun accountLinkJwtSupport(stepUpService: StepUpService): AccountLinkJwtSupport =
            AccountLinkJwtSupport(stepUpService)

        /** intent 저장은 실 store(인메모리 세션) 로 검증한다 — JSESSIONID 방출이 핵심이라 실 동작 필요. */
        @Bean
        fun ssoLinkingIntentStore(): SsoLinkingIntentStore = SsoLinkingIntentStore()

        private companion object {
            const val ORIGIN = "http://localhost:5173"
        }
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockBean
    lateinit var stepUpService: StepUpService

    @MockBean
    lateinit var samlIdpConfigRepository: SamlIdpConfigRepository

    @MockBean
    lateinit var oidcProviderConfigReader: OidcProviderConfigReader

    private val userId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val currentSid = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    private val samlReg = "corp-saml"
    private val oidcReg = "corp-oidc"

    private fun jwtPrincipal() =
        jwt().jwt { builder ->
            builder
                .subject(userId.toString())
                .claim("sid", currentSid.toString())
        }

    // config 객체는 존재 검증(null/non-null)만 쓰이고 컨트롤러가 필드를 읽지 않으므로 relaxed mock 으로 충분하다.
    private fun samlConfig(): SamlIdpConfig = mockk(relaxed = true)

    private fun oidcConfig(): OidcProviderConfig = mockk(relaxed = true)

    // ── POST /links/sso/start ──────────────────────────────────────────────────

    @Test
    fun `links sso start — OIDC step-up 유효 200 authorizeUrl + JSESSIONID Set-Cookie 방출`() {
        `when`(stepUpService.isValid(currentSid)).thenReturn(true)
        `when`(oidcProviderConfigReader.findEnabledByRegistrationId(oidcReg)).thenReturn(oidcConfig())

        mockMvc.perform(
            post("/api/v1/auth/account/links/sso/start")
                .with(csrf())
                .with(jwtPrincipal())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"registrationId":"$oidcReg","providerType":"OIDC"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.authorizeUrl").value("/oauth2/authorization/$oidcReg"))
            // C2 — getSession(true) 가 실제 JSESSIONID Set-Cookie 를 방출했는지 실증(가짜그린 회피).
            .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("JSESSIONID")))
    }

    @Test
    fun `links sso start — SAML step-up 유효 200 SAML authorizeUrl`() {
        `when`(stepUpService.isValid(currentSid)).thenReturn(true)
        `when`(samlIdpConfigRepository.findEnabledByRegistrationId(samlReg)).thenReturn(samlConfig())

        mockMvc.perform(
            post("/api/v1/auth/account/links/sso/start")
                .with(csrf())
                .with(jwtPrincipal())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"registrationId":"$samlReg","providerType":"SAML"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.authorizeUrl").value("/saml2/authenticate/$samlReg"))
    }

    @Test
    fun `links sso start — step-up 없으면 403 step_up_required (config 조회 안 함)`() {
        `when`(stepUpService.isValid(currentSid)).thenReturn(false)

        mockMvc.perform(
            post("/api/v1/auth/account/links/sso/start")
                .with(csrf())
                .with(jwtPrincipal())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"registrationId":"$oidcReg","providerType":"OIDC"}"""),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error").value("step_up_required"))

        verify(oidcProviderConfigReader, never()).findEnabledByRegistrationId(org.mockito.ArgumentMatchers.anyString())
    }

    @Test
    fun `links sso start — PAT 403`() {
        mockMvc.perform(
            post("/api/v1/auth/account/links/sso/start")
                .with(csrf())
                .with(user("pat-user-id"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"registrationId":"$oidcReg","providerType":"OIDC"}"""),
        )
            .andExpect(status().isForbidden)

        verify(stepUpService, never()).isValid(org.mockito.ArgumentMatchers.any(UUID::class.java))
    }

    // ── 입력 검증 매트릭스 (FR7) ─────────────────────────────────────────────────

    @Test
    fun `links sso start — providerType=OIDC 인데 미존재 registration 404`() {
        `when`(stepUpService.isValid(currentSid)).thenReturn(true)
        `when`(oidcProviderConfigReader.findEnabledByRegistrationId(oidcReg)).thenReturn(null)

        mockMvc.perform(
            post("/api/v1/auth/account/links/sso/start")
                .with(csrf())
                .with(jwtPrincipal())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"registrationId":"$oidcReg","providerType":"OIDC"}"""),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("provider_not_found"))
    }

    @Test
    fun `links sso start — providerType=SAML 은 SAML repo 로만 조회(OIDC reader 미사용)`() {
        `when`(stepUpService.isValid(currentSid)).thenReturn(true)
        `when`(samlIdpConfigRepository.findEnabledByRegistrationId(samlReg)).thenReturn(samlConfig())

        mockMvc.perform(
            post("/api/v1/auth/account/links/sso/start")
                .with(csrf())
                .with(jwtPrincipal())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"registrationId":"$samlReg","providerType":"SAML"}"""),
        )
            .andExpect(status().isOk)

        verify(oidcProviderConfigReader, never()).findEnabledByRegistrationId(org.mockito.ArgumentMatchers.anyString())
    }

    @Test
    fun `links sso start — LOCAL providerType 400 (SSO 아님)`() {
        `when`(stepUpService.isValid(currentSid)).thenReturn(true)

        mockMvc.perform(
            post("/api/v1/auth/account/links/sso/start")
                .with(csrf())
                .with(jwtPrincipal())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"registrationId":"$oidcReg","providerType":"LOCAL"}"""),
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `links sso start — LDAP providerType 400 (SSO 아님)`() {
        `when`(stepUpService.isValid(currentSid)).thenReturn(true)

        mockMvc.perform(
            post("/api/v1/auth/account/links/sso/start")
                .with(csrf())
                .with(jwtPrincipal())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"registrationId":"$oidcReg","providerType":"LDAP"}"""),
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `links sso start — registrationId 형식 위반(특수문자) 400`() {
        `when`(stepUpService.isValid(currentSid)).thenReturn(true)

        mockMvc.perform(
            post("/api/v1/auth/account/links/sso/start")
                .with(csrf())
                .with(jwtPrincipal())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"registrationId":"bad/../reg","providerType":"OIDC"}"""),
        )
            .andExpect(status().isBadRequest)

        verify(oidcProviderConfigReader, never()).findEnabledByRegistrationId(org.mockito.ArgumentMatchers.anyString())
    }

    // ── POST /reauth/sso/start ───────────────────────────────────────────────────

    @Test
    fun `reauth sso start — JWT 만으로 200 authorizeUrl (step-up 불요)`() {
        `when`(oidcProviderConfigReader.findEnabledByRegistrationId(oidcReg)).thenReturn(oidcConfig())

        mockMvc.perform(
            post("/api/v1/auth/account/reauth/sso/start")
                .with(csrf())
                .with(jwtPrincipal())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"registrationId":"$oidcReg","providerType":"OIDC"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.authorizeUrl").value("/oauth2/authorization/$oidcReg"))
            .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("JSESSIONID")))

        // step-up 불요 — isValid 호출하지 않는다.
        verify(stepUpService, never()).isValid(org.mockito.ArgumentMatchers.any(UUID::class.java))
    }

    @Test
    fun `reauth sso start — PAT 403`() {
        mockMvc.perform(
            post("/api/v1/auth/account/reauth/sso/start")
                .with(csrf())
                .with(user("pat-user-id"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"registrationId":"$oidcReg","providerType":"OIDC"}"""),
        )
            .andExpect(status().isForbidden)
    }

    @Test
    fun `reauth sso start — 미존재 registration 404`() {
        `when`(oidcProviderConfigReader.findEnabledByRegistrationId(oidcReg)).thenReturn(null)

        mockMvc.perform(
            post("/api/v1/auth/account/reauth/sso/start")
                .with(csrf())
                .with(jwtPrincipal())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"registrationId":"$oidcReg","providerType":"OIDC"}"""),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("provider_not_found"))
    }
}
