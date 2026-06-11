// MfaController 슬라이스 테스트 — setup/enable/status/disable + PAT 403 + verify permitAll/CSRF-ignore (FR-MF-01 Task 9)

package com.atlas.bts.identity.web

import com.atlas.bts.identity.config.CorsConfig
import com.atlas.bts.identity.config.SecurityConfig
import com.atlas.bts.identity.jwt.SidRevokeJwtConverter
import com.atlas.bts.identity.mfa.MfaBackupCodeService
import com.atlas.bts.identity.mfa.MfaBackupCodeService.BackupCodeStatus
import com.atlas.bts.identity.mfa.MfaBackupCodeService.GenerateResult
import com.atlas.bts.identity.mfa.MfaService
import com.atlas.bts.identity.mfa.MfaService.DisableResult
import com.atlas.bts.identity.mfa.MfaService.EnableResult
import com.atlas.bts.identity.mfa.MfaService.SetupResult
import com.atlas.bts.identity.pat.PersonalAccessTokenService
import com.atlas.bts.identity.session.SessionService
import com.atlas.bts.identity.user.User
import com.atlas.bts.identity.user.UserRepository
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
import org.springframework.http.MediaType
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.cors.CorsConfigurationSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * MfaController WebMvcTest 슬라이스 테스트 (FR-MF-01 Task 9).
 *
 * ## 검증 시나리오
 * - POST /totp/setup — JWT → 200 + otpauth_uri/qr_png_data_uri/secret_base32 (수동입력 fallback)
 * - POST /totp/setup — otpauth label 이 UUID 가 아니라 email(없으면 username)로 해소 (C-b)
 * - POST /totp/setup — 이미 ACTIVE → 409 already_enabled
 * - POST /totp/enable — 정답 코드 → 204
 * - POST /totp/enable — 오답 코드 → **400 invalid_code (500 아님 — catch-all 변질 회귀 가드)**
 * - POST /totp/enable — PENDING 없음 → 409 no_pending_setup
 * - POST /totp/enable — rate-limit 차단 → 429 too_many_attempts
 * - GET /totp — 200 + {enabled}
 * - DELETE /totp — 정답 코드 → 204
 * - DELETE /totp — 오답 코드 → 400 invalid_code
 * - DELETE /totp — 미활성 → 404 not_enabled
 * - DELETE /totp — rate-limit 차단 → 429 too_many_attempts
 * - POST /backup-codes — JWT + TOTP ACTIVE → 200 + {codes:[10개]} (평문 1회 노출, FR-MF-02 Task 7)
 * - POST /backup-codes — TOTP 미활성 → 409 totp_not_active
 * - POST /backup-codes — PAT → 403 / 미인증 → 401
 * - GET /backup-codes — JWT → 200 + {generated, remaining}
 * - GET /backup-codes — PAT → 403 / 미인증 → 401
 * - PAT 인증(Jwt null) → 403 session_management_requires_interactive_login (listSessions 선례 동일)
 * - 미인증 → 401 (Spring Security 필터)
 * - POST /api/v1/auth/mfa/verify (Task 10 경로) — CSRF 토큰 없이도 403 아님
 *   (SecurityConfig 가 verify 를 csrf.ignoringRequestMatchers + permitAll 양쪽에 등록 — BLOCKER-1).
 *   verify 핸들러는 Task 10 소관이므로 본 슬라이스는 [VerifyProbe] 더미로 라우팅만 검증한다.
 *
 * ## 의존성 모킹 전략 (AuthControllerTest 선례 동일)
 * - SecurityConfig 필수 Bean(JwtDecoder/SidRevokeJwtConverter/CorsConfigurationSource/
 *   PersonalAccessTokenService): @TestConfiguration + MockK.
 * - MfaController 의존 MfaService: @MockBean(Mockito) — companion object ByteBuddy 문제 회피.
 */
@WebMvcTest(
    controllers = [MfaController::class, MfaControllerTest.VerifyProbe::class],
    excludeAutoConfiguration = [OAuth2ClientAutoConfiguration::class],
)
@Import(SecurityConfig::class, MfaControllerTest.SecurityBeans::class, MfaControllerTest.VerifyProbe::class)
class MfaControllerTest {
    /**
     * Task 10 의 `/api/v1/auth/mfa/verify` 더미 핸들러.
     *
     * 실제 verify 로직은 Task 10(AuthController/별도) 소관이므로, 본 슬라이스는 SecurityConfig 가
     * verify 를 permitAll + CSRF-ignore 양쪽에 등록했는지(=CSRF 토큰 없이 POST 가 403 아님)만 라우팅으로 검증한다.
     */
    @RestController
    class VerifyProbe {
        /**
         * 라우팅 탐침용 더미 핸들러 — SecurityConfig 통과 시 200 을 반환할 뿐 실제 검증은 없다(Task 10 소관).
         * FunctionOnlyReturningConstant 억제 — 상수 반환은 의도된 probe 동작이다(SecurityConfigTest.ProbeController 선례).
         */
        @Suppress("FunctionOnlyReturningConstant")
        @PostMapping("/api/v1/auth/mfa/verify")
        fun verify(): String = "ok"
    }

    @TestConfiguration
    class SecurityBeans {
        @Bean
        fun jwtDecoder(): JwtDecoder = mockk(relaxed = true)

        @Bean
        fun clock(): Clock = Clock.fixed(Instant.parse("2026-06-11T10:00:00Z"), ZoneOffset.UTC)

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
    lateinit var mfaService: MfaService

    @MockBean
    lateinit var userRepository: UserRepository

    @MockBean
    lateinit var backupCodeService: MfaBackupCodeService

    private val userId = UUID.fromString("11111111-1111-1111-1111-111111111111")

    /** otpauth label 해소용 User — email 우선, 없으면 username fallback (C-b). */
    private fun userWith(
        email: String?,
        username: String = "alice-login",
    ): User =
        User(
            id = userId,
            username = username,
            email = email,
            displayName = "Alice",
            createdAt = Instant.parse("2026-06-11T10:00:00Z"),
            updatedAt = Instant.parse("2026-06-11T10:00:00Z"),
        )

    private fun jwtFor(uid: UUID) =
        jwt().jwt { builder ->
            builder.subject(uid.toString()).claim("sid", UUID.randomUUID().toString())
        }

    // ── POST /totp/setup ────────────────────────────────────────────────────────

    @Test
    fun `POST setup with JWT returns 200 with otpauth uri qr and secret_base32`() {
        `when`(userRepository.findById(anyUuid())).thenReturn(userWith(email = "alice@example.com"))
        `when`(mfaService.setup(anyUuid(), anyStr()))
            .thenReturn(
                SetupResult.Created(
                    otpauthUri = "otpauth://totp/BTS:alice?secret=ABCDEF&issuer=BTS",
                    qrPngDataUri = "data:image/png;base64,AAAA",
                    secretBase32 = "ABCDEF",
                ),
            )

        mockMvc.perform(post("/api/v1/auth/mfa/totp/setup").with(jwtFor(userId)).with(csrf()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.otpauth_uri").value("otpauth://totp/BTS:alice?secret=ABCDEF&issuer=BTS"))
            .andExpect(jsonPath("$.qr_png_data_uri").value("data:image/png;base64,AAAA"))
            // QR 스캔 불가 환경의 수동입력 fallback — secret_base32 응답 필드 (C-c, spec §API).
            .andExpect(jsonPath("$.secret_base32").value("ABCDEF"))
    }

    @Test
    fun `POST setup resolves otpauth label from email`() {
        `when`(userRepository.findById(anyUuid())).thenReturn(userWith(email = "alice@example.com"))
        `when`(mfaService.setup(anyUuid(), anyStr()))
            .thenReturn(
                SetupResult.Created(
                    otpauthUri = "otpauth://totp/BTS:alice?secret=ABCDEF&issuer=BTS",
                    qrPngDataUri = "data:image/png;base64,AAAA",
                    secretBase32 = "ABCDEF",
                ),
            )

        mockMvc.perform(post("/api/v1/auth/mfa/totp/setup").with(jwtFor(userId)).with(csrf()))
            .andExpect(status().isOk)

        // label 은 UUID 가 아니라 사용자 email 로 해소돼야 한다(C-b, spec FR-2/GAP-4).
        verify(mfaService).setup(eqUuid(userId), eqStr("alice@example.com"))
    }

    @Test
    fun `POST setup falls back to username when email is null`() {
        `when`(userRepository.findById(anyUuid())).thenReturn(userWith(email = null, username = "alice-login"))
        `when`(mfaService.setup(anyUuid(), anyStr()))
            .thenReturn(
                SetupResult.Created(
                    otpauthUri = "otpauth://totp/BTS:alice-login?secret=ABCDEF&issuer=BTS",
                    qrPngDataUri = "data:image/png;base64,AAAA",
                    secretBase32 = "ABCDEF",
                ),
            )

        mockMvc.perform(post("/api/v1/auth/mfa/totp/setup").with(jwtFor(userId)).with(csrf()))
            .andExpect(status().isOk)

        // email 이 없으면 username 으로 label 을 해소한다(C-b).
        verify(mfaService).setup(eqUuid(userId), eqStr("alice-login"))
    }

    @Test
    fun `POST setup when already enabled returns 409 already_enabled`() {
        `when`(userRepository.findById(anyUuid())).thenReturn(userWith(email = "alice@example.com"))
        `when`(mfaService.setup(anyUuid(), anyStr())).thenReturn(SetupResult.AlreadyEnabled)

        mockMvc.perform(post("/api/v1/auth/mfa/totp/setup").with(jwtFor(userId)).with(csrf()))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("already_enabled"))
    }

    @Test
    fun `POST setup with PAT returns 403`() {
        mockMvc.perform(post("/api/v1/auth/mfa/totp/setup").with(user("pat-user-id")).with(csrf()))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error").value("session_management_requires_interactive_login"))

        verify(mfaService, never()).setup(anyUuid(), anyStr())
    }

    @Test
    fun `POST setup without authentication returns 401`() {
        mockMvc.perform(post("/api/v1/auth/mfa/totp/setup").with(csrf()))
            .andExpect(status().isUnauthorized)
    }

    // ── POST /totp/enable ───────────────────────────────────────────────────────

    @Test
    fun `POST enable with valid code returns 204`() {
        `when`(mfaService.enable(anyUuid(), anyStr())).thenReturn(EnableResult.Success)

        mockMvc.perform(
            post("/api/v1/auth/mfa/totp/enable")
                .with(jwtFor(userId)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"code":"123456"}"""),
        )
            .andExpect(status().isNoContent)
    }

    /**
     * enable 오답 코드는 **400 invalid_code** 여야 한다 — 도메인 결과를 컨트롤러에서 직접 ResponseEntity 로
     * 매핑하므로, catch-all @ExceptionHandler 가 4xx 를 500 으로 변질시키는 회귀가 없어야 한다
     * (catch-all-exceptionhandler-swallows-responsestatusexception 교훈).
     */
    @Test
    fun `POST enable with invalid code returns 400 not 500`() {
        `when`(mfaService.enable(anyUuid(), anyStr())).thenReturn(EnableResult.InvalidCode)

        mockMvc.perform(
            post("/api/v1/auth/mfa/totp/enable")
                .with(jwtFor(userId)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"code":"000000"}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("invalid_code"))
    }

    @Test
    fun `POST enable with no pending setup returns 409 no_pending_setup`() {
        `when`(mfaService.enable(anyUuid(), anyStr())).thenReturn(EnableResult.NoPending)

        mockMvc.perform(
            post("/api/v1/auth/mfa/totp/enable")
                .with(jwtFor(userId)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"code":"123456"}"""),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("no_pending_setup"))
    }

    @Test
    fun `POST enable when rate-limited returns 429 too_many_attempts`() {
        `when`(mfaService.enable(anyUuid(), anyStr())).thenReturn(EnableResult.TooManyAttempts)

        mockMvc.perform(
            post("/api/v1/auth/mfa/totp/enable")
                .with(jwtFor(userId)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"code":"123456"}"""),
        )
            .andExpect(status().isTooManyRequests)
            .andExpect(jsonPath("$.error").value("too_many_attempts"))
    }

    @Test
    fun `POST enable with PAT returns 403`() {
        mockMvc.perform(
            post("/api/v1/auth/mfa/totp/enable")
                .with(user("pat-user-id")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"code":"123456"}"""),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error").value("session_management_requires_interactive_login"))

        verify(mfaService, never()).enable(anyUuid(), anyStr())
    }

    // ── GET /totp ─────────────────────────────────────────────────────────────

    @Test
    fun `GET status returns 200 with enabled true`() {
        `when`(mfaService.isEnabled(anyUuid())).thenReturn(true)

        mockMvc.perform(get("/api/v1/auth/mfa/totp").with(jwtFor(userId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.enabled").value(true))
    }

    @Test
    fun `GET status returns 200 with enabled false`() {
        `when`(mfaService.isEnabled(anyUuid())).thenReturn(false)

        mockMvc.perform(get("/api/v1/auth/mfa/totp").with(jwtFor(userId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.enabled").value(false))
    }

    @Test
    fun `GET status with PAT returns 403`() {
        mockMvc.perform(get("/api/v1/auth/mfa/totp").with(user("pat-user-id")))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error").value("session_management_requires_interactive_login"))
    }

    @Test
    fun `GET status without authentication returns 401`() {
        mockMvc.perform(get("/api/v1/auth/mfa/totp"))
            .andExpect(status().isUnauthorized)
    }

    // ── DELETE /totp ────────────────────────────────────────────────────────────

    @Test
    fun `DELETE with valid code returns 204`() {
        `when`(mfaService.disable(anyUuid(), anyStr())).thenReturn(DisableResult.Success)

        mockMvc.perform(
            delete("/api/v1/auth/mfa/totp")
                .with(jwtFor(userId)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"code":"123456"}"""),
        )
            .andExpect(status().isNoContent)
    }

    @Test
    fun `DELETE with invalid code returns 400 invalid_code`() {
        `when`(mfaService.disable(anyUuid(), anyStr())).thenReturn(DisableResult.InvalidCode)

        mockMvc.perform(
            delete("/api/v1/auth/mfa/totp")
                .with(jwtFor(userId)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"code":"000000"}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("invalid_code"))
    }

    @Test
    fun `DELETE when not enabled returns 404 not_enabled`() {
        `when`(mfaService.disable(anyUuid(), anyStr())).thenReturn(DisableResult.NotEnabled)

        mockMvc.perform(
            delete("/api/v1/auth/mfa/totp")
                .with(jwtFor(userId)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"code":"123456"}"""),
        )
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("not_enabled"))
    }

    @Test
    fun `DELETE when rate-limited returns 429 too_many_attempts`() {
        `when`(mfaService.disable(anyUuid(), anyStr())).thenReturn(DisableResult.TooManyAttempts)

        mockMvc.perform(
            delete("/api/v1/auth/mfa/totp")
                .with(jwtFor(userId)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"code":"123456"}"""),
        )
            .andExpect(status().isTooManyRequests)
            .andExpect(jsonPath("$.error").value("too_many_attempts"))
    }

    @Test
    fun `DELETE with PAT returns 403`() {
        mockMvc.perform(
            delete("/api/v1/auth/mfa/totp")
                .with(user("pat-user-id")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"code":"123456"}"""),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error").value("session_management_requires_interactive_login"))

        verify(mfaService, never()).disable(anyUuid(), anyStr())
    }

    // ── POST /backup-codes ──────────────────────────────────────────────────────

    @Test
    fun `POST backup-codes with JWT and active TOTP returns 200 with ten plaintext codes`() {
        val codes = (1..10).map { "code-%02d".format(it) }
        `when`(backupCodeService.generateOrRegenerate(anyUuid()))
            .thenReturn(GenerateResult.Generated(codes))

        mockMvc.perform(post("/api/v1/auth/mfa/backup-codes").with(jwtFor(userId)).with(csrf()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.codes.length()").value(10))
            .andExpect(jsonPath("$.codes[0]").value("code-01"))
            .andExpect(jsonPath("$.codes[9]").value("code-10"))
    }

    @Test
    fun `POST backup-codes when TOTP not active returns 409 totp_not_active`() {
        `when`(backupCodeService.generateOrRegenerate(anyUuid())).thenReturn(GenerateResult.NotActive)

        mockMvc.perform(post("/api/v1/auth/mfa/backup-codes").with(jwtFor(userId)).with(csrf()))
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("totp_not_active"))
    }

    @Test
    fun `POST backup-codes with PAT returns 403`() {
        mockMvc.perform(post("/api/v1/auth/mfa/backup-codes").with(user("pat-user-id")).with(csrf()))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error").value("session_management_requires_interactive_login"))

        verify(backupCodeService, never()).generateOrRegenerate(anyUuid())
    }

    @Test
    fun `POST backup-codes without authentication returns 401`() {
        mockMvc.perform(post("/api/v1/auth/mfa/backup-codes").with(csrf()))
            .andExpect(status().isUnauthorized)
    }

    // ── GET /backup-codes ───────────────────────────────────────────────────────

    @Test
    fun `GET backup-codes returns 200 with generated and remaining`() {
        `when`(backupCodeService.status(anyUuid())).thenReturn(BackupCodeStatus(generated = true, remaining = 7))

        mockMvc.perform(get("/api/v1/auth/mfa/backup-codes").with(jwtFor(userId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.generated").value(true))
            .andExpect(jsonPath("$.remaining").value(7))
    }

    @Test
    fun `GET backup-codes when none generated returns 200 with generated false`() {
        `when`(backupCodeService.status(anyUuid())).thenReturn(BackupCodeStatus(generated = false, remaining = 0))

        mockMvc.perform(get("/api/v1/auth/mfa/backup-codes").with(jwtFor(userId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.generated").value(false))
            .andExpect(jsonPath("$.remaining").value(0))
    }

    @Test
    fun `GET backup-codes with PAT returns 403`() {
        mockMvc.perform(get("/api/v1/auth/mfa/backup-codes").with(user("pat-user-id")))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error").value("session_management_requires_interactive_login"))

        verify(backupCodeService, never()).status(anyUuid())
    }

    @Test
    fun `GET backup-codes without authentication returns 401`() {
        mockMvc.perform(get("/api/v1/auth/mfa/backup-codes"))
            .andExpect(status().isUnauthorized)
    }

    // ── verify 경로 라우팅 (Task 10 핸들러는 더미) — BLOCKER-1 가드 ───────────────

    /**
     * `/api/v1/auth/mfa/verify` 는 SecurityConfig 가 csrf.ignoringRequestMatchers + permitAll 양쪽에
     * 등록해야 한다(BLOCKER-1). CSRF 토큰 없이 POST 해도 403(CSRF 거부)이 아니어야 통과로 본다.
     * permitAll 만 추가하고 CSRF-ignore 를 빠뜨리면 이 단언이 403 으로 실패한다.
     */
    @Test
    fun `POST verify without CSRF token is not forbidden (permitAll + csrf-ignore)`() {
        mockMvc.perform(post("/api/v1/auth/mfa/verify"))
            .andExpect(status().isOk)
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** Kotlin non-null UUID 파라미터용 Mockito any() 매처. */
    private fun anyUuid(): UUID = org.mockito.ArgumentMatchers.any(UUID::class.java) ?: UUID.randomUUID()

    /** Kotlin non-null String 파라미터용 Mockito any() 매처. */
    private fun anyStr(): String = org.mockito.ArgumentMatchers.anyString() ?: ""

    /** Kotlin non-null UUID 파라미터용 Mockito eq() 매처. */
    private fun eqUuid(value: UUID): UUID = org.mockito.ArgumentMatchers.eq(value) ?: value

    /** Kotlin non-null String 파라미터용 Mockito eq() 매처. */
    private fun eqStr(value: String): String = org.mockito.ArgumentMatchers.eq(value) ?: value
}
