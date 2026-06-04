// OidcProviderController 슬라이스 테스트 — FR-AU-04 활성 OIDC IdP 목록 조회 (민감정보 미노출)

package com.atlas.bts.identity.web

import com.atlas.bts.identity.config.CorsConfig
import com.atlas.bts.identity.config.SecurityConfig
import com.atlas.bts.identity.jwt.SidRevokeJwtConverter
import com.atlas.bts.identity.pat.PersonalAccessTokenService
import com.atlas.bts.identity.provider.oidc.OidcProviderConfig
import com.atlas.bts.identity.provider.oidc.OidcProviderConfigRepository
import com.atlas.bts.identity.session.SessionService
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.cors.CorsConfigurationSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * OidcProviderController 슬라이스 테스트 (FR-AU-04 / Task 6).
 *
 * 검증 항목.
 * - (a) 활성 IdP 목록을 registrationId / displayName 만으로 반환한다.
 * - (b) 컨트롤러는 [OidcProviderConfigRepository.findEnabled] 결과만 매핑한다(비활성 제외는 repo 책임 — repo 통합테스트가 보강).
 * - (c) 응답에 issuerUri/clientId/clientSecret/scopes/authnProviderId/enabled 등 민감·내부 정보가 노출되지 않는다.
 *
 * permitAll(인증 없이 200) 검증은 Task 5(SecurityConfig)에서 다룬다 — 본 슬라이스는 컨트롤러 로직에 집중한다.
 * `/api/v1/auth/oidc/providers` 는 아직 permitAll 미등록(Task 5)이므로, 컨트롤러 로직을 노출하기 위해
 * 각 요청에 jwt() post-processor 로 인증 컨텍스트를 부여한다(401 회피, SamlIdpControllerTest 규약).
 */
@WebMvcTest(
    controllers = [OidcProviderController::class],
    excludeAutoConfiguration = [OAuth2ClientAutoConfiguration::class],
)
@Import(
    SecurityConfig::class,
    OidcProviderControllerTest.MockBeans::class,
)
class OidcProviderControllerTest {
    @TestConfiguration
    class MockBeans {
        @Bean
        fun jwtDecoder(): JwtDecoder = mockk(relaxed = true)

        @Bean
        fun sidRevokeJwtConverter(): SidRevokeJwtConverter {
            val sessionService: SessionService = mockk(relaxed = true)
            val clock = Clock.fixed(Instant.parse("2026-06-04T10:00:00Z"), ZoneOffset.UTC)
            return SidRevokeJwtConverter(sessionService, clock)
        }

        @Bean
        fun corsConfigurationSource(): CorsConfigurationSource {
            val allowedOrigins = listOf("http://localhost:5173")
            return CorsConfig().corsConfigurationSource(allowedOrigins)
        }

        // SecurityConfig 가 PatAuthenticationFilter 생성을 위해 요구하는 Bean(컨트롤러는 PAT 미사용).
        @Bean
        fun personalAccessTokenService(): PersonalAccessTokenService = mockk(relaxed = true)

        @Bean
        fun oidcProviderConfigRepository(): OidcProviderConfigRepository = mockk()
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var oidcProviderConfigRepository: OidcProviderConfigRepository

    private fun config(
        registrationId: String,
        displayName: String,
    ): OidcProviderConfig {
        return OidcProviderConfig(
            id = UUID.randomUUID(),
            registrationId = registrationId,
            displayName = displayName,
            issuerUri = "https://idp.example.com/issuer/$registrationId",
            clientId = "client-id-$registrationId",
            clientSecretEncrypted = "ENC-SECRET-MATERIAL-$registrationId",
            scopes = "openid,profile,email",
            authnProviderId = UUID.fromString("00000000-0000-4a04-8000-000000000004"),
            enabled = true,
        )
    }

    @Test
    fun `providers 목록은 활성 IdP 의 registrationId 와 displayName 을 반환한다`() {
        every { oidcProviderConfigRepository.findEnabled() } returns
            listOf(config("google", "Google Workspace"), config("okta-oidc", "Okta OIDC"))

        mockMvc.perform(get("/api/v1/auth/oidc/providers").with(jwt()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.providers").isArray)
            .andExpect(jsonPath("$.providers.length()").value(2))
            .andExpect(jsonPath("$.providers[0].registrationId").value("google"))
            .andExpect(jsonPath("$.providers[0].displayName").value("Google Workspace"))
            .andExpect(jsonPath("$.providers[1].registrationId").value("okta-oidc"))
            .andExpect(jsonPath("$.providers[1].displayName").value("Okta OIDC"))
    }

    @Test
    fun `활성 IdP 가 없으면 빈 배열을 반환한다`() {
        every { oidcProviderConfigRepository.findEnabled() } returns emptyList()

        mockMvc.perform(get("/api/v1/auth/oidc/providers").with(jwt()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.providers").isArray)
            .andExpect(jsonPath("$.providers.length()").value(0))
    }

    @Test
    fun `응답에는 issuerUri clientId clientSecret scopes authnProviderId enabled 등 민감정보가 노출되지 않는다`() {
        every { oidcProviderConfigRepository.findEnabled() } returns listOf(config("google", "Google Workspace"))

        val body =
            mockMvc.perform(get("/api/v1/auth/oidc/providers").with(jwt()))
                .andExpect(status().isOk)
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                // 각 항목에는 registrationId / displayName 만 존재한다.
                .andExpect(jsonPath("$.providers[0].registrationId").exists())
                .andExpect(jsonPath("$.providers[0].displayName").exists())
                .andExpect(jsonPath("$.providers[0].issuerUri").doesNotExist())
                .andExpect(jsonPath("$.providers[0].clientId").doesNotExist())
                .andExpect(jsonPath("$.providers[0].clientSecretEncrypted").doesNotExist())
                .andExpect(jsonPath("$.providers[0].clientSecret").doesNotExist())
                .andExpect(jsonPath("$.providers[0].scopes").doesNotExist())
                .andExpect(jsonPath("$.providers[0].authnProviderId").doesNotExist())
                .andExpect(jsonPath("$.providers[0].enabled").doesNotExist())
                .andExpect(jsonPath("$.providers[0].id").doesNotExist())
                .andReturn()
                .response.contentAsString

        // 직렬화 본문 전체에 secret/내부 식별자 문자열이 새지 않았는지 추가 방어.
        assert(!body.contains("ENC-SECRET")) { "응답 본문에 client_secret 이 노출되었습니다: $body" }
        assert(!body.contains("client-id-")) { "응답 본문에 client_id 가 노출되었습니다: $body" }
        assert(!body.contains("idp.example.com")) { "응답 본문에 IdP issuer URI 가 노출되었습니다: $body" }
    }
}
