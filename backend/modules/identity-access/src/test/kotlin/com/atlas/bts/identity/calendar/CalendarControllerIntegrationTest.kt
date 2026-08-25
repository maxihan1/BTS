// CalendarController end-to-end 통합테스트 — prod 부팅 + 실제 JWT + PostgreSQL (FR-CA-01 Task 4)

package com.atlas.bts.identity.calendar

import com.atlas.bts.identity.provider.ldap.AutoProvisionService
import com.atlas.bts.identity.provider.ldap.ExternalAccountRepository
import com.atlas.bts.identity.provider.ldap.LdapProvider
import com.atlas.bts.identity.provider.ldap.LdapProviderConfigService
import com.atlas.bts.identity.support.SharedPostgres
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.ldap.core.LdapTemplate
import org.springframework.security.oauth2.jwt.JwtClaimsSet
import org.springframework.security.oauth2.jwt.JwtEncoder
import org.springframework.security.oauth2.jwt.JwtEncoderParameters
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.Security
import java.time.Instant
import java.util.UUID

/**
 * [CalendarController] end-to-end 통합테스트 (FR-CA-01 Task 4).
 *
 * ## 목적
 * 실 서비스([CalendarService]) + [StubUserCalendarLookupPortConfig] stub 포트 + 실 DB(PostgreSQL, `users`)
 * 로 GET 왕복, 창 검증 400, 날짜 형식 오류 400, 미인증 401 을 실제 Spring Security 필터 체인으로 확인한다.
 *
 * ## 부팅 패턴 (identity-access prod randomport boot recipe)
 * [com.atlas.bts.identity.web.KeymapControllerIntegrationTest] 를 미러한다 — `@SpringBootTest(RANDOM_PORT)`
 * + `@ActiveProfiles("prod")` + Testcontainers + LDAP `@MockBean` 5종 + 임시 RSA PEM 레시피(MinIO 무관 제외).
 * `@Import(StubUserCalendarLookupPortConfig::class)` 로 `UserCalendarLookupPort` 를 `@Primary` stub 으로
 * 대체한다 — identity-access 는 issue-tracking 을 gradle 의존하지 않으므로(BC 격리) 실 adapter 를 쓸 수 없다.
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
@Import(StubUserCalendarLookupPortConfig::class)
class CalendarControllerIntegrationTest {
    companion object {
        /**
         * 공용 컨테이너의 템플릿 DB 를 복제한 전용 데이터베이스.
         *
         * 격리는 그대로이고 컨테이너 기동과 마이그레이션 재적용만 사라진다.
         * 근거와 주의점은 [com.atlas.bts.identity.support.SharedPostgres] 헤더.
         */
        @JvmStatic
        val postgres = SharedPostgres.freshDatabase()

        @DynamicPropertySource
        @JvmStatic
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url") { postgres.jdbcUrl }
            registry.add("spring.datasource.username") { postgres.username }
            registry.add("spring.datasource.password") { postgres.password }
            // 템플릿 DB 에서 이미 적용됐다 — 여기서 다시 돌리면 이 최적화가 무의미해진다
            registry.add("spring.flyway.enabled") { "false" }
            // 공용 컨테이너라 커넥션 한도도 공유한다. context 캐시가 쌓이면
            // 기본 풀(10)로는 max_connections 를 넘긴다 — SharedPostgres 헤더 참조.
            registry.add("spring.datasource.hikari.maximum-pool-size") { SharedPostgres.MAX_POOL_SIZE }
            registry.add("bts.auth.issuer-uri") { "http://localhost:8090" }
            registry.add("bts.security.cors.allowed-origins") { "http://localhost:5173" }
            registry.add("spring.ldap.urls") { "ldap://localhost:389" }
            registry.add("spring.ldap.base") { "dc=bts,dc=local" }
            registry.add("bts.auth.jwt.private-key-pem-path") { pemFilePath }
        }

        /** 테스트용 임시 RSA 2048 PEM(PKCS#8) 파일 (KeymapControllerIntegrationTest 미러). */
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
    }

    // ── LDAP Bean 목킹 — 실제 LDAP 서버 없이 컨텍스트 부팅 ─────────────────────
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

    private val meId: UUID = UUID.fromString("0f000000-0000-0000-0000-000000000801")
    private val meSessionId: UUID = UUID.fromString("0f000000-0000-0000-0000-0000000008a1")
    private val meUsername = "calendar_me"
    private val meDisplayName = "Calendar Me"

    @BeforeEach
    fun setUp() {
        cleanTestData()
        seedUser()
        seedSession()
    }

    // ── 시나리오 1. 미인증 ────────────────────────────────────────────────────

    @Test
    fun `토큰 없이 캘린더를 조회하면 401`() {
        mockMvc.perform(get("/api/v1/users/me/calendar?from=2026-07-01&to=2026-07-31"))
            .andExpect(status().isUnauthorized)
    }

    // ── 시나리오 2. 정상 조회 — stub 시드 데이터 + JSON 필드 직렬화 ────────────

    @Test
    fun `인증 사용자가 조회하면 stub 시드된 이슈-worklog 이벤트를 반환한다`() {
        val token = issueJwt(meId, meSessionId)

        mockMvc.perform(
            get("/api/v1/users/me/calendar?from=2026-07-01&to=2026-07-31")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.timezone").value("UTC"))
            .andExpect(jsonPath("$.truncated").value(false))
            .andExpect(jsonPath("$.issueEvents.length()").value(1))
            .andExpect(jsonPath("$.issueEvents[0].key").value("CAL-1"))
            .andExpect(jsonPath("$.issueEvents[0].summary").value("캘린더 통합테스트 이슈"))
            .andExpect(jsonPath("$.issueEvents[0].startDate").value("2026-07-10"))
            .andExpect(jsonPath("$.issueEvents[0].dueDate").value("2026-07-12"))
            .andExpect(jsonPath("$.worklogEvents.length()").value(2))
            .andExpect(jsonPath("$.worklogEvents[0].issueKey").value("CAL-1"))
            .andExpect(jsonPath("$.worklogEvents[0].issueSummary").value("캘린더 통합테스트 이슈"))
            .andExpect(jsonPath("$.worklogEvents[0].date").value("2026-07-10"))
            .andExpect(jsonPath("$.worklogEvents[0].timeSpentSeconds").value(3600))
            .andExpect(jsonPath("$.worklogEvents[1].issueKey").value("CAL-2"))
            .andExpect(jsonPath("$.worklogEvents[1].issueSummary").doesNotExist())
    }

    // ── 시나리오 3. from > to → 400 ──────────────────────────────────────────

    @Test
    fun `from이 to보다 늦으면 400`() {
        val token = issueJwt(meId, meSessionId)

        mockMvc.perform(
            get("/api/v1/users/me/calendar?from=2026-07-31&to=2026-07-01")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token"),
        )
            .andExpect(status().isBadRequest)
    }

    // ── 시나리오 4. 창 길이 90일 초과 → 400 ──────────────────────────────────

    @Test
    fun `조회 창이 90일을 초과하면 400`() {
        val token = issueJwt(meId, meSessionId)

        mockMvc.perform(
            get("/api/v1/users/me/calendar?from=2026-01-01&to=2026-12-31")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token"),
        )
            .andExpect(status().isBadRequest)
    }

    // ── 시나리오 5. 날짜 형식 오류 → 400 ─────────────────────────────────────

    @Test
    fun `from 형식이 잘못되면 400`() {
        val token = issueJwt(meId, meSessionId)

        mockMvc.perform(
            get("/api/v1/users/me/calendar?from=bad&to=2026-07-31")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token"),
        )
            .andExpect(status().isBadRequest)
    }

    // ── 헬퍼 ───────────────────────────────────────────────────────────────────

    /** [userId] 를 subject, [sessionId] 를 sid claim 으로 하는 JWT 를 발급한다. */
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

    private fun cleanTestData() {
        jdbc.update("DELETE FROM sessions WHERE id = :id", mapOf("id" to meSessionId))
        jdbc.update("DELETE FROM users WHERE id = :id", mapOf("id" to meId))
    }

    private fun seedUser() {
        jdbc.update(
            "INSERT INTO users (id, username, display_name) VALUES (:id, :username, :displayName)" +
                " ON CONFLICT (id) DO NOTHING",
            mapOf("id" to meId, "username" to meUsername, "displayName" to meDisplayName),
        )
    }

    /** [com.atlas.bts.identity.jwt.SidRevokeJwtConverter] 가 sid claim 으로 조회하는 활성 세션. */
    private fun seedSession() {
        val now = Instant.now()
        jdbc.update(
            """
            INSERT INTO sessions (id, user_id, provider_id, created_at, expires_at, last_seen_at)
            VALUES (:id, :userId, :providerId, :createdAt, :expiresAt, :lastSeenAt)
            ON CONFLICT (id) DO NOTHING
            """,
            mapOf(
                "id" to meSessionId,
                "userId" to meId,
                "providerId" to "local",
                "createdAt" to java.sql.Timestamp.from(now),
                "expiresAt" to java.sql.Timestamp.from(now.plusSeconds(3600)),
                "lastSeenAt" to java.sql.Timestamp.from(now),
            ),
        )
    }
}
