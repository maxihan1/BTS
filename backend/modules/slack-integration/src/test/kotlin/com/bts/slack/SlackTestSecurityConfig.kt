// slack-integration 테스트용 SecurityFilterChain — callback/events/commands permitAll, 나머지 authenticated (FR-SL-01/03/04)

package com.bts.slack

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.HttpStatusEntryPoint

/**
 * slack-integration 통합 테스트 전용 [SecurityFilterChain] (FR-SL-01 Task 9).
 *
 * **중앙 identity-access SecurityConfig 는 건드리지 않는다**(BC 격리, ADR D7 — 배포 조립 후속). 이 설정은
 * slack test-boot 컨텍스트에만 `@Import` 되어 웹 레이어의 인가 경계를 필터 체인으로 실제 검증한다.
 *
 * ## 경계 (spec §API 표)
 * - `GET /slack/install/callback` — **permitAll**. Slack 이 브라우저를 통해 부르는 최상위 GET 리다이렉트라
 *   사용자 JWT 가 없다. 인가는 서명 state 로 대체된다([SlackOAuthStateSigner]).
 * - `POST /slack/events` — **permitAll**(FR-SL-03 Task 12, 정확히 이 메서드+경로만 — `/slack/` 하위 전체를
 *   허용하는 와일드카드 금지). Slack Events API 서버-투-서버 호출이라 사용자 JWT 가 없다. 인가는
 *   [com.bts.slack.security.SlackSignatureVerifier] 서명 검증으로 대체되며, 이 필터 체인은 그 검증 이전
 *   단계(HTTP 레이어)에서만 통과를 허용한다 — 서명 검증 자체는 컨트롤러가 무조건 수행한다.
 * - `POST /slack/commands` — **permitAll**(FR-SL-04 Task 9, `/slack/events` 와 동형 — 정확히 이 메서드+경로만).
 *   Slack slash 명령(`/atlas`) 서버-투-서버 호출이라 사용자 JWT 가 없다. 인가는 동일하게
 *   [com.bts.slack.security.SlackSignatureVerifier] 서명 검증으로 대체된다 — permitAll 은 인증을 없애는 것이
 *   아니라 필터의 JWT 를 컨트롤러의 서명검증으로 교체하는 것이며, 서명 검증은 permitAll 과 무관하게
 *   [com.bts.slack.web.SlackCommandsController] 가 무조건 선행한다(fail-closed, [SlackSlashCommandEndToEndTest]
 *   [com.bts.slack.command.SlackSlashCommandEndToEndTest] 가 실증).
 *
 * ### prod 중앙 배선은 배포 조립 후속 (ADR D8)
 * 이 permitAll 은 slack test-boot 컨텍스트 **한정**이다. prod 는 identity-access 중앙 `SecurityConfig` 에
 * `/slack/events`·automation 웹훅과 함께 `/slack/commands` 를 permitAll 로 등록해야 하며, 그 중앙 배선은
 * 배포 조립 PR 의 몫이다(BC 격리 — 여기서 중앙 config 를 건드리지 않는다,
 * memory `no-cross-bc-deployment-assembly`).
 * - `/api/v1/slack/…` (SPA 상태 조회/설치 URL) — **authenticated**. SPA(Bearer)가 호출하는 JSON view-layer.
 * - `/slack/install` (및 그 외 전부) — **authenticated**. 미인증은 [HttpStatusEntryPoint] 로 401.
 *   (관리자 여부는 컨트롤러 뒤 `SlackInstallService` 가 fail-closed 로 판정해 403 — 필터+서비스 이중 가드.)
 *
 * ## csrf
 * 이 필터 체인은 테스트 전용이라 CSRF 검증을 전부 비활성화한다(`csrf { it.disable() }`) — `/slack/events`
 * 를 위한 별도 CSRF-ignore 규칙이 필요 없다(이미 전역 비활성). prod 정책이 아닌 **테스트 전용** 설정이다.
 */
@TestConfiguration
class SlackTestSecurityConfig {
    /**
     * slack 웹 레이어 인가 필터 체인.
     *
     * @param http Spring Boot security auto-config 이 제공하는 [HttpSecurity].
     * @return callback permitAll · 나머지 authenticated · 미인증 401 필터 체인.
     */
    @Bean
    fun slackTestSecurityFilterChain(http: HttpSecurity): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .authorizeHttpRequests {
                it.requestMatchers(HttpMethod.GET, "/slack/install/callback").permitAll()
                it.requestMatchers(HttpMethod.POST, "/slack/events").permitAll()
                it.requestMatchers(HttpMethod.POST, "/slack/commands").permitAll()
                it.requestMatchers("/api/v1/slack/**").authenticated()
                it.anyRequest().authenticated()
            }.exceptionHandling {
                it.authenticationEntryPoint(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
            }
        return http.build()
    }
}
