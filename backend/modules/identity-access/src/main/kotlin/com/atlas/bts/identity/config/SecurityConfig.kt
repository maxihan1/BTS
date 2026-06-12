// Spring Security 필터 체인 — SAML(1)+OIDC(2)+STATELESS(3) 3체인, JWT/PAT/CSRF/CORS (FR-09-26/27/30, FR-AU-03/04)

package com.atlas.bts.identity.config

import com.atlas.bts.identity.jwt.SidRevokeJwtConverter
import com.atlas.bts.identity.pat.PersonalAccessToken
import com.atlas.bts.identity.pat.PersonalAccessTokenService
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
 * SAML SP-initiated 로그인은 AuthnRequest 상관관계/replay 방어 상태를 HttpSession 에 저장하므로
 * STATELESS 정책과 충돌한다. 따라서 SAML 경로만 [SamlSecurityConfig] 의 별도 체인(Order=1)이
 * 잡아 IF_REQUIRED 세션을 허용하고, 그 외 모든 경로는 이 [securityFilterChain] (Order=2)이
 * STATELESS 로 처리한다. SAML 체인의 세션 허용은 일반 API 의 STATELESS/JWT/CSRF 동작에 영향을 주지 않는다.
 * 로그인 전 호출되는 [SAML_IDPS_PATH] 만 이 체인에서 permitAll 로 추가 노출한다.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig(
    private val sidRevokeJwtConverter: SidRevokeJwtConverter,
    private val corsConfigurationSource: CorsConfigurationSource,
    private val personalAccessTokenService: PersonalAccessTokenService,
) {
    // LongMethod 억제 — 단일 SecurityFilterChain DSL 빌더는 필터 등록 순서 의존성 때문에 한 메서드에
    // 응집돼야 하며, csrf/authorizeHttpRequests 설정을 임의 헬퍼로 분해하면 가독성과 순서 보장이 깨진다.
    // FR-MF-01 에서 verify 를 CSRF-ignore + permitAll 양쪽에 등록(BLOCKER-1)하며 임계(60)를 1줄 넘었다.
    @Suppress("LongMethod")
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
        val patSkippingBearerTokenResolver =
            BearerTokenResolver { req: HttpServletRequest ->
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
                val patBearerMatcher =
                    RequestMatcher { req: HttpServletRequest ->
                        val header = req.getHeader("Authorization") ?: return@RequestMatcher false
                        header.startsWith("Bearer ${PersonalAccessToken.TOKEN_PREFIX}")
                    }
                csrf.ignoringRequestMatchers(
                    patBearerMatcher,
                )
                csrf.ignoringRequestMatchers(
                    "/api/v1/auth/login",
                    "/api/v1/auth/refresh",
                    // FR-MF-01: 로그인 2단계 검증 — 정식 세션 전(JWT 없음)이라 login 처럼 명시적 CSRF skip (BLOCKER-1).
                    MFA_VERIFY_PATH,
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
                    // FR-AU-04: 활성 OIDC Provider 목록도 로그인 전 호출되므로 permitAll
                    // (민감정보 미노출 — registrationId/displayName 만, OidcProviderController KDoc 참조).
                    OIDC_PROVIDERS_PATH,
                    // FR-AU-07: 도메인 기반 SSO 라우트 조회도 로그인 전 호출되므로 permitAll.
                    // SAML 체인(Order=1)·OIDC 체인(Order=2)은 /saml2/**·/oauth2/** 만 매칭하므로
                    // /api/v1/auth/route 는 이 STATELESS API 체인(Order=3)에 안전히 떨어진다(체인 충돌 없음).
                    // 도메인만으로 라우트 존재 여부만 판단하며 자격증명을 취급하지 않는다 (DomainRouteController KDoc 참조).
                    ROUTE_PATH,
                    // FR-MF-01: 2단계 검증은 정식 세션 전 호출이라 permitAll (totp/** 는 permitAll 아님 — authenticated).
                    MFA_VERIFY_PATH,
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
            // FR-MF-04: MFA 등록 게이트 — 인증 필터(BearerToken·PAT) 뒤에 등록해 principal 이 가용할 때 동작.
            // 클레임 mfa_enrollment_required=true 인 강제대상 미등록 요청을 allow-list 외 경로에서 403 차단(DB 0).
            // 미인증은 손대지 않고 통과 → 기존 401 흐름 유지(게이트가 401 을 403 으로 바꾸지 않음).
            .addFilterAfter(
                MfaEnrollmentGateFilter(),
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
        /**
         * 기존 STATELESS API 체인 우선순위 — SAML(1)/OIDC(2) 경로 외 모든 요청을 처리한다.
         * SAML 체인(Order=1)·OIDC 체인(Order=2)보다 후순위로, 세 체인 모두 distinct order 를 갖도록
         * 2→3 으로 1칸 밀었다 (FR-AU-04 C1 — @Order 동률 회피, OidcSecurityConfig KDoc 참조).
         */
        const val API_CHAIN_ORDER = 3

        /** 로그인 전 호출되는 활성 SAML IdP 목록 엔드포인트 (permitAll, [com.atlas.bts.identity.web.SamlIdpController]). */
        const val SAML_IDPS_PATH = "/api/v1/auth/saml/idps"

        /** 로그인 전 호출되는 활성 OIDC Provider 목록 엔드포인트 (permitAll, [com.atlas.bts.identity.web.OidcProviderController]). */
        const val OIDC_PROVIDERS_PATH = "/api/v1/auth/oidc/providers"

        /** 로그인 전 호출되는 도메인 기반 SSO 라우트 조회 엔드포인트 (permitAll, [com.atlas.bts.identity.web.DomainRouteController]). */
        const val ROUTE_PATH = "/api/v1/auth/route"

        /**
         * 로그인 2단계 TOTP 검증 엔드포인트 (FR-MF-01, Task 10 구현 예정).
         * 1단계(pw) 통과 후 정식 세션 발급 전에 호출되므로 permitAll + CSRF-ignore 양쪽에 등록한다(BLOCKER-1).
         */
        const val MFA_VERIFY_PATH = "/api/v1/auth/mfa/verify"
    }
}
