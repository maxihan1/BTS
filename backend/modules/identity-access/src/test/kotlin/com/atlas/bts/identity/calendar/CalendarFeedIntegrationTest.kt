// 캘린더 피드 end-to-end 통합테스트 — 발급/익명GET/취소/rotate/PAT403/401/CASCADE/격리 (FR-CA-02 Task 7)

package com.atlas.bts.identity.calendar

import com.atlas.bts.identity.provider.ldap.AutoProvisionService
import com.atlas.bts.identity.provider.ldap.ExternalAccountRepository
import com.atlas.bts.identity.provider.ldap.LdapProvider
import com.atlas.bts.identity.provider.ldap.LdapProviderConfigService
import com.bts.shared.calendar.CalendarIssuePage
import com.bts.shared.calendar.CalendarIssueView
import com.bts.shared.calendar.CalendarWorklogPage
import com.bts.shared.calendar.UserCalendarLookupPort
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.ldap.core.LdapTemplate
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Security
import java.time.Instant
import java.util.UUID

/** userId 별로 서로 다른 담당 이슈를 반환하는 stub — 교차사용자 격리 검증용(userA 피드에 userB 이슈 부재). */
private val USER_A_ID: UUID = UUID.fromString("0f000000-0000-0000-0000-0000000009a1")
private val USER_B_ID: UUID = UUID.fromString("0f000000-0000-0000-0000-0000000009b1")

/**
 * [CalendarFeedController] · [IcalFeedController] end-to-end 통합테스트 (FR-CA-02 Task 7).
 *
 * [CalendarControllerIntegrationTest] 부팅 레시피(prod + RANDOM_PORT + Testcontainers + LDAP @MockBean +
 * 임시 RSA PEM)를 미러하고, PAT full-boot 시드([PatAndConcurrencyIntegrationTest])를 결합한다.
 * `UserCalendarLookupPort` 는 issue-tracking 을 gradle 의존하지 않으므로(BC 격리) userId 별 데이터를 주는
 * [UserKeyedStubCalendarPortConfig] `@Primary` stub 으로 대체한다.
 *
 * 검증. 발급(JWT 201)→익명 GET .ics(200)→취소(204)→404 왕복 · 재발급 rotate(기존 URL 404) ·
 * PAT 관리 API 403 · 미인증 401 · 비-GET 피드 401 · user 삭제 시 토큰 CASCADE→404 · 교차사용자 격리 ·
 * negative-probe(원문 토큰/해시/타 사용자 미노출).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration",
    ],
)
@AutoConfigureMockMvc
@ActiveProfiles("prod")
@Import(UserKeyedStubCalendarPortConfig::class)
@Testcontainers
class CalendarFeedIntegrationTest {
    companion object {
        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("bts_test")
                .withUsername("bts")
                .withPassword("bts_test")

        @DynamicPropertySource
        @JvmStatic
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            registry.add("spring.flyway.enabled") { "true" }
            registry.add("bts.auth.issuer-uri") { "http://localhost:8090" }
            registry.add("bts.security.cors.allowed-origins") { "http://localhost:5173" }
            registry.add("spring.ldap.urls") { "ldap://localhost:389" }
            registry.add("spring.ldap.base") { "dc=bts,dc=local" }
            registry.add("bts.auth.jwt.private-key-pem-path") { pemFilePath }
        }

        val pemFilePath: String =
            run {
                if (Security.getProvider("BC") == null) {
                    Security.addProvider(BouncyCastleProvider())
                }
                val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
                val pemContent =
                    buildString {
                        appendLine("-----BEGIN PRIVATE KEY-----")
                        val mimeEncoder = java.util.Base64.getMimeEncoder(64, "\n".toByteArray())
                        appendLine(mimeEncoder.encodeToString(keyPair.private.encoded))
                        append("-----END PRIVATE KEY-----")
                    }
                val tmpFile = Files.createTempFile("bts-test-key-", ".pem")
                Files.writeString(tmpFile, pemContent)
                tmpFile.toAbsolutePath().toString()
            }

        /** SHA-256 hex — PAT token_hash 계산용("pat_" prefix 포함 전체 토큰). */
        private fun sha256Hex(raw: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }
    }

    @MockBean lateinit var ldapProvider: LdapProvider

    @MockBean lateinit var ldapProviderConfigService: LdapProviderConfigService

    @MockBean lateinit var externalAccountRepository: ExternalAccountRepository

    @MockBean lateinit var autoProvisionService: AutoProvisionService

    @MockBean lateinit var ldapTemplate: LdapTemplate

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var mockMvc: MockMvc

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var jwtEncoder: JwtEncoder

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var jdbc: NamedParameterJdbcTemplate

    @Autowired
    @Suppress("VarCouldBeVal")
    private lateinit var objectMapper: ObjectMapper

    private val userASession: UUID = UUID.fromString("0f000000-0000-0000-0000-0000000009a2")
    private val userBSession: UUID = UUID.fromString("0f000000-0000-0000-0000-0000000009b2")

    @BeforeEach
    fun setUp() {
        val ab = mapOf("a" to USER_A_ID, "b" to USER_B_ID)
        jdbc.update("DELETE FROM personal_access_tokens WHERE user_id IN (:a, :b)", ab)
        jdbc.update("DELETE FROM user_calendar_tokens WHERE user_id IN (:a, :b)", ab)
        jdbc.update("DELETE FROM sessions WHERE user_id IN (:a, :b)", ab)
        jdbc.update("DELETE FROM users WHERE id IN (:a, :b)", ab)
        seedUser(USER_A_ID, "feed_user_a")
        seedUser(USER_B_ID, "feed_user_b")
        seedSession(userASession, USER_A_ID)
        seedSession(userBSession, USER_B_ID)
    }

    // ── 시나리오 1. 발급 → 익명 GET .ics → 취소 → 404 왕복 + 격리 + negative-probe ──

    @Test
    fun `발급하면 익명 GET 이 200 text_calendar 로 소유자 이슈를 반환하고 취소하면 404`() {
        val token = issueFeedToken(USER_A_ID, userASession)

        val feedBody =
            mockMvc.perform(get("/ical/feed/$token.ics"))
                .andExpect(status().isOk)
                .andReturn().response.contentAsString

        assertThat(feedBody).contains("BEGIN:VCALENDAR")
        assertThat(feedBody).contains("USERA-1") // 소유자(A) 이슈
        assertThat(feedBody).doesNotContain("USERB-1") // 교차사용자 격리 — B 이슈 부재
        assertThat(feedBody).doesNotContain(sha256Hex(token)) // negative-probe: token_hash 미노출

        // 취소 → 즉시 404
        mockMvc.perform(
            delete("/api/v1/users/me/calendar/feed")
                .header(HttpHeaders.AUTHORIZATION, bearer(USER_A_ID, userASession))
                .with(csrf()),
        ).andExpect(status().isNoContent)
        mockMvc.perform(get("/ical/feed/$token.ics")).andExpect(status().isNotFound)
    }

    // ── 시나리오 2. 재발급 rotate — 기존 URL 404 ──────────────────────────────

    @Test
    fun `재발급하면 이전 토큰 URL 은 404 이고 새 토큰만 유효하다`() {
        val first = issueFeedToken(USER_A_ID, userASession)
        val second = issueFeedToken(USER_A_ID, userASession)

        assertThat(second).isNotEqualTo(first)
        mockMvc.perform(get("/ical/feed/$first.ics")).andExpect(status().isNotFound)
        mockMvc.perform(get("/ical/feed/$second.ics")).andExpect(status().isOk)
    }

    // ── 시나리오 3. PAT 관리 API → 403 (자격증명 관리는 대화형 로그인 전용) ─────

    @Test
    fun `PAT 로 관리 API 를 호출하면 403 calendar_feed_requires_interactive_login`() {
        val rawPat = "pat_${"a".repeat(48)}"
        insertActivePat(USER_A_ID, sha256Hex(rawPat))

        mockMvc.perform(
            post("/api/v1/users/me/calendar/feed")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $rawPat")
                .with(csrf()),
        ).andExpect(status().isForbidden)
    }

    // ── 시나리오 4. 미인증 관리 API → 401 · 비-GET 피드 → 401 ──────────────────

    @Test
    fun `미인증으로 관리 API 를 호출하면 401`() {
        mockMvc.perform(get("/api/v1/users/me/calendar/feed")).andExpect(status().isUnauthorized)
        mockMvc.perform(post("/api/v1/users/me/calendar/feed").with(csrf())).andExpect(status().isUnauthorized)
    }

    @Test
    fun `익명 피드 경로에 비-GET 을 호출하면 permitAll 이 아니라 401`() {
        mockMvc.perform(post("/ical/feed/anytoken.ics").with(csrf())).andExpect(status().isUnauthorized)
    }

    // ── 시나리오 5. user 삭제 → 토큰 CASCADE → 404 ────────────────────────────

    @Test
    fun `소유자 user 를 삭제하면 토큰이 CASCADE 삭제되어 피드가 404`() {
        val token = issueFeedToken(USER_A_ID, userASession)
        mockMvc.perform(get("/ical/feed/$token.ics")).andExpect(status().isOk)

        jdbc.update("DELETE FROM sessions WHERE user_id = :id", mapOf("id" to USER_A_ID))
        jdbc.update("DELETE FROM users WHERE id = :id", mapOf("id" to USER_A_ID))

        mockMvc.perform(get("/ical/feed/$token.ics")).andExpect(status().isNotFound)
    }

    // ── 시나리오 6. 무효 토큰 → 404 · 상태 조회 ──────────────────────────────

    @Test
    fun `존재하지 않는 토큰은 404 로 수렴한다`() {
        mockMvc.perform(get("/ical/feed/${"0".repeat(64)}.ics")).andExpect(status().isNotFound)
    }

    // ── 헬퍼 ───────────────────────────────────────────────────────────────────

    /** 관리 API(POST /me/calendar/feed)로 토큰을 발급하고 원문 토큰을 반환한다. */
    private fun issueFeedToken(
        userId: UUID,
        sessionId: UUID,
    ): String {
        val body =
            mockMvc.perform(
                post("/api/v1/users/me/calendar/feed")
                    .header(HttpHeaders.AUTHORIZATION, bearer(userId, sessionId))
                    .with(csrf()),
            ).andExpect(status().isCreated)
                .andReturn().response.contentAsString
        return objectMapper.readTree(body).get("token").asText()
    }

    private fun bearer(
        userId: UUID,
        sessionId: UUID,
    ): String = "Bearer ${issueJwt(userId, sessionId)}"

    private fun issueJwt(
        userId: UUID,
        sessionId: UUID,
    ): String {
        val now = Instant.now()
        val claims =
            JwtClaimsSet.builder()
                .subject(userId.toString())
                .issuer("http://localhost:8090")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(3600))
                .claim("sid", sessionId.toString())
                .build()
        return jwtEncoder.encode(JwtEncoderParameters.from(claims)).tokenValue
    }

    private fun seedUser(
        userId: UUID,
        username: String,
    ) {
        jdbc.update(
            "INSERT INTO users (id, username, display_name) VALUES (:id, :username, :displayName)" +
                " ON CONFLICT (id) DO NOTHING",
            mapOf("id" to userId, "username" to username, "displayName" to username),
        )
    }

    private fun seedSession(
        sessionId: UUID,
        userId: UUID,
    ) {
        val now = Instant.now()
        jdbc.update(
            """
            INSERT INTO sessions (id, user_id, provider_id, created_at, expires_at, last_seen_at)
            VALUES (:id, :userId, :providerId, :createdAt, :expiresAt, :lastSeenAt)
            ON CONFLICT (id) DO NOTHING
            """,
            mapOf(
                "id" to sessionId,
                "userId" to userId,
                "providerId" to "local",
                "createdAt" to java.sql.Timestamp.from(now),
                "expiresAt" to java.sql.Timestamp.from(now.plusSeconds(3600)),
                "lastSeenAt" to java.sql.Timestamp.from(now),
            ),
        )
    }

    private fun insertActivePat(
        userId: UUID,
        tokenHash: String,
    ) {
        jdbc.update(
            """
            INSERT INTO personal_access_tokens (id, user_id, name, token_hash, scopes, expires_at, revoked_at, created_at)
            VALUES (:id, :userId, :name, :tokenHash, '["*"]'::jsonb, NULL, NULL, :createdAt)
            """,
            mapOf(
                "id" to UUID.randomUUID(),
                "userId" to userId,
                "name" to "feed-integration-pat",
                "tokenHash" to tokenHash,
                "createdAt" to java.sql.Timestamp.from(Instant.now()),
            ),
        )
    }
}

/**
 * [CalendarFeedIntegrationTest] 전용 userId 별 stub 포트 — 교차사용자 격리 검증용.
 *
 * userA 는 `USERA-1`, userB 는 `USERB-1` 이슈를 반환하고 그 외 userId 는 빈 결과를 반환한다.
 */
@TestConfiguration
class UserKeyedStubCalendarPortConfig {
    @Bean
    @Primary
    fun stubUserCalendarLookupPort(): UserCalendarLookupPort =
        object : UserCalendarLookupPort {
            override fun listAssignedScheduledIssues(
                userId: UUID,
                from: java.time.LocalDate,
                to: java.time.LocalDate,
            ): CalendarIssuePage {
                val key =
                    when (userId) {
                        USER_A_ID -> "USERA-1"
                        USER_B_ID -> "USERB-1"
                        else -> return CalendarIssuePage(items = emptyList(), truncated = false)
                    }
                return CalendarIssuePage(
                    items =
                        listOf(
                            CalendarIssueView(
                                key = key,
                                summary = "$key 담당 이슈",
                                issueType = "task",
                                currentStateKey = "in_progress",
                                startDate = java.time.LocalDate.now(),
                                dueDate = java.time.LocalDate.now().plusDays(2),
                            ),
                        ),
                    truncated = false,
                )
            }

            override fun listWorklogs(
                userId: UUID,
                fromInstant: Instant,
                toInstant: Instant,
            ): CalendarWorklogPage = CalendarWorklogPage(items = emptyList(), truncated = false)
        }
}
