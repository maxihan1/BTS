// OIDC SP-initiated 로그인 전용 SecurityFilterChain — IF_REQUIRED 세션 + oauth2Login 결선 (FR-AU-04 Task 5)

package com.atlas.bts.identity.config

import com.atlas.bts.identity.provider.oidc.OidcAuthenticationSuccessHandler
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.util.matcher.AntPathRequestMatcher
import org.springframework.security.web.util.matcher.OrRequestMatcher
import org.springframework.security.web.util.matcher.RequestMatcher
import org.springframework.web.cors.CorsConfigurationSource

/**
 * OIDC SP-initiated 로그인 전용 SecurityFilterChain 설정 (FR-AU-04 Task 5).
 *
 * ## 별도 체인으로 분리한 이유 ([SamlSecurityConfig] 동형)
 * OIDC(OAuth2 Authorization Code) 로그인은 state/nonce/PKCE 상관관계 상태를 HttpSession 에 저장하므로
 * [SecurityConfig] 의 STATELESS 정책과 충돌한다. OIDC 경로([OIDC_PATHS])만 이 체인이 우선 매칭하여
 * [SessionCreationPolicy.IF_REQUIRED] 를 허용하고, 그 외 모든 경로는 [SecurityConfig] 의 STATELESS
 * 체인이 처리한다. 두 체인은 securityMatcher 로 격리되어 서로 영향을 주지 않는다.
 *
 * ## @Order 동률 회피 (C1 — 평가 순서 결정성)
 * [SamlSecurityConfig] 체인이 Order=1 이므로 OIDC 를 Order=1 로 두면 동률이 되어 평가 순서가
 * 비결정적이 된다. 따라서 OIDC 체인은 [OIDC_CHAIN_ORDER](=2), 기존 STATELESS 체인은 Order=3 으로
 * 1칸 밀어 SAML(1) → OIDC(2) → STATELESS(3) 모두 distinct order 로 결정성을 확보한다. 경로가 배타라
 * 동작 자체는 같지만 distinct order 로 평가 순서를 명시한다.
 *
 * ## 부팅 안전성 (profile-scoped boot 회귀 방지)
 * 클래스 레벨 [ConditionalOnBean] 으로 OIDC 협력자 빈([ClientRegistrationRepository] /
 * [OidcAuthenticationSuccessHandler]) 이 존재할 때만 이 설정이 활성화된다. 프로덕션은 컴포넌트 스캔으로
 * 협력자가 존재해 등록되고, 협력자가 없는 슬라이스 테스트(@WebMvcTest) 컨텍스트에서는 이 설정 전체를
 * 건너뛰어 부팅을 깨지 않는다.
 *
 * ## MVC 비의존 경로 매칭
 * 문자열 securityMatcher 는 Spring MVC 가 있으면 MvcRequestMatcher(mvcHandlerMappingIntrospector 의존)
 * 를 강제하여 MVC 빈이 없는 통합 테스트 컨텍스트 부팅을 깬다. 따라서 MVC 비의존 [AntPathRequestMatcher]
 * 로 명시 매칭한다.
 *
 * ## 복귀 경로 (returnTo)
 * 본 FR 은 [OidcAuthenticationSuccessHandler] 의 RelayStateValidator open-redirect 방어 로직만
 * 유지하고, oauth2Login 의 자체 state 검증과 충돌하는 returnTo 전달은 후속으로 미룬다(복귀 경로 자체는
 * nice-to-have, open-redirect 차단이 핵심). 핸들러는 returnTo 부재 시 기본 랜딩으로 안전하게 폴백한다.
 */
@Configuration
@ConditionalOnBean(ClientRegistrationRepository::class, OidcAuthenticationSuccessHandler::class)
class OidcSecurityConfig(
    private val corsConfigurationSource: CorsConfigurationSource,
) {
    /**
     * OIDC 전용 SecurityFilterChain (Order=2 — SAML(1)과 STATELESS(3) 사이, 경로 배타).
     *
     * - 세션 정책 IF_REQUIRED — oauth2Login 필터가 state/nonce/PKCE 상관관계 상태를 HttpSession 에 저장한다.
     * - oauth2Login 결선 — DB 기반 [ClientRegistrationRepository] + OIDC 성공 핸들러
     *   [OidcAuthenticationSuccessHandler] (JIT 프로비저닝 + BTS 세션/JWT 발급).
     * - CSRF skip (콜백 한정) — 콜백(/login/oauth2/code) 은 IdP redirect 로 도착해 CSRF 토큰을 가질 수
     *   없다. OAuth2 는 state 파라미터로 CSRF 를 방어하므로 보안은 유지된다. 이 체인은 OIDC 경로
     *   ([OIDC_PATHS])만 처리하므로 일반 API 의 CSRF 검증([SecurityConfig]) 에는 영향이 없다.
     * - OIDC 경로 authenticated — OIDC 흐름 자체가 인증 절차이므로 anyRequest authenticated.
     */
    @Bean
    @Order(OIDC_CHAIN_ORDER)
    fun oidcSecurityFilterChain(
        http: HttpSecurity,
        clientRegistrationRepository: ClientRegistrationRepository,
        oidcAuthenticationSuccessHandler: OidcAuthenticationSuccessHandler,
    ): SecurityFilterChain {
        return http
            .securityMatcher(oidcPathMatcher())
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED) }
            .cors { it.configurationSource(corsConfigurationSource) }
            // 콜백(/login/oauth2/code) 은 외부 IdP redirect 이므로 CSRF 토큰 부재 — OIDC 경로 한정 CSRF skip.
            // OAuth2 state 파라미터로 CSRF 방어가 유지되며, 일반 API CSRF 검증(SecurityConfig)에 영향 없음.
            .csrf { it.disable() }
            .authorizeHttpRequests { auth -> auth.anyRequest().authenticated() }
            .oauth2Login { oauth2 ->
                oauth2.clientRegistrationRepository(clientRegistrationRepository)
                // PKCE 강제(spec N2) — confidential client 여도 code_challenge+S256 부착(아래 헬퍼 참고).
                oauth2.authorizationEndpoint { ep ->
                    ep.authorizationRequestResolver(pkceAuthorizationRequestResolver(clientRegistrationRepository))
                }
                oauth2.successHandler(oidcAuthenticationSuccessHandler)
            }
            .build()
    }

    /**
     * PKCE(S256)를 강제하는 [OAuth2AuthorizationRequestResolver] (spec N2 — Authorization Code 가로채기 방어).
     *
     * Spring Security 6.x 기본 [DefaultOAuth2AuthorizationRequestResolver] 는 public client 이거나
     * requireProofKey=true 일 때만 code_challenge 를 부착한다. BTS 의 OIDC client 는 client_secret 을
     * 가진 **confidential client** 라 기본 동작으로는 PKCE 가 빠진다. OAuth 2.1 은 confidential client 에도
     * PKCE 를 권장(defense in depth)하므로, [OAuth2AuthorizationRequestCustomizers.withPkce] 로
     * code_challenge + code_challenge_method=S256 을 진입 요청에 강제 부착한다.
     */
    private fun pkceAuthorizationRequestResolver(
        clientRegistrationRepository: ClientRegistrationRepository,
    ): OAuth2AuthorizationRequestResolver =
        DefaultOAuth2AuthorizationRequestResolver(clientRegistrationRepository, AUTHORIZATION_BASE_URI).apply {
            setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce())
        }

    /** OIDC 경로([OIDC_PATHS])를 MVC 비의존 [AntPathRequestMatcher] 로 매칭하는 [RequestMatcher]. */
    private fun oidcPathMatcher(): RequestMatcher = OrRequestMatcher(OIDC_PATHS.map { AntPathRequestMatcher(it) })

    private companion object {
        /** OIDC 전용 체인 우선순위 — SAML(1) 다음, STATELESS(3) 보다 먼저 (C1 distinct order). */
        const val OIDC_CHAIN_ORDER = 2

        /** Spring 표준 authorization request 진입 base URI (/oauth2/authorization/{registrationId}). */
        const val AUTHORIZATION_BASE_URI = "/oauth2/authorization"

        /**
         * OIDC 전용 체인이 securityMatcher 로 잡는 경로 (Spring 표준 경로).
         * - /oauth2 — authorization 진입 (/oauth2/authorization/REGISTRATION_ID)
         * - /login/oauth2 — code 콜백 (/login/oauth2/code/REGISTRATION_ID, IdP redirect 수신)
         *
         * SAML 경로(/saml2, /login/saml2 접두사)와 접두사가 달라 겹치지 않는다.
         */
        val OIDC_PATHS = listOf("/oauth2/**", "/login/oauth2/**")
    }
}
