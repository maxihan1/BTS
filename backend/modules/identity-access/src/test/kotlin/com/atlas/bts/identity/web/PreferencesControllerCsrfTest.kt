// PreferencesController CSRF 검증 슬라이스 테스트
//
// 설계 근거 (Spring Security 6.3.x 동작):
//   jwt() 포스트 프로세서는 내부적으로 CsrfFilter.skipRequest()를 호출한다.
//   Bearer Token(JWT) 방식은 stateless이므로 CSRF 공격 면역 — Spring Security 설계 의도.
//   따라서 "jwt() + CSRF 없음 → 403" 시나리오는 테스트 프레임워크 수준에서 달성 불가.
//
//   대신 두 가지 현실적 시나리오를 검증한다 (상태변경 PATCH 대상 — FR-PF-01 Task 3).
//   1. 미인증 PATCH (CSRF 없음) → 403 — CookieCsrfTokenRepository 활성 시 CSRF 필터가 먼저 차단.
//      (CSRF 필터는 Spring Security 필터 체인에서 인증 필터보다 앞에 위치)
//   2. JWT 인증 PATCH → 200 (CookieCsrfTokenRepository가 활성화된 상태에서도 정상 동작)
//
//   CSRF 활성화(CookieCsrfTokenRepository) 자체는 SecurityConfig에서 코드로 보장하며,
//   브라우저 폼/SPA 세션 기반 CSRF 검증은 별도 통합 테스트(T6)에서 다룬다.

package com.atlas.bts.identity.web

import com.atlas.bts.identity.config.CorsConfig
import com.atlas.bts.identity.config.SecurityConfig
import com.atlas.bts.identity.jwt.SidRevokeJwtConverter
import com.atlas.bts.identity.pat.PersonalAccessTokenService
import com.atlas.bts.identity.preferences.PreferencesPatch
import com.atlas.bts.identity.preferences.UserPreferences
import com.atlas.bts.identity.preferences.UserPreferencesService
import com.atlas.bts.identity.session.SessionService
import io.mockk.every
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.cors.CorsConfigurationSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

// OAuth2ClientAutoConfiguration 제외 — Keycloak issuer-uri 네트워크 접속 차단
// SecurityConfig 가 SidRevokeJwtConverter + CorsConfigurationSource Bean 을 요구하므로 MockSecurityBeans 로 공급.
@WebMvcTest(
    controllers = [PreferencesController::class],
    excludeAutoConfiguration = [OAuth2ClientAutoConfiguration::class],
)
@Import(SecurityConfig::class, PreferencesControllerCsrfTest.MockSecurityBeans::class)
class PreferencesControllerCsrfTest {
    @TestConfiguration
    class MockSecurityBeans {
        @Bean
        fun jwtDecoder(): JwtDecoder = mockk(relaxed = true)

        @Bean
        fun sidRevokeJwtConverter(): SidRevokeJwtConverter {
            val sessionService: SessionService = mockk(relaxed = true)
            val now = Instant.parse("2026-05-21T10:00:00Z")
            val clock = Clock.fixed(now, ZoneOffset.UTC)
            return SidRevokeJwtConverter(sessionService, clock)
        }

        @Bean
        fun corsConfigurationSource(): CorsConfigurationSource {
            return CorsConfig().corsConfigurationSource(listOf("http://localhost:5173"))
        }

        // SecurityConfig 가 PatAuthenticationFilter 생성을 위해 요구하는 Bean.
        // PreferencesController CSRF 검증 자체는 PAT 를 사용하지 않으나 SecurityFilterChain 빌드 시점에 필요하다.
        @Bean
        fun personalAccessTokenService(): PersonalAccessTokenService = mockk(relaxed = true)

        // PreferencesController 생성자 의존성. CSRF 데모 흐름(JWT PATCH → 200)에서만 호출된다.
        @Bean
        fun userPreferencesService(): UserPreferencesService = mockk(relaxed = true)
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var userPreferencesService: UserPreferencesService

    @Test
    fun `PATCH without CSRF token returns 403`() {
        // 미인증 + CSRF 없음 — CookieCsrfTokenRepository 활성 시 CSRF 필터가 먼저 403으로 차단
        // (CsrfFilter는 필터 체인에서 ExceptionTranslationFilter보다 앞에 위치)
        mockMvc.perform(
            patch("/api/v1/users/me/preferences")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"),
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `PATCH with JWT authentication returns 200`() {
        // JWT Bearer Token 인증 — CookieCsrfTokenRepository 활성 상태에서 정상 동작 확인
        // jwt() 포스트 프로세서는 stateless Bearer 방식이므로 CsrfFilter를 skip (Spring Security 설계)
        val userId = UUID.fromString("11111111-1111-4111-8111-111111111111")
        every { userPreferencesService.patchPreferences(userId, PreferencesPatch()) } returns
            UserPreferences(userId = userId, theme = "dark", locale = "ko", dateFormat = "iso")

        mockMvc.perform(
            patch("/api/v1/users/me/preferences")
                .with(
                    jwt().jwt { builder ->
                        builder
                            .subject(userId.toString())
                            .claim("preferred_username", "alice")
                            .claim("email", "alice@bts.local")
                    },
                )
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.theme").value("dark"))
    }
}
