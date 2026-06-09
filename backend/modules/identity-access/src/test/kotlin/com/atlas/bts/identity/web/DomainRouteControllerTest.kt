// DomainRouteController 슬라이스 테스트 — FR-AU-07 도메인 기반 SSO 라우트 조회 (정규화 위임 + 미노출)

package com.atlas.bts.identity.web

import com.atlas.bts.identity.config.CorsConfig
import com.atlas.bts.identity.config.SecurityConfig
import com.atlas.bts.identity.jwt.SidRevokeJwtConverter
import com.atlas.bts.identity.pat.PersonalAccessTokenService
import com.atlas.bts.identity.provider.route.DomainProviderRouteRepository
import com.atlas.bts.identity.provider.route.RouteMatch
import com.atlas.bts.identity.session.SessionService
import com.atlas.bts.identity.spi.ProviderType
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
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
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.cors.CorsConfigurationSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * DomainRouteController 슬라이스 테스트 (FR-AU-07 / Task 2).
 *
 * 검증 항목.
 * - (a) 매칭 시 200 + {matched:true, type, registrationId, displayName} (SAML/OIDC 각 1건).
 * - (b) 미매칭(repository null) 시 200 + {matched:false}.
 * - (c) domain 파라미터 누락 → 400 (Spring 기본), 빈/공백 문자열 → 400.
 * - (d) 대문자/공백 입력 → repository.findRouteByDomain 에 정규화된 값 전달(정규화 위임, S5).
 *
 * permitAll 은 Task 3(SecurityConfig)에서 등록되므로, 본 슬라이스는 컨트롤러 로직에 집중한다.
 * `/api/v1/auth/route` 는 아직 permitAll 미등록이라 401 을 피하기 위해 jwt() post-processor 로
 * 인증 컨텍스트를 부여한다(SamlIdpControllerTest 규약).
 */
@WebMvcTest(
    controllers = [DomainRouteController::class],
    excludeAutoConfiguration = [OAuth2ClientAutoConfiguration::class],
)
@Import(
    SecurityConfig::class,
    DomainRouteControllerTest.MockBeans::class,
)
class DomainRouteControllerTest {
    @TestConfiguration
    class MockBeans {
        @Bean
        fun jwtDecoder(): JwtDecoder = mockk(relaxed = true)

        @Bean
        fun sidRevokeJwtConverter(): SidRevokeJwtConverter {
            val sessionService: SessionService = mockk(relaxed = true)
            val clock = Clock.fixed(Instant.parse("2026-06-09T10:00:00Z"), ZoneOffset.UTC)
            return SidRevokeJwtConverter(sessionService, clock)
        }

        @Bean
        fun corsConfigurationSource(): CorsConfigurationSource {
            return CorsConfig().corsConfigurationSource(listOf("http://localhost:5173"))
        }

        // SecurityConfig 가 PatAuthenticationFilter 생성을 위해 요구하는 Bean(컨트롤러는 PAT 미사용).
        @Bean
        fun personalAccessTokenService(): PersonalAccessTokenService = mockk(relaxed = true)

        @Bean
        fun domainProviderRouteRepository(): DomainProviderRouteRepository = mockk()
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var domainProviderRouteRepository: DomainProviderRouteRepository

    // --- (a) 매칭 시 200 + 전체 필드 ---------------------------------------------------

    @Test
    fun `SAML 라우트가 매칭되면 200 과 matched true 및 전체 필드를 반환한다`() {
        every { domainProviderRouteRepository.findRouteByDomain("partner.com") } returns
            RouteMatch(ProviderType.SAML, "okta", "Okta SSO")

        mockMvc.perform(get("/api/v1/auth/route").param("domain", "partner.com").with(jwt()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.matched").value(true))
            .andExpect(jsonPath("$.type").value("SAML"))
            .andExpect(jsonPath("$.registrationId").value("okta"))
            .andExpect(jsonPath("$.displayName").value("Okta SSO"))
    }

    @Test
    fun `OIDC 라우트가 매칭되면 200 과 matched true 및 전체 필드를 반환한다`() {
        every { domainProviderRouteRepository.findRouteByDomain("acme.io") } returns
            RouteMatch(ProviderType.OIDC, "keycloak", "Keycloak")

        mockMvc.perform(get("/api/v1/auth/route").param("domain", "acme.io").with(jwt()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.matched").value(true))
            .andExpect(jsonPath("$.type").value("OIDC"))
            .andExpect(jsonPath("$.registrationId").value("keycloak"))
            .andExpect(jsonPath("$.displayName").value("Keycloak"))
    }

    // --- (b) 미매칭(repository null) ---------------------------------------------------

    @Test
    fun `매칭되는 라우트가 없으면 200 과 matched false 를 반환한다`() {
        every { domainProviderRouteRepository.findRouteByDomain(any()) } returns null

        mockMvc.perform(get("/api/v1/auth/route").param("domain", "unknown.com").with(jwt()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.matched").value(false))
    }

    // --- (c) 파라미터 검증 (400) -------------------------------------------------------

    @Test
    fun `domain 파라미터가 누락되면 400 을 반환한다`() {
        mockMvc.perform(get("/api/v1/auth/route").with(jwt()))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `domain 파라미터가 빈 문자열이면 400 을 반환한다`() {
        mockMvc.perform(get("/api/v1/auth/route").param("domain", "").with(jwt()))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `domain 파라미터가 공백 문자열이면 400 을 반환한다`() {
        mockMvc.perform(get("/api/v1/auth/route").param("domain", "   ").with(jwt()))
            .andExpect(status().isBadRequest)
    }

    // --- (d) 정규화 위임 (S5) ----------------------------------------------------------

    @Test
    fun `대문자와 공백이 섞인 입력은 정규화된 도메인으로 repository 에 전달된다`() {
        val captured = slot<String>()
        every { domainProviderRouteRepository.findRouteByDomain(capture(captured)) } returns null

        mockMvc.perform(get("/api/v1/auth/route").param("domain", "  Partner.COM ").with(jwt()))
            .andExpect(status().isOk)

        verify { domainProviderRouteRepository.findRouteByDomain("partner.com") }
        assert(captured.captured == "partner.com") {
            "정규화된 'partner.com' 이 전달되어야 합니다. 실제: '${captured.captured}'"
        }
    }
}
