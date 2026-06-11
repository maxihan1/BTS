// AuthController 슬라이스 테스트 — login/logout/refresh/sessions/revokeSession 엔드포인트 (FR-AU-09 Task 21/Task 2/Task 3)

package com.atlas.bts.identity.web

import com.atlas.bts.identity.audit.AuthAuditLog
import com.atlas.bts.identity.audit.AuthAuditLogService
import com.atlas.bts.identity.audit.AuthEventType
import com.atlas.bts.identity.auth.CompositeAuthenticationManager
import com.atlas.bts.identity.config.CorsConfig
import com.atlas.bts.identity.config.SecurityConfig
import com.atlas.bts.identity.jwt.JwtIssuer
import com.atlas.bts.identity.jwt.SidRevokeJwtConverter
import com.atlas.bts.identity.mfa.MfaChallengeClaims
import com.atlas.bts.identity.mfa.MfaChallengeTokenService
import com.atlas.bts.identity.mfa.MfaService
import com.atlas.bts.identity.mfa.MfaService.VerifyResult
import com.atlas.bts.identity.pat.PersonalAccessTokenService
import com.atlas.bts.identity.provider.ldap.ProviderUnavailableException
import com.atlas.bts.identity.session.RefreshTokenRepository
import com.atlas.bts.identity.session.RefreshTokenService
import com.atlas.bts.identity.session.RefreshTokenService.FailureReason
import com.atlas.bts.identity.session.RefreshTokenService.RotateResult
import com.atlas.bts.identity.session.Session
import com.atlas.bts.identity.session.SessionService
import com.atlas.bts.identity.spi.AuthnResult
import com.atlas.bts.identity.spi.FailureReason.INVALID_CREDENTIALS
import com.atlas.bts.identity.spi.MfaChallenge
import com.atlas.bts.identity.spi.Principal
import com.atlas.bts.identity.spi.ProviderType
import com.atlas.bts.identity.systemrole.SystemRole
import com.atlas.bts.identity.systemrole.SystemRoleAssignmentRepository
import io.mockk.mockk
import jakarta.servlet.http.Cookie
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentCaptor
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
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
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.cors.CorsConfigurationSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * AuthController WebMvcTest 슬라이스 테스트 (FR-AU-09 Task 21).
 *
 * ## 검증 시나리오
 * - login 성공 (provider=local) — 200 + access_token + 디스패처에 "local" 전달 (FR-AU-06 Task 3)
 * - login 성공 (provider=ldap) — 디스패처에 "ldap" 전달 (FR-AU-06 Task 3)
 * - login provider 누락/빈 문자열 — 400 + {"error": "provider_required"} (FR-AU-06 Task 3)
 * - login 디스패처 Failure — 401 + {"error": "invalid_credentials"} (reason 무관, 열거 방지 NFR-06-01)
 * - login 디스패처 ProviderUnavailableException — 503 (FR-AU-06 Task 3)
 * - login 디스패처 RequiresMfa — 401 + {"error": "mfa_required"}
 * - logout 성공 — 204 + Cookie refresh_token Max-Age=0 (만료)
 * - logout 미인증 — 401
 * - refresh 성공 — 200 + access_token + 새 Set-Cookie refresh_token rotation
 * - refresh replay — 401 + {"error": "refresh_token_reused"}
 * - refresh 만료 — 401 + {"error": "refresh_token_expired"}
 * - refresh cookie 없음 — 401 + {"error": "refresh_token_invalid"}
 * - GET /sessions 성공 — 200 + 세션 목록 (sid/providerId/userAgent/ipAddress/lastSeenAt/createdAt/current)
 * - GET /sessions — 요청 JWT sid 와 일치 세션 current=true
 * - GET /sessions — deviceFingerprint 응답 미포함
 * - GET /sessions — 미인증 401
 * - GET /sessions — PAT 인증 403 + session_management_requires_interactive_login (FR-6b/EC-8)
 * - DELETE /sessions/{sid} 본인 다른 세션 204 + revoke + revokeChainFromSession 호출 검증 (FR-3/Task 3-a)
 * - DELETE /sessions/{sid} 타인 sid 404 (IDOR 방어 / FR-4 / S-3 / Task 3-b)
 * - DELETE /sessions/{sid} 현재 세션 409 cannot_revoke_current_session (FR-5 / S-4 / Task 3-c)
 * - DELETE /sessions/{sid} 미존재/비활성 sid 404 (EC-2 / Task 3-d)
 * - DELETE /sessions/{sid} PAT 인증 403 session_management_requires_interactive_login (FR-6b / EC-8 / Task 3-e)
 * - DELETE /sessions/{sid} 잘못된 UUID 형식 400 (EC-7 / Task 3-f)
 *
 * ## 의존성 모킹 전략
 * - SecurityConfig 필수 Bean (SidRevokeJwtConverter, JwtDecoder, CorsConfigurationSource, PersonalAccessTokenService):
 *   @TestConfiguration + MockK — WhoamiControllerTest 패턴과 일관.
 * - AuthController 의존 서비스
 *   (SessionService, RefreshTokenRepository, RefreshTokenService, JwtIssuer, CompositeAuthenticationManager):
 *   @MockBean (Mockito) — MockK 로 companion object 포함 클래스를 Spring @Bean 등록 시
 *   ByteBuddy 의 $Companion 클래스 로드 실패 (NoClassDefFoundError) 회피.
 */
@WebMvcTest(
    controllers = [AuthController::class],
    excludeAutoConfiguration = [OAuth2ClientAutoConfiguration::class],
)
@Import(SecurityConfig::class, AuthControllerTest.SecurityBeans::class)
// LargeClass 억제 — login/logout/refresh/sessions/revokeSession 5개 엔드포인트 + FR-AU-10 감사 emit
// 시나리오까지 단일 슬라이스 테스트로 응집한다. 엔드포인트별 분리는 공유 SecurityBeans/MockBean 중복을 낳는다.
@Suppress("LargeClass")
class AuthControllerTest {
    /** raw refresh token — 64자 소문자 hex */
    private val refreshTokenRaw = "ab".repeat(32)

    // FR-MF-01 Task 10 공용 고정값 (로그인 2단계 / verify 테스트).
    private val mfaUserId: UUID = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val mfaSessionId: UUID = UUID.fromString("22222222-2222-2222-2222-222222222222")
    private val mfaAccessToken = "eyJhbGciOiJSUzI1NiJ9.test.access"
    private val mfaChallengeTokenValue = "eyJhbGciOiJSUzI1NiJ9.challenge.token"
    private val mfaJti = "33333333-3333-3333-3333-333333333333"

    @TestConfiguration
    class SecurityBeans {
        @Bean
        fun jwtDecoder(): JwtDecoder = mockk(relaxed = true)

        /**
         * 테스트 고정 Clock — 세션 테스트의 now(2026-05-29T10:00:00Z)와 expiresAt(+1일) 구간 안에 있어야
         * isActive=true 판정이 맞다. AuthController 와 SidRevokeJwtConverter 양쪽에 동일 Bean 주입.
         */
        @Bean
        fun clock(): Clock = Clock.fixed(Instant.parse("2026-05-29T10:00:00Z"), ZoneOffset.UTC)

        @Bean
        fun sidRevokeJwtConverter(clock: Clock): SidRevokeJwtConverter {
            // SidRevokeJwtConverter 생성용 SessionService 는 지역 mock — Bean 등록 없음.
            // @MockBean SessionService 와는 별개 인스턴스이나, SidRevokeJwtConverter 는
            // jwt() 포스트 프로세서 사용 시 실제로 호출되지 않으므로 무방.
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

    // AuthController 의존 서비스 — Mockito @MockBean (companion object ByteBuddy 문제 회피)
    @MockBean
    lateinit var authenticationManager: CompositeAuthenticationManager

    @MockBean
    lateinit var sessionService: SessionService

    @MockBean
    lateinit var refreshTokenRepository: RefreshTokenRepository

    @MockBean
    lateinit var refreshTokenService: RefreshTokenService

    @MockBean
    lateinit var jwtIssuer: JwtIssuer

    @MockBean
    lateinit var systemRoleAssignmentRepository: SystemRoleAssignmentRepository

    @MockBean
    lateinit var authAuditLogService: AuthAuditLogService

    @MockBean
    lateinit var mfaService: MfaService

    @MockBean
    lateinit var mfaChallengeTokenService: MfaChallengeTokenService

    /** 기본값: 전역 역할 없음 (일반 사용자). 역할 의존 케이스는 개별 테스트에서 재정의. */
    @org.junit.jupiter.api.BeforeEach
    fun stubSystemRoles() {
        `when`(systemRoleAssignmentRepository.findRolesByUser(anyUuid()))
            .thenReturn(emptySet<SystemRole>())
    }

    /**
     * 기본값: TOTP 미활성 (isEnabled=false) — 기존 로그인 흐름이 2단계로 빠지지 않게 한다(회귀 0).
     * TOTP 활성 케이스는 개별 테스트에서 `when(mfaService.isEnabled(...)).thenReturn(true)` 로 재정의한다.
     */
    @org.junit.jupiter.api.BeforeEach
    fun stubMfaDisabledByDefault() {
        `when`(mfaService.isEnabled(anyUuid())).thenReturn(false)
    }

    // ── login 성공 (provider=local) — 디스패처 위임 (FR-AU-06 Task 3) ───────────

    @Test
    fun `login success with provider local returns 200 and dispatches to local`() {
        val userId = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val sessionId = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val accessToken = "eyJhbGciOiJSUzI1NiJ9.test.access"

        val principal =
            Principal(
                userId = userId,
                providerType = ProviderType.LOCAL,
                displayName = "Alice",
                externalSubject = null,
            )
        val mockSession =
            mock(Session::class.java).also {
                `when`(it.id).thenReturn(sessionId)
                `when`(it.userId).thenReturn(userId)
                `when`(it.providerId).thenReturn("local")
            }

        `when`(
            authenticationManager.authenticate(
                eqStr("local"),
                org.mockito.ArgumentMatchers.anyString(),
                anyCharArray(),
            ),
        ).thenReturn(AuthnResult.Success(principal))
        `when`(
            sessionService.create(
                anyUuid(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.nullable(String::class.java),
                org.mockito.ArgumentMatchers.nullable(String::class.java),
                anyBool(),
            ),
        ).thenReturn(mockSession)
        `when`(
            jwtIssuer.issue(
                anyUuid(),
                anyUuid(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyList(),
                anyBool(),
            ),
        ).thenReturn(accessToken)

        mockMvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"provider":"local","username":"alice","password":"secret"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.access_token").value(accessToken))
            .andExpect(jsonPath("$.token_type").value("Bearer"))
            .andExpect(jsonPath("$.expires_in").value(900))
            // 쿠키 속성(HttpOnly/Secure)은 buildRefreshCookie 공유 헬퍼로 보장돼 refresh 테스트가 전수 검증.
            .andExpect(cookie().exists("refresh_token"))
            .andExpect(cookie().maxAge("refresh_token", 1209600))
            .andExpect(cookie().path("refresh_token", "/api/v1/auth"))

        // 디스패처에 정확히 "local" provider + "alice" username 이 전달됐는지 검증 (FR-AU-06 명시 선택)
        verify(authenticationManager).authenticate(eqStr("local"), eqStr("alice"), anyCharArray())
    }

    // ── login provider=ldap — 디스패처에 "ldap" 전달 검증 (FR-AU-06 Task 3) ─────

    @Test
    fun `login with provider ldap dispatches to ldap`() {
        // ldap 결과는 본 테스트 관심사가 아니므로 Failure 로 단순화(발급 경로 미진입).
        `when`(
            authenticationManager.authenticate(
                eqStr("ldap"),
                org.mockito.ArgumentMatchers.anyString(),
                anyCharArray(),
            ),
        ).thenReturn(AuthnResult.Failure(INVALID_CREDENTIALS))

        mockMvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"provider":"ldap","username":"bob","password":"secret"}"""),
        )
            .andExpect(status().isUnauthorized)

        verify(authenticationManager).authenticate(
            eqStr("ldap"),
            eqStr("bob"),
            anyCharArray(),
        )
    }

    // ── login provider 누락/빈 문자열 — 400 provider_required (FR-AU-06 Task 3) ─

    @Test
    fun `login with blank provider returns 400 provider_required`() {
        mockMvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"provider":"","username":"alice","password":"secret"}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("provider_required"))

        verify(authenticationManager, never()).authenticate(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            anyCharArray(),
        )
    }

    @Test
    fun `login with missing provider field returns 400 provider_required`() {
        mockMvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"username":"alice","password":"secret"}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("provider_required"))

        verify(authenticationManager, never()).authenticate(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            anyCharArray(),
        )
    }

    // ── login 디스패처 Failure — 401 invalid_credentials (reason 무관) ──────────

    /**
     * 디스패처가 [AuthnResult.Failure] 를 반환하면 reason 과 무관하게 401 invalid_credentials 로 응답한다.
     * 비활성/미등록(PROVIDER_UNAVAILABLE)·잠금(ACCOUNT_LOCKED) 까지 단일 응답코드로 통일해
     * 계정/구성 열거를 차단한다 (NFR-06-01). 모든 [FailureReason] 을 순회 검증한다.
     */
    @Test
    fun `login returns 401 invalid_credentials for every dispatcher failure reason`() {
        com.atlas.bts.identity.spi.FailureReason.entries.forEach { reason ->
            `when`(
                authenticationManager.authenticate(
                    org.mockito.ArgumentMatchers.anyString(),
                    org.mockito.ArgumentMatchers.anyString(),
                    anyCharArray(),
                ),
            ).thenReturn(AuthnResult.Failure(reason))

            mockMvc.perform(
                post("/api/v1/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"provider":"local","username":"alice","password":"wrong"}"""),
            )
                .andExpect(status().isUnauthorized)
                .andExpect(jsonPath("$.error").value("invalid_credentials"))
        }
    }

    // ── login 디스패처 ProviderUnavailableException — 503 (FR-AU-06 Task 3) ─────

    /**
     * 디스패처가 [ProviderUnavailableException] 을 전파하면 503 으로 응답한다.
     * catch-all @ExceptionHandler 가 500 으로 변질시키지 않도록 login 내부에서 직접 처리한다.
     */
    @Test
    fun `login returns 503 when dispatcher throws ProviderUnavailableException`() {
        `when`(
            authenticationManager.authenticate(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                anyCharArray(),
            ),
        ).thenThrow(ProviderUnavailableException("LDAP server down", providerType = "LDAP"))

        mockMvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"provider":"ldap","username":"alice","password":"secret"}"""),
        )
            .andExpect(status().isServiceUnavailable)
            .andExpect(jsonPath("$.error").value("provider_unavailable"))
    }

    // ── login 디스패처 RequiresMfa — 401 mfa_required ──────────────────────────

    @Test
    fun `login returns 401 mfa_required when dispatcher requires mfa`() {
        `when`(
            authenticationManager.authenticate(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                anyCharArray(),
            ),
        ).thenReturn(AuthnResult.RequiresMfa(MfaChallenge.NOT_IMPLEMENTED_YET))

        mockMvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"provider":"local","username":"alice","password":"secret"}"""),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").value("mfa_required"))
    }

    // ── FR-MF-01 로그인 2단계 게이트 + /auth/mfa/verify (Task 10) ─────────────────

    /**
     * (a) TOTP 미활성 사용자가 login 하면 기존 토큰 응답을 그대로 받는다 (회귀 0).
     *
     * isEnabled=false(기본 stub) 이면 `mfaService.isEnabled` 가 false 라 1단계 통과 즉시 정식 세션을
     * 발급한다. 응답은 기존 200 TokenResponse 형태이며 `mfa_required` 필드가 없어야 한다.
     * 또한 챌린지 토큰은 발급되지 않아야 한다(`issueChallenge` 미호출).
     */
    @Test
    fun `login with TOTP disabled returns normal token response without mfa_required (no regression)`() {
        stubLoginSuccess(mfaUserId, mfaSessionId, mfaAccessToken)
        `when`(mfaService.isEnabled(mfaUserId)).thenReturn(false)

        mockMvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"provider":"local","username":"alice","password":"secret"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.access_token").value(mfaAccessToken))
            .andExpect(jsonPath("$.token_type").value("Bearer"))
            .andExpect(jsonPath("$.mfa_required").doesNotExist())
            .andExpect(cookie().exists("refresh_token"))

        verify(mfaChallengeTokenService, never()).issueChallenge(anyUuid(), org.mockito.ArgumentMatchers.anyString())
    }

    /**
     * (b) TOTP 활성 사용자가 login 하면 정식 세션 대신 200 mfa_required + 챌린지 토큰을 받는다.
     *
     * isEnabled=true 면 1단계(pw) 통과 후 정식 세션을 발급하지 않고 단명 챌린지 토큰을 발급한다.
     * 응답은 `{mfa_required:true, mfa_challenge_token, expires_in}` 이며, 정식 세션/refresh 쿠키는
     * 발급되지 않아야 한다(`sessionService.create`·`refreshTokenRepository.save` 미호출, refresh_token 쿠키 부재).
     */
    @Test
    fun `login with TOTP enabled returns 200 mfa_required with challenge token and no session`() {
        stubLoginSuccess(mfaUserId, mfaSessionId, mfaAccessToken)
        `when`(mfaService.isEnabled(mfaUserId)).thenReturn(true)
        `when`(mfaChallengeTokenService.issueChallenge(mfaUserId, "local")).thenReturn(mfaChallengeTokenValue)

        mockMvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"provider":"local","username":"alice","password":"secret"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.mfa_required").value(true))
            .andExpect(jsonPath("$.mfa_challenge_token").value(mfaChallengeTokenValue))
            .andExpect(jsonPath("$.expires_in").exists())
            .andExpect(jsonPath("$.access_token").doesNotExist())
            .andExpect(cookie().doesNotExist("refresh_token"))

        verify(mfaChallengeTokenService).issueChallenge(mfaUserId, "local")
        // 정식 세션/refresh 토큰은 발급되지 않는다(2단계 통과 전).
        verify(sessionService, never()).create(
            anyUuid(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.nullable(String::class.java),
            org.mockito.ArgumentMatchers.nullable(String::class.java),
            anyBool(),
        )
    }

    /**
     * (c) /auth/mfa/verify 정답 코드 → 200 access_token + refresh 쿠키 + 세션 mfaVerified=true.
     *
     * 챌린지 토큰 validate 성공 + consume(일회용) 통과 + `mfaService.verifyLogin` Success 면
     * `issueTokens(mfaVerified=true)` 로 정식 세션을 발급한다. 세션 생성이 `mfaVerified=true` 인자로
     * 호출됐는지 ArgumentCaptor 로 단언한다(JWT mfa_verified 클레임 원천).
     */
    @Test
    fun `verify with correct code returns 200 access_token and creates session with mfaVerified true`() {
        val mockSession =
            mock(Session::class.java).also {
                `when`(it.id).thenReturn(mfaSessionId)
                `when`(it.userId).thenReturn(mfaUserId)
                `when`(it.providerId).thenReturn("local")
            }
        `when`(mfaChallengeTokenService.validate(mfaChallengeTokenValue))
            .thenReturn(MfaChallengeClaims(userId = mfaUserId, providerId = "local", jti = mfaJti))
        `when`(mfaChallengeTokenService.consume(mfaJti)).thenReturn(true)
        `when`(mfaService.verifyLogin(mfaUserId, "123456")).thenReturn(VerifyResult.Success)
        `when`(
            sessionService.create(
                anyUuid(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.nullable(String::class.java),
                org.mockito.ArgumentMatchers.nullable(String::class.java),
                anyBool(),
            ),
        ).thenReturn(mockSession)
        `when`(
            jwtIssuer.issue(
                anyUuid(),
                anyUuid(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyList(),
                anyBool(),
            ),
        ).thenReturn(mfaAccessToken)

        mockMvc.perform(
            post("/api/v1/auth/mfa/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"mfa_challenge_token":"$mfaChallengeTokenValue","code":"123456"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.access_token").value(mfaAccessToken))
            .andExpect(jsonPath("$.token_type").value("Bearer"))
            .andExpect(cookie().exists("refresh_token"))
            .andExpect(cookie().httpOnly("refresh_token", true))
            .andExpect(cookie().secure("refresh_token", true))

        // 세션이 mfaVerified=true 로 생성됐는지 — JWT mfa_verified 클레임 원천 (FR-MF-01 GAP-1).
        val mfaVerifiedCaptor = ArgumentCaptor.forClass(Boolean::class.java)
        verify(sessionService).create(
            anyUuid(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.nullable(String::class.java),
            org.mockito.ArgumentMatchers.nullable(String::class.java),
            captureBool(mfaVerifiedCaptor),
        )
        assertThat(mfaVerifiedCaptor.value).isTrue()
    }

    /**
     * (d-1) verify 오답 코드 → 401 invalid_code. verifyLogin 이 InvalidCode 면 정식 세션 미발급.
     */
    @Test
    fun `verify with wrong code returns 401 invalid_code`() {
        `when`(mfaChallengeTokenService.validate(mfaChallengeTokenValue))
            .thenReturn(MfaChallengeClaims(userId = mfaUserId, providerId = "local", jti = mfaJti))
        `when`(mfaChallengeTokenService.consume(mfaJti)).thenReturn(true)
        `when`(mfaService.verifyLogin(mfaUserId, "000000")).thenReturn(VerifyResult.InvalidCode)

        mockMvc.perform(
            post("/api/v1/auth/mfa/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"mfa_challenge_token":"$mfaChallengeTokenValue","code":"000000"}"""),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").value("invalid_code"))

        verify(sessionService, never()).create(
            anyUuid(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.nullable(String::class.java),
            org.mockito.ArgumentMatchers.nullable(String::class.java),
            anyBool(),
        )
    }

    /**
     * (d-2) verify 만료/위조 토큰 → 401. validate 가 null 이면 코드 검증 없이 거부하고 verifyLogin 미호출.
     */
    @Test
    fun `verify with expired or forged challenge token returns 401`() {
        `when`(mfaChallengeTokenService.validate(mfaChallengeTokenValue)).thenReturn(null)

        mockMvc.perform(
            post("/api/v1/auth/mfa/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"mfa_challenge_token":"$mfaChallengeTokenValue","code":"123456"}"""),
        )
            .andExpect(status().isUnauthorized)

        verify(mfaService, never()).verifyLogin(anyUuid(), org.mockito.ArgumentMatchers.anyString())
    }

    /**
     * (d-3) 이미 소비된(재사용) 챌린지 토큰 → 401. validate 성공이라도 consume 이 false 면 일회용 위반으로
     * 거부하고 verifyLogin 을 호출하지 않는다 (C1 replay 방어).
     */
    @Test
    fun `verify with already-consumed challenge token returns 401 and skips verifyLogin`() {
        `when`(mfaChallengeTokenService.validate(mfaChallengeTokenValue))
            .thenReturn(MfaChallengeClaims(userId = mfaUserId, providerId = "local", jti = mfaJti))
        `when`(mfaChallengeTokenService.consume(mfaJti)).thenReturn(false)

        mockMvc.perform(
            post("/api/v1/auth/mfa/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"mfa_challenge_token":"$mfaChallengeTokenValue","code":"123456"}"""),
        )
            .andExpect(status().isUnauthorized)

        verify(mfaService, never()).verifyLogin(anyUuid(), org.mockito.ArgumentMatchers.anyString())
    }

    /**
     * (d-4) limiter 차단 → 429 too_many_attempts. verifyLogin 이 TooManyAttempts 면 429 로 매핑한다.
     * catch-all 핸들러가 429 를 500 으로 변질시키지 않아야 한다.
     */
    @Test
    fun `verify when rate-limited returns 429 too_many_attempts`() {
        `when`(mfaChallengeTokenService.validate(mfaChallengeTokenValue))
            .thenReturn(MfaChallengeClaims(userId = mfaUserId, providerId = "local", jti = mfaJti))
        `when`(mfaChallengeTokenService.consume(mfaJti)).thenReturn(true)
        `when`(mfaService.verifyLogin(mfaUserId, "123456")).thenReturn(VerifyResult.TooManyAttempts)

        mockMvc.perform(
            post("/api/v1/auth/mfa/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"mfa_challenge_token":"$mfaChallengeTokenValue","code":"123456"}"""),
        )
            .andExpect(status().isTooManyRequests)
            .andExpect(jsonPath("$.error").value("too_many_attempts"))
    }

    /**
     * (BLOCKER-1) /auth/mfa/verify POST 는 CSRF 토큰 없이 403 이 아니어야 한다.
     *
     * SecurityConfig(Task 9)가 verify 를 `csrf.ignoringRequestMatchers` 에 등록했으므로 CSRF 필터가
     * POST 를 막지 않는다. CSRF 미등록이면 정식 세션 전(JWT 없음)이라 oauth2 자동 skip 도 적용되지 않아
     * 403 이 났을 것이다. validate=null 로 401 을 유도해 "403(CSRF 차단)이 아님" 을 확인한다.
     */
    @Test
    fun `verify POST without csrf token is not blocked by CSRF filter`() {
        `when`(mfaChallengeTokenService.validate(org.mockito.ArgumentMatchers.anyString())).thenReturn(null)

        mockMvc.perform(
            post("/api/v1/auth/mfa/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"mfa_challenge_token":"any","code":"123456"}"""),
        )
            // 핵심 단언 — CSRF 로 막혔다면 403 이다. 401(검증 실패)이면 CSRF 필터를 통과한 것.
            .andExpect(status().isUnauthorized)
    }

    // ── logout 성공 ────────────────────────────────────────────────────────────

    @Test
    fun `logout returns 204 and expires refresh_token cookie`() {
        val sessionId = UUID.fromString("22222222-2222-2222-2222-222222222222")

        mockMvc.perform(
            post("/api/v1/auth/logout")
                .with(
                    jwt().jwt { builder ->
                        builder
                            .subject("11111111-1111-1111-1111-111111111111")
                            .claim("sid", sessionId.toString())
                    },
                ),
        )
            .andExpect(status().isNoContent)
            .andExpect(cookie().maxAge("refresh_token", 0))
    }

    // ── logout 미인증 — 401 ────────────────────────────────────────────────────

    @Test
    fun `logout without bearer token returns 401`() {
        mockMvc.perform(
            post("/api/v1/auth/logout")
                .with(csrf()),
        )
            .andExpect(status().isUnauthorized)
    }

    // ── refresh 성공 ───────────────────────────────────────────────────────────

    @Test
    fun `refresh returns 200 with new access_token and rotated refresh cookie`() {
        val accessToken = "eyJhbGciOiJSUzI1NiJ9.refreshed.access"
        val newRefreshRaw = "cd".repeat(32)

        `when`(refreshTokenService.rotate(org.mockito.ArgumentMatchers.anyString())).thenReturn(
            RotateResult.Success(
                accessToken = accessToken,
                newRefreshTokenRaw = newRefreshRaw,
            ),
        )

        mockMvc.perform(
            post("/api/v1/auth/refresh")
                .with(csrf())
                .cookie(Cookie("refresh_token", refreshTokenRaw)),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.access_token").value(accessToken))
            .andExpect(jsonPath("$.token_type").value("Bearer"))
            .andExpect(jsonPath("$.expires_in").value(900))
            .andExpect(cookie().exists("refresh_token"))
            .andExpect(cookie().httpOnly("refresh_token", true))
            .andExpect(cookie().secure("refresh_token", true))
            .andExpect(cookie().maxAge("refresh_token", 1209600))
            .andExpect(cookie().path("refresh_token", "/api/v1/auth"))
    }

    // ── refresh replay — 401 (EC-23) ──────────────────────────────────────────

    @Test
    fun `refresh returns 401 with refresh_token_reused when token is replayed`() {
        `when`(refreshTokenService.rotate(org.mockito.ArgumentMatchers.anyString())).thenReturn(
            RotateResult.Failure(FailureReason.Replay),
        )

        mockMvc.perform(
            post("/api/v1/auth/refresh")
                .with(csrf())
                .cookie(Cookie("refresh_token", refreshTokenRaw)),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").value("refresh_token_reused"))
    }

    // ── refresh 만료 — 401 (EC-04) ────────────────────────────────────────────

    @Test
    fun `refresh returns 401 with refresh_token_expired when token is expired`() {
        `when`(refreshTokenService.rotate(org.mockito.ArgumentMatchers.anyString())).thenReturn(
            RotateResult.Failure(FailureReason.Expired),
        )

        mockMvc.perform(
            post("/api/v1/auth/refresh")
                .with(csrf())
                .cookie(Cookie("refresh_token", refreshTokenRaw)),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").value("refresh_token_expired"))
    }

    // ── refresh cookie 없음 — 401 ─────────────────────────────────────────────

    @Test
    fun `refresh returns 401 with refresh_token_invalid when cookie is absent`() {
        mockMvc.perform(
            post("/api/v1/auth/refresh")
                .with(csrf()),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").value("refresh_token_invalid"))
    }

    // ── GET /sessions 200 — 목록 반환 + 필드 정합 ────────────────────────────────

    @Test
    fun `GET sessions returns 200 with session list and correct fields`() {
        val userId = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val currentSid = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val otherSid = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
        val now = Instant.parse("2026-05-29T10:00:00Z")
        val created = Instant.parse("2026-05-20T09:00:00Z")

        val currentSession =
            Session(
                id = currentSid,
                userId = userId,
                providerId = "local",
                deviceFingerprint = "fp-secret-12",
                ipAddress = "10.0.0.1",
                userAgent = "Mozilla/5.0",
                createdAt = created,
                expiresAt = now.plusSeconds(86400),
                lastSeenAt = now,
                revokedAt = null,
                revokeReason = null,
            )
        val otherSession =
            Session(
                id = otherSid,
                userId = userId,
                providerId = "ldap",
                deviceFingerprint = null,
                ipAddress = null,
                userAgent = null,
                createdAt = created,
                expiresAt = now.plusSeconds(86400),
                lastSeenAt = now.minusSeconds(3600),
                revokedAt = null,
                revokeReason = null,
            )

        `when`(sessionService.findActiveByUser(userId)).thenReturn(listOf(currentSession, otherSession))

        mockMvc.perform(
            get("/api/v1/auth/sessions")
                .with(
                    jwt().jwt { builder ->
                        builder
                            .subject(userId.toString())
                            .claim("sid", currentSid.toString())
                    },
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.sessions").isArray)
            .andExpect(jsonPath("$.sessions.length()").value(2))
            .andExpect(jsonPath("$.sessions[0].sid").value(currentSid.toString()))
            .andExpect(jsonPath("$.sessions[0].providerId").value("local"))
            .andExpect(jsonPath("$.sessions[0].userAgent").value("Mozilla/5.0"))
            .andExpect(jsonPath("$.sessions[0].ipAddress").value("10.0.0.1"))
            .andExpect(jsonPath("$.sessions[0].lastSeenAt").exists())
            .andExpect(jsonPath("$.sessions[0].createdAt").exists())
            .andExpect(jsonPath("$.sessions[0].current").value(true))
            .andExpect(jsonPath("$.sessions[1].sid").value(otherSid.toString()))
            .andExpect(jsonPath("$.sessions[1].current").value(false))
    }

    // ── GET /sessions — deviceFingerprint 응답 미포함 (NFR-2) ─────────────────

    @Test
    fun `GET sessions does not expose deviceFingerprint in response`() {
        val userId = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val sid = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val now = Instant.parse("2026-05-29T10:00:00Z")

        val session =
            Session(
                id = sid,
                userId = userId,
                providerId = "local",
                deviceFingerprint = "should-not-appear",
                ipAddress = "10.0.0.1",
                userAgent = "Mozilla/5.0",
                createdAt = now,
                expiresAt = now.plusSeconds(86400),
                lastSeenAt = now,
                revokedAt = null,
                revokeReason = null,
            )

        `when`(sessionService.findActiveByUser(userId)).thenReturn(listOf(session))

        mockMvc.perform(
            get("/api/v1/auth/sessions")
                .with(
                    jwt().jwt { builder ->
                        builder
                            .subject(userId.toString())
                            .claim("sid", sid.toString())
                    },
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.sessions[0].deviceFingerprint").doesNotExist())
    }

    // ── GET /sessions — 미인증 401 ─────────────────────────────────────────────

    @Test
    fun `GET sessions without authentication returns 401`() {
        mockMvc.perform(get("/api/v1/auth/sessions"))
            .andExpect(status().isUnauthorized)
    }

    // ── GET /sessions — PAT 인증 시 403 (FR-6b / EC-8) ───────────────────────

    @Test
    fun `GET sessions with PAT authentication returns 403 with session_management_requires_interactive_login`() {
        // PAT 인증 시 principal 은 UsernamePasswordAuthenticationToken(userId String) 이며 Jwt 타입이 아님.
        // Spring Security Test 의 user() 포스트 프로세서가 UsernamePasswordAuthenticationToken 을 설정한다.
        mockMvc.perform(
            get("/api/v1/auth/sessions")
                .with(
                    org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                        .user("pat-user-id"),
                ),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error").value("session_management_requires_interactive_login"))
    }

    // ── DELETE /sessions/{sid} — Task 3 ──────────────────────────────────────

    /**
     * (a) 본인의 다른 활성 세션을 강제종료하면 204 를 반환하고,
     * sessionService.revoke("user_revoke") 와 refreshTokenRepository.revokeChainFromSession 을
     * 둘 다 호출해야 한다 (FR-3 / S-2).
     */
    @Test
    fun `DELETE sessions - own other session returns 204 and calls revoke and revokeChain`() {
        val userId = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val currentSid = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val targetSid = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
        val now = Instant.parse("2026-05-29T10:00:00Z")

        val targetSession =
            Session(
                id = targetSid,
                userId = userId,
                providerId = "local",
                deviceFingerprint = null,
                ipAddress = null,
                userAgent = null,
                createdAt = now,
                expiresAt = now.plusSeconds(86400),
                lastSeenAt = now,
                revokedAt = null,
                revokeReason = null,
            )
        `when`(sessionService.lookup(targetSid)).thenReturn(targetSession)

        mockMvc.perform(
            delete("/api/v1/auth/sessions/$targetSid")
                .with(csrf())
                .with(
                    jwt().jwt { builder ->
                        builder
                            .subject(userId.toString())
                            .claim("sid", currentSid.toString())
                    },
                ),
        )
            .andExpect(status().isNoContent)

        verify(sessionService).revoke(targetSid, "user_revoke")
        verify(refreshTokenRepository).revokeChainFromSession(targetSid)
    }

    /**
     * (b) 타인의 sid 로 DELETE 를 호출하면 404 를 반환한다 (IDOR 방어 / FR-4 / S-3).
     * 타인 세션은 revoke 되지 않아야 한다.
     */
    @Test
    fun `DELETE sessions - other user's sid returns 404 (IDOR defense)`() {
        val requestingUserId = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val otherUserId = UUID.fromString("99999999-9999-9999-9999-999999999999")
        val currentSid = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val otherUserSid = UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc")
        val now = Instant.parse("2026-05-29T10:00:00Z")

        val otherSession =
            Session(
                id = otherUserSid,
                userId = otherUserId,
                providerId = "local",
                deviceFingerprint = null,
                ipAddress = null,
                userAgent = null,
                createdAt = now,
                expiresAt = now.plusSeconds(86400),
                lastSeenAt = now,
                revokedAt = null,
                revokeReason = null,
            )
        `when`(sessionService.lookup(otherUserSid)).thenReturn(otherSession)

        mockMvc.perform(
            delete("/api/v1/auth/sessions/$otherUserSid")
                .with(csrf())
                .with(
                    jwt().jwt { builder ->
                        builder
                            .subject(requestingUserId.toString())
                            .claim("sid", currentSid.toString())
                    },
                ),
        )
            .andExpect(status().isNotFound)

        verify(sessionService, never()).revoke(anyUuid(), org.mockito.ArgumentMatchers.anyString())
        verify(refreshTokenRepository, never()).revokeChainFromSession(anyUuid())
    }

    /**
     * (c) 현재 세션(요청 JWT 의 sid 와 동일한 sid)을 강제종료 시도하면
     * 409 Conflict + cannot_revoke_current_session 에러코드를 반환한다 (FR-5 / S-4).
     */
    @Test
    fun `DELETE sessions - current session returns 409 with cannot_revoke_current_session`() {
        val userId = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val currentSid = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val now = Instant.parse("2026-05-29T10:00:00Z")

        val currentSession =
            Session(
                id = currentSid,
                userId = userId,
                providerId = "local",
                deviceFingerprint = null,
                ipAddress = null,
                userAgent = null,
                createdAt = now,
                expiresAt = now.plusSeconds(86400),
                lastSeenAt = now,
                revokedAt = null,
                revokeReason = null,
            )
        `when`(sessionService.lookup(currentSid)).thenReturn(currentSession)

        mockMvc.perform(
            delete("/api/v1/auth/sessions/$currentSid")
                .with(csrf())
                .with(
                    jwt().jwt { builder ->
                        builder
                            .subject(userId.toString())
                            .claim("sid", currentSid.toString())
                    },
                ),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.error").value("cannot_revoke_current_session"))

        verify(sessionService, never()).revoke(anyUuid(), org.mockito.ArgumentMatchers.anyString())
        verify(refreshTokenRepository, never()).revokeChainFromSession(anyUuid())
    }

    /**
     * (d) 미존재 또는 비활성(이미 revoke 된) sid 를 DELETE 하면 404 를 반환한다 (EC-2).
     * sessionService.lookup 이 null 을 반환하는 경우와 동일하게 처리.
     */
    @Test
    fun `DELETE sessions - non-existent or inactive sid returns 404`() {
        val userId = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val currentSid = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val missingSid = UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd")

        `when`(sessionService.lookup(missingSid)).thenReturn(null)

        mockMvc.perform(
            delete("/api/v1/auth/sessions/$missingSid")
                .with(csrf())
                .with(
                    jwt().jwt { builder ->
                        builder
                            .subject(userId.toString())
                            .claim("sid", currentSid.toString())
                    },
                ),
        )
            .andExpect(status().isNotFound)

        verify(sessionService, never()).revoke(anyUuid(), org.mockito.ArgumentMatchers.anyString())
        verify(refreshTokenRepository, never()).revokeChainFromSession(anyUuid())
    }

    /**
     * (d-2) 이미 revoke 된(비활성, revoked_at 채워진) 본인 세션을 DELETE 하면 404 를 반환한다 (EC-2).
     * lookup 이 null 이 아닌 revoked Session 을 반환하는 경우 — isActive=false 가드로 404 (멱등 재폐기 방지).
     */
    @Test
    fun `DELETE sessions - already revoked own session returns 404`() {
        val userId = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val currentSid = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
        val revokedSid = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee")
        val now = Instant.now()
        val revokedSession =
            Session(
                id = revokedSid,
                userId = userId,
                providerId = "local",
                deviceFingerprint = null,
                ipAddress = null,
                userAgent = null,
                createdAt = now.minusSeconds(86400),
                expiresAt = now.plusSeconds(86400),
                lastSeenAt = now.minusSeconds(3600),
                revokedAt = now.minusSeconds(60),
                revokeReason = "logout",
            )

        `when`(sessionService.lookup(revokedSid)).thenReturn(revokedSession)

        mockMvc.perform(
            delete("/api/v1/auth/sessions/$revokedSid")
                .with(csrf())
                .with(
                    jwt().jwt { builder ->
                        builder
                            .subject(userId.toString())
                            .claim("sid", currentSid.toString())
                    },
                ),
        )
            .andExpect(status().isNotFound)

        verify(sessionService, never()).revoke(anyUuid(), org.mockito.ArgumentMatchers.anyString())
        verify(refreshTokenRepository, never()).revokeChainFromSession(anyUuid())
    }

    /**
     * (e) PAT 인증으로 DELETE 를 호출하면 403 + session_management_requires_interactive_login 을 반환한다
     * (FR-6b / EC-8). GET /sessions 의 PAT 분기와 동일 패턴.
     */
    @Test
    fun `DELETE sessions - PAT authentication returns 403 with session_management_requires_interactive_login`() {
        val targetSid = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")

        mockMvc.perform(
            delete("/api/v1/auth/sessions/$targetSid")
                .with(csrf())
                .with(
                    org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                        .user("pat-user-id"),
                ),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error").value("session_management_requires_interactive_login"))
    }

    /**
     * (f) 잘못된 UUID 형식의 sid path 파라미터를 전달하면 400 Bad Request 를 반환한다 (EC-7).
     * Spring 이 @PathVariable UUID 바인딩 실패 시 MethodArgumentTypeMismatchException → 400 자동 처리.
     */
    @Test
    fun `DELETE sessions - invalid UUID format sid returns 400`() {
        mockMvc.perform(
            delete("/api/v1/auth/sessions/not-a-valid-uuid")
                .with(csrf())
                .with(
                    jwt().jwt { builder ->
                        builder
                            .subject("11111111-1111-1111-1111-111111111111")
                            .claim("sid", "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
                    },
                ),
        )
            .andExpect(status().isBadRequest)
    }

    // ── FR-AU-10 감사 emit (LOGIN_SUCCESS / LOGIN_FAILURE / LOGOUT) ─────────────

    /**
     * (a) 로그인 성공 시 LOGIN_SUCCESS 감사 이벤트를 기록한다 (spec §5).
     * userId=principal.userId, providerId=principal.providerType.name.lowercase(),
     * ip=request.remoteAddr, userAgent=request User-Agent 헤더.
     */
    @Test
    fun `login success records LOGIN_SUCCESS audit event`() {
        val userId = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val sessionId = UUID.fromString("22222222-2222-2222-2222-222222222222")

        val principal =
            Principal(
                userId = userId,
                providerType = ProviderType.LOCAL,
                displayName = "Alice",
                externalSubject = null,
            )
        val mockSession =
            mock(Session::class.java).also {
                `when`(it.id).thenReturn(sessionId)
                `when`(it.userId).thenReturn(userId)
                `when`(it.providerId).thenReturn("local")
            }

        `when`(
            authenticationManager.authenticate(
                eqStr("local"),
                org.mockito.ArgumentMatchers.anyString(),
                anyCharArray(),
            ),
        ).thenReturn(AuthnResult.Success(principal))
        `when`(
            sessionService.create(
                anyUuid(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.nullable(String::class.java),
                org.mockito.ArgumentMatchers.nullable(String::class.java),
                anyBool(),
            ),
        ).thenReturn(mockSession)
        `when`(
            jwtIssuer.issue(
                anyUuid(),
                anyUuid(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyList(),
                anyBool(),
            ),
        ).thenReturn("eyJhbGciOiJSUzI1NiJ9.test.access")

        mockMvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .header("User-Agent", "Mozilla/5.0 (audit-test)")
                .content("""{"provider":"local","username":"alice","password":"secret"}"""),
        )
            .andExpect(status().isOk)

        val captor = ArgumentCaptor.forClass(AuthAuditLog::class.java)
        verify(authAuditLogService).record(captureAuditLog(captor))
        val event = captor.value
        assertThat(event.eventType).isEqualTo(AuthEventType.LOGIN_SUCCESS)
        assertThat(event.userId).isEqualTo(userId)
        assertThat(event.providerId).isEqualTo("local")
        assertThat(event.ipAddress).isEqualTo("127.0.0.1")
        assertThat(event.userAgent).isEqualTo("Mozilla/5.0 (audit-test)")
    }

    /**
     * (b) 자격증명 실패(PROVIDER_UNAVAILABLE 외) 시 LOGIN_FAILURE 감사 이벤트를 기록한다 (spec §5 / EC-1).
     * userId=null(존재 probe 회피 NFR-2), metadata 에 username 시도값과 reason 포함.
     */
    @Test
    fun `login credential failure records LOGIN_FAILURE audit event with null userId`() {
        `when`(
            authenticationManager.authenticate(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                anyCharArray(),
            ),
        ).thenReturn(AuthnResult.Failure(INVALID_CREDENTIALS))

        mockMvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"provider":"local","username":"alice","password":"wrong"}"""),
        )
            .andExpect(status().isUnauthorized)

        val captor = ArgumentCaptor.forClass(AuthAuditLog::class.java)
        verify(authAuditLogService).record(captureAuditLog(captor))
        val event = captor.value
        assertThat(event.eventType).isEqualTo(AuthEventType.LOGIN_FAILURE)
        assertThat(event.userId).isNull()
        assertThat(event.metadata["username"]).isEqualTo("alice")
        assertThat(event.metadata["reason"]).isEqualTo(INVALID_CREDENTIALS.name)
    }

    /**
     * (c) PROVIDER_UNAVAILABLE 실패는 LOGIN_FAILURE 를 emit 하지 않는다 (EC-11 무중복).
     * provider-unavailable 은 LdapProvider 깊은 지점에서 LDAP_UNAVAILABLE 로만 기록되므로
     * AuthController 가 추가 기록하면 이중 기록이 된다.
     */
    @Test
    fun `login provider unavailable failure does not emit LOGIN_FAILURE`() {
        `when`(
            authenticationManager.authenticate(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                anyCharArray(),
            ),
        ).thenReturn(AuthnResult.Failure(com.atlas.bts.identity.spi.FailureReason.PROVIDER_UNAVAILABLE))

        mockMvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"provider":"ldap","username":"alice","password":"secret"}"""),
        )
            .andExpect(status().isUnauthorized)

        verify(authAuditLogService, never()).record(anyAuditLog())
    }

    /**
     * (d) 로그아웃 시 LOGOUT 감사 이벤트를 기록한다 (spec §5).
     * userId=JWT subject, metadata.sid=JWT sid 클레임.
     */
    @Test
    fun `logout records LOGOUT audit event with sid metadata`() {
        val userId = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val sessionId = UUID.fromString("22222222-2222-2222-2222-222222222222")

        mockMvc.perform(
            post("/api/v1/auth/logout")
                .with(
                    jwt().jwt { builder ->
                        builder
                            .subject(userId.toString())
                            .claim("sid", sessionId.toString())
                    },
                ),
        )
            .andExpect(status().isNoContent)

        val captor = ArgumentCaptor.forClass(AuthAuditLog::class.java)
        verify(authAuditLogService).record(captureAuditLog(captor))
        val event = captor.value
        assertThat(event.eventType).isEqualTo(AuthEventType.LOGOUT)
        assertThat(event.userId).isEqualTo(userId)
        assertThat(event.metadata["sid"]).isEqualTo(sessionId.toString())
    }

    /**
     * (e) B-1 best-effort — record() 가 예외를 던져도 로그인 응답은 200 이고 예외가 전파되지 않는다.
     * AuthController 는 의도적 무-트랜잭션이라 감사 INSERT 실패가 로그인 가용성을 인질로 잡으면 안 된다.
     */
    @Test
    fun `login still returns 200 when audit record throws (best-effort)`() {
        val userId = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val sessionId = UUID.fromString("22222222-2222-2222-2222-222222222222")

        val principal =
            Principal(
                userId = userId,
                providerType = ProviderType.LOCAL,
                displayName = "Alice",
                externalSubject = null,
            )
        val mockSession =
            mock(Session::class.java).also {
                `when`(it.id).thenReturn(sessionId)
                `when`(it.userId).thenReturn(userId)
                `when`(it.providerId).thenReturn("local")
            }

        `when`(
            authenticationManager.authenticate(
                eqStr("local"),
                org.mockito.ArgumentMatchers.anyString(),
                anyCharArray(),
            ),
        ).thenReturn(AuthnResult.Success(principal))
        `when`(
            sessionService.create(
                anyUuid(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.nullable(String::class.java),
                org.mockito.ArgumentMatchers.nullable(String::class.java),
                anyBool(),
            ),
        ).thenReturn(mockSession)
        `when`(
            jwtIssuer.issue(
                anyUuid(),
                anyUuid(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyList(),
                anyBool(),
            ),
        ).thenReturn("eyJhbGciOiJSUzI1NiJ9.test.access")
        doThrow(RuntimeException("audit DB down")).`when`(authAuditLogService)
            .record(anyAuditLog())

        mockMvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"provider":"local","username":"alice","password":"secret"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.access_token").value("eyJhbGciOiJSUzI1NiJ9.test.access"))
    }

    /**
     * (e-2) B-1 best-effort (logout) — record() 가 예외를 던져도 로그아웃 응답은 204 이고 예외 미전파.
     */
    @Test
    fun `logout still returns 204 when audit record throws (best-effort)`() {
        doThrow(RuntimeException("audit DB down")).`when`(authAuditLogService)
            .record(anyAuditLog())

        mockMvc.perform(
            post("/api/v1/auth/logout")
                .with(
                    jwt().jwt { builder ->
                        builder
                            .subject("11111111-1111-1111-1111-111111111111")
                            .claim("sid", "22222222-2222-2222-2222-222222222222")
                    },
                ),
        )
            .andExpect(status().isNoContent)
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * CharArray 파라미터의 Mockito any() 매처 — Kotlin non-null CharArray 에 null 전달 방지.
     * 디스패처 authenticate(providerId, username, password: CharArray) 의 password 인자에 사용한다.
     */
    private fun anyCharArray(): CharArray = org.mockito.ArgumentMatchers.any(CharArray::class.java) ?: charArrayOf()

    /**
     * String 파라미터의 Mockito eq() 매처 — eq() 가 null 을 반환해
     * Kotlin non-null String 파라미터에서 NPE 가 나는 것을 Elvis 로 방지한다.
     */
    private fun eqStr(value: String): String = org.mockito.ArgumentMatchers.eq(value) ?: value

    /**
     * UUID 파라미터의 Mockito any() 매처 — Kotlin non-null UUID 에 null 전달 방지.
     */
    private fun anyUuid(): UUID = org.mockito.ArgumentMatchers.any(UUID::class.java) ?: UUID.randomUUID()

    /**
     * Boolean 파라미터의 Mockito anyBoolean() 매처 — sessionService.create 의 mfaVerified 인자에 사용한다.
     * anyBoolean() 은 primitive boolean 을 반환하므로 null 가드가 불필요하다.
     */
    private fun anyBool(): Boolean = org.mockito.ArgumentMatchers.anyBoolean()

    /**
     * Boolean ArgumentCaptor.capture() 의 Kotlin primitive 가드.
     * capture() 는 boxed null 을 반환할 수 있어 Kotlin primitive Boolean 파라미터에서 NPE 를 유발한다.
     * 부수효과(인자 기록)는 유지하면서 placeholder false 로 치환한다.
     */
    private fun captureBool(captor: ArgumentCaptor<Boolean>): Boolean = captor.capture() ?: false

    /**
     * 1단계(pw) 인증 성공 + 세션/JWT 발급 stub 을 구성한다 (FR-MF-01 로그인 2단계 테스트 공용).
     *
     * 디스패처가 [AuthnResult.Success] 를 반환하고, 세션 생성/JWT 발급이 정해진 값을 돌려주도록 stub 한다.
     * MFA 분기(isEnabled)는 각 테스트가 별도로 stub 한다.
     */
    private fun stubLoginSuccess(
        userId: UUID,
        sessionId: UUID,
        accessToken: String,
    ) {
        val principal =
            Principal(
                userId = userId,
                providerType = ProviderType.LOCAL,
                displayName = "Alice",
                externalSubject = null,
            )
        val mockSession =
            mock(Session::class.java).also {
                `when`(it.id).thenReturn(sessionId)
                `when`(it.userId).thenReturn(userId)
                `when`(it.providerId).thenReturn("local")
            }
        `when`(
            authenticationManager.authenticate(
                eqStr("local"),
                org.mockito.ArgumentMatchers.anyString(),
                anyCharArray(),
            ),
        ).thenReturn(AuthnResult.Success(principal))
        `when`(
            sessionService.create(
                anyUuid(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.nullable(String::class.java),
                org.mockito.ArgumentMatchers.nullable(String::class.java),
                anyBool(),
            ),
        ).thenReturn(mockSession)
        `when`(
            jwtIssuer.issue(
                anyUuid(),
                anyUuid(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyList(),
                anyBool(),
            ),
        ).thenReturn(accessToken)
    }

    /**
     * AuthAuditLog 파라미터의 Mockito any() 매처 — Kotlin non-null record(AuthAuditLog) 에 null 전달 방지.
     */
    private fun anyAuditLog(): AuthAuditLog =
        org.mockito.ArgumentMatchers.any(AuthAuditLog::class.java)
            ?: AuthAuditLog(userId = null, eventType = AuthEventType.LOGOUT, providerId = "test")

    /**
     * ArgumentCaptor.capture() 의 Kotlin non-null 가드.
     * Mockito capture() 는 호출 시 null 을 반환해 Kotlin non-null 파라미터(record(AuthAuditLog)) 에서
     * NPE 를 유발한다. capture() 부수효과(인자 기록)는 유지하면서 placeholder 로 null 을 치환한다.
     */
    private fun captureAuditLog(captor: ArgumentCaptor<AuthAuditLog>): AuthAuditLog =
        captor.capture() ?: AuthAuditLog(userId = null, eventType = AuthEventType.LOGOUT, providerId = "test")
}
