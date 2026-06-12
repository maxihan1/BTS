// WhoamiController 슬라이스 테스트 — 401 미인증 / 200 JWT / 200 PAT / PAT 만료·revoke → 401 / PAT_USED 감사 이벤트 검증

package com.atlas.bts.identity.web

import com.atlas.bts.identity.audit.AuthAuditLog
import com.atlas.bts.identity.audit.AuthAuditLogService
import com.atlas.bts.identity.audit.AuthEventType
import com.atlas.bts.identity.config.CorsConfig
import com.atlas.bts.identity.config.SecurityConfig
import com.atlas.bts.identity.credential.StoredPasswordCredential
import com.atlas.bts.identity.credential.StoredPasswordCredentialRepository
import com.atlas.bts.identity.jwt.JwtIssuer
import com.atlas.bts.identity.jwt.SidRevokeJwtConverter
import com.atlas.bts.identity.pat.PatVerificationException
import com.atlas.bts.identity.pat.PersonalAccessToken
import com.atlas.bts.identity.pat.PersonalAccessTokenService
import com.atlas.bts.identity.session.SessionService
import com.atlas.bts.identity.user.User
import com.atlas.bts.identity.user.UserRepository
import com.bts.shared.permission.SystemPermissionResolver
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
        fun corsConfigurationSource(): CorsConfigurationSource {
            return CorsConfig().corsConfigurationSource(listOf("http://localhost:5173"))
        }

        @Bean
        fun personalAccessTokenService(): PersonalAccessTokenService = mockk()

        @Bean
        fun authAuditLogService(): AuthAuditLogService = mockk(relaxed = true)

        @Bean
        fun userRepository(): UserRepository = mockk(relaxed = true)

        @Bean
        fun storedPasswordCredentialRepository(): StoredPasswordCredentialRepository = mockk(relaxed = true)

        @Bean
        fun systemPermissionResolver(): SystemPermissionResolver = mockk(relaxed = true)
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var personalAccessTokenService: PersonalAccessTokenService

    @Autowired
    lateinit var authAuditLogService: AuthAuditLogService

    @Autowired
    lateinit var userRepository: UserRepository

    @Autowired
    lateinit var storedPasswordCredentialRepository: StoredPasswordCredentialRepository

    @Autowired
    lateinit var systemPermissionResolver: SystemPermissionResolver

    // ── 기존 JWT 케이스 (PR #2 회귀 방지) ─────────────────────────────────────

    @Test
    fun `whoami returns 401 without authentication`() {
        mockMvc.perform(get("/api/v1/users/me/whoami"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `whoami returns 200 with mock JWT and authMethod jwt`() {
        val aliceId = UUID.fromString("00000000-0000-0000-0000-000000000001")
        val now = Instant.parse("2026-05-21T10:00:00Z")
        every { userRepository.findById(aliceId) } returns
            User(
                id = aliceId,
                username = "alice",
                email = "alice@bts.local",
                displayName = "Alice",
                createdAt = now,
                updatedAt = now,
            )
        // 일반 사용자: 강제 변경 플래그 없음(false), 시스템 관리자 아님(false)
        val cred = credential(aliceId, mustChange = false)
        every { storedPasswordCredentialRepository.findByUserId(aliceId) } returns cred
        every { systemPermissionResolver.isSystemAdmin(aliceId) } returns false

        mockMvc.perform(
            get("/api/v1/users/me/whoami").with(
                jwt().jwt { builder ->
                    builder.subject(aliceId.toString())
                },
            ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.username").value("alice"))
            .andExpect(jsonPath("$.email").value("alice@bts.local"))
            .andExpect(jsonPath("$.authMethod").value("jwt"))
            .andExpect(jsonPath("$.userId").value(aliceId.toString()))
            .andExpect(jsonPath("$.mustChangePassword").value(false))
            .andExpect(jsonPath("$.isSystemAdmin").value(false))
    }

    @Test
    fun `whoami returns 401 when JWT subject is not a valid UUID`() {
        mockMvc.perform(
            get("/api/v1/users/me/whoami").with(
                jwt().jwt { builder -> builder.subject("not-a-uuid") },
            ),
        )
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `whoami returns 401 when User is not found in DB`() {
        val unknownId = UUID.fromString("99999999-9999-9999-9999-999999999999")
        every { userRepository.findById(unknownId) } returns null
        mockMvc.perform(
            get("/api/v1/users/me/whoami").with(
                jwt().jwt { builder -> builder.subject(unknownId.toString()) },
            ),
        )
            .andExpect(status().isUnauthorized)
    }

    // ── PAT 케이스 ──────────────────────────────────────────────────────────────

    @Test
    fun `whoami returns 200 with valid PAT and authMethod pat`() {
        val activePat =
            PersonalAccessToken(
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
            // PAT 분기는 비밀번호/시스템역할 컨텍스트를 노출하지 않으므로 둘 다 false 고정
            .andExpect(jsonPath("$.mustChangePassword").value(false))
            .andExpect(jsonPath("$.isSystemAdmin").value(false))
    }

    @Test
    fun `whoami returns 401 when PAT is expired`() {
        val failure = Result.failure<PersonalAccessToken>(PatVerificationException("PAT is expired or revoked"))
        every { personalAccessTokenService.verify(RAW_PAT) } returns failure

        mockMvc.perform(
            get("/api/v1/users/me/whoami")
                .header("Authorization", "Bearer $RAW_PAT"),
        )
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `whoami returns 401 when PAT is revoked`() {
        val failure = Result.failure<PersonalAccessToken>(PatVerificationException("PAT is expired or revoked"))
        every { personalAccessTokenService.verify(RAW_PAT) } returns failure

        mockMvc.perform(
            get("/api/v1/users/me/whoami")
                .header("Authorization", "Bearer $RAW_PAT"),
        )
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `whoami records PAT_USED audit event on successful PAT authentication`() {
        val activePat =
            PersonalAccessToken(
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

    // ── Task 5: mustChangePassword / isSystemAdmin (FR-AU-05) ────────────────────

    @Test
    fun `whoami JWT 사용자의 강제 변경 플래그가 true 이면 mustChangePassword true 반영`() {
        val bobId = UUID.fromString("00000000-0000-0000-0000-000000000002")
        val now = Instant.parse("2026-05-21T10:00:00Z")
        every { userRepository.findById(bobId) } returns
            User(
                id = bobId,
                username = "bob",
                email = "bob@bts.local",
                displayName = "Bob",
                createdAt = now,
                updatedAt = now,
            )
        every { storedPasswordCredentialRepository.findByUserId(bobId) } returns credential(bobId, mustChange = true)
        every { systemPermissionResolver.isSystemAdmin(bobId) } returns false

        mockMvc.perform(
            get("/api/v1/users/me/whoami").with(
                jwt().jwt { builder -> builder.subject(bobId.toString()) },
            ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.mustChangePassword").value(true))
            .andExpect(jsonPath("$.isSystemAdmin").value(false))
    }

    @Test
    fun `whoami JWT 사용자가 시스템 관리자이면 isSystemAdmin true 반영`() {
        val adminId = UUID.fromString("00000000-0000-0000-0000-000000000003")
        val now = Instant.parse("2026-05-21T10:00:00Z")
        every { userRepository.findById(adminId) } returns
            User(
                id = adminId,
                username = "admin",
                email = "admin@bts.local",
                displayName = "Admin",
                createdAt = now,
                updatedAt = now,
            )
        val cred = credential(adminId, mustChange = false)
        every { storedPasswordCredentialRepository.findByUserId(adminId) } returns cred
        every { systemPermissionResolver.isSystemAdmin(adminId) } returns true

        mockMvc.perform(
            get("/api/v1/users/me/whoami").with(
                jwt().jwt { builder -> builder.subject(adminId.toString()) },
            ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.mustChangePassword").value(false))
            .andExpect(jsonPath("$.isSystemAdmin").value(true))
    }

    @Test
    fun `whoami JWT 사용자의 local_credentials 행이 없으면 mustChangePassword false (SSO 사용자)`() {
        val ssoId = UUID.fromString("00000000-0000-0000-0000-000000000004")
        val now = Instant.parse("2026-05-21T10:00:00Z")
        every { userRepository.findById(ssoId) } returns
            User(
                id = ssoId,
                username = "sso-user",
                email = "sso@bts.local",
                displayName = "Sso User",
                createdAt = now,
                updatedAt = now,
            )
        // local_credentials 행 없음 → null → mustChangePassword=false 로 귀결
        every { storedPasswordCredentialRepository.findByUserId(ssoId) } returns null
        every { systemPermissionResolver.isSystemAdmin(ssoId) } returns false

        mockMvc.perform(
            get("/api/v1/users/me/whoami").with(
                jwt().jwt { builder -> builder.subject(ssoId.toString()) },
            ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.mustChangePassword").value(false))
            .andExpect(jsonPath("$.isSystemAdmin").value(false))
    }

    @Test
    fun `whoami PAT 인증은 mustChangePassword 와 isSystemAdmin 모두 false 고정`() {
        val activePat =
            PersonalAccessToken(
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
        // PAT 사용자가 실제로는 시스템 관리자/강제변경 대상이더라도 PAT 분기는 조회하지 않고 false 고정
        val cred = credential(PAT_USER_ID, mustChange = true)
        every { storedPasswordCredentialRepository.findByUserId(PAT_USER_ID) } returns cred
        every { systemPermissionResolver.isSystemAdmin(PAT_USER_ID) } returns true

        mockMvc.perform(
            get("/api/v1/users/me/whoami")
                .header("Authorization", "Bearer $RAW_PAT"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.authMethod").value("pat"))
            .andExpect(jsonPath("$.mustChangePassword").value(false))
            .andExpect(jsonPath("$.isSystemAdmin").value(false))
            // FR-MF-04: PAT 분기는 MFA 강제 컨텍스트와 무관하므로 mfaEnrollmentRequired 도 false 고정
            .andExpect(jsonPath("$.mfaEnrollmentRequired").value(false))
    }

    // ── Task 6: mfaEnrollmentRequired (FR-MF-04) — JWT 클레임 출처 노출 ────────────

    @Test
    fun `whoami JWT 클레임 mfa_enrollment_required true 이면 mfaEnrollmentRequired true 반영`() {
        val userId = UUID.fromString("00000000-0000-0000-0000-000000000005")
        val now = Instant.parse("2026-05-21T10:00:00Z")
        every { userRepository.findById(userId) } returns
            User(
                id = userId,
                username = "mfa-target",
                email = "mfa-target@bts.local",
                displayName = "Mfa Target",
                createdAt = now,
                updatedAt = now,
            )
        every { storedPasswordCredentialRepository.findByUserId(userId) } returns credential(userId, mustChange = false)
        every { systemPermissionResolver.isSystemAdmin(userId) } returns true

        mockMvc.perform(
            get("/api/v1/users/me/whoami").with(
                jwt().jwt { builder ->
                    builder.subject(userId.toString())
                    builder.claim(JwtIssuer.CLAIM_MFA_ENROLLMENT_REQUIRED, true)
                },
            ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.mfaEnrollmentRequired").value(true))
    }

    @Test
    fun `whoami JWT 클레임 mfa_enrollment_required false 이면 mfaEnrollmentRequired false 반영`() {
        val userId = UUID.fromString("00000000-0000-0000-0000-000000000006")
        val now = Instant.parse("2026-05-21T10:00:00Z")
        every { userRepository.findById(userId) } returns
            User(
                id = userId,
                username = "mfa-enrolled",
                email = "mfa-enrolled@bts.local",
                displayName = "Mfa Enrolled",
                createdAt = now,
                updatedAt = now,
            )
        every { storedPasswordCredentialRepository.findByUserId(userId) } returns credential(userId, mustChange = false)
        every { systemPermissionResolver.isSystemAdmin(userId) } returns false

        mockMvc.perform(
            get("/api/v1/users/me/whoami").with(
                jwt().jwt { builder ->
                    builder.subject(userId.toString())
                    builder.claim(JwtIssuer.CLAIM_MFA_ENROLLMENT_REQUIRED, false)
                },
            ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.mfaEnrollmentRequired").value(false))
    }

    @Test
    fun `whoami JWT 클레임 mfa_enrollment_required 부재이면 mfaEnrollmentRequired false`() {
        val userId = UUID.fromString("00000000-0000-0000-0000-000000000007")
        val now = Instant.parse("2026-05-21T10:00:00Z")
        every { userRepository.findById(userId) } returns
            User(
                id = userId,
                username = "no-claim",
                email = "no-claim@bts.local",
                displayName = "No Claim",
                createdAt = now,
                updatedAt = now,
            )
        every { storedPasswordCredentialRepository.findByUserId(userId) } returns credential(userId, mustChange = false)
        every { systemPermissionResolver.isSystemAdmin(userId) } returns false

        // 클레임을 박지 않은 토큰 — 부재 → false 로 귀결
        mockMvc.perform(
            get("/api/v1/users/me/whoami").with(
                jwt().jwt { builder -> builder.subject(userId.toString()) },
            ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.mfaEnrollmentRequired").value(false))
    }

    /** local_credentials 행 픽스처 — mustChangePassword 플래그만 변주 */
    private fun credential(
        userId: UUID,
        mustChange: Boolean,
    ): StoredPasswordCredential {
        val ts = Instant.parse("2026-01-01T00:00:00Z")
        return StoredPasswordCredential(
            userId = userId,
            passwordHash = "\$argon2id\$irrelevant",
            createdAt = ts,
            updatedAt = ts,
            mustChangePassword = mustChange,
        )
    }
}
