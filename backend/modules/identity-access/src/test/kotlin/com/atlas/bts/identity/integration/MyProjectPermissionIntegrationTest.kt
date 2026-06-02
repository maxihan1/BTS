// GET /api/v1/users/me/project-permissions 컨트롤러 HTTP 레이어 + JWT 인증 흐름 통합테스트 (FR-PM-02 CREATE 게이트 Task 1)

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
 * [com.atlas.bts.identity.web.MyProjectPermissionController] HTTP 레이어 통합테스트 (FR-PM-02 CREATE 게이트 Task 1).
 *
 * ## 목적
 * - 실제 JWT를 발급해 `Authorization: Bearer` 헤더로 전달하여 컨트롤러 → @AuthenticationPrincipal 주입 → resolver 호출 경로를 검증한다.
 * - 미인증 요청은 401, projectKey 누락/공백은 400을 반환함을 확인한다.
 * - 존재하지 않는 projectKey는 404가 아니라 200 + CREATE:false 임을 확인한다.
 *
 * ## 검증 시나리오
 * | 행위자   | projectKey | 기대 CREATE | 기대 status |
 * |----------|------------|-------------|-------------|
 * | MEMBER   | PERMTEST   | true        | 200         |
 * | 비멤버   | PERMTEST   | false       | 200         |
 * | 미인증   | PERMTEST   | -           | 401         |
 * | MEMBER   | (공백)     | -           | 400         |
 * | MEMBER   | ZZZZ(미존재)| false      | 200         |
 *
 * ## 부팅 패턴
 * [MyIssuePermissionIntegrationTest] 동일.
 * @SpringBootTest + @AutoConfigureMockMvc + @ActiveProfiles("prod") + Testcontainers + MockBean(LDAP 4종).
 *
 * @see com.atlas.bts.identity.web.MyProjectPermissionController
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
class MyProjectPermissionIntegrationTest {
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

    // LDAP Bean 목킹 — 실제 LDAP 서버 없이 컨텍스트 부팅 (MyIssuePermissionIntegrationTest 선례)
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
    private val memberId: UUID = UUID.fromString("00000000-2222-0000-0000-000000000002")
    private val nonMemberId: UUID = UUID.fromString("00000000-3333-0000-0000-000000000002")

    /** 각 사용자 JWT에 포함할 sid — sessions 테이블 행을 setUp에서 삽입한다. */
    private val memberSessionId: UUID = UUID.fromString("eeeeeeee-2222-0000-0000-000000000002")
    private val nonMemberSessionId: UUID = UUID.fromString("eeeeeeee-3333-0000-0000-000000000002")

    private val projectId: UUID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000003")
    private val projectKey = "PERMTEST"

    // ── 설정 ──────────────────────────────────────────────────────────────────

    @BeforeEach
    fun setUp() {
        ensureProjectsTableExists()
        cleanTestData()
        seedUsers()
        seedSessions()
        seedProject()
        seedMemberships()
    }

    // ── 테스트 케이스 ─────────────────────────────────────────────────────────

    @Test
    fun `MEMBER JWT — CREATE true`() {
        val token = issueJwt(memberId, memberSessionId)
        mockMvc.perform(
            get("/api/v1/users/me/project-permissions")
                .param("projectKey", projectKey)
                .header("Authorization", "Bearer $token")
                .accept(MediaType.APPLICATION_JSON),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.projectKey").value(projectKey))
            .andExpect(jsonPath("$.permissions.CREATE").value(true))
    }

    @Test
    fun `비멤버 JWT — CREATE false (401 아님)`() {
        val token = issueJwt(nonMemberId, nonMemberSessionId)
        mockMvc.perform(
            get("/api/v1/users/me/project-permissions")
                .param("projectKey", projectKey)
                .header("Authorization", "Bearer $token")
                .accept(MediaType.APPLICATION_JSON),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.projectKey").value(projectKey))
            .andExpect(jsonPath("$.permissions.CREATE").value(false))
    }

    @Test
    fun `미인증 — 401 반환`() {
        mockMvc.perform(
            get("/api/v1/users/me/project-permissions")
                .param("projectKey", projectKey)
                .accept(MediaType.APPLICATION_JSON),
        )
            .andExpect(status().isUnauthorized)
    }

    @Test
    fun `projectKey 공백 — 400 반환`() {
        val token = issueJwt(memberId, memberSessionId)
        mockMvc.perform(
            get("/api/v1/users/me/project-permissions")
                .param("projectKey", "   ")
                .header("Authorization", "Bearer $token")
                .accept(MediaType.APPLICATION_JSON),
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `존재하지 않는 projectKey — 200 + CREATE false (404 아님)`() {
        val token = issueJwt(memberId, memberSessionId)
        mockMvc.perform(
            get("/api/v1/users/me/project-permissions")
                .param("projectKey", "ZZZZ")
                .header("Authorization", "Bearer $token")
                .accept(MediaType.APPLICATION_JSON),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.projectKey").value("ZZZZ"))
            .andExpect(jsonPath("$.permissions.CREATE").value(false))
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

    private fun cleanTestData() {
        jdbc.update("DELETE FROM project_permission_scheme WHERE project_id = :id", mapOf("id" to projectId))
        jdbc.update("DELETE FROM project_memberships WHERE project_id = :id", mapOf("id" to projectId))
        jdbc.update(
            "DELETE FROM sessions WHERE id IN (:ids)",
            mapOf("ids" to listOf(memberSessionId, nonMemberSessionId)),
        )
        jdbc.update(
            "DELETE FROM users WHERE id IN (:ids)",
            mapOf("ids" to listOf(memberId, nonMemberId)),
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
            Triple(memberId, "projperm_member", "ProjPerm Member"),
            Triple(nonMemberId, "projperm_nonmember", "ProjPerm NonMember"),
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
        jdbc.update(
            """
            INSERT INTO project_memberships (project_id, user_id, role, created_at, updated_at)
            VALUES (:projectId, :userId, :role, :createdAt, :updatedAt)
            """,
            mapOf(
                "projectId" to projectId,
                "userId" to memberId,
                "role" to "MEMBER",
                "createdAt" to java.sql.Timestamp.from(now),
                "updatedAt" to java.sql.Timestamp.from(now),
            ),
        )
    }
}
