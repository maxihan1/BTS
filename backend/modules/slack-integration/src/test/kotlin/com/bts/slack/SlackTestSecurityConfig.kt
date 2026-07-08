// slack-integration 통합 테스트용 SecurityFilterChain — callback permitAll, install authenticated (FR-SL-01 Task 9)

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
 * - `/slack/install` (및 그 외 전부) — **authenticated**. 미인증은 [HttpStatusEntryPoint] 로 401.
 *   (관리자 여부는 컨트롤러 뒤 `SlackInstallService` 가 fail-closed 로 판정해 403 — 필터+서비스 이중 가드.)
 *
 * ## csrf
 * 두 엔드포인트 모두 GET(안전 메서드)이라 CSRF 검증 대상이 아니다. 테스트 편의상 비활성화한다(콜백의
 * permitAll 도달성만 확인). prod 정책이 아닌 **테스트 전용** 설정이다.
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
                it.anyRequest().authenticated()
            }.exceptionHandling {
                it.authenticationEntryPoint(HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED))
            }
        return http.build()
    }
}
