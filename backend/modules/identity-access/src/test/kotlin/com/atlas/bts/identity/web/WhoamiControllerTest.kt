// WhoamiController 슬라이스 테스트 — 401 미인증 / 200 JWT 인증 두 케이스 검증

package com.atlas.bts.identity.web

import com.atlas.bts.identity.config.SecurityConfig
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status

@WebMvcTest(WhoamiController::class)
@Import(SecurityConfig::class)
class WhoamiControllerTest {

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
