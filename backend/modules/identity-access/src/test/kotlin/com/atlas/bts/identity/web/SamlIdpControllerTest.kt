// SamlIdpController 슬라이스 테스트 — FR-AU-03 활성 SAML IdP 목록 조회 (민감정보 미노출)

package com.atlas.bts.identity.web

import com.atlas.bts.identity.config.CorsConfig
import com.atlas.bts.identity.config.SecurityConfig
import com.atlas.bts.identity.jwt.SidRevokeJwtConverter
import com.atlas.bts.identity.pat.PersonalAccessTokenService
import com.atlas.bts.identity.provider.saml.SamlIdpConfig
import com.atlas.bts.identity.provider.saml.SamlIdpConfigRepository
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
 * SamlIdpController 슬라이스 테스트 (FR-AU-03 / Task 6).
 *
 * 검증 항목.
 * - (a) 활성 IdP 목록을 registrationId / displayName 만으로 반환한다.
 * - (b) 컨트롤러는 [SamlIdpConfigRepository.findAllEnabled] 결과만 매핑한다(비활성 제외는 repo 책임 — repo 통합테스트가 보강).
 * - (c) 응답에 인증서/SSO URL/entityId/authnProviderId/enabled 등 민감·내부 정보가 노출되지 않는다.
 *
 * permitAll(인증 없이 200) 검증은 Task 5(SecurityConfig)에서 다룬다 — 본 슬라이스는 컨트롤러 로직에 집중한다.
 */
@WebMvcTest(
    controllers = [SamlIdpController::class],
    excludeAutoConfiguration = [OAuth2ClientAutoConfiguration::class],
)
@Import(
    SecurityConfig::class,
    SamlIdpControllerTest.MockBeans::class,
)
class SamlIdpControllerTest {

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
        fun corsConfigurationSource(): CorsConfigurationSource =
            CorsConfig().corsConfigurationSource(listOf("http://localhost:5173"))

        // SecurityConfig 가 PatAuthenticationFilter 생성을 위해 요구하는 Bean(컨트롤러는 PAT 미사용).
        @Bean
        fun personalAccessTokenService(): PersonalAccessTokenService = mockk(relaxed = true)

        @Bean
        fun samlIdpConfigRepository(): SamlIdpConfigRepository = mockk()
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var samlIdpConfigRepository: SamlIdpConfigRepository

    private fun config(
        registrationId: String,
        displayName: String,
    ): SamlIdpConfig =
        SamlIdpConfig(
            id = UUID.randomUUID(),
            registrationId = registrationId,
            displayName = displayName,
            idpEntityId = "https://idp.example.com/entity/$registrationId",
            idpSsoUrl = "https://idp.example.com/sso/$registrationId",
            idpX509Cert = "-----BEGIN CERTIFICATE-----\nSECRET-CERT-MATERIAL\n-----END CERTIFICATE-----\n",
            authnProviderId = UUID.fromString("00000000-0000-4a03-8000-000000000003"),
            enabled = true,
        )

    @Test
    fun `idps 목록은 활성 IdP 의 registrationId 와 displayName 을 반환한다`() {
        every { samlIdpConfigRepository.findAllEnabled() } returns
            listOf(config("okta", "Okta SSO"), config("azure", "Azure AD"))

        mockMvc.perform(get("/api/v1/auth/saml/idps"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.idps").isArray)
            .andExpect(jsonPath("$.idps.length()").value(2))
            .andExpect(jsonPath("$.idps[0].registrationId").value("okta"))
            .andExpect(jsonPath("$.idps[0].displayName").value("Okta SSO"))
            .andExpect(jsonPath("$.idps[1].registrationId").value("azure"))
            .andExpect(jsonPath("$.idps[1].displayName").value("Azure AD"))
    }

    @Test
    fun `활성 IdP 가 없으면 빈 배열을 반환한다`() {
        every { samlIdpConfigRepository.findAllEnabled() } returns emptyList()

        mockMvc.perform(get("/api/v1/auth/saml/idps"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.idps").isArray)
            .andExpect(jsonPath("$.idps.length()").value(0))
    }

    @Test
    fun `응답에는 인증서 SSO URL entityId authnProviderId enabled 등 민감정보가 노출되지 않는다`() {
        every { samlIdpConfigRepository.findAllEnabled() } returns listOf(config("okta", "Okta SSO"))

        val body =
            mockMvc.perform(get("/api/v1/auth/saml/idps"))
                .andExpect(status().isOk)
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                // 각 항목에는 registrationId / displayName 만 존재한다.
                .andExpect(jsonPath("$.idps[0].registrationId").exists())
                .andExpect(jsonPath("$.idps[0].displayName").exists())
                .andExpect(jsonPath("$.idps[0].idpEntityId").doesNotExist())
                .andExpect(jsonPath("$.idps[0].idpSsoUrl").doesNotExist())
                .andExpect(jsonPath("$.idps[0].idpX509Cert").doesNotExist())
                .andExpect(jsonPath("$.idps[0].authnProviderId").doesNotExist())
                .andExpect(jsonPath("$.idps[0].enabled").doesNotExist())
                .andExpect(jsonPath("$.idps[0].id").doesNotExist())
                .andReturn()
                .response.contentAsString

        // 직렬화 본문 전체에 인증서/내부 식별자 문자열이 새지 않았는지 추가 방어.
        assert(!body.contains("CERTIFICATE")) { "응답 본문에 인증서가 노출되었습니다: $body" }
        assert(!body.contains("idp.example.com")) { "응답 본문에 IdP 내부 URL 이 노출되었습니다: $body" }
    }
}
