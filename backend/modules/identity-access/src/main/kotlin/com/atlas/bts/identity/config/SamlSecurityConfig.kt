// SAML SP-initiated 로그인 전용 SecurityFilterChain — IF_REQUIRED 세션 + saml2Login 결선 (FR-AU-03 게이트1 D1)

package com.atlas.bts.identity.config

import com.atlas.bts.identity.provider.saml.Saml2AuthenticationSuccessHandler
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.util.matcher.AntPathRequestMatcher
import org.springframework.security.web.util.matcher.OrRequestMatcher
import org.springframework.security.web.util.matcher.RequestMatcher
import org.springframework.web.cors.CorsConfigurationSource

/**
 * SAML SP-initiated 로그인 전용 SecurityFilterChain 설정 (FR-AU-03 / 게이트1 D1 — BLOCKER 해소).
 *
 * ## 별도 체인으로 분리한 이유
 * SAML SP-initiated 로그인은 AuthnRequest 상관관계/replay 방어 상태를 HttpSession 에 저장하므로
 * [SecurityConfig] 의 STATELESS 정책과 충돌한다. SAML 경로([SAML_PATHS])만 이 체인이 [Order](1)로
 * 우선 매칭하여 [SessionCreationPolicy.IF_REQUIRED] 를 허용하고, 그 외 모든 경로는 [SecurityConfig]
 * 의 STATELESS 체인(Order=2)이 처리한다. 두 체인은 securityMatcher 로 격리되어 서로 영향을 주지 않는다.
 *
 * ## 부팅 안전성 (profile-scoped boot 회귀 방지)
 * 클래스 레벨 [ConditionalOnBean] 으로 SAML 협력자 빈([RelyingPartyRegistrationRepository] /
 * [Saml2AuthenticationSuccessHandler]) 이 존재할 때만 이 설정이 활성화된다. 프로덕션은 컴포넌트 스캔으로
 * 협력자(@Component)가 존재해 등록되고, 협력자가 없는 슬라이스 테스트(@WebMvcTest) 컨텍스트에서는 이
 * 설정 전체를 건너뛰어 부팅을 깨지 않는다.
 *
 * ## MVC 비의존 경로 매칭
 * 문자열 securityMatcher 는 Spring MVC 가 있으면 MvcRequestMatcher(mvcHandlerMappingIntrospector 의존)
 * 를 강제하여 MVC 빈이 없는 통합 테스트 컨텍스트 부팅을 깬다. 따라서 MVC 비의존 [AntPathRequestMatcher]
 * 로 명시 매칭한다.
 *
 * ## 세션 고정 방어 + JSESSIONID SameSite (FR-AU-08b — EC10/EC17)
 * - session-fixation 을 `changeSessionId` 로 명시한다(EC10). SSO 연결 시작(start)이 HttpSession 에
 *   심은 LinkingIntent 가 인증 성공 시 세션 회전으로 유실되면 연결 모드 콜백이 일반 로그인으로 새므로,
 *   세션 ID 만 바꾸고 속성을 보존하는 전략을 강제한다.
 * - JSESSIONID 쿠키 SameSite 는 `application.yml` 의 `server.servlet.session.cookie.same-site: none`
 *   으로 둔다(EC17). SAML ACS 는 IdP 가 외부에서 **cross-site POST** 로 콜백해, SameSite=Strict 면
 *   start 단계 세션 쿠키가 그 POST 에 동반되지 않아 왕복이 끊긴다. None 으로 cross-site 동반을 허용하되,
 *   CSRF 는 별도 토큰식(쿠키 SameSite=Strict, 무변경)으로 방어하므로 안전하다. `secure: true` 는 http
 *   부팅 환경(base/dev/test)에서 쿠키 미방출을 일으키므로 `application-prod.yml` 에만 둔다(C4 — https 강제).
 */
@Configuration
@ConditionalOnBean(RelyingPartyRegistrationRepository::class, Saml2AuthenticationSuccessHandler::class)
class SamlSecurityConfig(
    private val corsConfigurationSource: CorsConfigurationSource,
) {
    /**
     * SAML 전용 SecurityFilterChain (Order=1 — 일반 STATELESS 체인보다 우선 매칭).
     *
     * - 세션 정책 IF_REQUIRED — saml2Login 필터가 AuthnRequest 상관관계 상태를 HttpSession 에 저장한다.
     * - saml2Login 결선 — DB 기반 [RelyingPartyRegistrationRepository] + SAML 성공 핸들러
     *   [Saml2AuthenticationSuccessHandler] (JIT 프로비저닝 + BTS 세션/JWT 발급).
     * - CSRF skip (ACS 한정) — ACS(/login/saml2/sso) 는 IdP 가 외부에서 POST 하므로 CSRF 토큰을
     *   가질 수 없다. 이 체인은 SAML 경로([SAML_PATHS])만 처리하므로 일반 API 의 CSRF 검증
     *   ([SecurityConfig]) 에는 영향이 없다.
     * - SAML 경로 authenticated — SAML 흐름 자체가 인증 절차이므로 anyRequest authenticated.
     */
    @Bean
    @Order(SAML_CHAIN_ORDER)
    fun samlSecurityFilterChain(
        http: HttpSecurity,
        relyingPartyRegistrationRepository: RelyingPartyRegistrationRepository,
        saml2AuthenticationSuccessHandler: Saml2AuthenticationSuccessHandler,
    ): SecurityFilterChain {
        return http
            .securityMatcher(samlPathMatcher())
            .sessionManagement {
                it.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                // session-fixation 명시(EC10) — 인증 성공 시 세션 ID 만 회전하고 속성(SSO LinkingIntent)은
                // 보존한다. newSession 전략이면 start 단계가 심은 intent 가 유실돼 연결 모드 콜백이
                // 일반 로그인으로 새므로(fail-open), changeSessionId 로 속성 이관을 보장한다.
                it.sessionFixation { sf -> sf.changeSessionId() }
            }
            .cors { it.configurationSource(corsConfigurationSource) }
            // ACS(/login/saml2/sso) 는 외부 IdP POST 이므로 CSRF 토큰 부재 — SAML 경로 한정 CSRF skip.
            // 이 체인은 SAML 경로만 처리하므로 일반 API CSRF 검증(SecurityConfig)에 영향 없음.
            .csrf { it.disable() }
            .authorizeHttpRequests { auth -> auth.anyRequest().authenticated() }
            .saml2Login { saml2 ->
                saml2.relyingPartyRegistrationRepository(relyingPartyRegistrationRepository)
                saml2.successHandler(saml2AuthenticationSuccessHandler)
            }
            .build()
    }

    /** SAML 경로([SAML_PATHS])를 MVC 비의존 [AntPathRequestMatcher] 로 매칭하는 [RequestMatcher]. */
    private fun samlPathMatcher(): RequestMatcher = OrRequestMatcher(SAML_PATHS.map { AntPathRequestMatcher(it) })

    private companion object {
        /** SAML 전용 체인 우선순위 — 일반 API(STATELESS) 체인보다 먼저 매칭한다 (낮을수록 우선). */
        const val SAML_CHAIN_ORDER = 1

        /**
         * SAML 전용 체인이 securityMatcher 로 잡는 경로 (BLOCKER 1 — 표준 경로로 통일).
         * - /saml2 — Spring 표준 AuthnRequest 진입 (/saml2/authenticate/REGISTRATION_ID)
         * - /login/saml2 — ACS 콜백 (/login/saml2/sso/REGISTRATION_ID, IdP POST 수신)
         *
         * 과거 /sso/saml2 별칭은 saml2Login 이 바인딩하는 대응 필터가 없는 미배선 별칭이라 제거했다.
         * 프론트/백 모두 표준 경로(/saml2/authenticate/{registrationId})로 통일한다.
         */
        val SAML_PATHS = listOf("/saml2/**", "/login/saml2/**")
    }
}
