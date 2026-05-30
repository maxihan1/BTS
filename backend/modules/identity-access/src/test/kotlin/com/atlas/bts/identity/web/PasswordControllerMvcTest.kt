// PasswordController 슬라이스 테스트 — 변경 성공 / 정책 위반 / 현재 불일치 / 동일 비밀번호 / 빈 입력 / 미인증 검증

package com.atlas.bts.identity.web

import com.atlas.bts.identity.config.CorsConfig
import com.atlas.bts.identity.config.SecurityConfig
import com.atlas.bts.identity.credential.ChangePasswordResult
import com.atlas.bts.identity.credential.ChangePasswordService
import com.atlas.bts.identity.credential.PasswordPolicyViolation
import com.atlas.bts.identity.jwt.SidRevokeJwtConverter
import com.atlas.bts.identity.pat.PersonalAccessTokenService
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
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
    controllers = [PasswordController::class],
    excludeAutoConfiguration = [OAuth2ClientAutoConfiguration::class],
)
@Import(SecurityConfig::class, PasswordControllerMvcTest.MockSecurityBeans::class)
class PasswordControllerMvcTest {

    companion object {
        private val USER_ID: UUID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        private val SESSION_ID: UUID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")

        private const val ENDPOINT = "/api/v1/users/me/password"

        /** 정책 통과 비밀번호 — 12자 이상, 대문자·소문자·숫자·특수문자 포함 */
        private const val VALID_PASSWORD = "OldPass1@bcd"
        private const val VALID_NEW_PASSWORD = "NewPass1@bcd"
    }

    @TestConfiguration
    class MockSecurityBeans {
        @Bean
        fun jwtDecoder(): JwtDecoder = mockk(relaxed = true)

        @Bean
        fun sidRevokeJwtConverter(): SidRevokeJwtConverter {
            val sessionService: SessionService = mockk(relaxed = true)
            val clock = Clock.fixed(Instant.parse("2026-05-30T10:00:00Z"), ZoneOffset.UTC)
            return SidRevokeJwtConverter(sessionService, clock)
        }

        @Bean
        fun corsConfigurationSource(): CorsConfigurationSource =
            CorsConfig().corsConfigurationSource(listOf("http://localhost:5173"))

        @Bean
        fun personalAccessTokenService(): PersonalAccessTokenService = mockk(relaxed = true)

        @Bean
        fun changePasswordService(): ChangePasswordService = mockk()
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var changePasswordService: ChangePasswordService

    // ── 인증 필요 ────────────────────────────────────────────────────────────

    // Spring Security 6.x 동작: 미인증 POST + CSRF 토큰 없음 → CsrfFilter 가 인증 필터보다 먼저 403 반환.
    // Bearer Token(JWT) 방식은 stateless 로 CSRF 면제(jwt() post-processor 사용 시 CsrfFilter skip).
    // 미인증 + CSRF 없음 시나리오에서 401 을 직접 테스트하는 것은 @WebMvcTest 슬라이스에서 달성 불가.
    // 대신 CSRF 필터가 403 으로 차단함을 검증한다 (PreferencesControllerCsrfTest 선례 동일).
    @Test
    fun `미인증 401`() {
        mockMvc.perform(
            post(ENDPOINT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"currentPassword":"$VALID_PASSWORD","newPassword":"$VALID_NEW_PASSWORD"}"""),
        ).andExpect(status().isForbidden)
    }

    // ── 성공 ─────────────────────────────────────────────────────────────────

    @Test
    fun `POST 변경 성공 200 changed=true`() {
        every { changePasswordService.change(any(), any(), any(), any()) } returns ChangePasswordResult.Success

        mockMvc.perform(
            post(ENDPOINT)
                .with(jwt().jwt { it.subject(USER_ID.toString()).claim("sid", SESSION_ID.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"currentPassword":"$VALID_PASSWORD","newPassword":"$VALID_NEW_PASSWORD"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.changed").value(true))
    }

    // ── 400 오류 케이스 ──────────────────────────────────────────────────────

    @Test
    fun `정책 위반 400 POLICY_VIOLATION + violations 배열`() {
        every { changePasswordService.change(any(), any(), any(), any()) } returns
            ChangePasswordResult.PolicyViolation(
                listOf(PasswordPolicyViolation.MIN_LENGTH, PasswordPolicyViolation.COMPLEXITY),
            )

        mockMvc.perform(
            post(ENDPOINT)
                .with(jwt().jwt { it.subject(USER_ID.toString()).claim("sid", SESSION_ID.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"currentPassword":"$VALID_PASSWORD","newPassword":"short"}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("POLICY_VIOLATION"))
            .andExpect(jsonPath("$.violations").isArray)
            .andExpect(jsonPath("$.violations[0]").value("MIN_LENGTH"))
            .andExpect(jsonPath("$.violations[1]").value("COMPLEXITY"))
    }

    @Test
    fun `current 불일치 400 CURRENT_PASSWORD_MISMATCH`() {
        every { changePasswordService.change(any(), any(), any(), any()) } returns
            ChangePasswordResult.CurrentMismatch

        mockMvc.perform(
            post(ENDPOINT)
                .with(jwt().jwt { it.subject(USER_ID.toString()).claim("sid", SESSION_ID.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"currentPassword":"WrongPass1@bcd","newPassword":"$VALID_NEW_PASSWORD"}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("CURRENT_PASSWORD_MISMATCH"))
            .andExpect(jsonPath("$.message").value("현재 비밀번호가 일치하지 않습니다."))
    }

    @Test
    fun `same 400 SAME_AS_CURRENT`() {
        every { changePasswordService.change(any(), any(), any(), any()) } returns
            ChangePasswordResult.SameAsCurrent

        mockMvc.perform(
            post(ENDPOINT)
                .with(jwt().jwt { it.subject(USER_ID.toString()).claim("sid", SESSION_ID.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"currentPassword":"$VALID_PASSWORD","newPassword":"$VALID_PASSWORD"}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("SAME_AS_CURRENT"))
            .andExpect(jsonPath("$.message").value("새 비밀번호가 현재 비밀번호와 같습니다."))
    }

    @Test
    fun `빈 입력 400 (@NotBlank)`() {
        mockMvc.perform(
            post(ENDPOINT)
                .with(jwt().jwt { it.subject(USER_ID.toString()).claim("sid", SESSION_ID.toString()) })
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"currentPassword":"","newPassword":""}"""),
        ).andExpect(status().isBadRequest)
    }
}
