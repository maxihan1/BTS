// AuthController 슬라이스 테스트 — login/logout/refresh 3 엔드포인트 RED 케이스 (FR-AU-09 Task 21)

package com.atlas.bts.identity.web

import com.atlas.bts.identity.config.CorsConfig
import com.atlas.bts.identity.config.SecurityConfig
import com.atlas.bts.identity.jwt.JwtIssuer
import com.atlas.bts.identity.jwt.SidRevokeJwtConverter
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
import io.mockk.every
import io.mockk.mockk
import jakarta.servlet.http.Cookie
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
 * AuthController WebMvcTest 슬라이스 테스트 (FR-AU-09 Task 21 RED).
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
 *
 * ## CSRF 처리
 * - login: SecurityConfig 에서 CSRF skip
 * - logout: jwt() 포스트 프로세서 — Bearer stateless 로 CsrfFilter skip
 * - refresh: csrf() 포스트 프로세서 사용 (public endpoint, CSRF 보호 대상)
 */
@WebMvcTest(
    controllers = [AuthController::class],
    excludeAutoConfiguration = [OAuth2ClientAutoConfiguration::class],
)
@Import(SecurityConfig::class, AuthControllerTest.MockBeans::class)
class AuthControllerTest {

    /** raw refresh token — 64자 소문자 hex (RefreshToken.HASH_LENGTH 와 동일 길이) */
    private val refreshTokenRaw = "ab".repeat(32)

    @TestConfiguration
    class MockBeans {
        @Bean
        fun jwtDecoder(): JwtDecoder = mockk(relaxed = true)

        @Bean
        fun sidRevokeJwtConverter(): SidRevokeJwtConverter {
            val sessionService: SessionService = mockk(relaxed = true)
            val clock = Clock.fixed(Instant.parse("2026-05-21T10:00:00Z"), ZoneOffset.UTC)
            return SidRevokeJwtConverter(sessionService, clock)
        }

        @Bean
        fun corsConfigurationSource(): CorsConfigurationSource =
            CorsConfig().corsConfigurationSource(listOf("http://localhost:5173"))

        @Bean
        fun providerRegistry(): ProviderRegistry = mockk(relaxed = true)

        @Bean
        fun sessionService(): SessionService = mockk(relaxed = true)

        @Bean
        fun refreshTokenRepository(): RefreshTokenRepository = mockk(relaxed = true)

        @Bean
        fun refreshTokenService(): RefreshTokenService = mockk(relaxed = true)

        @Bean
        fun jwtIssuer(): JwtIssuer = mockk(relaxed = true)
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var providerRegistry: ProviderRegistry

    @Autowired
    lateinit var sessionService: SessionService

    @Autowired
    lateinit var refreshTokenRepository: RefreshTokenRepository

    @Autowired
    lateinit var refreshTokenService: RefreshTokenService

    @Autowired
    lateinit var jwtIssuer: JwtIssuer

    // ── login 성공 ─────────────────────────────────────────────────────────────

    @Test
    fun `login success returns 200 with access_token body and refresh_token cookie`() {
        val userId = UUID.fromString("11111111-1111-1111-1111-111111111111")
        val sessionId = UUID.fromString("22222222-2222-2222-2222-222222222222")
        val accessToken = "eyJhbGciOiJSUzI1NiJ9.test.access"

        val mockProvider: com.atlas.bts.identity.spi.AuthenticationProvider = mockk()
        val principal = Principal(
            userId = userId,
            providerType = ProviderType.LOCAL,
            displayName = "Alice",
            externalSubject = null,
        )
        val mockSession: Session = mockk {
            every { id } returns sessionId
            every { userId } returns userId
            every { providerId } returns "local"
        }

        every { providerRegistry.findFor(any<Credential>()) } returns mockProvider
        every { mockProvider.authenticate(any()) } returns AuthnResult.Success(principal)
        every { sessionService.create(any(), any(), any(), any()) } returns mockSession
        every { jwtIssuer.issue(userId, sessionId, "local", emptyList()) } returns accessToken
        every { refreshTokenRepository.save(any()) } returns Unit

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
        val mockProvider: com.atlas.bts.identity.spi.AuthenticationProvider = mockk()

        every { providerRegistry.findFor(any<Credential>()) } returns mockProvider
        every { mockProvider.authenticate(any()) } returns AuthnResult.Failure(INVALID_CREDENTIALS)

        mockMvc.perform(
            post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"provider":"local","username":"alice","password":"wrong"}"""),
        )
            .andExpect(status().isUnauthorized)
            .andExpect(jsonPath("$.error").value("invalid_credentials"))
    }

    // ── login 실패 (provider 없음) ────────────────────────────────────────────

    @Test
    fun `login returns 401 when no provider found for credential`() {
        every { providerRegistry.findFor(any<Credential>()) } returns null

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

        every { sessionService.revoke(any(), any()) } returns Unit
        every { refreshTokenRepository.revokeChainFromSession(any()) } returns 0

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

        every { refreshTokenService.rotate(any()) } returns RotateResult.Success(
            accessToken = accessToken,
            newRefreshTokenRaw = newRefreshRaw,
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
        every { refreshTokenService.rotate(any()) } returns RotateResult.Failure(FailureReason.Replay)

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
        every { refreshTokenService.rotate(any()) } returns RotateResult.Failure(FailureReason.Expired)

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
}
