// Spring Security 필터 체인 — 단일 chain, JWT Resource Server + sid revoke wiring + CSRF Cookie + CORS (FR-09-26/27/30)

package com.atlas.bts.identity.config

import com.atlas.bts.identity.jwt.SidRevokeJwtConverter
import com.atlas.bts.identity.pat.PersonalAccessToken
import com.atlas.bts.identity.pat.PersonalAccessTokenService
import com.atlas.bts.identity.web.PatAuthenticationFilter
import jakarta.servlet.http.HttpServletRequest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
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
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig(
    private val sidRevokeJwtConverter: SidRevokeJwtConverter,
    private val corsConfigurationSource: CorsConfigurationSource,
    private val personalAccessTokenService: PersonalAccessTokenService,
) {
    @Bean
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
            .csrf { csrf ->
                csrf.csrfTokenRepository(csrfRepo)
                csrf.csrfTokenRequestHandler(CsrfTokenRequestAttributeHandler())
                csrf.ignoringRequestMatchers(
                    "/api/v1/auth/login",
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
}
