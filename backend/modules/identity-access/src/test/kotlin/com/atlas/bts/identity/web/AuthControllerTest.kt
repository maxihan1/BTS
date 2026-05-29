// AuthController 슬라이스 테스트 — login/logout/refresh/sessions/revokeSession 엔드포인트 (FR-AU-09 Task 21/Task 2/Task 3)

package com.atlas.bts.identity.web

import com.atlas.bts.identity.config.CorsConfig
import com.atlas.bts.identity.config.SecurityConfig
import com.atlas.bts.identity.jwt.JwtIssuer
import com.atlas.bts.identity.jwt.SidRevokeJwtConverter
import com.atlas.bts.identity.pat.PersonalAccessTokenService
import com.atlas.bts.identity.session.RefreshTokenRepository
import com.atlas.bts.identity.session.RefreshTokenService
import com.atlas.bts.identity.session.RefreshTokenService.FailureReason
import com.atlas.bts.identity.session.RefreshTokenService.RotateResult
import com.atlas.bts.identity.session.Session
import com.atlas.bts.identity.session.SessionService
import com.atlas.bts.identity.spi.AuthnResult
import com.atlas.bts.identity.spi.Credential
import com.atlas.bts.identity.spi.FailureReason.INVALID_CREDENTIALS
import com.atlas.bts.identity.spi.Principal
import com.atlas.bts.identity.spi.ProviderRegistry
import com.atlas.bts.identity.spi.ProviderType
import io.mockk.mockk
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
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
 * - login 성공 — 200 + access_token body + Set-Cookie refresh_token HttpOnly Secure SameSite=Strict Max-Age=1209600
 * - login 실패 (비밀번호 불일치) — 401 + {"error": "invalid_credentials"}
 * - login 실패 (provider 없음) — 401 + {"error": "invalid_credentials"}
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
 * - AuthController 의존 서비스 (SessionService, RefreshTokenRepository, RefreshTokenService, JwtIssuer, ProviderRegistry):
 *   @MockBean (Mockito) — MockK 로 companion object 포함 클래스를 Spring @Bean 등록 시
 *   ByteBuddy 의 $Companion 클래스 로드 실패 (NoClassDefFoundError) 회피.
 */
@WebMvcTest(
    controllers = [AuthController::class],
    excludeAutoConfiguration = [OAuth2ClientAutoConfiguration::class],
)
@Import(SecurityConfig::class, AuthControllerTest.SecurityBeans::class)
class AuthControllerTest {

    /** raw refresh token — 64자 소문자 hex */
    private val refreshTokenRaw = "ab".repeat(32)

    @TestConfiguration
    class SecurityBeans {
        @Bean
        fun jwtDecoder(): JwtDecoder = mockk(relaxed = true)

        @Bean
        fun sidRevokeJwtConverter(): SidRevokeJwtConverter {
            // SidRevokeJwtConverter 생성용 SessionService 는 지역 mock — Bean 등록 없음.
            // @MockBean SessionService 와는 별개 인스턴스이나, SidRevokeJwtConverter 는
            // jwt() 포스트 프로세서 사용 시 실제로 호출되지 않으므로 무방.
            val sessionService: SessionService = mockk(relaxed = true)
            val clock = Clock.fixed(Instant.parse("2026-05-21T10:00:00Z"), ZoneOffset.UTC)
            return SidRevokeJwtConverter(sessionService, clock)
        }

        @Bean
        fun corsConfigurationSource(): CorsConfigurationSource =
            CorsConfig().corsConfigurationSource(listOf("http://localhost:5173"))

        @Bean
        fun personalAccessTokenService(): PersonalAccessTokenService = mockk(relaxed = true)
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    // AuthController 의존 서비스 — Mockito @MockBean (companion object ByteBuddy 문제 회피)
    @MockBean
    lateinit var providerRegistry: ProviderRegistry

    @MockBean
    lateinit var sessionService: SessionService

    @MockBean
    lateinit var refreshTokenRepository: RefreshTokenRepository

    @MockBean
    lateinit var refreshTokenService: RefreshTokenService

    @MockBean
    lateinit var jwtIssuer: JwtIssuer

    // ── login 성공 ─────────────────────────────────────────────────────────────

    @Test
    fun `login success returns 200 with access_token body and refresh_token cookie`() {
        val userId = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val sessionId = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val accessToken = "eyJhbGciOiJSUzI1NiJ9.test.access"

        val mockProvider = mock(com.atlas.bts.identity.spi.AuthenticationProvider::class.java)
        val principal =
            Principal(
                userId = userId,
                providerType = ProviderType.LOCAL,
                displayName = "Alice",
                externalSubject = null,
            )
        val mockSession = mock(Session::class.java).also {
            `when`(it.id).thenReturn(sessionId)
            `when`(it.userId).thenReturn(userId)
            `when`(it.providerId).thenReturn("local")
        }

        `when`(providerRegistry.findFor(anyCredential())).thenReturn(mockProvider)
        `when`(mockProvider.authenticate(anyCredential())).thenReturn(AuthnResult.Success(principal))
        `when`(
            sessionService.create(
                anyUuid(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.nullable(String::class.java),
                org.mockito.ArgumentMatchers.nullable(String::class.java),
            ),
        ).thenReturn(mockSession)
        `when`(
            jwtIssuer.issue(
                anyUuid(),
                anyUuid(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyList(),
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
            .andExpect(cookie().exists("refresh_token"))
            .andExpect(cookie().httpOnly("refresh_token", true))
            .andExpect(cookie().secure("refresh_token", true))
            .andExpect(cookie().maxAge("refresh_token", 1209600))
            .andExpect(cookie().path("refresh_token", "/api/v1/auth"))
    }

    // ── login 실패 (비밀번호 불일치) ────────────────────────────────────────────

    @Test
    fun `login returns 401 when password is wrong`() {
        val mockProvider = mock(com.atlas.bts.identity.spi.AuthenticationProvider::class.java)

        `when`(providerRegistry.findFor(anyCredential())).thenReturn(mockProvider)
        `when`(mockProvider.authenticate(anyCredential())).thenReturn(AuthnResult.Failure(INVALID_CREDENTIALS))

        mockMvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"provider":"local","username":"alice","password":"wrong"}"""),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").value("invalid_credentials"))
    }

    // ── login 실패 (provider 없음) ─────────────────────────────────────────────

    @Test
    fun `login returns 401 when no provider found for credential`() {
        `when`(providerRegistry.findFor(anyCredential())).thenReturn(null)

        mockMvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"provider":"local","username":"nobody","password":"x"}"""),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").value("invalid_credentials"))
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

        val currentSession = Session(
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
        val otherSession = Session(
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

        val session = Session(
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

        val targetSession = Session(
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

        val otherSession = Session(
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

        val currentSession = Session(
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

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Credential sealed interface 의 Mockito any() 매처.
     * Kotlin non-null 타입에 Mockito any() 가 null 을 반환하는 것을 방지하기 위해
     * Elvis 연산자로 더미 기본값을 제공한다.
     */
    private fun anyCredential(): Credential =
        org.mockito.ArgumentMatchers.any(Credential::class.java)
            ?: Credential.UsernamePassword("", charArrayOf())

    /**
     * UUID 파라미터의 Mockito any() 매처 — Kotlin non-null UUID 에 null 전달 방지.
     */
    private fun anyUuid(): UUID =
        org.mockito.ArgumentMatchers.any(UUID::class.java) ?: UUID.randomUUID()
}
