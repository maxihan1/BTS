// search-export-import 모듈 OpenAPI 3.1 전역 설정 — springdoc + bearerAuth JWT 보안 스킴 정의

package com.bts.search.config

import io.swagger.v3.oas.annotations.OpenAPIDefinition
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType
import io.swagger.v3.oas.annotations.info.Info
import io.swagger.v3.oas.annotations.security.SecurityScheme
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.WebSecurity
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer

/** bearerAuth 보안 스킴 이름. SearchController 의 SecurityRequirement 에서 참조한다. */
const val BEARER_AUTH_SCHEME = "bearerAuth"

private const val API_TITLE = "Atlas Search API"
private const val API_VERSION = "1.0"
private const val API_DESCRIPTION = "Atlas Search AQL 이슈 검색 REST API. JWT Bearer 인증을 사용한다."

/**
 * search-export-import 모듈 OpenAPI 3.1 전역 설정.
 *
 * springdoc 에 API 기본 정보(제목·버전·설명)와 bearerAuth JWT 보안 스킴을 등록한다.
 * [openApiWebSecurityCustomizer] 가 springdoc 경로를 Spring Security 필터 체인 밖으로 이동시켜
 * /v3/api-docs 에 인증 없이 접근할 수 있게 한다.
 *
 * issue-tracking 의 OpenApiConfig + OpenApiSecurityConfig 와 동일한 역할이다.
 * search-export-import 는 단일 파일로 통합한다.
 */
@Configuration(proxyBeanMethods = false)
@OpenAPIDefinition(
    info =
        Info(
            title = API_TITLE,
            version = API_VERSION,
            description = API_DESCRIPTION,
        ),
)
@SecurityScheme(
    name = BEARER_AUTH_SCHEME,
    type = SecuritySchemeType.HTTP,
    scheme = "bearer",
    bearerFormat = "JWT",
)
class OpenApiConfig {
    /**
     * springdoc 경로를 Spring Security FilterChainProxy 에서 제외한다.
     *
     * WebSecurityCustomizer.ignoring 은 SecurityFilterChain 빈을 추가하지 않으므로
     * Spring Boot 자동 설정의 기본 체인이 그대로 유지된다.
     *
     * 제외 경로.
     * - /v3/api-docs 계열 — OpenAPI 3.1 JSON 스펙
     * - /swagger-ui 계열 — Swagger UI 정적 리소스 + 진입 HTML
     */
    @Bean
    fun openApiWebSecurityCustomizer(): WebSecurityCustomizer =
        WebSecurityCustomizer { web: WebSecurity ->
            web.ignoring().requestMatchers(
                "/v3/api-docs/**",
                "/swagger-ui/**",
                "/swagger-ui.html",
            )
        }
}
