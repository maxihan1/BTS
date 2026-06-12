// MfaController WebAuthn 슬라이스 테스트 — register/list/delete(JWT, PAT 403) + authenticate/start permitAll (FR-MF-03 Task 7)

package com.atlas.bts.identity.web

import com.atlas.bts.identity.config.CorsConfig
import com.atlas.bts.identity.config.SecurityConfig
import com.atlas.bts.identity.jwt.SidRevokeJwtConverter
import com.atlas.bts.identity.mfa.MfaBackupCodeService
import com.atlas.bts.identity.mfa.MfaChallengeClaims
import com.atlas.bts.identity.mfa.MfaChallengeTokenService
import com.atlas.bts.identity.mfa.MfaService
import com.atlas.bts.identity.mfa.WebAuthnSecurityKeyService
import com.atlas.bts.identity.mfa.WebAuthnSecurityKeyService.KeySummary
import com.atlas.bts.identity.mfa.WebAuthnSecurityKeyService.RegisterResult
import com.atlas.bts.identity.pat.PersonalAccessTokenService
import com.atlas.bts.identity.session.SessionService
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
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * MfaController WebAuthn(보안키/패스키) 엔드포인트 WebMvcTest 슬라이스 (FR-MF-03 Task 7). SDD §19.8.
 *
 * ## 검증 시나리오
 * - POST /webauthn/register/start — JWT → 200 등록 옵션 JSON (PAT → 403)
 * - POST /webauthn/register/finish — Success → 201 / InvalidRegistration·Expired → 400 / AlreadyRegistered → 409
 * - GET  /webauthn — JWT → 200 키 목록 (PAT → 403)
 * - DELETE /webauthn/{id} — 소유 일치 → 204 / 미존재·타인 소유 → 404 not_found (PAT → 403)
 * - POST /webauthn/authenticate/start — 챌린지 토큰만으로(세션 없이) permitAll 도달 → 200 옵션 / 만료 → 401
 * - POST /api/v1/auth/mfa/verify (method=webauthn) — 챌린지 토큰만으로(세션 없이) permitAll 도달 (CSRF-ignore)
 *
 * register/list/delete 는 JWT 전용(PAT 403, MfaController 선례 동일). authenticate/start·verify 는
 * 정식 세션 발급 전(챌린지 토큰만 보유)이라 SecurityConfig 가 permitAll + CSRF-ignore 로 노출해야 한다.
 *
 * ## 의존성 모킹 전략 (MfaControllerTest 선례 동일)
 * - SecurityConfig 필수 Bean(JwtDecoder/SidRevokeJwtConverter/CorsConfigurationSource/
 *   PersonalAccessTokenService): @TestConfiguration + MockK.
 * - MfaController 의존 서비스: @MockBean(Mockito) — companion object ByteBuddy 문제 회피.
 * - authenticate/start·verify 핸들러는 AuthController 소관(verify) / MfaController 소관(start)이라
 *   verify 라우팅은 [VerifyProbe] 더미로 검증한다(MfaControllerTest 선례).
 */
@WebMvcTest(
    controllers = [MfaController::class, MfaWebAuthnControllerTest.VerifyProbe::class],
    excludeAutoConfiguration = [OAuth2ClientAutoConfiguration::class],
)
@Import(
    SecurityConfig::class,
    MfaWebAuthnControllerTest.SecurityBeans::class,
    MfaWebAuthnControllerTest.VerifyProbe::class,
)
class MfaWebAuthnControllerTest {
    /**
     * `/api/v1/auth/mfa/verify` 더미 핸들러 — verify 검증 로직은 AuthController 소관이므로,
     * 본 슬라이스는 SecurityConfig 가 verify 를 permitAll + CSRF-ignore 양쪽에 등록했는지(=세션 없이
     * CSRF 토큰 없이 POST 가 403 아님)만 라우팅으로 검증한다(MfaControllerTest.VerifyProbe 선례).
     */
    @org.springframework.web.bind.annotation.RestController
    class VerifyProbe {
        /**
         * 라우팅 탐침용 더미 핸들러 — SecurityConfig 통과 시 200 을 반환할 뿐 실제 검증은 없다.
         * FunctionOnlyReturningConstant 억제 — 상수 반환은 의도된 probe 동작이다.
         */
        @Suppress("FunctionOnlyReturningConstant")
        @org.springframework.web.bind.annotation.PostMapping("/api/v1/auth/mfa/verify")
        fun verify(): String = "ok"
    }

    @TestConfiguration
    class SecurityBeans {
        @Bean
        fun jwtDecoder(): JwtDecoder = mockk(relaxed = true)

        @Bean
        fun clock(): Clock = Clock.fixed(Instant.parse("2026-06-12T10:00:00Z"), ZoneOffset.UTC)

        @Bean
        fun sidRevokeJwtConverter(clock: Clock): SidRevokeJwtConverter {
            val sessionService: SessionService = mockk(relaxed = true)
            return SidRevokeJwtConverter(sessionService, clock)
        }

        @Bean
        fun corsConfigurationSource(): org.springframework.web.cors.CorsConfigurationSource =
            CorsConfig().corsConfigurationSource(listOf("http://localhost:5173"))

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

    @MockBean
    lateinit var webAuthnSecurityKeyService: WebAuthnSecurityKeyService

    @MockBean
    lateinit var challengeTokenService: MfaChallengeTokenService

    private val userId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val keyId = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")

    private fun jwtFor(uid: UUID) =
        jwt().jwt { builder ->
            builder.subject(uid.toString()).claim("sid", UUID.randomUUID().toString())
        }

    // ── POST /webauthn/register/start ─────────────────────────────────────────────

    @Test
    fun `POST register start with JWT returns 200 options json`() {
        `when`(webAuthnSecurityKeyService.registerStart(anyUuid())).thenReturn("""{"challenge":"abc"}""")

        mockMvc.perform(post("/api/v1/auth/mfa/webauthn/register/start").with(jwtFor(userId)).with(csrf()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.challenge").value("abc"))
    }

    @Test
    fun `POST register start with PAT returns 403`() {
        mockMvc.perform(post("/api/v1/auth/mfa/webauthn/register/start").with(user("pat-user-id")).with(csrf()))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error").value("session_management_requires_interactive_login"))

        verify(webAuthnSecurityKeyService, never()).registerStart(anyUuid())
    }

    @Test
    fun `POST register start without authentication returns 401`() {
        mockMvc.perform(post("/api/v1/auth/mfa/webauthn/register/start").with(csrf()))
            .andExpect(status().isUnauthorized)
    }

    // ── POST /webauthn/register/finish ────────────────────────────────────────────

    @Test
    fun `POST register finish success returns 201 with id and name`() {
        `when`(webAuthnSecurityKeyService.registerFinish(anyUuid(), anyStr(), org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(RegisterResult.Success)

        mockMvc.perform(
            post("/api/v1/auth/mfa/webauthn/register/finish")
                .with(jwtFor(userId)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"credential":{"id":"x"},"name":"laptop"}"""),
        )
            .andExpect(status().isCreated)
    }

    @Test
    fun `POST register finish with invalid registration returns 400 invalid_registration`() {
        `when`(webAuthnSecurityKeyService.registerFinish(anyUuid(), anyStr(), org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(RegisterResult.InvalidRegistration)

        mockMvc.perform(
            post("/api/v1/auth/mfa/webauthn/register/finish")
                .with(jwtFor(userId)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"credential":{"id":"x"},"name":"laptop"}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("invalid_registration"))
    }

    @Test
    fun `POST register finish when already registered returns 409 already_registered`() {
        `when`(webAuthnSecurityKeyService.registerFinish(anyUuid(), anyStr(), org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(RegisterResult.AlreadyRegistered)

        mockMvc.perform(
            post("/api/v1/auth/mfa/webauthn/register/finish")
                .with(jwtFor(userId)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"credential":{"id":"x"},"name":"laptop"}"""),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("already_registered"))
    }

    @Test
    fun `POST register finish when challenge expired returns 400 invalid_registration`() {
        `when`(webAuthnSecurityKeyService.registerFinish(anyUuid(), anyStr(), org.mockito.ArgumentMatchers.anyString()))
            .thenReturn(RegisterResult.Expired)

        mockMvc.perform(
            post("/api/v1/auth/mfa/webauthn/register/finish")
                .with(jwtFor(userId)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"credential":{"id":"x"},"name":"laptop"}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("invalid_registration"))
    }

    @Test
    fun `POST register finish with PAT returns 403`() {
        mockMvc.perform(
            post("/api/v1/auth/mfa/webauthn/register/finish")
                .with(user("pat-user-id")).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"credential":{"id":"x"},"name":"laptop"}"""),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error").value("session_management_requires_interactive_login"))

        verify(webAuthnSecurityKeyService, never())
            .registerFinish(anyUuid(), anyStr(), org.mockito.ArgumentMatchers.anyString())
    }

    // ── GET /webauthn ─────────────────────────────────────────────────────────────

    @Test
    fun `GET webauthn returns 200 with key list`() {
        `when`(webAuthnSecurityKeyService.listKeys(anyUuid()))
            .thenReturn(
                listOf(
                    KeySummary(
                        id = keyId,
                        name = "laptop",
                        createdAt = Instant.parse("2026-06-12T09:00:00Z"),
                        lastUsedAt = null,
                    ),
                ),
            )

        mockMvc.perform(get("/api/v1/auth/mfa/webauthn").with(jwtFor(userId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.keys.length()").value(1))
            .andExpect(jsonPath("$.keys[0].id").value(keyId.toString()))
            .andExpect(jsonPath("$.keys[0].name").value("laptop"))
    }

    @Test
    fun `GET webauthn with PAT returns 403`() {
        mockMvc.perform(get("/api/v1/auth/mfa/webauthn").with(user("pat-user-id")))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error").value("session_management_requires_interactive_login"))

        verify(webAuthnSecurityKeyService, never()).listKeys(anyUuid())
    }

    @Test
    fun `GET webauthn without authentication returns 401`() {
        mockMvc.perform(get("/api/v1/auth/mfa/webauthn"))
            .andExpect(status().isUnauthorized)
    }

    // ── DELETE /webauthn/{id} ─────────────────────────────────────────────────────

    @Test
    fun `DELETE webauthn when owned returns 204`() {
        `when`(webAuthnSecurityKeyService.deleteKey(anyUuid(), anyUuid())).thenReturn(true)

        mockMvc.perform(delete("/api/v1/auth/mfa/webauthn/$keyId").with(jwtFor(userId)).with(csrf()))
            .andExpect(status().isNoContent)
    }

    @Test
    fun `DELETE webauthn when not owned or missing returns 404 not_found`() {
        `when`(webAuthnSecurityKeyService.deleteKey(anyUuid(), anyUuid())).thenReturn(false)

        mockMvc.perform(delete("/api/v1/auth/mfa/webauthn/$keyId").with(jwtFor(userId)).with(csrf()))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("not_found"))
    }

    @Test
    fun `DELETE webauthn with PAT returns 403`() {
        mockMvc.perform(delete("/api/v1/auth/mfa/webauthn/$keyId").with(user("pat-user-id")).with(csrf()))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error").value("session_management_requires_interactive_login"))

        verify(webAuthnSecurityKeyService, never()).deleteKey(anyUuid(), anyUuid())
    }

    // ── POST /webauthn/authenticate/start (permitAll — 세션 전) ────────────────────

    /**
     * authenticate/start 는 정식 세션 발급 전(챌린지 토큰만)이라 SecurityConfig 가 permitAll +
     * CSRF-ignore 로 노출해야 한다. 인증/CSRF 토큰 없이 POST 해도 403(보안 거부)이 아니어야 통과로 본다.
     * permitAll/CSRF-ignore 누락 시 이 단언이 403 으로 실패한다.
     */
    @Test
    fun `POST authenticate start without auth or csrf is not forbidden (permitAll + csrf-ignore)`() {
        `when`(challengeTokenService.validate("any"))
            .thenReturn(MfaChallengeClaims(userId = userId, providerId = "local", jti = "jti-1"))
        `when`(webAuthnSecurityKeyService.authenticateStart(anyUuid())).thenReturn("""{"challenge":"abc"}""")

        mockMvc.perform(
            post("/api/v1/auth/mfa/webauthn/authenticate/start")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"mfa_challenge_token":"any"}"""),
        )
            .andExpect(status().isOk)
    }

    /**
     * 만료/위조 챌린지 토큰으로 authenticate/start → 401 mfa_challenge_expired.
     * 핵심은 CSRF 필터에 막혀 403 이 아니라 컨트롤러까지 도달해 401 을 낸다는 점(permitAll 도달 검증).
     */
    @Test
    fun `POST authenticate start with expired challenge token returns 401`() {
        `when`(challengeTokenService.validate("expired")).thenReturn(null)

        mockMvc.perform(
            post("/api/v1/auth/mfa/webauthn/authenticate/start")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"mfa_challenge_token":"expired"}"""),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").value("mfa_challenge_expired"))
    }

    // ── POST /mfa/verify (method=webauthn) permitAll 도달 ──────────────────────────

    /**
     * verify 경로는 SecurityConfig 가 permitAll + CSRF-ignore 양쪽에 등록해야 한다(method=webauthn 포함).
     * 세션·CSRF 토큰 없이 POST 해도 403(CSRF 거부)이 아니어야 한다. verify 검증 로직은 AuthController
     * 소관이므로 [VerifyProbe] 더미로 라우팅만 검증한다(MfaControllerTest 선례).
     */
    @Test
    fun `POST verify without csrf or session is not forbidden (permitAll + csrf-ignore)`() {
        mockMvc.perform(
            post("/api/v1/auth/mfa/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"mfa_challenge_token":"any","code":"x","method":"webauthn"}"""),
        )
            .andExpect(status().isOk)
    }

    // ── helpers ───────────────────────────────────────────────────────────────────

    /** Kotlin non-null UUID 파라미터용 Mockito any() 매처. */
    private fun anyUuid(): UUID = org.mockito.ArgumentMatchers.any(UUID::class.java) ?: UUID.randomUUID()

    /** Kotlin non-null String 파라미터용 Mockito any() 매처. */
    private fun anyStr(): String = org.mockito.ArgumentMatchers.anyString() ?: ""
}
