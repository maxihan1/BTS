// AuthController 슬라이스 테스트 — login/logout/refresh 3 엔드포인트 (FR-AU-09 Task 21)

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
