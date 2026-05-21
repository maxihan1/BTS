// Spring Security 필터 체인 — 단일 chain, JWT Resource Server + sid revoke wiring + CSRF Cookie + CORS (FR-09-26/27/30)

package com.atlas.bts.identity.config

import com.atlas.bts.identity.jwt.SidRevokeJwtConverter
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.csrf.CookieCsrfTokenRepository
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler
import org.springframework.web.cors.CorsConfigurationSource

/**
 * BTS 단일 SecurityFilterChain 설정 (FR-09-26 / FR-09-27 / FR-09-30).
 *
 * 설계 결정 (BLOCKER #4/#5 해소).
 * - Spring Authorization Server 미도입. RegisteredClient / OAuth2AuthorizationServerConfiguration 사용 금지.
 *   BTS는 JWT를 자체 발급(JwtIssuer)하며 Nimbus-JOSE-JWT 9.40 + spring-security-oauth2-jose 를 직접 사용한다.
 * - /oauth2/token 등 Spring Authorization Server 표준 endpoint 비활성. 공격 표면 축소 (BLOCKER #5).
 * - 단일 SecurityFilterChain Bean — Order(1) AuthServer + Order(2) API 분리 구조 폐기.
 *
 * permitAll 4경로 (FR-09-30).
 * - /api/v1/auth/login      — 로그인 요청 (credentials 수신)
 * - /api/v1/auth/providers  — 활성 Provider 목록 조회 (인증 전 필요)
 * - /.well-known/jwks.json  — 공개키 제공 (외부 검증용)
 * - /actuator/health        — 헬스체크 (로드밸런서)
 *
 * CSRF Cookie 모드 (ADR docs/decisions/2026-05-20-csrf-cookie-mode.md).
 * - CookieCsrfTokenRepository.withHttpOnlyFalse() — SPA가 Cookie 읽어 X-XSRF-TOKEN 헤더로 전송.
 * - login / jwks.json / actuator 경로는 CSRF skip (credentials 수신 이전 + 공개 리소스).
 *
 * JWT sid revoke 회로 (FR-09-11 / Task 34).
 * - [SidRevokeJwtConverter] 를 jwtAuthenticationConverter 로 등록.
 *   모든 API 요청의 JWT sid 클레임으로 세션 revoke 여부를 확인한다.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig(
    private val sidRevokeJwtConverter: SidRevokeJwtConverter,
    private val corsConfigurationSource: CorsConfigurationSource,
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
                // FR-09-30 permitAll 4경로
                auth.requestMatchers(
                    "/api/v1/auth/login",
                    "/api/v1/auth/providers",
                    "/.well-known/jwks.json",
                    "/actuator/health",
                ).permitAll()
                auth.requestMatchers("/api/**").authenticated()
                auth.anyRequest().authenticated()
            }
            // FR-09-11: SidRevokeJwtConverter — sid claim 으로 세션 revoke 여부 확인 후 인증 토큰 발급
            .oauth2ResourceServer { rs ->
                rs.jwt { jwt -> jwt.jwtAuthenticationConverter(sidRevokeJwtConverter) }
            }
            .build()
    }
}
