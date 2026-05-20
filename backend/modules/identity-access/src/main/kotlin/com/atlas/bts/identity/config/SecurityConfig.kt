// Spring Security 필터 체인 — /api/v1/** 전체 인증 강제, JWT Resource Server + CSRF Cookie 설정

package com.atlas.bts.identity.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.csrf.CookieCsrfTokenRepository
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler

@Configuration
@EnableWebSecurity
class SecurityConfig {
    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        // CookieCsrfTokenRepository 설정
        // withHttpOnlyFalse(): SPA가 Cookie에서 토큰 읽어 X-XSRF-TOKEN 헤더로 전송해야 하므로 JS 접근 허용.
        // setCookieCustomizer: SameSite=Strict 설정 (DEVELOPMENT.md §1.5 + ADR: docs/decisions/2026-05-20-csrf-cookie-mode.md).
        // T5 CONCERN #2 반영 — ADR에 명시된 SameSite=Strict 의무 사항을 코드로 보장.
        // secure(true): HTTPS 환경에서 Cookie 전송 제한. Spring Security가 dev 환경(HTTP) 자동 완화.
        val csrfRepo =
            CookieCsrfTokenRepository.withHttpOnlyFalse().apply {
                setCookieCustomizer { cookie ->
                    cookie.sameSite("Strict")
                    cookie.secure(true)
                }
            }

        return http
            // DEVELOPMENT.md §1.5 — CSRF 비활성화 금지.
            .csrf { csrf ->
                csrf.csrfTokenRepository(csrfRepo)
                csrf.csrfTokenRequestHandler(CsrfTokenRequestAttributeHandler())
            }
            .authorizeHttpRequests { auth ->
                auth.requestMatchers("/api/v1/**").authenticated()
                auth.anyRequest().permitAll()
            }
            .oauth2ResourceServer { it.jwt {} }
            .build()
    }
}
