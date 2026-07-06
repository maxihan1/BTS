// OutOfOfficeController end-to-end 통합테스트 — prod 부팅 + 실제 JWT/PAT (FR-PR-03 Task 4)

package com.atlas.bts.identity.web

import com.atlas.bts.identity.provider.ldap.AutoProvisionService
import com.atlas.bts.identity.provider.ldap.ExternalAccountRepository
import com.atlas.bts.identity.provider.ldap.LdapProvider
import com.atlas.bts.identity.provider.ldap.LdapProviderConfigService
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.hamcrest.Matchers.nullValue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
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
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Security
import java.sql.Timestamp
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * [OutOfOfficeController] end-to-end 통합테스트 (FR-PR-03 Task 4).
 *
 * ## 부팅 패턴 (identity-access prod randomport boot recipe)
 * [com.atlas.bts.identity.integration.MyProjectPermissionIntegrationTest] /
 * [com.atlas.bts.identity.integration.UserProfileFlowIntegrationTest] 의
 * `@SpringBootTest(RANDOM_PORT)` + `@AutoConfigureMockMvc` + `@ActiveProfiles("prod")` +
 * Testcontainers + LDAP `@MockBean` + 임시 RSA PEM(JwtEncoder/JwtDecoder 서명키) 레시피를 미러한다.
 *
 * ## 검증 시나리오
 * GET(미인증 401 / 신규 all-null / 설정 후 active 계산), PATCH(정상 설정 / 기간만 / 검증 400 각종),
 * DELETE(204 + 멱등), PAT 인증(401 — JWT subject 전용).
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
@Testcontainers
class OutOfOfficeControllerTest {
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

        /** 테스트용 임시 RSA 2048 PEM(PKCS#8) 파일 ([com.atlas.bts.identity.integration.MyProjectPermissionIntegrationTest] 미러). */
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

        /** EC-26: SHA-256(rawToken) hex 64자 — "pat_" prefix 포함 전체 토큰을 해시. */
        private fun sha256Hex(raw: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        }

        /** PAT raw token 형식: "pat_" + 48자 body. */
        private fun buildRawPat(body: String = "b".repeat(48)): String = "pat_$body"
    }

    // LDAP Bean 목킹 — 실제 LDAP 서버 없이 컨텍스트 부팅 ([com.atlas.bts.identity.integration.MyProjectPermissionIntegrationTest] 선례)
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

    private val meId: UUID = UUID.fromString("0f000000-0000-0000-0000-000000000701")
    private val delegateId: UUID = UUID.fromString("0f000000-0000-0000-0000-000000000702")
    private val meSessionId: UUID = UUID.fromString("0f000000-0000-0000-0000-0000000007a1")

    private val meUsername = "prooo_me"
    private val meDisplayName = "PR Ooo Me"
    private val delegateUsername = "prooo_delegate"
    private val delegateDisplayName = "PR Ooo Delegate"

    private val rawPat = buildRawPat()

    @BeforeEach
    fun setUp() {
        cleanTestData()
        seedUsers()
        seedSessions()
    }

    // ── GET ──────────────────────────────────────────────────────────────────

    @Test
    fun `토큰 없이 GET me ooo 하면 401`() {
        mockMvc.perform(get("/api/v1/users/me/ooo")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `설정 없는 신규 사용자 GET은 all-null과 active false`() {
        val token = issueJwt(meId, meSessionId)

        mockMvc.perform(
            get("/api/v1/users/me/ooo").header("Authorization", "Bearer $token"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.startsAt").value(nullValue()))
            .andExpect(jsonPath("$.endsAt").value(nullValue()))
            .andExpect(jsonPath("$.delegateUserId").value(nullValue()))
            .andExpect(jsonPath("$.delegateName").value(nullValue()))
            .andExpect(jsonPath("$.message").value(nullValue()))
            .andExpect(jsonPath("$.active").value(false))
    }

    @Test
    fun `유효한 PAT로 GET me ooo 접근하면 401 (JWT subject 전용)`() {
        insertActivePat(sha256Hex(rawPat), meId)

        mockMvc.perform(
            get("/api/v1/users/me/ooo").header("Authorization", "Bearer $rawPat"),
        ).andExpect(status().isUnauthorized)
    }

    // ── PATCH ────────────────────────────────────────────────────────────────

    @Test
    fun `PATCH 기간-대리자-메시지 설정 후 200과 저장값 반환`() {
        val token = issueJwt(meId, meSessionId)
        val startsAt = Instant.now().truncatedTo(ChronoUnit.MILLIS).plusSeconds(3600)
        val endsAt = startsAt.plusSeconds(3600)

        performPatch(
            token,
            """{"startsAt":"$startsAt","endsAt":"$endsAt","delegateUserId":"$delegateId","message":"휴가 중입니다."}""",
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.startsAt").value(startsAt.toString()))
            .andExpect(jsonPath("$.endsAt").value(endsAt.toString()))
            .andExpect(jsonPath("$.delegateUserId").value(delegateId.toString()))
            .andExpect(jsonPath("$.delegateName").value(delegateDisplayName))
            .andExpect(jsonPath("$.message").value("휴가 중입니다."))
            // startsAt 이 미래이므로 아직 활성 아님(예약)
            .andExpect(jsonPath("$.active").value(false))
    }

    @Test
    fun `PATCH 기간만 설정해도 200 (대리자-메시지 없이도)`() {
        val token = issueJwt(meId, meSessionId)
        val startsAt = Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(60)
        val endsAt = startsAt.plusSeconds(3600)

        performPatch(token, """{"startsAt":"$startsAt","endsAt":"$endsAt"}""")
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.delegateUserId").value(nullValue()))
            .andExpect(jsonPath("$.delegateName").value(nullValue()))
            .andExpect(jsonPath("$.message").value(nullValue()))
            // startsAt 과거, endsAt 미래 → 현재 활성
            .andExpect(jsonPath("$.active").value(true))
    }

    @Test
    fun `PATCH endsAt이 startsAt 이하이면 400`() {
        val token = issueJwt(meId, meSessionId)
        val startsAt = Instant.now().truncatedTo(ChronoUnit.MILLIS).plusSeconds(7200)
        val endsAt = startsAt.minusSeconds(3600)

        performPatch(token, """{"startsAt":"$startsAt","endsAt":"$endsAt"}""")
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `PATCH endsAt이 이미 종료된 시각이면 400`() {
        val token = issueJwt(meId, meSessionId)
        val startsAt = Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(7200)
        val endsAt = Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(60)

        performPatch(token, """{"startsAt":"$startsAt","endsAt":"$endsAt"}""")
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `PATCH 대리자가 본인이면 400`() {
        val token = issueJwt(meId, meSessionId)
        val startsAt = Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(60)
        val endsAt = startsAt.plusSeconds(3600)

        performPatch(token, """{"startsAt":"$startsAt","endsAt":"$endsAt","delegateUserId":"$meId"}""")
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `PATCH 대리자가 존재하지 않으면 400`() {
        val token = issueJwt(meId, meSessionId)
        val startsAt = Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(60)
        val endsAt = startsAt.plusSeconds(3600)
        val unknownId = UUID.randomUUID()

        performPatch(token, """{"startsAt":"$startsAt","endsAt":"$endsAt","delegateUserId":"$unknownId"}""")
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `PATCH 메시지 501자면 400`() {
        val token = issueJwt(meId, meSessionId)
        val startsAt = Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(60)
        val endsAt = startsAt.plusSeconds(3600)
        val tooLong = "가".repeat(501)

        performPatch(token, """{"startsAt":"$startsAt","endsAt":"$endsAt","message":"$tooLong"}""")
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `토큰 없이 PATCH me ooo 하면 401`() {
        mockMvc.perform(
            patch("/api/v1/users/me/ooo")
                .with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"startsAt":"2026-07-10T00:00:00Z","endsAt":"2026-07-14T00:00:00Z"}"""),
        ).andExpect(status().isUnauthorized)
    }

    // ── DELETE ───────────────────────────────────────────────────────────────

    @Test
    fun `DELETE 후 204와 재조회 all-null, 재호출도 204 (멱등)`() {
        val token = issueJwt(meId, meSessionId)
        val startsAt = Instant.now().truncatedTo(ChronoUnit.MILLIS).minusSeconds(60)
        val endsAt = startsAt.plusSeconds(3600)
        performPatch(token, """{"startsAt":"$startsAt","endsAt":"$endsAt"}""").andExpect(status().isOk)

        mockMvc.perform(
            delete("/api/v1/users/me/ooo").header("Authorization", "Bearer $token").with(csrf()),
        ).andExpect(status().isNoContent)

        mockMvc.perform(get("/api/v1/users/me/ooo").header("Authorization", "Bearer $token"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.startsAt").value(nullValue()))
            .andExpect(jsonPath("$.active").value(false))

        // 멱등 — 이미 없어도 204
        mockMvc.perform(
            delete("/api/v1/users/me/ooo").header("Authorization", "Bearer $token").with(csrf()),
        ).andExpect(status().isNoContent)
    }

    // ── 헬퍼 ───────────────────────────────────────────────────────────────────

    private fun performPatch(
        token: String,
        json: String,
    ): ResultActions =
        mockMvc.perform(
            patch("/api/v1/users/me/ooo")
                .header("Authorization", "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json)
                .with(csrf()),
        )

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

    private fun insertActivePat(
        tokenHash: String,
        userId: UUID,
    ): UUID {
        val patId = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO personal_access_tokens
                (id, user_id, name, token_hash, scopes, expires_at, revoked_at, created_at)
            VALUES
                (:id, :userId, :name, :tokenHash, '["*"]'::jsonb, NULL, NULL, :createdAt)
            """,
            mapOf(
                "id" to patId,
                "userId" to userId,
                "name" to "ooo-test-pat",
                "tokenHash" to tokenHash,
                "createdAt" to Timestamp.from(Instant.now()),
            ),
        )
        return patId
    }

    private fun cleanTestData() {
        val userIds = listOf(meId, delegateId)
        jdbc.update("DELETE FROM personal_access_tokens WHERE user_id IN (:ids)", mapOf("ids" to userIds))
        jdbc.update("DELETE FROM user_ooo WHERE user_id IN (:ids)", mapOf("ids" to userIds))
        jdbc.update("DELETE FROM sessions WHERE id IN (:ids)", mapOf("ids" to listOf(meSessionId)))
        jdbc.update("DELETE FROM users WHERE id IN (:ids)", mapOf("ids" to userIds))
    }

    private fun seedUsers() {
        listOf(
            Triple(meId, meUsername, meDisplayName),
            Triple(delegateId, delegateUsername, delegateDisplayName),
        ).forEach { (id, username, displayName) ->
            jdbc.update(
                "INSERT INTO users (id, username, email, display_name) VALUES (:id, :username, :email, :displayName)" +
                    " ON CONFLICT (id) DO NOTHING",
                mapOf(
                    "id" to id,
                    "username" to username,
                    "email" to "$username@example.com",
                    "displayName" to displayName,
                ),
            )
        }
    }

    private fun seedSessions() {
        val now = Instant.now()
        val expiresAt = now.plusSeconds(3600)
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
                "createdAt" to Timestamp.from(now),
                "expiresAt" to Timestamp.from(expiresAt),
                "lastSeenAt" to Timestamp.from(now),
            ),
        )
    }
}
