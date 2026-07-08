// PreferencesController 슬라이스 테스트 — 환경설정 조회/2-state PATCH/검증 400/JWT 전용 인증 (FR-PF-01 Task 3)

package com.atlas.bts.identity.web

import com.atlas.bts.identity.config.CorsConfig
import com.atlas.bts.identity.config.SecurityConfig
import com.atlas.bts.identity.jwt.SidRevokeJwtConverter
import com.atlas.bts.identity.pat.PersonalAccessTokenService
import com.atlas.bts.identity.preferences.PreferencesPatch
import com.atlas.bts.identity.preferences.PreferencesValidationException
import com.atlas.bts.identity.preferences.UserPreferences
import com.atlas.bts.identity.preferences.UserPreferencesService
import com.atlas.bts.identity.session.SessionService
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
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
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
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
 * [PreferencesController] WebMvcTest 슬라이스 테스트 (FR-PF-01 Task 3).
 *
 * ## 검증 시나리오
 * - GET   /me/preferences — 200 + 기본값(theme/locale/dateFormat) / 미인증 401 / 비-JWT principal(PAT) 401.
 * - PATCH /me/preferences — 유효값 200 갱신(2-state 매핑) / 잘못된 enum 400 / 비-JWT principal(PAT) 401.
 *
 * ## 의존성 모킹 전략 ([UserProfileControllerTest] 선례)
 * [UserPreferencesService] 는 companion object 가 없는 평범한 클래스라 MockK 로 안전하게 `@Bean` 등록이
 * 가능하다. SecurityConfig 필수 Bean 은 `@TestConfiguration` + MockK 로 공급한다.
 */
@WebMvcTest(
    controllers = [PreferencesController::class],
    excludeAutoConfiguration = [OAuth2ClientAutoConfiguration::class],
)
@Import(SecurityConfig::class, PreferencesControllerMvcTest.SecurityBeans::class)
class PreferencesControllerMvcTest {
    @TestConfiguration
    class SecurityBeans {
        @Bean
        fun jwtDecoder(): JwtDecoder = mockk(relaxed = true)

        @Bean
        fun clock(): Clock = Clock.fixed(Instant.parse("2026-07-05T10:00:00Z"), ZoneOffset.UTC)

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

        @Bean
        fun userPreferencesService(): UserPreferencesService = mockk()
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var userPreferencesService: UserPreferencesService

    private val userId = UUID.fromString("11111111-1111-4111-8111-111111111111")

    @BeforeEach
    fun resetMock() {
        clearMocks(userPreferencesService)
    }

    private fun jwtFor(uid: UUID) = jwt().jwt { builder -> builder.subject(uid.toString()) }

    private fun preferences(
        theme: String = UserPreferences.DEFAULT_THEME,
        locale: String = UserPreferences.DEFAULT_LOCALE,
        dateFormat: String = UserPreferences.DEFAULT_DATE_FORMAT,
        startPage: String = UserPreferences.DEFAULT_START_PAGE,
    ): UserPreferences =
        UserPreferences(
            userId = userId,
            theme = theme,
            locale = locale,
            dateFormat = dateFormat,
            startPage = startPage,
        )

    // ── GET /me/preferences ─────────────────────────────────────────────────────

    @Test
    fun `GET me preferences returns 401 without authentication`() {
        mockMvc.perform(get("/api/v1/users/me/preferences"))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `GET me preferences returns 401 with PAT-style non-JWT principal`() {
        mockMvc.perform(get("/api/v1/users/me/preferences").with(user("some-authenticated-principal")))
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `GET me preferences returns 200 with defaults`() {
        every { userPreferencesService.getPreferences(userId) } returns preferences()

        mockMvc.perform(get("/api/v1/users/me/preferences").with(jwtFor(userId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.theme").value("system"))
            .andExpect(jsonPath("$.locale").value("ko"))
            .andExpect(jsonPath("$.dateFormat").value("iso"))
            .andExpect(jsonPath("$.startPage").value("dashboards"))
    }

    // ── PATCH /me/preferences ───────────────────────────────────────────────────

    @Test
    fun `PATCH me preferences with theme only maps to 2-state patch and returns 200 updated`() {
        every { userPreferencesService.patchPreferences(userId, PreferencesPatch(theme = "dark")) } returns
            preferences(theme = "dark")

        mockMvc.perform(
            patch("/api/v1/users/me/preferences")
                .with(jwtFor(userId))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"theme":"dark"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.theme").value("dark"))
            .andExpect(jsonPath("$.locale").value("ko"))
            .andExpect(jsonPath("$.dateFormat").value("iso"))
    }

    @Test
    fun `PATCH me preferences with startPage only maps to 2-state patch and returns 200 updated`() {
        every { userPreferencesService.patchPreferences(userId, PreferencesPatch(startPage = "issues")) } returns
            preferences(startPage = "issues")

        mockMvc.perform(
            patch("/api/v1/users/me/preferences")
                .with(jwtFor(userId))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"startPage":"issues"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.startPage").value("issues"))
    }

    @Test
    fun `PATCH me preferences with invalid startPage returns 400`() {
        every { userPreferencesService.patchPreferences(userId, any()) } throws
            PreferencesValidationException("유효하지 않은 시작 페이지 값입니다.")

        mockMvc.perform(
            patch("/api/v1/users/me/preferences")
                .with(jwtFor(userId))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"startPage":"bad"}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("PREFERENCES_VALIDATION_FAILED"))
            .andExpect(jsonPath("$.message").value("유효하지 않은 시작 페이지 값입니다."))
    }

    @Test
    fun `PATCH me preferences with invalid enum returns 400`() {
        every { userPreferencesService.patchPreferences(userId, any()) } throws
            PreferencesValidationException("유효하지 않은 테마 값입니다.")

        mockMvc.perform(
            patch("/api/v1/users/me/preferences")
                .with(jwtFor(userId))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"theme":"neon"}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("PREFERENCES_VALIDATION_FAILED"))
            .andExpect(jsonPath("$.message").value("유효하지 않은 테마 값입니다."))
    }

    @Test
    fun `PATCH me preferences returns 401 with PAT-style non-JWT principal`() {
        mockMvc.perform(
            patch("/api/v1/users/me/preferences")
                .with(user("some-authenticated-principal"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"theme":"dark"}"""),
        )
            .andExpect(status().isUnauthorized)
    }
}
