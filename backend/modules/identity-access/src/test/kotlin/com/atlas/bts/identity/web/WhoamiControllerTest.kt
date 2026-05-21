// WhoamiController 슬라이스 테스트 — 401 미인증 / 200 JWT / 200 PAT / PAT 만료·revoke → 401 / PAT_USED 감사 이벤트 검증

package com.atlas.bts.identity.web

import com.atlas.bts.identity.audit.AuthAuditLog
import com.atlas.bts.identity.audit.AuthAuditLogService
import com.atlas.bts.identity.audit.AuthEventType
import com.atlas.bts.identity.config.CorsConfig
import com.atlas.bts.identity.config.SecurityConfig
import com.atlas.bts.identity.jwt.SidRevokeJwtConverter
import com.atlas.bts.identity.pat.PatVerificationException
import com.atlas.bts.identity.pat.PersonalAccessToken
import com.atlas.bts.identity.pat.PersonalAccessTokenService
import com.atlas.bts.identity.session.SessionService
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
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
import org.springframework.web.cors.CorsConfigurationSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

// OAuth2ClientAutoConfiguration 제외 — @WebMvcTest 환경에서 Keycloak issuer-uri 네트워크 접속 차단
// SecurityConfig 가 SidRevokeJwtConverter + CorsConfigurationSource Bean 을 요구하므로 MockSecurityBeans 로 공급.
@WebMvcTest(
    controllers = [WhoamiController::class],
    excludeAutoConfiguration = [OAuth2ClientAutoConfiguration::class],
)
@Import(SecurityConfig::class, WhoamiControllerTest.MockSecurityBeans::class)
class WhoamiControllerTest {

    companion object {
        // EC-26: "pat_" prefix 포함 52자 raw token (pat_ + 48자 body)
        private const val RAW_PAT = "pat_aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private val PAT_USER_ID: UUID = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc")
        private val PAT_ID: UUID = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd")
    }

    @TestConfiguration
    class MockSecurityBeans {
        @Bean
        fun jwtDecoder(): JwtDecoder = mockk(relaxed = true)

        @Bean
        fun sidRevokeJwtConverter(): SidRevokeJwtConverter {
            val sessionService: SessionService = mockk(relaxed = true)
            val now = Instant.parse("2026-05-21T10:00:00Z")
            val clock = Clock.fixed(now, ZoneOffset.UTC)
            // WhoamiControllerTest 는 jwt() post-processor 를 사용하므로 실제 SidRevokeJwtConverter 는 호출되지 않음.
            return SidRevokeJwtConverter(sessionService, clock)
        }

        @Bean
        fun corsConfigurationSource(): CorsConfigurationSource =
            CorsConfig().corsConfigurationSource(listOf("http://localhost:5173"))

        @Bean
        fun personalAccessTokenService(): PersonalAccessTokenService = mockk()

        @Bean
        fun authAuditLogService(): AuthAuditLogService = mockk(relaxed = true)
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var personalAccessTokenService: PersonalAccessTokenService

    @Autowired
    lateinit var authAuditLogService: AuthAuditLogService

    // ── 기존 JWT 케이스 (PR #2 회귀 방지) ─────────────────────────────────────

    @Test
    fun `whoami returns 401 without authentication`() {
        mockMvc.perform(get("/api/v1/users/me/whoami"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `whoami returns 200 with mock JWT and authMethod jwt`() {
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
            .andExpect(jsonPath("$.authMethod").value("jwt"))
    }

    // ── PAT 케이스 ──────────────────────────────────────────────────────────────

    @Test
    fun `whoami returns 200 with valid PAT and authMethod pat`() {
        val activePat = PersonalAccessToken(
            id = PAT_ID,
            userId = PAT_USER_ID,
            name = "ci-token",
            tokenHash = "irrelevant-hash",
            scopes = listOf("*"),
            expiresAt = null,
            lastUsedAt = null,
            revokedAt = null,
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        )
        every { personalAccessTokenService.verify(RAW_PAT) } returns Result.success(activePat)

        mockMvc.perform(
            get("/api/v1/users/me/whoami")
                .header("Authorization", "Bearer $RAW_PAT"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.userId").value(PAT_USER_ID.toString()))
            .andExpect(jsonPath("$.authMethod").value("pat"))
    }

    @Test
    fun `whoami returns 401 when PAT is expired`() {
        every { personalAccessTokenService.verify(RAW_PAT) } returns
            Result.failure(PatVerificationException("PAT is expired or revoked"))

        mockMvc.perform(
            get("/api/v1/users/me/whoami")
                .header("Authorization", "Bearer $RAW_PAT"),
        )
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `whoami returns 401 when PAT is revoked`() {
        every { personalAccessTokenService.verify(RAW_PAT) } returns
            Result.failure(PatVerificationException("PAT is expired or revoked"))

        mockMvc.perform(
            get("/api/v1/users/me/whoami")
                .header("Authorization", "Bearer $RAW_PAT"),
        )
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `whoami records PAT_USED audit event on successful PAT authentication`() {
        val activePat = PersonalAccessToken(
            id = PAT_ID,
            userId = PAT_USER_ID,
            name = "ci-token",
            tokenHash = "irrelevant-hash",
            scopes = listOf("*"),
            expiresAt = null,
            lastUsedAt = null,
            revokedAt = null,
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        )
        every { personalAccessTokenService.verify(RAW_PAT) } returns Result.success(activePat)
        val eventSlot = slot<AuthAuditLog>()
        every { authAuditLogService.record(capture(eventSlot)) } returns Unit

        mockMvc.perform(
            get("/api/v1/users/me/whoami")
                .header("Authorization", "Bearer $RAW_PAT"),
        )
            .andExpect(status().isOk)

        verify(exactly = 1) { authAuditLogService.record(any()) }
        assertThat(eventSlot.captured.eventType).isEqualTo(AuthEventType.PAT_USED)
        assertThat(eventSlot.captured.userId).isEqualTo(PAT_USER_ID)
        assertThat(eventSlot.captured.providerId).isEqualTo("pat")
    }
}
