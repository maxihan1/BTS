// LinkableProvidersController 슬라이스 테스트 — 연결 가능 provider 통합 목록 엔드포인트 (FR-AU-08 D6 Task 2)

package com.atlas.bts.identity.web

import com.atlas.bts.identity.config.CorsConfig
import com.atlas.bts.identity.config.SecurityConfig
import com.atlas.bts.identity.jwt.SidRevokeJwtConverter
import com.atlas.bts.identity.pat.PersonalAccessTokenService
import com.atlas.bts.identity.provider.AuthnProviderConfigRepository
import com.atlas.bts.identity.provider.AuthnProviderInfo
import com.atlas.bts.identity.provider.oidc.OidcProviderConfig
import com.atlas.bts.identity.provider.oidc.OidcProviderConfigRepository
import com.atlas.bts.identity.provider.saml.SamlIdpConfig
import com.atlas.bts.identity.provider.saml.SamlIdpConfigRepository
import com.atlas.bts.identity.session.SessionService
import com.atlas.bts.identity.spi.ProviderType
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.cors.CorsConfigurationSource
import java.time.Clock
import java.util.UUID

/**
 * LinkableProvidersController WebMvcTest 슬라이스 테스트 (FR-AU-08 D6 Task 2).
 *
 * ## 검증 시나리오
 * - GET /linkable-providers 200 — LDAP/SAML/OIDC 통합 목록. LDAP 은 providerId(UUID)+displayName 이고
 *   registrationId 키 부재, SAML/OIDC 는 registrationId+displayName 이고 providerId 키 부재.
 * - GET /linkable-providers PAT 403 — Jwt 아님(=null) → account_linking_requires_interactive_login.
 * - GET /linkable-providers 빈 결과 200 — 세 source 모두 비면 `{"linkable":[]}`.
 *
 * ## 의존성 모킹 전략 (AccountLinkControllerTest 선례와 일관)
 * - SecurityConfig 필수 Bean(JwtDecoder/Clock/SidRevokeJwtConverter/CorsConfigurationSource/PAT/AccountLinkJwtSupport):
 *   @TestConfiguration + MockK/실객체.
 * - 컨트롤러 의존 repository(AuthnProviderConfigRepository/SamlIdpConfigRepository/OidcProviderConfigRepository):
 *   @MockBean(Mockito) — companion object 포함 클래스를 Spring @Bean 으로 MockK 등록 시 ByteBuddy $Companion 로드 실패 회피.
 * - OIDC 는 인터페이스가 아닌 **구현체 [OidcProviderConfigRepository]** 로 주입한다(findEnabled() 는 구현체에만 존재).
 */
@WebMvcTest(
    controllers = [LinkableProvidersController::class],
    excludeAutoConfiguration = [OAuth2ClientAutoConfiguration::class],
)
@Import(SecurityConfig::class, LinkableProvidersControllerTest.SecurityBeans::class)
class LinkableProvidersControllerTest {
    @TestConfiguration
    class SecurityBeans {
        @Bean
        fun jwtDecoder(): JwtDecoder = mockk(relaxed = true)

        @Bean
        fun clock(): Clock = Clock.systemUTC()

        @Bean
        fun sidRevokeJwtConverter(clock: Clock): SidRevokeJwtConverter {
            val sessionService: SessionService = mockk(relaxed = true)
            return SidRevokeJwtConverter(sessionService, clock)
        }

        @Bean
        fun corsConfigurationSource(): CorsConfigurationSource = CorsConfig().corsConfigurationSource(listOf(ORIGIN))

        @Bean
        fun personalAccessTokenService(): PersonalAccessTokenService = mockk(relaxed = true)

        /**
         * 공통 인증 헬퍼([AccountLinkJwtSupport]) 를 실 객체로 등록한다.
         *
         * @WebMvcTest 슬라이스는 컴포넌트 스캔을 하지 않으므로 @Component 인 헬퍼가 자동 등록되지 않는다.
         * step-up 게이팅은 linkable-providers 에서 쓰지 않으므로 relaxed mock StepUpService 로 충분하다.
         */
        @Bean
        fun accountLinkJwtSupport(): AccountLinkJwtSupport = AccountLinkJwtSupport(mockk(relaxed = true))

        private companion object {
            /** CORS 허용 origin — SPA dev 서버. */
            const val ORIGIN = "http://localhost:5173"
        }
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockBean
    lateinit var authnProviderConfigRepository: AuthnProviderConfigRepository

    @MockBean
    lateinit var samlIdpConfigRepository: SamlIdpConfigRepository

    @MockBean
    lateinit var oidcProviderConfigRepository: OidcProviderConfigRepository

    private val userId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val ldapProviderId = UUID.fromString("22222222-2222-2222-2222-222222222222")

    /** userId(subject) 를 가진 JWT principal 포스트 프로세서. */
    private fun jwtPrincipal() =
        jwt().jwt { builder ->
            builder.subject(userId.toString())
        }

    // ── GET /linkable-providers 200 — 통합 목록 ──────────────────────────────────

    @Test
    fun `GET linkable-providers returns 200 with unified LDAP SAML OIDC list`() {
        `when`(authnProviderConfigRepository.findEnabledByType(ProviderType.LDAP)).thenReturn(
            listOf(
                AuthnProviderInfo(
                    id = ldapProviderId,
                    name = "Corp LDAP",
                    type = ProviderType.LDAP,
                    enabled = true,
                ),
            ),
        )
        `when`(samlIdpConfigRepository.findAllEnabled()).thenReturn(
            listOf(samlConfig(registrationId = "okta", displayName = "Okta SAML")),
        )
        `when`(oidcProviderConfigRepository.findEnabled()).thenReturn(
            listOf(oidcConfig(registrationId = "google", displayName = "Google OIDC")),
        )

        mockMvc.perform(get("/api/v1/auth/account/linkable-providers").with(jwtPrincipal()))
            .andExpect(status().isOk)
            // LDAP — kind=LDAP, providerId(UUID)+displayName, registrationId 키 부재
            .andExpect(jsonPath("$.linkable[0].kind").value("LDAP"))
            .andExpect(jsonPath("$.linkable[0].providerId").value(ldapProviderId.toString()))
            .andExpect(jsonPath("$.linkable[0].displayName").value("Corp LDAP"))
            .andExpect(jsonPath("$.linkable[0].registrationId").doesNotExist())
            // SAML — kind=SAML, registrationId+displayName, providerId 키 부재
            .andExpect(jsonPath("$.linkable[1].kind").value("SAML"))
            .andExpect(jsonPath("$.linkable[1].registrationId").value("okta"))
            .andExpect(jsonPath("$.linkable[1].displayName").value("Okta SAML"))
            .andExpect(jsonPath("$.linkable[1].providerId").doesNotExist())
            // OIDC — kind=OIDC, registrationId+displayName, providerId 키 부재
            .andExpect(jsonPath("$.linkable[2].kind").value("OIDC"))
            .andExpect(jsonPath("$.linkable[2].registrationId").value("google"))
            .andExpect(jsonPath("$.linkable[2].displayName").value("Google OIDC"))
            .andExpect(jsonPath("$.linkable[2].providerId").doesNotExist())
    }

    @Test
    fun `GET linkable-providers with PAT returns 403`() {
        mockMvc.perform(get("/api/v1/auth/account/linkable-providers").with(user("pat-user-id")))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error").value("account_linking_requires_interactive_login"))
    }

    @Test
    fun `GET linkable-providers with all sources empty returns 200 with empty list`() {
        `when`(authnProviderConfigRepository.findEnabledByType(ProviderType.LDAP)).thenReturn(emptyList())
        `when`(samlIdpConfigRepository.findAllEnabled()).thenReturn(emptyList())
        `when`(oidcProviderConfigRepository.findEnabled()).thenReturn(emptyList())

        mockMvc.perform(get("/api/v1/auth/account/linkable-providers").with(jwtPrincipal()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.linkable").isArray)
            .andExpect(jsonPath("$.linkable").isEmpty)
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun samlConfig(
        registrationId: String,
        displayName: String,
    ): SamlIdpConfig =
        SamlIdpConfig(
            id = UUID.randomUUID(),
            registrationId = registrationId,
            displayName = displayName,
            idpEntityId = "entity",
            idpSsoUrl = "https://idp.example.com/sso",
            idpX509Cert = "cert",
            authnProviderId = UUID.randomUUID(),
            enabled = true,
        )

    private fun oidcConfig(
        registrationId: String,
        displayName: String,
    ): OidcProviderConfig =
        OidcProviderConfig(
            id = UUID.randomUUID(),
            registrationId = registrationId,
            displayName = displayName,
            issuerUri = "https://idp.example.com",
            clientId = "client",
            clientSecretEncrypted = "enc",
            scopes = "openid,profile,email",
            authnProviderId = UUID.randomUUID(),
            enabled = true,
        )
}
