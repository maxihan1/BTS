// SAML 전용 SecurityFilterChain 검증 — ConditionalOnBean 활성/비활성 + securityMatcher + saml2Login 결선 (FR-AU-03 Task 5)

package com.atlas.bts.identity.config

import com.atlas.bts.identity.provider.saml.Saml2AuthenticationSuccessHandler
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.context.annotation.Configuration
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository
import org.springframework.security.saml2.provider.service.web.authentication.Saml2WebSsoAuthenticationFilter
import org.springframework.security.web.DefaultSecurityFilterChain
import org.springframework.security.web.SecurityFilterChain
import org.springframework.web.cors.CorsConfigurationSource

/**
 * SAML 전용 SecurityFilterChain 분리 검증 (FR-AU-03 Task 5 / 게이트1 D1).
 *
 * [WebApplicationContextRunner] 로 [SamlSecurityConfig] 의 클래스 레벨 @ConditionalOnBean 을
 * production 과 동일하게 평가한다.
 *
 * 검증 항목.
 * - (a) SAML 협력자 빈이 있으면 [SamlSecurityConfig.samlSecurityFilterChain] 빈이 등록된다.
 * - (b) SAML 협력자 빈이 없으면 SAML 체인이 등록되지 않는다 (슬라이스 부팅 회귀 방지).
 * - (c) SAML 체인은 SAML 경로(/login/saml2/sso/x)는 매칭하고, 일반 api 경로(/api/v1/protected)는 매칭하지 않는다.
 * - (d) SAML 체인은 saml2Login 필터([Saml2WebSsoAuthenticationFilter])를 보유한다 (saml2Login 결선).
 *
 * permitAll(/api/v1/auth/saml/idps) / 401 등 HTTP 동작은 [SecurityConfig] 책임이므로
 * [SecurityConfigTest] 가 검증한다.
 *
 * @see SamlSecurityConfig 검증 대상 설정 클래스
 */
class SamlSecurityConfigTest {
    private val runner =
        WebApplicationContextRunner()
            .withUserConfiguration(EnableWebSecurityConfig::class.java, SamlSecurityConfig::class.java)
            .withBean(CorsConfigurationSource::class.java, { CorsConfig().corsConfigurationSource(ALLOWED_ORIGINS) })

    // --- (a) 협력자 있음 → SAML 체인 등록 -------------------------------------------

    @Test
    fun `SAML 협력자 빈이 있으면 samlSecurityFilterChain 빈이 등록된다`() {
        runnerWithSamlCollaborators().run { ctx ->
            assertThat(ctx).hasBean("samlSecurityFilterChain")
        }
    }

    // --- (b) 협력자 없음 → SAML 체인 미등록 (슬라이스 부팅 회귀 방지) ---------------

    @Test
    fun `SAML 협력자 빈이 없으면 SAML 체인이 등록되지 않는다 (ConditionalOnBean 슬라이스 보호)`() {
        runner.run { ctx ->
            assertThat(ctx).doesNotHaveBean("samlSecurityFilterChain")
        }
    }

    // --- (c) securityMatcher 동작 ---------------------------------------------------

    @Test
    fun `SAML 체인은 SAML ACS 경로만 매칭하고 일반 api 경로는 매칭하지 않는다`() {
        runnerWithSamlCollaborators().run { ctx ->
            val chain = ctx.getBean("samlSecurityFilterChain", SecurityFilterChain::class.java)
            assertThat(chain.matches(request("POST", "/login/saml2/sso/okta"))).isTrue()
            assertThat(chain.matches(request("GET", "/api/v1/protected"))).isFalse()
        }
    }

    // --- (d) saml2Login 결선 (SAML 필터 존재) ---------------------------------------

    @Test
    fun `SAML 체인은 saml2Login 필터(Saml2WebSsoAuthenticationFilter)를 보유한다`() {
        runnerWithSamlCollaborators().run { ctx ->
            val chain = ctx.getBean("samlSecurityFilterChain", SecurityFilterChain::class.java)
            val hasSamlFilter =
                (chain as DefaultSecurityFilterChain).filters.any { it is Saml2WebSsoAuthenticationFilter }
            assertThat(hasSamlFilter).isTrue()
        }
    }

    // --- 헬퍼 ----------------------------------------------------------------------

    /**
     * 매칭 검증용 mock 요청. AntPathRequestMatcher 는 servletPath 기반으로 매칭하므로
     * requestURI 와 동일하게 servletPath 를 설정한다.
     */
    private fun request(
        method: String,
        path: String,
    ): MockHttpServletRequest = MockHttpServletRequest(method, path).apply { servletPath = path }

    private fun runnerWithSamlCollaborators(): WebApplicationContextRunner =
        runner
            .withBean(
                RelyingPartyRegistrationRepository::class.java,
                { mockk<RelyingPartyRegistrationRepository>(relaxed = true) },
            )
            .withBean(
                Saml2AuthenticationSuccessHandler::class.java,
                { mockk<Saml2AuthenticationSuccessHandler>(relaxed = true) },
            )

    /** SAML 체인 부팅에 필요한 @EnableWebSecurity 활성화용 빈 컨피그. */
    @Configuration
    @EnableWebSecurity
    class EnableWebSecurityConfig

    private companion object {
        /** CORS 허용 origin — 테스트용 SPA dev 서버. */
        val ALLOWED_ORIGINS = listOf("http://localhost:5173")
    }
}
