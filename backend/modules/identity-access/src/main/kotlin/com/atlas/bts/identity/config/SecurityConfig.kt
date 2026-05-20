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
        return http
            // DEVELOPMENT.md §1.5 — CSRF 비활성화 금지.
            // withHttpOnlyFalse(): SPA가 Cookie에서 토큰 읽어 X-XSRF-TOKEN 헤더로 전송해야 하므로 JS 접근 허용.
            // XSS 보완은 SameSite=Strict + 입력 sanitization 의존 (ADR: docs/decisions/2026-05-20-csrf-cookie-mode.md).
            .csrf { csrf ->
                csrf.csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
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
