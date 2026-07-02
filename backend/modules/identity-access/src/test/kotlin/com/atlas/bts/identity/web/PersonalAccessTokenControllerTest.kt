// PersonalAccessTokenController 슬라이스 테스트 — 발급 201/목록 200/취소 204 + PAT 403 + IDOR 404 + 검증 400 (FR-API-04 Task 5)

package com.atlas.bts.identity.web

import com.atlas.bts.identity.config.CorsConfig
import com.atlas.bts.identity.config.SecurityConfig
import com.atlas.bts.identity.jwt.SidRevokeJwtConverter
import com.atlas.bts.identity.pat.BlankPatNameException
import com.atlas.bts.identity.pat.EmptyScopeException
import com.atlas.bts.identity.pat.InvalidPatExpiryException
import com.atlas.bts.identity.pat.IssuedPersonalAccessToken
import com.atlas.bts.identity.pat.PatQuotaExceededException
import com.atlas.bts.identity.pat.PersonalAccessToken
import com.atlas.bts.identity.pat.PersonalAccessTokenNotFoundException
import com.atlas.bts.identity.pat.PersonalAccessTokenService
import com.atlas.bts.identity.pat.UnknownScopeException
import com.atlas.bts.identity.session.SessionService
import io.mockk.clearMocks
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.not
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.web.cors.CorsConfigurationSource
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

/**
 * PersonalAccessTokenController WebMvcTest 슬라이스 테스트 (FR-API-04 Task 5).
 *
 * ## 검증 시나리오 (`/api/v1/users/me/pats`)
 * - POST — JWT → 201 + raw token(1회) + 메타(name/scopes/expiresAt). token_hash/userId 비노출.
 * - POST — PAT(Jwt null) → 403 / 미인증 → 401.
 * - POST — 미지/빈 scope·무기한/범위밖 만료·name 공백·개수상한 → 400 (미지 scope 원문 미반사).
 * - GET  — JWT → 200 + 요약 목록(token/hash/userId 미포함, 만료 배지용 expiresAt 포함).
 * - GET  — PAT → 403 / 미인증 → 401.
 * - DELETE {id} — 본인 활성/이미취소(서비스 멱등) → 204 / 타인·미존재(IDOR) → 404 / PAT → 403 / 미인증 → 401.
 *
 * ## 의존성 모킹 전략 (TrustedDeviceControllerTest 선례)
 * SecurityConfig 필수 Bean + 컨트롤러 의존 [PersonalAccessTokenService] 는 companion object ByteBuddy
 * 문제를 피하려 @TestConfiguration + MockK 로 등록하고, 테스트마다 [clearMocks] 로 초기화한다.
 */
@WebMvcTest(
    controllers = [PersonalAccessTokenController::class],
    excludeAutoConfiguration = [OAuth2ClientAutoConfiguration::class],
)
@Import(SecurityConfig::class, PersonalAccessTokenControllerTest.SecurityBeans::class)
class PersonalAccessTokenControllerTest {
    @TestConfiguration
    class SecurityBeans {
        @Bean
        fun jwtDecoder(): JwtDecoder = mockk(relaxed = true)

        @Bean
        fun clock(): Clock = Clock.fixed(Instant.parse("2026-07-01T10:00:00Z"), ZoneOffset.UTC)

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
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var patService: PersonalAccessTokenService

    private val userId = UUID.fromString("11111111-1111-1111-1111-111111111111")
    private val patId = UUID.fromString("22222222-2222-2222-2222-222222222222")
    private val rawToken = "pat_" + "x".repeat(48)

    @BeforeEach
    fun resetMock() {
        clearMocks(patService)
    }

    private fun jwtFor(uid: UUID) =
        jwt().jwt { builder ->
            builder.subject(uid.toString()).claim("sid", UUID.randomUUID().toString())
        }

    private fun patWith(
        id: UUID = patId,
        name: String = "ci-token",
        scopes: List<String> = listOf("read:issues"),
        expiresAt: Instant? = Instant.parse("2026-07-31T10:00:00Z"),
        lastUsedAt: Instant? = null,
    ): PersonalAccessToken =
        PersonalAccessToken(
            id = id,
            userId = userId,
            name = name,
            // 64자 소문자 hex — 응답에 노출되면 안 되는 비밀값.
            tokenHash = "a".repeat(64),
            scopes = scopes,
            expiresAt = expiresAt,
            lastUsedAt = lastUsedAt,
            revokedAt = null,
            createdAt = Instant.parse("2026-07-01T10:00:00Z"),
        )

    // ── POST /pats ─────────────────────────────────────────────────────────────

    @Test
    fun `POST pats with JWT returns 201 with raw token and metadata`() {
        every { patService.issue(userId, "ci-token", listOf("read:issues"), 30) } returns
            IssuedPersonalAccessToken(rawToken = rawToken, token = patWith())

        mockMvc.perform(
            post("/api/v1/users/me/pats")
                .with(jwtFor(userId))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"ci-token","scopes":["read:issues"],"expiresInDays":30}"""),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id").value(patId.toString()))
            .andExpect(jsonPath("$.name").value("ci-token"))
            .andExpect(jsonPath("$.scopes[0]").value("read:issues"))
            .andExpect(jsonPath("$.token").value(rawToken))
            .andExpect(jsonPath("$.expiresAt").value("2026-07-31T10:00:00Z"))

        verify { patService.issue(userId, "ci-token", listOf("read:issues"), 30) }
    }

    @Test
    fun `POST pats never exposes token_hash or userId`() {
        every { patService.issue(any(), any(), any(), any()) } returns
            IssuedPersonalAccessToken(rawToken = rawToken, token = patWith())

        mockMvc.perform(
            post("/api/v1/users/me/pats")
                .with(jwtFor(userId))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"ci-token","scopes":["read:issues"],"expiresInDays":30}"""),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.tokenHash").doesNotExist())
            .andExpect(jsonPath("$.token_hash").doesNotExist())
            .andExpect(jsonPath("$.userId").doesNotExist())
    }

    @Test
    fun `POST pats with PAT returns 403`() {
        mockMvc.perform(
            post("/api/v1/users/me/pats")
                .with(user("pat-user-id"))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"ci-token","scopes":["read:issues"],"expiresInDays":30}"""),
        )
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error").value("session_management_requires_interactive_login"))

        verify(exactly = 0) { patService.issue(any(), any(), any(), any()) }
    }

    @Test
    fun `POST pats without authentication returns 401`() {
        mockMvc.perform(
            post("/api/v1/users/me/pats")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"ci-token","scopes":["read:issues"],"expiresInDays":30}"""),
        )
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `POST pats with unknown scope returns 400 and does not reflect the scope`() {
        every { patService.issue(any(), any(), any(), any()) } throws UnknownScopeException()

        mockMvc.perform(
            post("/api/v1/users/me/pats")
                .with(jwtFor(userId))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"ci-token","scopes":["evil:scope"],"expiresInDays":30}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("invalid_scope"))
            .andExpect(content().string(not(containsString("evil:scope"))))
    }

    @Test
    fun `POST pats with empty scope returns 400`() {
        every { patService.issue(any(), any(), any(), any()) } throws EmptyScopeException()

        mockMvc.perform(
            post("/api/v1/users/me/pats")
                .with(jwtFor(userId))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"ci-token","scopes":[],"expiresInDays":30}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("invalid_scope"))
    }

    @Test
    fun `POST pats with null expiresInDays returns 400`() {
        every { patService.issue(any(), any(), any(), any()) } throws InvalidPatExpiryException()

        mockMvc.perform(
            post("/api/v1/users/me/pats")
                .with(jwtFor(userId))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"ci-token","scopes":["read:issues"],"expiresInDays":null}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("invalid_expiry"))
    }

    @Test
    fun `POST pats with blank name returns 400`() {
        every { patService.issue(any(), any(), any(), any()) } throws BlankPatNameException()

        mockMvc.perform(
            post("/api/v1/users/me/pats")
                .with(jwtFor(userId))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"   ","scopes":["read:issues"],"expiresInDays":30}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("invalid_name"))
    }

    @Test
    fun `POST pats when quota exceeded returns 400`() {
        every { patService.issue(any(), any(), any(), any()) } throws PatQuotaExceededException()

        mockMvc.perform(
            post("/api/v1/users/me/pats")
                .with(jwtFor(userId))
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"ci-token","scopes":["read:issues"],"expiresInDays":30}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("quota_exceeded"))
    }

    // ── GET /pats ──────────────────────────────────────────────────────────────

    @Test
    fun `GET pats with JWT returns 200 with summaries`() {
        every { patService.listByUser(userId) } returns
            listOf(patWith(lastUsedAt = Instant.parse("2026-07-02T09:00:00Z")))

        mockMvc.perform(get("/api/v1/users/me/pats").with(jwtFor(userId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.pats.length()").value(1))
            .andExpect(jsonPath("$.pats[0].id").value(patId.toString()))
            .andExpect(jsonPath("$.pats[0].name").value("ci-token"))
            .andExpect(jsonPath("$.pats[0].scopes[0]").value("read:issues"))
            .andExpect(jsonPath("$.pats[0].expiresAt").value("2026-07-31T10:00:00Z"))
            .andExpect(jsonPath("$.pats[0].lastUsedAt").value("2026-07-02T09:00:00Z"))
    }

    @Test
    fun `GET pats never exposes token or hash`() {
        every { patService.listByUser(userId) } returns listOf(patWith())

        mockMvc.perform(get("/api/v1/users/me/pats").with(jwtFor(userId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.pats[0].token").doesNotExist())
            .andExpect(jsonPath("$.pats[0].tokenHash").doesNotExist())
            .andExpect(jsonPath("$.pats[0].token_hash").doesNotExist())
            .andExpect(jsonPath("$.pats[0].userId").doesNotExist())
    }

    @Test
    fun `GET pats with no tokens returns 200 with empty list`() {
        every { patService.listByUser(userId) } returns emptyList()

        mockMvc.perform(get("/api/v1/users/me/pats").with(jwtFor(userId)))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.pats.length()").value(0))
    }

    @Test
    fun `GET pats with PAT returns 403`() {
        mockMvc.perform(get("/api/v1/users/me/pats").with(user("pat-user-id")))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error").value("session_management_requires_interactive_login"))

        verify(exactly = 0) { patService.listByUser(any()) }
    }

    @Test
    fun `GET pats without authentication returns 401`() {
        mockMvc.perform(get("/api/v1/users/me/pats"))
            .andExpect(status().isUnauthorized)
    }

    // ── DELETE /pats/{id} ────────────────────────────────────────────────────────

    /**
     * 본인 활성 취소·본인 이미취소(서비스 멱등) 모두 컨트롤러 관점에선 예외 없이 완료되어 204 다.
     * 활성/이미취소 구분은 [PersonalAccessTokenService.revoke] 내부(T4 테스트) 관심사다.
     */
    @Test
    fun `DELETE pat when owned or already revoked returns 204`() {
        every { patService.revoke(patId, userId) } returns Unit

        mockMvc.perform(delete("/api/v1/users/me/pats/{id}", patId).with(jwtFor(userId)).with(csrf()))
            .andExpect(status().isNoContent)

        verify { patService.revoke(patId, userId) }
    }

    @Test
    fun `DELETE pat when not owned or missing returns 404 not_found`() {
        every { patService.revoke(any(), any()) } throws PersonalAccessTokenNotFoundException()

        mockMvc.perform(delete("/api/v1/users/me/pats/{id}", patId).with(jwtFor(userId)).with(csrf()))
            .andExpect(status().isNotFound)
            .andExpect(jsonPath("$.error").value("not_found"))
    }

    @Test
    fun `DELETE pat with PAT returns 403`() {
        mockMvc.perform(delete("/api/v1/users/me/pats/{id}", patId).with(user("pat-user-id")).with(csrf()))
            .andExpect(status().isForbidden)
            .andExpect(jsonPath("$.error").value("session_management_requires_interactive_login"))

        verify(exactly = 0) { patService.revoke(any(), any()) }
    }

    @Test
    fun `DELETE pat without authentication returns 401`() {
        mockMvc.perform(delete("/api/v1/users/me/pats/{id}", patId).with(csrf()))
            .andExpect(status().isUnauthorized)
    }
}
