// CORS 환경별 허용 출처 설정 — /api/v1/** 경로에만 적용 (FR-09-24)
// Task 25 에서 application.yml bts.security.cors.allowed-origins 값을 주입받는다.
// dev: http://localhost:5173 / prod: BTS_FRONTEND_ORIGIN override (application-prod.yml).
// allowCredentials=true + 와일드카드 origin 병용 금지 — 반드시 명시적 출처 목록 사용.

package com.atlas.bts.identity.config

import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.CorsConfigurationSource
import org.springframework.web.cors.UrlBasedCorsConfigurationSource

@Configuration
class CorsConfig {
    @Bean
    fun corsConfigurationSource(
        @Value("\${bts.security.cors.allowed-origins}") origins: List<String>,
    ): CorsConfigurationSource {
        val cfg =
            CorsConfiguration().apply {
                allowedOrigins = origins
                allowedMethods = listOf("GET", "POST", "PUT", "DELETE", "OPTIONS")
                // Authorization: JWT Bearer / X-XSRF-TOKEN: CSRF Cookie (DEVELOPMENT.md §1.5) / Content-Type: JSON body
                allowedHeaders = listOf("Authorization", "X-XSRF-TOKEN", "Content-Type")
                // Cookie 기반 세션/CSRF 전달에 필수. 와일드카드 origin과 병용 불가
                allowCredentials = true
            }
        return UrlBasedCorsConfigurationSource().apply {
            registerCorsConfiguration("/api/v1/**", cfg)
        }
    }
}
