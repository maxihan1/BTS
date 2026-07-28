// GET /api/v1/users/me/issue-permissions 컨트롤러 HTTP 레이어 + JWT 인증 흐름 통합테스트 (FR-PM-02 D6 Task 1)

package com.atlas.bts.identity.integration

import com.atlas.bts.identity.provider.ldap.AutoProvisionService
import com.atlas.bts.identity.provider.ldap.ExternalAccountRepository
import com.atlas.bts.identity.provider.ldap.LdapProvider
import com.atlas.bts.identity.provider.ldap.LdapProviderConfigService
import org.bouncycastle.jce.provider.BouncyCastleProvider
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
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.Security
import java.time.Instant
import java.util.UUID

/**
 * [com.atlas.bts.identity.web.MyIssuePermissionController] HTTP 레이어 통합테스트 (FR-PM-02 D6 Task 1).
 *
 * ## 목적
 * - 실제 JWT를 발급해 `Authorization: Bearer` 헤더로 전달하여 컨트롤러 → @AuthenticationPrincipal 주입 → resolver 호출 경로를 검증한다.
 * - 미인증 요청은 401을 반환함을 확인한다.
 *
 * ## 검증 시나리오
 * | 행위자   | 기대 UPDATE | 기대 SOFT_DELETE | 기대 TRANSITION |
 * |----------|-------------|------------------|-----------------|
 * | ADMIN    | true        | true             | true            |
 * | MEMBER   | true        | false            | true            |
 * | 비멤버   | false       | false            | false           |
 * | 미인증   | 401         | -                | -               |
 *
 * ## 부팅 패턴
 * [com.atlas.bts.identity.permission.IdentityAccessIssuePermissionResolverIntegrationTest] 동일.
 * @SpringBootTest + @AutoConfigureMockMvc + @ActiveProfiles("prod") + Testcontainers + MockBean(LDAP 4종).
 *
 * @see com.atlas.bts.identity.web.MyIssuePermissionController
 * @see docs/decisions/2026-06-02-issue-permission-query-api.md
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
class MyIssuePermissionIntegrationTest {
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

        /**
         * 테스트용 임시 RSA 2048 PEM(PKCS#8) 파일.
         *
         * BouncyCastle provider 먼저 등록 후 생성한다.
         * JwtEncoder가 이 키로 JWT를 서명하고, JwtDecoder가 같은 키 쌍의 공개키로 검증한다.
         */
        val pemFilePath: String =
            run {
                if (Security.getProvider("BC") == null) {
                    Security.addProvider(BouncyCastleProvider())
                }
                val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
                val privKey = keyPair.private
                val pemContent =
                    buildString {
                        appendLine("-----BEGIN PRIVATE KEY-----")
                        val mimeEncoder = java.util.Base64.getMimeEncoder(64, "\n".toByteArray())
                        appendLine(mimeEncoder.encodeToString(privKey.encoded))
                        append("-----END PRIVATE KEY-----")
                    }
                val tmpFile = Files.createTempFile("bts-test-key-", ".pem")
                Files.writeString(tmpFile, pemContent)
                tmpFile.toAbsolutePath().toString()
            }
    }

    // LDAP Bean 목킹 — 실제 LDAP 서버 없이 컨텍스트 부팅 (IdentityAccessIssuePermissionResolverIntegrationTest 선례)
    @MockBean lateinit var ldapProvider: LdapProvider

    @MockBean lateinit var ldapProviderConfigService: LdapProviderConfigService

    @MockBean lateinit var externalAccountRepository: ExternalAccountRepository

    @MockBean lateinit var autoProvisionService: AutoProvisionService

    @MockBean lateinit var ldapTemplate: LdapTemplate

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jwtEncoder: JwtEncoder

    @Autowired
    private lateinit var jdbc: NamedParameterJdbcTemplate

    // ── 픽스처 식별값 ──────────────────────────────────────────────────────────
    private val adminId: UUID = UUID.fromString("00000000-1111-0000-0000-000000000001")
    private val memberId: UUID = UUID.fromString("00000000-2222-0000-0000-000000000001")
    private val nonMemberId: UUID = UUID.fromString("00000000-3333-0000-0000-000000000001")

    /** 각 사용자 JWT에 포함할 sid — sessions 테이블 행을 setUp에서 삽입한다. */
    private val adminSessionId: UUID = UUID.fromString("eeeeeeee-1111-0000-0000-000000000001")
    private val memberSessionId: UUID = UUID.fromString("eeeeeeee-2222-0000-0000-000000000001")
    private val nonMemberSessionId: UUID = UUID.fromString("eeeeeeee-3333-0000-0000-000000000001")

    private val projectId: UUID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000002")
    private val projectKey = "PERMTEST"
    private val issueKey = "PERMTEST-1"
    private val issueId: UUID = UUID.fromString("dddddddd-0000-0000-0000-000000000001")

    @Suppress("UnusedPrivateProperty")
    private val defaultSchemeId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")

    // ── 설정 ──────────────────────────────────────────────────────────────────

    @BeforeEach
    fun setUp() {
        ensureProjectsTableExists()
        ensureIssuesTableExists()
        cleanTestData()
        seedUsers()
        seedSessions()
        seedProject()
        seedIssue()
        seedMemberships()
    }

    // ── 테스트 케이스 ─────────────────────────────────────────────────────────

    @Test
    fun `ADMIN JWT — UPDATE true, SOFT_DELETE true`() {
        val token = issueJwt(adminId, adminSessionId)
        mockMvc.perform(
            get("/api/v1/users/me/issue-permissions")
                .param("issueKey", issueKey)
                .header("Authorization", "Bearer $token")
                .accept(MediaType.APPLICATION_JSON),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.issueKey").value(issueKey))
            .andExpect(jsonPath("$.permissions.UPDATE").value(true))
            .andExpect(jsonPath("$.permissions.SOFT_DELETE").value(true))
    }

    @Test
    fun `MEMBER JWT — UPDATE true, SOFT_DELETE false`() {
        val token = issueJwt(memberId, memberSessionId)
        mockMvc.perform(
            get("/api/v1/users/me/issue-permissions")
                .param("issueKey", issueKey)
                .header("Authorization", "Bearer $token")
                .accept(MediaType.APPLICATION_JSON),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.issueKey").value(issueKey))
            .andExpect(jsonPath("$.permissions.UPDATE").value(true))
            .andExpect(jsonPath("$.permissions.SOFT_DELETE").value(false))
    }

    @Test
    fun `비멤버 JWT — UPDATE false, SOFT_DELETE false, TRANSITION false`() {
        val token = issueJwt(nonMemberId, nonMemberSessionId)
        mockMvc.perform(
            get("/api/v1/users/me/issue-permissions")
                .param("issueKey", issueKey)
                .header("Authorization", "Bearer $token")
                .accept(MediaType.APPLICATION_JSON),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.issueKey").value(issueKey))
            .andExpect(jsonPath("$.permissions.UPDATE").value(false))
            .andExpect(jsonPath("$.permissions.SOFT_DELETE").value(false))
            .andExpect(jsonPath("$.permissions.TRANSITION").value(false))
    }

    @Test
    fun `미인증 — 401 반환`() {
        mockMvc.perform(
            get("/api/v1/users/me/issue-permissions")
                .param("issueKey", issueKey)
                .accept(MediaType.APPLICATION_JSON),
        )
            .andExpect(status().isUnauthorized)
    }

    // ── 픽스처 헬퍼 ───────────────────────────────────────────────────────────

    /**
     * 주어진 userId를 subject로, sessionId를 sid claim으로 하는 JWT를 발급한다.
     *
     * JwtEncoder는 SpringBootTest 컨텍스트에서 pemFilePath의 RSA 키로 자동 배선된다.
     * issuer는 컨텍스트 프로퍼티와 일치시켜 JwtDecoder 검증을 통과한다.
     * sid claim은 SidRevokeJwtConverter가 sessions 테이블에서 활성 세션을 조회하는 데 필요하다.
     *
     * @param userId JWT sub(subject) — actorId 추출에 사용
     * @param sessionId JWT sid claim — SidRevokeJwtConverter 세션 revoke 검증에 사용
     */
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

    private fun ensureProjectsTableExists() {
        jdbc.jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS projects (
                id         UUID PRIMARY KEY,
                key        VARCHAR(10) UNIQUE,
                deleted_at TIMESTAMPTZ
            )
            """.trimIndent(),
        )
    }

    /**
     * cross-BC 조회 대상인 `issues` 최소 스텁 — `projects` 와 같은 이유·같은 방식이다.
     *
     * ★2026-07-27 부터 필요해졌다. 보안 등급 게이트가 `VIEW` 에서 **쓰기 계열 전체**로 확대되면서
     * `IssueSecurityLookup` 이 `UPDATE`/`SOFT_DELETE` 판정에서도 `issues` 를 읽는다. 확대 이전에는
     * 그 경로를 타지 않아 이 테이블 없이도 통과했다 — 즉 이 스텁의 부재는 결함이 아니라
     * **의존이 늘어난 결과**다.
     *
     * `security_level_id` 는 NULL 로 심는다(등급 미지정 = 공개). 이 테스트의 관심사는 매트릭스 위임이지
     * 등급 판정이 아니므로, 게이트가 곧장 통과하는 상태를 기본값으로 둔다.
     */
    private fun ensureIssuesTableExists() {
        jdbc.jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS issues (
                id                UUID PRIMARY KEY,
                key               VARCHAR(64) UNIQUE,
                security_level_id UUID,
                reporter_id       UUID,
                assignee_id       UUID,
                deleted_at        TIMESTAMPTZ
            )
            """.trimIndent(),
        )
    }

    private fun seedIssue() {
        jdbc.update(
            """
            INSERT INTO issues (id, key, security_level_id, reporter_id, assignee_id)
            VALUES (:id, :key, NULL, :reporter, NULL)
            ON CONFLICT (key) DO NOTHING
            """.trimIndent(),
            mapOf("id" to issueId, "key" to issueKey, "reporter" to adminId),
        )
    }

    private fun cleanTestData() {
        jdbc.update("DELETE FROM issues WHERE key = :key", mapOf("key" to issueKey))
        jdbc.update("DELETE FROM project_permission_scheme WHERE project_id = :id", mapOf("id" to projectId))
        jdbc.update("DELETE FROM project_memberships WHERE project_id = :id", mapOf("id" to projectId))
        jdbc.update(
            "DELETE FROM sessions WHERE id IN (:ids)",
            mapOf("ids" to listOf(adminSessionId, memberSessionId, nonMemberSessionId)),
        )
        jdbc.update(
            "DELETE FROM users WHERE id IN (:ids)",
            mapOf("ids" to listOf(adminId, memberId, nonMemberId)),
        )
        jdbc.update("DELETE FROM projects WHERE id = :id", mapOf("id" to projectId))
    }

    /**
     * 테스트용 활성 세션을 삽입한다.
     *
     * SidRevokeJwtConverter가 JWT sid claim으로 sessions 테이블에서 세션을 조회하므로,
     * 각 사용자에 대응하는 활성 세션(revoked_at IS NULL + expires_at 미래)을 사전에 심는다.
     */
    private fun seedSessions() {
        val now = Instant.now()
        val expiresAt = now.plusSeconds(3600)
        listOf(
            Pair(adminSessionId, adminId),
            Pair(memberSessionId, memberId),
            Pair(nonMemberSessionId, nonMemberId),
        ).forEach { (sessionId, userId) ->
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
                    "expiresAt" to java.sql.Timestamp.from(expiresAt),
                    "lastSeenAt" to java.sql.Timestamp.from(now),
                ),
            )
        }
    }

    private fun seedUsers() {
        listOf(
            Triple(adminId, "permtest_admin", "PermTest Admin"),
            Triple(memberId, "permtest_member", "PermTest Member"),
            Triple(nonMemberId, "permtest_nonmember", "PermTest NonMember"),
        ).forEach { (id, username, displayName) ->
            jdbc.update(
                "INSERT INTO users (id, username, display_name) VALUES (:id, :username, :displayName)" +
                    " ON CONFLICT (id) DO NOTHING",
                mapOf("id" to id, "username" to username, "displayName" to displayName),
            )
        }
    }

    private fun seedProject() {
        jdbc.update(
            "INSERT INTO projects (id, key, deleted_at) VALUES (:id, :key, NULL) ON CONFLICT (id) DO NOTHING",
            mapOf("id" to projectId, "key" to projectKey),
        )
    }

    private fun seedMemberships() {
        val now = Instant.now()
        listOf(
            Pair(adminId, "PROJECT_ADMIN"),
            Pair(memberId, "MEMBER"),
        ).forEach { (userId, role) ->
            jdbc.update(
                """
                INSERT INTO project_memberships (project_id, user_id, role, created_at, updated_at)
                VALUES (:projectId, :userId, :role, :createdAt, :updatedAt)
                """,
                mapOf(
                    "projectId" to projectId,
                    "userId" to userId,
                    "role" to role,
                    "createdAt" to java.sql.Timestamp.from(now),
                    "updatedAt" to java.sql.Timestamp.from(now),
                ),
            )
        }
    }
}
