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
 * 이 설정은 automation test-boot 컨텍스트에만 `@Import` 되어 웹훅 엔드포인트의 permitAll 인가 경계를
 * 실제 필터 체인으로 검증한다.
 *
 * ## ★ 중앙 [com.atlas.bts.identity.config.SecurityConfig] 와 정합을 유지할 것 (FR-AT-07 PR-C)
 * PR-C 가 두 경로군을 중앙 `INBOUND_WEBHOOK_PATHS` 에 등록했다(ADR `2026-07-17-git-webhook-inbound-permitall`).
 * 여기 매처가 중앙과 다르면 **BC 테스트가 통과해도 prod 가 다르게 동작한다** — 그래서 중앙과 동일하게
 * **메서드 고정 + 단일 세그먼트 와일드카드**로 맞춘다(과거 전역 하위경로 와일드카드 divergence 정합화).
 *
 * ## ★ 이 설정으로 검증할 수 **없는** 것 — CSRF
 * 아래 `csrf { it.disable() }` 때문에 **BC 테스트는 중앙 CSRF-ignore 누락을 원리적으로 잡지 못한다.**
 * 중앙에서 CSRF-ignore 를 빠뜨려도 여기서는 계속 초록이고 prod 에서만 403 이 된다.
 * **app 모듈 prod 조립 HTTP 테스트가 유일한 관문이다**(ADR §결과). 여기 초록을 CSRF 근거로 인용하지 말 것.
 *
 * ## 경계 (스펙 §API 표)
 * - `POST /api/v1/automation/webhooks/{token}` — **permitAll**. 인가는 경로 토큰 소지로 대체된다.
 * - `POST /api/v1/webhooks/git/{token}` — **permitAll**. 인가는 컨트롤러의 서명 검증이 담당한다.
 * - 그 외 전부 — **authenticated**. 미인증은 [HttpStatusEntryPoint] 로 401(룰 CRUD 엔드포인트도 이 기본값을 상속).
 *
 * ## csrf
 * 웹훅은 세션 기반 인증이 아닌 토큰 소지/서명 기반 서버-투-서버 POST 라 CSRF 검증 대상이 아니다
 * (slack callback 선례 동형 — 테스트 전용 설정, prod 정책의 정본이 아니다).
 */
@TestConfiguration
class AutomationTestSecurityConfig {
    /**
     * automation 웹 레이어 인가 필터 체인.
     *
     * 매처는 중앙 `SecurityConfig.INBOUND_WEBHOOK_PATHS` 의 automation·git 항목과 **같은 형태**
     * (POST 고정 + 단일 세그먼트 와일드카드)로 유지한다 — divergence 는 prod 와의 동작 차이로 직결된다.
     *
     * @param http Spring Boot security auto-config 이 제공하는 [HttpSecurity].
     * @return 인바운드 웹훅 permitAll · 나머지 authenticated · 미인증 401 필터 체인.
     */
    @Bean
    fun automationTestSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .authorizeHttpRequests {
                it.requestMatchers(HttpMethod.POST, "/api/v1/automation/webhooks/*").permitAll()
                it.requestMatchers(HttpMethod.POST, "/api/v1/webhooks/git/*").permitAll()
                it.anyRequest().authenticated()
            }.exceptionHandling {
                it.authenticationEntryPoint(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
            }
        return http.build()
    }
}
