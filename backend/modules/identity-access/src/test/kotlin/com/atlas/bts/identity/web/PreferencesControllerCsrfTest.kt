// PreferencesController CSRF 검증 슬라이스 테스트
//
// 설계 근거 (Spring Security 6.3.x 동작):
//   jwt() 포스트 프로세서는 내부적으로 CsrfFilter.skipRequest()를 호출한다.
//   Bearer Token(JWT) 방식은 stateless이므로 CSRF 공격 면역 — Spring Security 설계 의도.
//   따라서 "jwt() + CSRF 없음 → 403" 시나리오는 테스트 프레임워크 수준에서 달성 불가.
//
//   대신 두 가지 현실적 시나리오를 검증한다.
//   1. 미인증 POST (CSRF 없음) → 403 — CookieCsrfTokenRepository 활성 시 CSRF 필터가 먼저 차단.
//      (CSRF 필터는 Spring Security 필터 체인에서 인증 필터보다 앞에 위치)
//   2. JWT 인증 POST → 200 (CookieCsrfTokenRepository가 활성화된 상태에서도 정상 동작)
//
//   CSRF 활성화(CookieCsrfTokenRepository) 자체는 SecurityConfig에서 코드로 보장하며,
//   브라우저 폼/SPA 세션 기반 CSRF 검증은 별도 통합 테스트(T6)에서 다룬다.

package com.atlas.bts.identity.web

import com.atlas.bts.identity.config.SecurityConfig
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

// OAuth2ClientAutoConfiguration 제외 — Keycloak issuer-uri 네트워크 접속 차단
// JwtDecoder는 MockJwtDecoderConfig으로 모의 빈 제공
@WebMvcTest(
    controllers = [PreferencesController::class],
    excludeAutoConfiguration = [OAuth2ClientAutoConfiguration::class],
)
@Import(SecurityConfig::class, PreferencesControllerCsrfTest.MockJwtDecoderConfig::class)
class PreferencesControllerCsrfTest {
    @TestConfiguration
    class MockJwtDecoderConfig {
        @Bean
        fun jwtDecoder(): JwtDecoder = mockk(relaxed = true)
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `POST without CSRF token returns 403`() {
        // 미인증 + CSRF 없음 — CookieCsrfTokenRepository 활성 시 CSRF 필터가 먼저 403으로 차단
        // (CsrfFilter는 필터 체인에서 ExceptionTranslationFilter보다 앞에 위치)
        mockMvc.perform(
            post("/api/v1/users/me/preferences")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"),
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `POST with JWT authentication returns 200`() {
        // JWT Bearer Token 인증 — CookieCsrfTokenRepository 활성 상태에서 정상 동작 확인
        // jwt() 포스트 프로세서는 stateless Bearer 방식이므로 CsrfFilter를 skip (Spring Security 설계)
        mockMvc.perform(
            post("/api/v1/users/me/preferences")
                .with(
                    jwt().jwt { builder ->
                        builder
                            .subject("alice-id")
                            .claim("preferred_username", "alice")
                            .claim("email", "alice@bts.local")
                    },
                )
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.ok").value(true))
    }
}
