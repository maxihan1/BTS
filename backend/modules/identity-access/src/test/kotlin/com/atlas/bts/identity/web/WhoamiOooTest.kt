// WhoamiController 슬라이스 테스트 — 부재중(OOO) view-layer oooActive/oooUntil 검증 (FR-PR-03 Task 5)

package com.atlas.bts.identity.web

import com.atlas.bts.identity.audit.AuthAuditLogService
import com.atlas.bts.identity.config.CorsConfig
import com.atlas.bts.identity.config.SecurityConfig
import com.atlas.bts.identity.credential.StoredPasswordCredential
import com.atlas.bts.identity.credential.StoredPasswordCredentialRepository
import com.atlas.bts.identity.jwt.SidRevokeJwtConverter
import com.atlas.bts.identity.ooo.OutOfOffice
import com.atlas.bts.identity.ooo.OutOfOfficeRepository
import com.atlas.bts.identity.pat.PersonalAccessToken
import com.atlas.bts.identity.pat.PersonalAccessTokenService
import com.atlas.bts.identity.preferences.UserPreferencesService
import com.atlas.bts.identity.profile.UserProfileRepository
import com.atlas.bts.identity.session.SessionService
import com.atlas.bts.identity.status.UserStatusRepository
import com.atlas.bts.identity.user.User
import com.atlas.bts.identity.user.UserRepository
import com.bts.shared.permission.SystemPermissionResolver
import io.mockk.every
import io.mockk.mockk
import org.hamcrest.Matchers.nullValue
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
import java.time.Instant
import java.util.UUID

/**
 * [WhoamiController] 부재중(OOO) view-layer 슬라이스 테스트 (FR-PR-03 Task 5).
 *
 * [WhoamiControllerTest] 와 동일 컨트롤러를 대상으로 하되, OOO 시나리오 전용으로 별도 파일로 분리한다
 * (기존 파일은 FR-AU-05/FR-MF-04/FR-PR-01/02 회귀 스위트가 이미 크다). WhoamiController 가 요구하는
 * 전체 협력자(OutOfOfficeRepository 포함)를 이 파일의 [MockSecurityBeans] 로 독립 공급한다.
 *
 * ## 검증 시나리오
 * - 활성 OOO 보유 JWT 사용자 → `oooActive:true`, `oooUntil=endsAt`(ISO 문자열).
 * - 미설정/만료/미래예약(비활성) → `oooActive:false`, `oooUntil:null`.
 * - PAT 인증 → `oooActive:false`, `oooUntil:null`(봇 컨텍스트, repository 미조회).
 */
@WebMvcTest(
    controllers = [WhoamiController::class],
    excludeAutoConfiguration = [OAuth2ClientAutoConfiguration::class],
)
@Import(SecurityConfig::class, WhoamiOooTest.MockSecurityBeans::class)
class WhoamiOooTest {
    companion object {
        private const val RAW_PAT = "pat_cccccccccccccccccccccccccccccccccccccccccccccc"
        private val PAT_USER_ID: UUID = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee")
        private val PAT_ID: UUID = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff")
    }

    @TestConfiguration
    class MockSecurityBeans {
        @Bean
        fun jwtDecoder(): JwtDecoder = mockk(relaxed = true)

        @Bean
        fun sidRevokeJwtConverter(): SidRevokeJwtConverter {
            val sessionService: SessionService = mockk(relaxed = true)
            return SidRevokeJwtConverter(sessionService)
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

        @Bean
        fun userProfileRepository(): UserProfileRepository = mockk(relaxed = true)

        @Bean
        fun userStatusRepository(): UserStatusRepository = mockk(relaxed = true)

        // WhoamiController 가 부재중 view-layer(oooActive/oooUntil) 파생을 위해 새로 주입받는 의존 (FR-PR-03).
        // @WebMvcTest 슬라이스에는 실 빈이 없으므로 mockk 로 공급해야 컨텍스트가 로드된다
        // (미공급 시 "No qualifying bean of type OutOfOfficeRepository").
        @Bean
        fun outOfOfficeRepository(): OutOfOfficeRepository = mockk(relaxed = true)

        // WhoamiController 가 환경설정 view-layer(theme/locale/dateFormat/startPage)를 위해 주입받는 의존 (FR-PF-01/02).
        // @WebMvcTest 슬라이스에는 실 빈이 없으므로 mockk 로 공급 (미공급 시 "No qualifying bean of type UserPreferencesService").
        @Bean
        fun userPreferencesService(): UserPreferencesService = mockk(relaxed = true)
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var userRepository: UserRepository

    @Autowired
    lateinit var storedPasswordCredentialRepository: StoredPasswordCredentialRepository

    @Autowired
    lateinit var systemPermissionResolver: SystemPermissionResolver

    @Autowired
    lateinit var outOfOfficeRepository: OutOfOfficeRepository

    @Autowired
    lateinit var personalAccessTokenService: PersonalAccessTokenService

    private fun stubUser(userId: UUID) {
        val now = Instant.parse("2026-07-07T10:00:00Z")
        every { userRepository.findById(userId) } returns
            User(
                id = userId,
                username = "ooo-user",
                email = "ooo-user@bts.local",
                displayName = "Ooo User",
                createdAt = now,
                updatedAt = now,
            )
        every { storedPasswordCredentialRepository.findByUserId(userId) } returns credential(userId)
        every { systemPermissionResolver.isSystemAdmin(userId) } returns false
    }

    private fun credential(userId: UUID): StoredPasswordCredential {
        val ts = Instant.parse("2026-01-01T00:00:00Z")
        return StoredPasswordCredential(
            userId = userId,
            passwordHash = "\$argon2id\$irrelevant",
            createdAt = ts,
            updatedAt = ts,
            mustChangePassword = false,
        )
    }

    @Test
    fun `whoami JWT 사용자가 활성 OOO 보유 시 oooActive true와 oooUntil 노출`() {
        val userId = UUID.fromString("00000000-0000-0000-0000-0000000000c1")
        stubUser(userId)
        val endsAt = Instant.parse("2026-07-14T00:00:00Z")
        every { outOfOfficeRepository.findActiveByUserId(userId, any()) } returns
            OutOfOffice(
                userId = userId,
                startsAt = Instant.parse("2026-07-05T00:00:00Z"),
                endsAt = endsAt,
                delegateUserId = null,
                delegateName = null,
                message = null,
            )

        mockMvc.perform(
            get("/api/v1/users/me/whoami").with(
                jwt().jwt { builder -> builder.subject(userId.toString()) },
            ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.oooActive").value(true))
            .andExpect(jsonPath("$.oooUntil").value(endsAt.toString()))
    }

    @Test
    fun `whoami JWT 사용자가 OOO 미설정-만료-미래예약(비활성)이면 oooActive false와 oooUntil null`() {
        val userId = UUID.fromString("00000000-0000-0000-0000-0000000000c2")
        stubUser(userId)
        every { outOfOfficeRepository.findActiveByUserId(userId, any()) } returns null

        mockMvc.perform(
            get("/api/v1/users/me/whoami").with(
                jwt().jwt { builder -> builder.subject(userId.toString()) },
            ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.oooActive").value(false))
            .andExpect(jsonPath("$.oooUntil").value(nullValue()))
    }

    @Test
    fun `whoami PAT 인증은 oooActive-oooUntil 모두 고정(false-null, 봇 컨텍스트)`() {
        val activePat =
            PersonalAccessToken(
                id = PAT_ID,
                userId = PAT_USER_ID,
                name = "ooo-ci-token",
                tokenHash = "irrelevant-hash",
                scopes = listOf("*"),
                expiresAt = null,
                lastUsedAt = null,
                revokedAt = null,
                createdAt = Instant.parse("2026-01-01T00:00:00Z"),
            )
        every { personalAccessTokenService.verify(RAW_PAT) } returns Result.success(activePat)

        mockMvc.perform(
            get("/api/v1/users/me/whoami").header("Authorization", "Bearer $RAW_PAT"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.authMethod").value("pat"))
            .andExpect(jsonPath("$.oooActive").value(false))
            .andExpect(jsonPath("$.oooUntil").value(nullValue()))
    }
}
