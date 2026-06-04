// OIDC 전용 SecurityFilterChain 검증 — ConditionalOnBean 활성/비활성 + securityMatcher + oauth2Login 결선 (FR-AU-04 Task 5)

package com.atlas.bts.identity.config

import com.atlas.bts.identity.provider.oidc.OidcAuthenticationSuccessHandler
import io.mockk.mockk
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.runner.WebApplicationContextRunner
import org.springframework.context.annotation.Configuration
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter
import org.springframework.security.oauth2.client.web.OAuth2LoginAuthenticationFilter
import org.springframework.security.web.DefaultSecurityFilterChain
import org.springframework.security.web.SecurityFilterChain
import org.springframework.web.cors.CorsConfigurationSource

/**
 * OIDC 전용 SecurityFilterChain 분리 검증 (FR-AU-04 Task 5).
 *
 * [WebApplicationContextRunner] 로 [OidcSecurityConfig] 의 클래스 레벨 @ConditionalOnBean 을
 * production 과 동일하게 평가한다 ([SamlSecurityConfigTest] 동형).
 *
 * 검증 항목.
 * - (a) OIDC 협력자 빈이 있으면 [OidcSecurityConfig.oidcSecurityFilterChain] 빈이 등록된다.
 * - (b) OIDC 협력자 빈이 없으면 OIDC 체인이 등록되지 않는다 (슬라이스 부팅 회귀 방지).
 * - (c) OIDC 체인은 OIDC 경로(/oauth2/x, /login/oauth2/x)는 매칭하고, 일반 api 경로는 매칭하지 않는다.
 * - (d) OIDC 체인은 oauth2Login 필터(진입 redirect + 콜백 code 교환)를 보유한다 (oauth2Login 결선).
 *
 * permitAll(/api/v1/auth/oidc/providers) / 401 등 HTTP 동작은 [SecurityConfig] 책임이므로
 * [SecurityConfigTest] 가 검증한다.
 *
 * @see OidcSecurityConfig 검증 대상 설정 클래스
 */
class OidcSecurityConfigTest {
    private val runner =
        WebApplicationContextRunner()
            .withUserConfiguration(EnableWebSecurityConfig::class.java, OidcSecurityConfig::class.java)
            .withBean(CorsConfigurationSource::class.java, { CorsConfig().corsConfigurationSource(ALLOWED_ORIGINS) })

    // --- (a) 협력자 있음 → OIDC 체인 등록 -------------------------------------------

    @Test
    fun `OIDC 협력자 빈이 있으면 oidcSecurityFilterChain 빈이 등록된다`() {
        runnerWithOidcCollaborators().run { ctx ->
            assertThat(ctx).hasBean("oidcSecurityFilterChain")
        }
    }

    // --- (b) 협력자 없음 → OIDC 체인 미등록 (슬라이스 부팅 회귀 방지) ---------------

    @Test
    fun `OIDC 협력자 빈이 없으면 OIDC 체인이 등록되지 않는다 (ConditionalOnBean 슬라이스 보호)`() {
        runner.run { ctx ->
            assertThat(ctx).doesNotHaveBean("oidcSecurityFilterChain")
        }
    }

    // --- (c) securityMatcher 동작 ---------------------------------------------------

    @Test
    fun `OIDC 체인은 표준 OIDC 경로만 매칭하고 일반 api 경로는 매칭하지 않는다`() {
        runnerWithOidcCollaborators().run { ctx ->
            val chain = ctx.getBean("oidcSecurityFilterChain", SecurityFilterChain::class.java)
            // 표준 authorization 진입 + code 콜백은 매칭한다.
            assertThat(chain.matches(request("GET", "/oauth2/authorization/okta"))).isTrue()
            assertThat(chain.matches(request("GET", "/login/oauth2/code/okta"))).isTrue()
            assertThat(chain.matches(request("GET", "/api/v1/protected"))).isFalse()
        }
    }

    @Test
    fun `OIDC 체인은 SAML 경로(saml2)와 겹치지 않는다`() {
        runnerWithOidcCollaborators().run { ctx ->
            val chain = ctx.getBean("oidcSecurityFilterChain", SecurityFilterChain::class.java)
            assertThat(chain.matches(request("GET", "/saml2/authenticate/okta"))).isFalse()
            assertThat(chain.matches(request("POST", "/login/saml2/sso/okta"))).isFalse()
        }
    }

    // --- (d) oauth2Login 결선 (OAuth2 필터 존재) ------------------------------------

    @Test
    fun `OIDC 체인은 oauth2Login 진입 redirect 필터를 보유한다`() {
        runnerWithOidcCollaborators().run { ctx ->
            val chain = ctx.getBean("oidcSecurityFilterChain", SecurityFilterChain::class.java)
            val hasRedirectFilter =
                (chain as DefaultSecurityFilterChain).filters.any { it is OAuth2AuthorizationRequestRedirectFilter }
            assertThat(hasRedirectFilter).isTrue()
        }
    }

    @Test
    fun `OIDC 체인은 oauth2Login 콜백(code 교환) 필터를 보유한다`() {
        runnerWithOidcCollaborators().run { ctx ->
            val chain = ctx.getBean("oidcSecurityFilterChain", SecurityFilterChain::class.java)
            val hasLoginFilter =
                (chain as DefaultSecurityFilterChain).filters.any { it is OAuth2LoginAuthenticationFilter }
            assertThat(hasLoginFilter).isTrue()
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

    private fun runnerWithOidcCollaborators(): WebApplicationContextRunner =
        runner
            .withBean(
                ClientRegistrationRepository::class.java,
                { mockk<ClientRegistrationRepository>(relaxed = true) },
            )
            .withBean(
                OidcAuthenticationSuccessHandler::class.java,
                { mockk<OidcAuthenticationSuccessHandler>(relaxed = true) },
            )

    /** OIDC 체인 부팅에 필요한 @EnableWebSecurity 활성화용 빈 컨피그. */
    @Configuration
    @EnableWebSecurity
    class EnableWebSecurityConfig

    private companion object {
        /** CORS 허용 origin — 테스트용 SPA dev 서버. */
        val ALLOWED_ORIGINS = listOf("http://localhost:5173")
    }
}
