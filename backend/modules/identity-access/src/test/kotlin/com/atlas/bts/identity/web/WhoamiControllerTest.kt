// WhoamiController 슬라이스 테스트 — 401 미인증 / 200 JWT 인증 두 케이스 검증

package com.atlas.bts.identity.web

import com.atlas.bts.identity.config.SecurityConfig
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import io.mockk.mockk

// OAuth2ClientAutoConfiguration 제외 — @WebMvcTest 환경에서 Keycloak issuer-uri 네트워크 접속 차단
// JwtDecoder는 MockJwtDecoderConfig으로 모의 빈 제공 (spring-security-test jwt() 포스트 프로세서가 우회)
@WebMvcTest(
    controllers = [WhoamiController::class],
    excludeAutoConfiguration = [OAuth2ClientAutoConfiguration::class],
)
@Import(SecurityConfig::class, WhoamiControllerTest.MockJwtDecoderConfig::class)
class WhoamiControllerTest {

    @TestConfiguration
    class MockJwtDecoderConfig {
        @Bean
        fun jwtDecoder(): JwtDecoder = mockk(relaxed = true)
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    @Test
    fun `whoami returns 401 without authentication`() {
        mockMvc.perform(get("/api/v1/users/me/whoami"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `whoami returns 200 with mock JWT`() {
        mockMvc.perform(
            get("/api/v1/users/me/whoami").with(
                jwt().jwt { builder ->
                    builder
                        .subject("alice-id")
                        .claim("preferred_username", "alice")
                        .claim("email", "alice@bts.local")
                },
            ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.username").value("alice"))
            .andExpect(jsonPath("$.email").value("alice@bts.local"))
    }
}
