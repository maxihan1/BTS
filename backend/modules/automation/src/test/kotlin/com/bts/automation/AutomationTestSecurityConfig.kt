// automation 통합 테스트용 SecurityFilterChain — 웹훅 permitAll, 그 외 authenticated (FR-AT-01 Task 9)

package com.bts.automation

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint

/**
 * automation 통합 테스트 전용 [SecurityFilterChain] (FR-AT-01 Task 9, slack-integration
 * `SlackTestSecurityConfig` 동형).
 *
 * automation·`spring-security-web`/`-config` 가 클래스패스에 있으면 Spring Boot 기본 보안
 * 자동구성(`SecurityAutoConfiguration`)이 커스텀 필터 체인 부재 시 "전 경로 인증 필요" 기본값을
 * 적용한다. 이 기본값 그대로면 [com.bts.automation.adapter.web.AutomationWebhookController] 의
 * permitAll 설계(토큰이 인증 수단)를 test-boot 에서 검증할 수 없으므로, 이 테스트 전용 필터 체인으로
 * 실제 인가 경계를 재현한다.
 *
 * **중앙 identity-access SecurityConfig 는 건드리지 않는다**(BC 격리, automation 배포조립 부재 —
 * 후속 ADR). 이 설정은 automation test-boot 컨텍스트에만 `@Import` 되어 웹훅 엔드포인트의 permitAll
 * 인가 경계를 실제 필터 체인으로 검증한다.
 *
 * ## 경계 (스펙 §API 표)
 * - `POST /api/v1/automation/webhooks` 하위 전체 — **permitAll**. 인가는 경로 토큰 소지로 대체된다.
 * - 그 외 전부 — **authenticated**. 미인증은 [HttpStatusEntryPoint] 로 401(향후 Task 6 룰 CRUD
 *   엔드포인트도 이 기본값을 상속).
 *
 * ## csrf
 * 웹훅은 세션 기반 인증이 아닌 토큰 소지 기반 서버-투-서버 POST 라 CSRF 검증 대상이 아니다
 * (slack callback 선례 동형 — 테스트 전용 설정, prod 정책 아님).
 */
@TestConfiguration
class AutomationTestSecurityConfig {
    /**
     * automation 웹 레이어 인가 필터 체인.
     *
     * @param http Spring Boot security auto-config 이 제공하는 [HttpSecurity].
     * @return 웹훅 permitAll · 나머지 authenticated · 미인증 401 필터 체인.
     */
    @Bean
    fun automationTestSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .authorizeHttpRequests {
                it.requestMatchers(HttpMethod.POST, "/api/v1/automation/webhooks/**").permitAll()
                it.anyRequest().authenticated()
            }.exceptionHandling {
                it.authenticationEntryPoint(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
            }
        return http.build()
    }
}
