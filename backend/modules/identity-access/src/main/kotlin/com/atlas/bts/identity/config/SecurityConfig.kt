// Spring Security 필터 체인 — /api/v1/** 전체 인증 강제, JWT Resource Server 설정
// T5에서 CSRF CookieCsrfTokenRepository로 교체 예정 (현재 PoC 진행을 위해 임시 disable)

package com.atlas.bts.identity.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.web.SecurityFilterChain

@Configuration
@EnableWebSecurity
class SecurityConfig {

    @Bean
    fun securityFilterChain(http: HttpSecurity): SecurityFilterChain {
        return http
            // T5에서 CookieCsrfTokenRepository로 교체 — DEVELOPMENT.md §1.5
            .csrf { it.disable() }
            .authorizeHttpRequests { auth ->
                auth.requestMatchers("/api/v1/**").authenticated()
                auth.anyRequest().permitAll()
            }
            .oauth2ResourceServer { it.jwt {} }
            .build()
    }
}
