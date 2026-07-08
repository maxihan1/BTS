// KeymapController end-to-end 통합테스트 — prod 부팅 + 실제 JWT + PostgreSQL (FR-PF-03 Task 5)

package com.atlas.bts.identity.web

import com.atlas.bts.identity.provider.ldap.AutoProvisionService
import com.atlas.bts.identity.provider.ldap.ExternalAccountRepository
import com.atlas.bts.identity.provider.ldap.LdapProvider
import com.atlas.bts.identity.provider.ldap.LdapProviderConfigService
import org.assertj.core.api.Assertions.assertThat
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.HttpHeaders
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
import java.util.UUID

/**
 * [KeymapController] end-to-end 통합테스트 (FR-PF-03 Task 5).
 *
 * ## 목적
 * 실 서비스([com.atlas.bts.identity.keymap.UserKeymapService]) + 실 DB(PostgreSQL, `user_keymap`) 로
 * GET/PATCH 왕복, 부분 override 정규화 저장 + 병합 조회, 검증 400/충돌 409, PAT(JWT 전용 게이트) 401 을
 * 실제 Spring Security 필터 체인으로 확인한다.
 *
 * ## 부팅 패턴 (identity-access prod randomport boot recipe)
 * [com.atlas.bts.identity.integration.UserProfileFlowIntegrationTest] 의
 * `@SpringBootTest(RANDOM_PORT)` + `@ActiveProfiles("prod")` + Testcontainers + LDAP `@MockBean` 5종 +
 * 임시 RSA PEM 레시피를 미러한다(MinIO 는 단축키와 무관하므로 제외).
 *
 * ## CSRF
 * JWT Bearer PATCH 에는 [csrf] 후처리기로 유효 CSRF 토큰을 첨부한다(CSRF 우회가 아니라 표준 테스트
 * 위생 — [SecurityConfig] 는 `/me/keymap` 을 CSRF 예외 목록에 올리지 않으므로 실제로 검증이 활성 상태다).
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
class KeymapControllerIntegrationTest {
    companion object {
        /** Testcontainers PostgreSQL 16 — Flyway V001(users)/V004(sessions)/V033(user_keymap) 등 자동 마이그레이션. */
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

        /** EC-26: PAT 시나리오용 SHA-256(rawToken) hex — [com.atlas.bts.identity.integration.PatAndConcurrencyIntegrationTest] 미러. */
        private fun sha256Hex(raw: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            return digest.digest(raw.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
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

    private val meId: UUID = UUID.fromString("0f000000-0000-0000-0000-000000000701")
    private val meSessionId: UUID = UUID.fromString("0f000000-0000-0000-0000-0000000007a1")
    private val meUsername = "keymap_me"
    private val meDisplayName = "Keymap Me"

    @BeforeEach
    fun setUp() {
        cleanTestData()
        seedUser()
        seedSession()
    }

    // ── 시나리오 1. 미인증 ────────────────────────────────────────────────────

    @Test
    fun `토큰 없이 단축키를 조회하면 401`() {
        mockMvc.perform(get("/api/v1/users/me/keymap")).andExpect(status().isUnauthorized)
    }

    // ── 시나리오 2. 신규 사용자 기본값 ─────────────────────────────────────────

    @Test
    fun `override 없는 신규 사용자는 기본 5종을 조회한다`() {
        val token = issueJwt(meId, meSessionId)

        mockMvc.perform(get("/api/v1/users/me/keymap").header(HttpHeaders.AUTHORIZATION, "Bearer $token"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.bindings.length()").value(5))
            .andExpect(jsonPath("$.bindings[0].action").value("help"))
            .andExpect(jsonPath("$.bindings[0].keyCombo").value("?"))
            .andExpect(jsonPath("$.bindings[0].trigger").value("single"))
            .andExpect(jsonPath("$.bindings[0].customized").value(false))
    }

    // ── 시나리오 3. 부분 override 저장 + 병합 조회 ─────────────────────────────

    @Test
    fun `일부 action만 재지정한 PATCH는 override 행만 저장되고 GET은 나머지를 기본값으로 병합한다`() {
        val token = issueJwt(meId, meSessionId)
        val body =
            """{"bindings":[
                {"action":"help","keyCombo":"?"},
                {"action":"create-issue","keyCombo":"n"},
                {"action":"search","keyCombo":"/"},
                {"action":"goto-my-issues","keyCombo":"g i"},
                {"action":"goto-dashboard","keyCombo":"g d"}
            ]}"""

        mockMvc.perform(
            patch("/api/v1/users/me/keymap")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(csrf()),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.bindings[1].action").value("create-issue"))
            .andExpect(jsonPath("$.bindings[1].keyCombo").value("n"))
            .andExpect(jsonPath("$.bindings[1].customized").value(true))

        // 저장소에는 기본값과 다른 create-issue 행 1개만 저장된다(UserKeymapService.normalizeOverrides).
        val overrideCount =
            jdbc.queryForObject(
                "SELECT count(*) FROM user_keymap WHERE user_id = :userId",
                mapOf("userId" to meId),
                Int::class.java,
            )
        assertThat(overrideCount).isEqualTo(1)

        // GET 은 override(create-issue) + 나머지 기본값 4종을 병합해 반환한다.
        mockMvc.perform(get("/api/v1/users/me/keymap").header(HttpHeaders.AUTHORIZATION, "Bearer $token"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.bindings[1].keyCombo").value("n"))
            .andExpect(jsonPath("$.bindings[0].keyCombo").value("?"))
    }

    // ── 시나리오 4. 화이트리스트 위반(action 누락) → 400 ───────────────────────

    @Test
    fun `action 4종만 보낸 PATCH는 400 KEYMAP_VALIDATION_FAILED`() {
        val token = issueJwt(meId, meSessionId)
        val body =
            """{"bindings":[
                {"action":"help","keyCombo":"?"},
                {"action":"create-issue","keyCombo":"c"},
                {"action":"search","keyCombo":"/"},
                {"action":"goto-my-issues","keyCombo":"g i"}
            ]}"""

        mockMvc.perform(
            patch("/api/v1/users/me/keymap")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(csrf()),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.code").value("KEYMAP_VALIDATION_FAILED"))
    }

    // ── 시나리오 5. 완전 중복 충돌 → 409 ───────────────────────────────────────

    @Test
    fun `두 action이 같은 key_combo를 가지면 409 KEYMAP_CONFLICT`() {
        val token = issueJwt(meId, meSessionId)
        val body =
            """{"bindings":[
                {"action":"help","keyCombo":"?"},
                {"action":"create-issue","keyCombo":"c"},
                {"action":"search","keyCombo":"c"},
                {"action":"goto-my-issues","keyCombo":"g i"},
                {"action":"goto-dashboard","keyCombo":"g d"}
            ]}"""

        mockMvc.perform(
            patch("/api/v1/users/me/keymap")
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(csrf()),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.code").value("KEYMAP_CONFLICT"))
            .andExpect(jsonPath("$.conflicts[0].type").value("duplicate"))
    }

    // ── 시나리오 6. PAT 인증(JWT 전용 게이트) ──────────────────────────────────

    /**
     * PAT 는 stateless 자격증명이라 `@AuthenticationPrincipal Jwt` 타입 불일치로 null 이 되고,
     * [KeymapController.currentUserId] 가 401 을 던진다([PreferencesController] 동일 원칙 —
     * `session-management-pat-exclusion` 의 세션관리 전용 403 분기와는 별개 엔드포인트다).
     */
    @Test
    fun `PAT 인증으로 조회하면 401 (JWT 전용)`() {
        val rawPat = "pat_" + "k".repeat(48)
        insertActivePat(sha256Hex(rawPat))

        mockMvc.perform(get("/api/v1/users/me/keymap").header(HttpHeaders.AUTHORIZATION, "Bearer $rawPat"))
            .andExpect(status().isUnauthorized)
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
        jdbc.update("DELETE FROM user_keymap WHERE user_id = :id", mapOf("id" to meId))
        jdbc.update("DELETE FROM personal_access_tokens WHERE user_id = :id", mapOf("id" to meId))
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
                "createdAt" to Timestamp.from(now),
                "expiresAt" to Timestamp.from(now.plusSeconds(3600)),
                "lastSeenAt" to Timestamp.from(now),
            ),
        )
    }

    /** EC-26: `token_hash = SHA-256("pat_" + body)` — 무기한(expires_at NULL)·미revoke PAT row. */
    private fun insertActivePat(tokenHash: String) {
        jdbc.update(
            """
            INSERT INTO personal_access_tokens
                (id, user_id, name, token_hash, scopes, expires_at, revoked_at, created_at)
            VALUES
                (:id, :userId, :name, :tokenHash, '["*"]'::jsonb, NULL, NULL, :createdAt)
            """,
            mapOf(
                "id" to UUID.randomUUID(),
                "userId" to meId,
                "name" to "keymap-integration-test-pat",
                "tokenHash" to tokenHash,
                "createdAt" to Timestamp.from(Instant.now()),
            ),
        )
    }
}
