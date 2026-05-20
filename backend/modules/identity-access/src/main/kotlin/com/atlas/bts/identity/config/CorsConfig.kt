// CORS 환경별 허용 출처 설정 — /api/v1/** 경로에만 적용 (FR-09-24)

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
                allowedHeaders = listOf("Authorization", "X-XSRF-TOKEN", "Content-Type")
                allowCredentials = true
            }
        return UrlBasedCorsConfigurationSource().apply {
            registerCorsConfiguration("/api/v1/**", cfg)
        }
    }
}
