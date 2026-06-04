// Spring Security 필터 체인 — SAML 전용(@Order(1)) + 기존 STATELESS(@Order(2)) 2체인, JWT Resource Server + sid revoke + CSRF Cookie + CORS (FR-09-26/27/30, FR-AU-03)

package com.atlas.bts.identity.config

import com.atlas.bts.identity.jwt.SidRevokeJwtConverter
import com.atlas.bts.identity.pat.PersonalAccessToken
import com.atlas.bts.identity.pat.PersonalAccessTokenService
import com.atlas.bts.identity.provider.saml.Saml2AuthenticationSuccessHandler
import com.atlas.bts.identity.web.PatAuthenticationFilter
import jakarta.servlet.http.HttpServletRequest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.annotation.Order
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.csrf.CookieCsrfTokenRepository
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler
import org.springframework.security.web.util.matcher.RequestMatcher
import org.springframework.web.cors.CorsConfigurationSource

/**
 * BTS 단일 SecurityFilterChain 설정 (FR-09-26 / FR-09-27 / FR-09-30).
 *
 * ## BLOCKER #4 해소 — Spring Authorization Server 미도입
 * RegisteredClient / OAuth2AuthorizationServerConfiguration 사용 금지.
 * BTS는 JWT를 자체 발급(JwtIssuer, Task 12)하며 Nimbus-JOSE-JWT 9.40 +
 * spring-security-oauth2-jose 를 직접 사용한다.
 * 이전 plan의 Order(1) AuthServer + Order(2) BTS API 분리 구조 폐기.
 * 단일 SecurityFilterChain Bean 으로 통합한다.
 *
 * ## BLOCKER #5 해소 — 표준 OAuth2 endpoint 비활성
 * /oauth2/token, /oauth2/authorize 등 Spring Authorization Server 표준 endpoint 가
 * 미등록 상태로 404 를 반환하므로 공격 표면이 없다.
 * 사용자 로그인은 /api/v1/auth/login (Custom) 만 제공한다.
 *
 * ## permitAll 4경로 (FR-09-30)
 * - /api/v1/auth/login      — 로그인 요청 (credentials 수신, CSRF skip)
 * - /api/v1/auth/providers  — 활성 Provider 목록 조회 (인증 전 필요)
 * - /.well-known/jwks.json  — 공개키 제공 (외부 검증용, CSRF skip)
 * - /actuator/health        — 헬스체크 (로드밸런서, CSRF skip)
 *
 * ## CSRF Cookie 모드 (ADR docs/decisions/2026-05-20-csrf-cookie-mode.md)
 * CookieCsrfTokenRepository.withHttpOnlyFalse() — SPA가 Cookie를 읽어 X-XSRF-TOKEN 헤더로 전송.
 * SameSite=Strict + secure=true 설정 (DEVELOPMENT.md §1.5).
 *
 * ## JWT sid revoke 회로 (FR-09-11 / Task 34)
 * [SidRevokeJwtConverter] 를 jwtAuthenticationConverter 로 등록.
 * 모든 API 요청의 JWT sid 클레임으로 세션 revoke 여부를 Caffeine 캐시(5s TTL)와 함께 확인한다.
 *
 * ## @EnableMethodSecurity
 * 엔드포인트별 @PreAuthorize 이중 가드 (DEVELOPMENT.md §1.4 Spring Security 주의사항).
 * SecurityConfig 필터 체인 + @PreAuthorize 두 레이어로 우회를 방지한다.
 *
 * ## SAML 전용 체인 분리 (FR-AU-03 / 게이트1 D1 — BLOCKER 해소)
 * SAML SP-initiated 로그인은 AuthnRequest 상관관계/replay 방어 상태를 [jakarta.servlet.http.HttpSession]
 * 에 저장하므로 STATELESS 정책과 충돌한다. 따라서 SAML 경로([SAML_PATHS])만 [samlSecurityFilterChain]
 * (Order=1) 이 securityMatcher 로 잡아 [SessionCreationPolicy.IF_REQUIRED] 를 허용하고,
 * 그 외 모든 경로는 기존 [securityFilterChain] (Order=2) 이 STATELESS 로 처리한다.
 * SAML 체인의 세션 허용이 일반 API 의 STATELESS/JWT/CSRF 동작에 영향을 주지 않는다.
 *
 * ## RelyingPartyRegistrationRepository 부팅 안전성 (profile-scoped boot 회귀 방지)
 * SAML 체인은 [RelyingPartyRegistrationRepository] 빈을 결선한다. DB(saml_idp_configs) 가 비어도
 * 부팅 시점에 조회하지 않으므로(요청 시 lazy 조회) non-prod 통합 테스트 컨텍스트 부팅을 깨지 않는다.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig(
    private val sidRevokeJwtConverter: SidRevokeJwtConverter,
    private val corsConfigurationSource: CorsConfigurationSource,
    private val personalAccessTokenService: PersonalAccessTokenService,
) {
    /**
     * SAML 전용 SecurityFilterChain (Order=1 — 일반 체인보다 우선 매칭, FR-AU-03 / 게이트1 D1).
     *
     * [SAML_PATHS] (AuthnRequest 진입 /saml2, /sso/saml2 + ACS 콜백 /login/saml2) 만
     * securityMatcher 로 잡는다. 그 외 경로는 이 체인이 처리하지 않으므로 [securityFilterChain] 으로 넘어간다.
     *
     * - 세션 정책 IF_REQUIRED — saml2Login 필터가 AuthnRequest 상관관계 상태를 HttpSession 에 저장한다.
     * - saml2Login 결선 — DB 기반 [RelyingPartyRegistrationRepository] + SAML 성공 핸들러
     *   [Saml2AuthenticationSuccessHandler] (JIT 프로비저닝 + BTS 세션/JWT 발급).
     * - CSRF skip (ACS 한정) — ACS(/login/saml2/sso) 는 IdP 가 외부에서 POST 하므로 CSRF 토큰을
     *   가질 수 없어 SAML 경로 한정으로 CSRF 를 비활성화한다. 이 체인은 SAML 경로만 처리하므로
     *   일반 API 의 CSRF 검증(기존 [securityFilterChain]) 에는 영향이 없다.
     * - SAML 경로 authenticated — SAML 흐름 자체가 인증 절차이므로 anyRequest authenticated.
     */
    @Bean
    @Order(SAML_CHAIN_ORDER)
    fun samlSecurityFilterChain(
        http: HttpSecurity,
        relyingPartyRegistrationRepository: RelyingPartyRegistrationRepository,
        saml2AuthenticationSuccessHandler: Saml2AuthenticationSuccessHandler,
    ): SecurityFilterChain =
        http
            .securityMatcher(*SAML_PATHS)
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED) }
            .cors { it.configurationSource(corsConfigurationSource) }
            // ACS(/login/saml2/sso/**) 는 외부 IdP POST 이므로 CSRF 토큰 부재 — SAML 경로 한정 CSRF skip.
            // 이 체인은 SAML 경로만 처리하므로 일반 API CSRF 검증(기존 체인)에 영향 없음.
            .csrf { it.disable() }
            .authorizeHttpRequests { auth -> auth.anyRequest().authenticated() }
            .saml2Login { saml2 ->
                saml2.relyingPartyRegistrationRepository(relyingPartyRegistrationRepository)
                saml2.successHandler(saml2AuthenticationSuccessHandler)
            }
            .build()

    @Bean
    @Order(API_CHAIN_ORDER)
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        // CookieCsrfTokenRepository 설정
        // withHttpOnlyFalse(): SPA가 Cookie 토큰 읽어 X-XSRF-TOKEN 헤더로 전송하므로 JS 접근 허용.
        // setCookieCustomizer: SameSite=Strict (DEVELOPMENT.md §1.5, ADR csrf-cookie-mode).
        // secure(true): HTTPS 환경에서 Cookie 전송 제한.
        val csrfRepo =
            CookieCsrfTokenRepository.withHttpOnlyFalse().apply {
                setCookieCustomizer { cookie ->
                    cookie.sameSite("Strict")
                    cookie.secure(true)
                }
            }

        // EC-26: pat_ prefix 토큰은 JWT 파싱 대상에서 제외.
        // DefaultBearerTokenResolver 가 Authorization 헤더에서 Bearer 토큰을 추출하되,
        // pat_ prefix 인 경우 null 을 반환하여 JWT 필터가 처리하지 않도록 한다.
        // PAT 요청은 PatAuthenticationFilter 가 JWT 필터보다 먼저 처리하여 SecurityContext 에 인증 정보를 설정한다.
        val delegate = DefaultBearerTokenResolver()
        val patSkippingBearerTokenResolver = BearerTokenResolver { req: HttpServletRequest ->
            val token = delegate.resolve(req)
            if (token != null && token.startsWith(PersonalAccessToken.TOKEN_PREFIX)) null else token
        }

        return http
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .cors { it.configurationSource(corsConfigurationSource) }
            // DEVELOPMENT.md §1.5 — CSRF 비활성화 금지.
            // login: credentials 수신 전이므로 CSRF skip. jwks.json / actuator: 공개 리소스.
            // refresh: Cookie 기반 엔드포인트. SameSite=Strict refresh_token Cookie 로
            //   CSRF 위험을 동등하게 방어한다 (AuthController KDoc §CSRF 처리 참조).
            //   Bearer 토큰 없이 Cookie 만으로 동작하므로 CSRF skip 추가 (FR-09-21 회귀 방지).
            .csrf { csrf ->
                csrf.csrfTokenRepository(csrfRepo)
                csrf.csrfTokenRequestHandler(CsrfTokenRequestAttributeHandler())
                // PAT Bearer 요청: Authorization 헤더에 pat_ prefix 토큰이 있으면 CSRF skip.
                // PAT는 stateless 자격증명이며 CSRF 공격 벡터(쿠키 기반 세션)가 없다.
                // JWT Bearer 요청은 oauth2ResourceServer가 자동으로 CSRF를 skip한다.
                val patBearerMatcher = RequestMatcher { req: HttpServletRequest ->
                    val header = req.getHeader("Authorization") ?: return@RequestMatcher false
                    header.startsWith("Bearer ${PersonalAccessToken.TOKEN_PREFIX}")
                }
                csrf.ignoringRequestMatchers(
                    patBearerMatcher,
                )
                csrf.ignoringRequestMatchers(
                    "/api/v1/auth/login",
                    "/api/v1/auth/refresh",
                    "/.well-known/jwks.json",
                    "/actuator/**",
                )
            }
            .authorizeHttpRequests { auth ->
                // FR-09-30 permitAll 4경로 + refresh (쿠키 기반, 인증 토큰 불요)
                auth.requestMatchers(
                    "/api/v1/auth/login",
                    "/api/v1/auth/refresh",
                    "/api/v1/auth/providers",
                    // FR-AU-03: 활성 SAML IdP 목록은 로그인 전 호출되므로 permitAll
                    // (민감정보 미노출 — registrationId/displayName 만, SamlIdpController KDoc 참조).
                    SAML_IDPS_PATH,
                    "/.well-known/jwks.json",
                    "/actuator/health",
                ).permitAll()
                auth.requestMatchers("/api/**").authenticated()
                auth.anyRequest().authenticated()
            }
            // PAT Bearer 필터: JWT 필터보다 먼저 실행하여 pat_ prefix 토큰을 SecurityContext 에 설정
            .addFilterBefore(
                PatAuthenticationFilter(personalAccessTokenService),
                BearerTokenAuthenticationFilter::class.java,
            )
            // FR-09-11: SidRevokeJwtConverter — sid claim 으로 세션 revoke 여부 확인 후 인증 토큰 발급
            // bearerTokenResolver: pat_ prefix 토큰은 null 반환하여 JWT 필터가 처리하지 않도록 한다
            .oauth2ResourceServer { rs ->
                rs.bearerTokenResolver(patSkippingBearerTokenResolver)
                rs.jwt { jwt -> jwt.jwtAuthenticationConverter(sidRevokeJwtConverter) }
            }
            .build()
    }

    private companion object {
        /** SAML 전용 체인 우선순위 — 일반 API 체인보다 먼저 매칭한다 (낮을수록 우선). */
        const val SAML_CHAIN_ORDER = 1

        /** 기존 STATELESS API 체인 우선순위 — SAML 경로 외 모든 요청을 처리한다. */
        const val API_CHAIN_ORDER = 2

        /** 로그인 전 호출되는 활성 SAML IdP 목록 엔드포인트 (permitAll, [com.atlas.bts.identity.web.SamlIdpController]). */
        const val SAML_IDPS_PATH = "/api/v1/auth/saml/idps"

        /**
         * SAML 전용 체인이 securityMatcher 로 잡는 경로.
         * - /saml2 — Spring 표준 AuthnRequest 진입 (/saml2/authenticate/REGISTRATION_ID)
         * - /sso/saml2 — SP-initiated 진입 별칭 (게이트1 D1 명시 경로)
         * - /login/saml2 — ACS 콜백 (/login/saml2/sso/REGISTRATION_ID, IdP POST 수신)
         */
        val SAML_PATHS = arrayOf("/saml2/**", "/sso/saml2/**", "/login/saml2/**")
    }
}
