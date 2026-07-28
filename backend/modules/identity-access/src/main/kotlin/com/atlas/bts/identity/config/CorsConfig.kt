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
                // 컨트롤러가 실제로 등록한 HTTP 메서드 집합을 덮어야 한다.
                // `CorsAllowedMethodsCoverageTest`(app 모듈)가 조립 컨텍스트의 실제 핸들러 매핑과
                // 이 목록의 차집합이 0 인지 강제한다 — 하드코딩 목록끼리 눈으로 대조하지 않는다.
                // OPTIONS 는 등록 핸들러가 없어도 필요하다(preflight 자체가 OPTIONS).
                allowedMethods = listOf("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")
                // Authorization: JWT Bearer / X-XSRF-TOKEN: CSRF Cookie (DEVELOPMENT.md §1.5) / Content-Type: JSON body
                allowedHeaders = listOf("Authorization", "X-XSRF-TOKEN", "Content-Type")
                // ★기본적으로 브라우저는 응답 헤더 중 CORS-safelisted 몇 개만 JS 에 노출한다.
                // Content-Disposition 은 거기에 없으므로 명시하지 않으면 cross-origin 에서
                // `res.headers.get('content-disposition')` 이 항상 null 이 된다.
                // 실사용 4곳 — search.ts:222·298(Export), automation-rules.ts:186(YAML), imports.ts:137(오류 CSV).
                // 전부 이 헤더에서 다운로드 파일명을 뽑는다.
                exposedHeaders = listOf("Content-Disposition")
                // Cookie 기반 세션/CSRF 전달에 필수. 와일드카드 origin과 병용 불가
                allowCredentials = true
            }
        return UrlBasedCorsConfigurationSource().apply {
            registerCorsConfiguration("/api/v1/**", cfg)
        }
    }
}
