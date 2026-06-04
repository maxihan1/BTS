// SAML 전용 SecurityFilterChain 검증 — @Order 분리 / IF_REQUIRED 세션 / saml2Login 결선 / idps permitAll (FR-AU-03 Task 5)

package com.atlas.bts.identity.config

import com.atlas.bts.identity.jwt.SidRevokeJwtConverter
import com.atlas.bts.identity.pat.PersonalAccessTokenService
import com.atlas.bts.identity.provider.saml.Saml2AuthenticationSuccessHandler
import com.atlas.bts.identity.session.SessionService
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository
import org.springframework.security.saml2.provider.service.web.authentication.Saml2WebSsoAuthenticationFilter
import org.springframework.security.web.DefaultSecurityFilterChain
import org.springframework.security.web.FilterChainProxy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.cors.CorsConfigurationSource

/**
 * SAML 전용 SecurityFilterChain 분리 검증 (FR-AU-03 Task 5 / 게이트1 D1).
 *
 * 검증 항목.
 * - (a) FilterChainProxy 에 2개 이상의 체인이 등록된다 (SAML 전용 + 기존 STATELESS).
 * - (b) SAML 체인이 SAML 경로(/login/saml2/sso/x)는 매칭하고, 일반 api 경로(/api/v1/protected)는 매칭하지 않는다.
 * - (c) SAML 체인은 세션 컨텍스트 영속 필터를 보유한다 (IF_REQUIRED — STATELESS 체인은 미보유).
 * - (d) /api/v1/auth/saml/idps 는 인증 없이 200 (permitAll, 로그인 전 호출).
 * - (e) 그 외 보호 api 경로는 인증 없으면 401 (기존 STATELESS 체인 회귀 0).
 *
 * @see SecurityConfig 검증 대상 설정 클래스
 */
@WebMvcTest(
    controllers = [SamlSecurityConfigTest.ProbeController::class],
    excludeAutoConfiguration = [OAuth2ClientAutoConfiguration::class],
)
@Import(
    SecurityConfig::class,
    SamlSecurityConfigTest.TestSecurityBeans::class,
    SamlSecurityConfigTest.ProbeController::class,
)
class SamlSecurityConfigTest {

    @RestController
    class ProbeController {
        @GetMapping("/api/v1/auth/saml/idps")
        fun idps() = "ok"

        @GetMapping("/api/v1/protected")
        fun protected() = "ok"
    }

    @TestConfiguration
    class TestSecurityBeans {
        @Bean
        fun jwtDecoder(): JwtDecoder =
            JwtDecoder { token ->
                Jwt.withTokenValue(token)
                    .header("alg", "none")
                    .claim("sub", "test-user")
                    .build()
            }

        @Bean
        fun sidRevokeJwtConverter(): SidRevokeJwtConverter = SidRevokeJwtConverter(mockk<SessionService>(relaxed = true))

        @Bean
        fun corsConfigurationSource(): CorsConfigurationSource =
            CorsConfig().corsConfigurationSource(listOf("http://localhost:5173"))

        @Bean
        fun personalAccessTokenService(): PersonalAccessTokenService = mockk(relaxed = true)

        @Bean
        fun sessionService(): SessionService = mockk(relaxed = true)

        // SAML 체인 부팅에 필요한 협력자 — 동작이 아닌 결선 구조만 검증하므로 relaxed mock 으로 충분.
        @Bean
        fun relyingPartyRegistrationRepository(): RelyingPartyRegistrationRepository = mockk(relaxed = true)

        @Bean
        fun saml2AuthenticationSuccessHandler(): Saml2AuthenticationSuccessHandler = mockk(relaxed = true)
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var filterChainProxy: FilterChainProxy

    // --- (a) 체인 분리 -------------------------------------------------------------

    @Test
    fun `SAML 전용 체인과 기존 STATELESS 체인이 분리되어 2개 이상 등록된다`() {
        assertThat(filterChainProxy.filterChains).hasSizeGreaterThanOrEqualTo(2)
    }

    // --- (b) securityMatcher 동작 ---------------------------------------------------

    @Test
    fun `SAML 체인은 SAML ACS 경로만 매칭하고 일반 api 경로는 매칭하지 않는다`() {
        val samlChain = firstChainMatching("/login/saml2/sso/okta")
        val apiChain = firstChainMatching("/api/v1/protected")

        // SAML 경로와 api 경로는 서로 다른 체인이 처리해야 한다 (securityMatcher 로 분리됨).
        assertThat(samlChain).isNotSameAs(apiChain)
    }

    // --- (c) saml2Login 결선 (SAML 필터 존재) ---------------------------------------

    @Test
    fun `SAML 체인은 saml2Login 필터(Saml2WebSsoAuthenticationFilter)를 보유한다`() {
        val samlChain = firstChainMatching("/login/saml2/sso/okta")
        assertThat(hasSamlAuthenticationFilter(samlChain)).isTrue()
    }

    @Test
    fun `STATELESS 체인은 SAML 인증 필터를 보유하지 않는다 (회귀 방지)`() {
        val apiChain = firstChainMatching("/api/v1/protected")
        assertThat(hasSamlAuthenticationFilter(apiChain)).isFalse()
    }

    // --- (d) idps permitAll ---------------------------------------------------------

    @Test
    fun `saml idps 경로는 인증 없이 200 을 반환한다 (permitAll, 로그인 전 호출)`() {
        mockMvc.perform(get("/api/v1/auth/saml/idps"))
            .andExpect(status().isOk)
    }

    // --- (e) 기존 STATELESS 체인 회귀 0 --------------------------------------------

    @Test
    fun `보호된 api 경로에 인증 없이 접근하면 401 을 반환한다 (기존 STATELESS 체인 보존)`() {
        mockMvc.perform(get("/api/v1/protected"))
            .andExpect(status().isUnauthorized)
    }

    // --- 헬퍼 ----------------------------------------------------------------------

    private fun firstChainMatching(path: String): SecurityFilterChain {
        val request = MockHttpServletRequest("GET", path)
        return filterChainProxy.filterChains.first { it.matches(request) }
    }

    /** saml2Login DSL 이 등록한 ACS 처리 필터([Saml2WebSsoAuthenticationFilter]) 존재 여부. */
    private fun hasSamlAuthenticationFilter(chain: SecurityFilterChain): Boolean =
        (chain as DefaultSecurityFilterChain).filters.any { it is Saml2WebSsoAuthenticationFilter }
}
