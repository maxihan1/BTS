// PreferencesController CSRF 검증 슬라이스 테스트 — CSRF 토큰 없으면 403, 있으면 200 검증

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
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

// OAuth2ClientAutoConfiguration 제외 — Keycloak issuer-uri 네트워크 접속 차단
// JwtDecoder는 MockJwtDecoderConfig으로 모의 빈 제공 (spring-security-test jwt() 포스트 프로세서가 우회)
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
                // csrf() 없음 — CSRF 토큰 미전송
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"),
        ).andExpect(status().isForbidden)
    }

    @Test
    fun `POST with CSRF token returns 200`() {
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
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.ok").value(true))
    }
}
