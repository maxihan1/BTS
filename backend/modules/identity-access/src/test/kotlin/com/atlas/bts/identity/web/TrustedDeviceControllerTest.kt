// TrustedDeviceController 슬라이스 테스트 — 목록/단건취소/전체취소 + PAT 403 + IDOR 404 (FR-MF-05 Task 5)

package com.atlas.bts.identity.web

import com.atlas.bts.identity.config.CorsConfig
import com.atlas.bts.identity.config.SecurityConfig
import com.atlas.bts.identity.jwt.SidRevokeJwtConverter
import com.atlas.bts.identity.mfa.TrustedDevice
import com.atlas.bts.identity.mfa.TrustedDeviceService
import com.atlas.bts.identity.pat.PersonalAccessTokenService
import com.atlas.bts.identity.session.SessionService
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.cors.CorsConfigurationSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * TrustedDeviceController WebMvcTest 슬라이스 테스트 (FR-MF-05 Task 5).
 *
 * ## 검증 시나리오
 * - GET /trusted-devices — JWT → 200 + {devices:[{id,label,createdAt,lastUsedAt,expiresAt}]} (token_hash 비노출)
 * - GET /trusted-devices — 빈 목록 → 200 + {devices:[]}
 * - GET /trusted-devices — PAT(Jwt null) → 403 session_management_requires_interactive_login
 * - GET /trusted-devices — 미인증 → 401 (Spring Security 필터)
 * - DELETE /trusted-devices/{id} — 소유 일치 삭제 → 204
 * - DELETE /trusted-devices/{id} — 타인/미존재(IDOR) → 404 not_found (존재 여부 미노출)
 * - DELETE /trusted-devices/{id} — PAT → 403 / 미인증 → 401
 * - DELETE /trusted-devices — 전체 취소 → 204
 * - DELETE /trusted-devices — PAT → 403 / 미인증 → 401
 *
 * ## 의존성 모킹 전략 (MfaControllerTest 선례 동일)
 * - SecurityConfig 필수 Bean(JwtDecoder/SidRevokeJwtConverter/CorsConfigurationSource/
 *   PersonalAccessTokenService): @TestConfiguration + MockK.
 * - 컨트롤러 의존 TrustedDeviceService: @MockBean(Mockito) — companion object ByteBuddy 문제 회피.
 */
@WebMvcTest(
    controllers = [TrustedDeviceController::class],
    excludeAutoConfiguration = [OAuth2ClientAutoConfiguration::class],
)
@Import(SecurityConfig::class, TrustedDeviceControllerTest.SecurityBeans::class)
class TrustedDeviceControllerTest {
    @TestConfiguration
    class SecurityBeans {
        @Bean
        fun jwtDecoder(): JwtDecoder = mockk(relaxed = true)

        @Bean
        fun clock(): Clock = Clock.fixed(Instant.parse("2026-06-13T10:00:00Z"), ZoneOffset.UTC)

        @Bean
        fun sidRevokeJwtConverter(clock: Clock): SidRevokeJwtConverter {
            val sessionService: SessionService = mockk(relaxed = true)
            return SidRevokeJwtConverter(sessionService, clock)
        }

        @Bean
        fun corsConfigurationSource(): CorsConfigurationSource {
            return CorsConfig().corsConfigurationSource(listOf("http://localhost:5173"))
        }

        @Bean
        fun personalAccessTokenService(): PersonalAccessTokenService = mockk(relaxed = true)
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    @MockBean
    lateinit var trustedDeviceService: TrustedDeviceService

    private val userId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val deviceId = UUID.fromString("22222222-2222-2222-2222-222222222222")

    private fun jwtFor(uid: UUID) =
        jwt().jwt { builder ->
            builder.subject(uid.toString()).claim("sid", UUID.randomUUID().toString())
        }

    private fun deviceWith(
        id: UUID = deviceId,
        label: String? = "Chrome on macOS",
        lastUsedAt: Instant? = Instant.parse("2026-06-12T09:00:00Z"),
    ): TrustedDevice =
        TrustedDevice(
            id = id,
            userId = userId,
            // 64자 소문자 hex — TrustedDevice init 블록이 형식을 강제한다. 응답에 노출되면 안 되는 비밀값.
            tokenHash = "a".repeat(64),
            label = label,
            createdAt = Instant.parse("2026-06-10T08:00:00Z"),
            expiresAt = Instant.parse("2026-07-10T08:00:00Z"),
            lastUsedAt = lastUsedAt,
        )

    // ── GET /trusted-devices ──────────────────────────────────────────────────────

    @Test
    fun `GET trusted-devices with JWT returns 200 with device summaries`() {
        `when`(trustedDeviceService.list(anyUuid())).thenReturn(listOf(deviceWith()))

        mockMvc.perform(get("/api/v1/auth/mfa/trusted-devices").with(jwtFor(userId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.devices.length()").value(1))
            .andExpect(jsonPath("$.devices[0].id").value(deviceId.toString()))
            .andExpect(jsonPath("$.devices[0].label").value("Chrome on macOS"))
            .andExpect(jsonPath("$.devices[0].createdAt").value("2026-06-10T08:00:00Z"))
            .andExpect(jsonPath("$.devices[0].lastUsedAt").value("2026-06-12T09:00:00Z"))
            .andExpect(jsonPath("$.devices[0].expiresAt").value("2026-07-10T08:00:00Z"))
    }

    /**
     * token_hash(SHA-256 해시)는 응답 JSON 어디에도 노출되면 안 된다(§1.1.1 — 비밀값 미노출).
     * 응답 DTO 가 도메인 [TrustedDevice] 를 그대로 직렬화하면 tokenHash 가 새므로, DTO 변환을 강제하는 회귀 가드다.
     */
    @Test
    fun `GET trusted-devices never exposes token_hash`() {
        `when`(trustedDeviceService.list(anyUuid())).thenReturn(listOf(deviceWith()))

        mockMvc.perform(get("/api/v1/auth/mfa/trusted-devices").with(jwtFor(userId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.devices[0].tokenHash").doesNotExist())
            .andExpect(jsonPath("$.devices[0].token_hash").doesNotExist())
            .andExpect(jsonPath("$.devices[0].userId").doesNotExist())
    }

    @Test
    fun `GET trusted-devices with no devices returns 200 with empty list`() {
        `when`(trustedDeviceService.list(anyUuid())).thenReturn(emptyList())

        mockMvc.perform(get("/api/v1/auth/mfa/trusted-devices").with(jwtFor(userId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.devices.length()").value(0))
    }

    @Test
    fun `GET trusted-devices with PAT returns 403`() {
        mockMvc.perform(get("/api/v1/auth/mfa/trusted-devices").with(user("pat-user-id")))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error").value("session_management_requires_interactive_login"))

        verify(trustedDeviceService, never()).list(anyUuid())
    }

    @Test
    fun `GET trusted-devices without authentication returns 401`() {
        mockMvc.perform(get("/api/v1/auth/mfa/trusted-devices"))
            .andExpect(status().isUnauthorized)
    }

    // ── DELETE /trusted-devices/{id} ──────────────────────────────────────────────

    @Test
    fun `DELETE trusted-device when owned returns 204`() {
        `when`(trustedDeviceService.revoke(anyUuid(), anyUuid())).thenReturn(true)

        mockMvc.perform(delete("/api/v1/auth/mfa/trusted-devices/{id}", deviceId).with(jwtFor(userId)).with(csrf()))
            .andExpect(status().isNoContent)

        verify(trustedDeviceService).revoke(eqUuid(userId), eqUuid(deviceId))
    }

    /**
     * 타인 소유/미존재는 모두 **404 not_found** 로 응답해 타인 디바이스의 존재 여부를 노출하지 않는다(OWASP IDOR).
     * 서비스가 false(소유 불일치/미존재)를 반환하면 컨트롤러는 404 로 일반화한다.
     */
    @Test
    fun `DELETE trusted-device when not owned or missing returns 404 not_found`() {
        `when`(trustedDeviceService.revoke(anyUuid(), anyUuid())).thenReturn(false)

        mockMvc.perform(delete("/api/v1/auth/mfa/trusted-devices/{id}", deviceId).with(jwtFor(userId)).with(csrf()))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("not_found"))
    }

    @Test
    fun `DELETE trusted-device with PAT returns 403`() {
        mockMvc.perform(
            delete("/api/v1/auth/mfa/trusted-devices/{id}", deviceId).with(user("pat-user-id")).with(csrf()),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error").value("session_management_requires_interactive_login"))

        verify(trustedDeviceService, never()).revoke(anyUuid(), anyUuid())
    }

    @Test
    fun `DELETE trusted-device without authentication returns 401`() {
        mockMvc.perform(delete("/api/v1/auth/mfa/trusted-devices/{id}", deviceId).with(csrf()))
            .andExpect(status().isUnauthorized)
    }

    // ── DELETE /trusted-devices (전체) ────────────────────────────────────────────

    @Test
    fun `DELETE all trusted-devices returns 204`() {
        `when`(trustedDeviceService.revokeAll(anyUuid())).thenReturn(2)

        mockMvc.perform(delete("/api/v1/auth/mfa/trusted-devices").with(jwtFor(userId)).with(csrf()))
            .andExpect(status().isNoContent)

        verify(trustedDeviceService).revokeAll(eqUuid(userId))
    }

    @Test
    fun `DELETE all trusted-devices when none exist still returns 204`() {
        `when`(trustedDeviceService.revokeAll(anyUuid())).thenReturn(0)

        mockMvc.perform(delete("/api/v1/auth/mfa/trusted-devices").with(jwtFor(userId)).with(csrf()))
            .andExpect(status().isNoContent)
    }

    @Test
    fun `DELETE all trusted-devices with PAT returns 403`() {
        mockMvc.perform(delete("/api/v1/auth/mfa/trusted-devices").with(user("pat-user-id")).with(csrf()))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error").value("session_management_requires_interactive_login"))

        verify(trustedDeviceService, never()).revokeAll(anyUuid())
    }

    @Test
    fun `DELETE all trusted-devices without authentication returns 401`() {
        mockMvc.perform(delete("/api/v1/auth/mfa/trusted-devices").with(csrf()))
            .andExpect(status().isUnauthorized)
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

    /** Kotlin non-null UUID 파라미터용 Mockito any() 매처. */
    private fun anyUuid(): UUID = org.mockito.ArgumentMatchers.any(UUID::class.java) ?: UUID.randomUUID()

    /** Kotlin non-null UUID 파라미터용 Mockito eq() 매처. */
    private fun eqUuid(value: UUID): UUID = org.mockito.ArgumentMatchers.eq(value) ?: value
}
