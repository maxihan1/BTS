// springdoc 경로(/v3/api-docs, /swagger-ui)를 Spring Security 필터 체인에서 제외하는 설정
// CONCERN-1: Spring Security 가 classpath 에 있을 때 문서 경로에 인증을 요구하지 않도록 보장한다.
// WebSecurityCustomizer.ignoring 은 SecurityFilterChain 빈을 추가하지 않으므로
// Spring Boot 자동 설정의 기본 체인이 그대로 유지된다.

package com.bts.issue.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.config.annotation.web.builders.WebSecurity
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer

@Configuration(proxyBeanMethods = false)
class OpenApiSecurityConfig {
    // springdoc 경로를 FilterChainProxy 에서 완전 제외한다.
    // 제외 경로: /v3/api-docs 계열(OpenAPI JSON), /swagger-ui 계열(Swagger UI 정적 리소스)
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
