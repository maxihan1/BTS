// UserStatusController 슬라이스 테스트 — 상태 조회/replace/clear + 검증 400/미인증 401 (FR-PR-02 Task 4)

package com.atlas.bts.identity.web

import com.atlas.bts.identity.config.CorsConfig
import com.atlas.bts.identity.config.SecurityConfig
import com.atlas.bts.identity.jwt.SidRevokeJwtConverter
import com.atlas.bts.identity.pat.PersonalAccessTokenService
import com.atlas.bts.identity.session.SessionService
import com.atlas.bts.identity.status.StatusValidationException
import com.atlas.bts.identity.status.StatusView
import com.atlas.bts.identity.status.UserStatusService
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.BeforeEach
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.cors.CorsConfigurationSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * [UserStatusController] WebMvcTest 슬라이스 테스트 (FR-PR-02 Task 4).
 *
 * ## 검증 시나리오
 * - GET  /me/status  — 200(활성 상태) / 200(미설정 all-null) / 미인증 401.
 * - PATCH /me/status — 200(replace) / 200(해제 all-null) / 검증 실패 400 / 미인증 401.
 *
 * ## 의존성 모킹 ([UserProfileControllerTest] 선례)
 * [UserStatusService] 는 MockK `@Bean` 등록. SecurityConfig 필수 Bean 은 `@TestConfiguration` + MockK.
 */
@WebMvcTest(
    controllers = [UserStatusController::class],
    excludeAutoConfiguration = [OAuth2ClientAutoConfiguration::class],
)
@Import(SecurityConfig::class, UserStatusControllerTest.SecurityBeans::class)
class UserStatusControllerTest {
    @TestConfiguration
    class SecurityBeans {
        @Bean
        fun jwtDecoder(): JwtDecoder = mockk(relaxed = true)

        @Bean
        fun clock(): Clock = Clock.fixed(Instant.parse("2026-07-06T10:00:00Z"), ZoneOffset.UTC)

        @Bean
        fun sidRevokeJwtConverter(clock: Clock): SidRevokeJwtConverter {
            val sessionService: SessionService = mockk(relaxed = true)
            return SidRevokeJwtConverter(sessionService, clock)
        }

        @Bean
        fun corsConfigurationSource(): CorsConfigurationSource =
            CorsConfig().corsConfigurationSource(listOf("http://localhost:5173"))

        @Bean
        fun personalAccessTokenService(): PersonalAccessTokenService = mockk(relaxed = true)

        @Bean
        fun userStatusService(): UserStatusService = mockk()
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var userStatusService: UserStatusService

    private val userId = UUID.fromString("22222222-2222-4222-8222-222222222222")

    @BeforeEach
    fun resetMock() {
        clearMocks(userStatusService)
    }

    private fun jwtFor(uid: UUID) = jwt().jwt { builder -> builder.subject(uid.toString()) }

    // ── GET /me/status ─────────────────────────────────────────────────────────

    @Test
    fun `GET me status returns 401 without authentication`() {
        mockMvc.perform(get("/api/v1/users/me/status"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `GET me status returns 200 with active status`() {
        val expiresAt = Instant.parse("2099-01-01T00:00:00Z")
        every { userStatusService.getActiveStatus(userId) } returns StatusView("🌴", "휴가 중", expiresAt)

        mockMvc.perform(get("/api/v1/users/me/status").with(jwtFor(userId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.emoji").value("🌴"))
            .andExpect(jsonPath("$.text").value("휴가 중"))
            .andExpect(jsonPath("$.expiresAt").value("2099-01-01T00:00:00Z"))
    }

    @Test
    fun `GET me status returns 200 all-null when no status`() {
        every { userStatusService.getActiveStatus(userId) } returns StatusView(null, null, null)

        mockMvc.perform(get("/api/v1/users/me/status").with(jwtFor(userId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.emoji").value(nullValue()))
            .andExpect(jsonPath("$.text").value(nullValue()))
            .andExpect(jsonPath("$.expiresAt").value(nullValue()))
    }

    // ── PATCH /me/status ───────────────────────────────────────────────────────

    @Test
    fun `PATCH me status returns 401 without authentication`() {
        mockMvc.perform(
            patch("/api/v1/users/me/status")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"emoji":"🌴"}"""),
        )
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `PATCH me status replaces status and returns 200`() {
        every { userStatusService.setStatus(userId, any()) } returns
            StatusView("🌴", "휴가 중", Instant.parse("2099-01-01T00:00:00Z"))

        mockMvc.perform(
            patch("/api/v1/users/me/status")
                .with(jwtFor(userId))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"emoji":"🌴","text":"휴가 중","expiresAt":"2099-01-01T00:00:00Z"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.emoji").value("🌴"))
            .andExpect(jsonPath("$.text").value("휴가 중"))
    }

    @Test
    fun `PATCH me status with empty values clears status and returns all-null`() {
        every { userStatusService.setStatus(userId, any()) } returns StatusView(null, null, null)

        mockMvc.perform(
            patch("/api/v1/users/me/status")
                .with(jwtFor(userId))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"emoji":"","text":""}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.emoji").value(nullValue()))
            .andExpect(jsonPath("$.text").value(nullValue()))
    }

    @Test
    fun `PATCH me status returns 400 when service rejects validation`() {
        every { userStatusService.setStatus(userId, any()) } throws
            StatusValidationException("상태 텍스트는 100자를 초과할 수 없습니다.")

        mockMvc.perform(
            patch("/api/v1/users/me/status")
                .with(jwtFor(userId))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"text":"tooooo long"}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("STATUS_VALIDATION_FAILED"))
    }
}
