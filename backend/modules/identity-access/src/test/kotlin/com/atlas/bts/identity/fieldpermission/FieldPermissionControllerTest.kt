// 필드 권한 규칙 CRUD API end-to-end 통합테스트 — prod 실 필터체인 + MANAGE_FIELD_PERMISSIONS 실판정 (FR-PM-07 PR-A Task 5)

package com.atlas.bts.identity.fieldpermission

import com.atlas.bts.identity.credential.LocalCredentialService
import com.atlas.bts.identity.provider.ldap.AutoProvisionService
import com.atlas.bts.identity.provider.ldap.ExternalAccountRepository
import com.atlas.bts.identity.provider.ldap.LdapProvider
import com.atlas.bts.identity.provider.ldap.LdapProviderConfigService
import com.atlas.bts.identity.support.SharedPostgres
import com.atlas.bts.identity.user.UserRepository
import org.assertj.core.api.Assertions.assertThat
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpEntity
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.ldap.core.LdapTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.Security
import java.util.UUID

/**
 * 필드 권한 규칙 CRUD API end-to-end 통합테스트 (FR-PM-07 PR-A Task 5).
 *
 * ## 목적 (prod ground-truth)
 * [com.atlas.bts.identity.fieldpermission.web.FieldPermissionController] →
 * [com.atlas.bts.identity.fieldpermission.application.FieldPermissionApplicationService] →
 * [com.atlas.bts.identity.fieldpermission.repository.FieldPermissionRepository] 전 계층을
 * `@ActiveProfiles("prod")` + 실 내장 Tomcat + Spring Security 필터 체인 위에서 HTTP 왕복으로 검증한다.
 *
 * 핵심은 **MANAGE_FIELD_PERMISSIONS 게이트의 실판정**이다. non-prod AlwaysAllow 마스킹이 없는 prod 에서
 * 실 [com.atlas.bts.identity.permission.PermissionSchemeRepository.roleHasPermission] 가
 * DB 기반으로 권한을 판정하므로, 비보유자의 403(EC11)이 가짜 그린이 아닌 진짜 거부임을 보장한다.
 *
 * ## 시나리오
 * | 번호 | 시나리오 | 기대 |
 * |---|---|---|
 * | S6-POST | PROJECT_ADMIN 규칙 추가 | 201 |
 * | S6-GET | 규칙 목록 (groupName 포함) | 200 |
 * | S6-DELETE | 규칙 삭제 | 204 |
 * | S6-IDEMPOTENT | 동일 규칙 2회 추가 | 201 then 201 (행 1개) |
 * | EC11 | MANAGE_FIELD_PERMISSIONS 미보유(MEMBER) | 403 |
 * | 미인증 | 토큰 없음 → 401 (500 변질 없음) | 401 |
 * | EC12 | 미존재 group_id | 422 |
 * | EC13 | 미정의 CORE field_key(화이트리스트 위반) | 422 |
 * | EC13-CUSTOM | CUSTOM key 는 존재 확인 생략 — 임의 key 허용 | 201 |
 *
 * ## 부팅 셋업 (UserGroupIntegrationTest + FieldPermissionRepositoryTest 합집합)
 * - prod PEM 키 셋업 + RANDOM_PORT + TestRestTemplate + LDAP @MockBean 5종.
 * - 게이트 판정에 필요한 projects / project_memberships / user_groups 행을 직접 시드한다.
 * - PROJECT_ADMIN→MANAGE_FIELD_PERMISSIONS 는 V018 기본 스킴 시드를 그대로 이용한다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration",
    ],
)
@ActiveProfiles("prod")
class FieldPermissionControllerTest {
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

        /**
         * 테스트용 임시 RSA 2048 PEM 파일 경로 (PemFileKeyProvider 요구).
         * PemFileKeyProvider 가 BouncyCastle PEMParser 를 사용하므로 BC provider 를 먼저 등록한다.
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

    // LDAP Bean 목킹 — 실제 LDAP 서버 없이 prod 컨텍스트 부팅 (선례 동일)
    @MockBean lateinit var ldapProvider: LdapProvider

    @MockBean lateinit var ldapProviderConfigService: LdapProviderConfigService

    @MockBean lateinit var externalAccountRepository: ExternalAccountRepository

    @MockBean lateinit var autoProvisionService: AutoProvisionService

    @MockBean lateinit var ldapTemplate: LdapTemplate

    @LocalServerPort
    var port: Int = 0

    @Autowired
    lateinit var restTemplate: TestRestTemplate

    @Autowired
    lateinit var userRepository: UserRepository

    @Autowired
    lateinit var localCredentialService: LocalCredentialService

    @Autowired
    lateinit var jdbc: NamedParameterJdbcTemplate

    // ── 픽스처 ──────────────────────────────────────────────────────────────────

    private lateinit var adminId: UUID
    private lateinit var memberId: UUID
    private lateinit var projectId: UUID
    private lateinit var groupId: UUID

    private val adminPassword = "S3cur3@Admin1"
    private val memberPassword = "S3cur3@Member1"
    private val projectKey = "FPC"

    @BeforeEach
    fun setUp() {
        ensureProjectsTableExists()
        cleanTables()
        seedUsers()
        seedProject()
        seedGroup()
        // admin 은 PROJECT_ADMIN(V018 기본 스킴에서 MANAGE_FIELD_PERMISSIONS 보유), member 는 MEMBER(미보유).
        seedMembership(adminId, "PROJECT_ADMIN")
        seedMembership(memberId, "MEMBER")
    }

    // ── S6. POST 규칙 추가 ──────────────────────────────────────────────────────

    @Test
    fun `S6-POST PROJECT_ADMIN 규칙 추가 — 201`() {
        val token = loginJwt("fpc_admin", adminPassword)

        val resp = postRule(token, projectKey, "CORE", "summary", groupId, "VIEW")

        assertThat(resp.statusCode).isEqualTo(HttpStatus.CREATED)
        val body = resp.body as Map<*, *>
        assertThat(body["fieldKind"]).isEqualTo("CORE")
        assertThat(body["fieldKey"]).isEqualTo("summary")
        assertThat(body["groupId"].toString()).isEqualTo(groupId.toString())
        assertThat(body["accessLevel"]).isEqualTo("VIEW")
    }

    @Test
    fun `S6-IDEMPOTENT 동일 규칙 2회 추가 — 멱등 (행 1개)`() {
        val token = loginJwt("fpc_admin", adminPassword)

        assertThat(postRule(token, projectKey, "CORE", "priority", groupId, "EDIT").statusCode)
            .isEqualTo(HttpStatus.CREATED)
        assertThat(postRule(token, projectKey, "CORE", "priority", groupId, "EDIT").statusCode)
            .isEqualTo(HttpStatus.CREATED)

        val count =
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM field_permissions WHERE project_id = :pid AND field_key = 'priority'",
                mapOf("pid" to projectId),
                Int::class.java,
            ) ?: -1
        assertThat(count).isEqualTo(1)
    }

    @Test
    fun `EC13-CUSTOM 임의 CUSTOM key 는 존재 확인 생략 — 201`() {
        val token = loginJwt("fpc_admin", adminPassword)

        val resp = postRule(token, projectKey, "CUSTOM", "cf_anything_42", groupId, "EDIT")

        assertThat(resp.statusCode).isEqualTo(HttpStatus.CREATED)
    }

    // ── S6. GET 규칙 목록 ───────────────────────────────────────────────────────

    @Test
    fun `S6-GET 규칙 목록 — 200 groupName 포함`() {
        val token = loginJwt("fpc_admin", adminPassword)
        postRule(token, projectKey, "CORE", "summary", groupId, "VIEW")

        val resp = listRules(token, projectKey)

        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
        @Suppress("UNCHECKED_CAST")
        val rules = resp.body as List<Map<*, *>>
        assertThat(rules).hasSize(1)
        assertThat(rules[0]["fieldKey"]).isEqualTo("summary")
        assertThat(rules[0]["groupName"]).isEqualTo("fr-pm-07-t5-group")
        assertThat(rules[0]["id"]).isNotNull()
    }

    // ── S6. DELETE 규칙 삭제 ────────────────────────────────────────────────────

    @Test
    fun `S6-DELETE 규칙 삭제 — 204`() {
        val token = loginJwt("fpc_admin", adminPassword)
        postRule(token, projectKey, "CORE", "summary", groupId, "VIEW")
        val ruleId = ruleId(token, projectKey, "summary")

        val resp =
            restTemplate.exchange(
                url("/api/v1/projects/$projectKey/field-permissions/$ruleId"),
                HttpMethod.DELETE,
                HttpEntity<Void>(authHeaders(token)),
                Void::class.java,
            )

        assertThat(resp.statusCode).isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(listRulesBody(token, projectKey)).isEmpty()
    }

    // ── EC11. 권한 미보유 거부 ───────────────────────────────────────────────────

    @Test
    fun `EC11 MEMBER 규칙 추가 — 403`() {
        val token = loginJwt("fpc_member", memberPassword)

        val resp = postRule(token, projectKey, "CORE", "summary", groupId, "VIEW")

        assertThat(resp.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `EC11 MEMBER 규칙 목록 — 403`() {
        val token = loginJwt("fpc_member", memberPassword)

        // 403 바디는 error 객체이므로 List 디코딩 listRules 대신 Map 디코딩으로 요청한다.
        val resp =
            restTemplate.exchange(
                url("/api/v1/projects/$projectKey/field-permissions"),
                HttpMethod.GET,
                HttpEntity<Void>(authHeaders(token)),
                Map::class.java,
            )

        assertThat(resp.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat((resp.body as Map<*, *>)["error"]).isEqualTo("forbidden")
    }

    // ── 미인증 → 401 (500 변질 없음) ─────────────────────────────────────────────

    @Test
    fun `미인증 규칙 목록 — 401`() {
        val resp =
            restTemplate.exchange(
                url("/api/v1/projects/$projectKey/field-permissions"),
                HttpMethod.GET,
                HttpEntity<Void>(HttpHeaders()),
                Map::class.java,
            )

        assertThat(resp.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    // ── EC12. 미존재 group_id ─────────────────────────────────────────────────────

    @Test
    fun `EC12 미존재 group_id 규칙 추가 — 422`() {
        val token = loginJwt("fpc_admin", adminPassword)

        val resp = postRule(token, projectKey, "CORE", "summary", UUID.randomUUID(), "VIEW")

        assertThat(resp.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
        assertThat((resp.body as Map<*, *>)["error"]).isEqualTo("group_not_found")
    }

    // ── EC13. 미정의 CORE field_key ───────────────────────────────────────────────

    @Test
    fun `EC13 미정의 CORE field_key 규칙 추가 — 422`() {
        val token = loginJwt("fpc_admin", adminPassword)

        val resp = postRule(token, projectKey, "CORE", "totally_unknown_core", groupId, "VIEW")

        assertThat(resp.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
        assertThat((resp.body as Map<*, *>)["error"]).isEqualTo("invalid_field_key")
    }

    @Test
    fun `EC13 제외 필드(securityLevelId) CORE 규칙 추가 — 422`() {
        val token = loginJwt("fpc_admin", adminPassword)

        val resp = postRule(token, projectKey, "CORE", "securityLevelId", groupId, "VIEW")

        assertThat(resp.statusCode).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
        assertThat((resp.body as Map<*, *>)["error"]).isEqualTo("invalid_field_key")
    }

    // ── private 헬퍼 — fixture ─────────────────────────────────────────────────────

    /**
     * projects 테이블을 생성한다.
     *
     * identity-access Flyway 에 없는 cross-BC 테이블이므로 테스트 DB 에 직접 생성한다.
     * ProjectDirectory 가 id / key / deleted_at 컬럼을 쿼리한다.
     */
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

    private fun cleanTables() {
        jdbc.update("DELETE FROM field_permissions", emptyMap<String, Any>())
        jdbc.update("DELETE FROM project_memberships", emptyMap<String, Any>())
        jdbc.update("DELETE FROM user_groups WHERE name = :name", mapOf("name" to "fr-pm-07-t5-group"))
        jdbc.update("DELETE FROM local_credentials", emptyMap<String, Any>())
        jdbc.update(
            "DELETE FROM users WHERE username IN ('fpc_admin', 'fpc_member')",
            emptyMap<String, Any>(),
        )
        jdbc.update("DELETE FROM projects WHERE key = :key", mapOf("key" to projectKey))
    }

    private fun seedUsers() {
        val admin = userRepository.save("fpc_admin", "fpc-admin@example.com", "FPC Admin")
        val member = userRepository.save("fpc_member", "fpc-member@example.com", "FPC Member")
        adminId = admin.id
        memberId = member.id
        localCredentialService.store(adminId, adminPassword.toCharArray())
        localCredentialService.store(memberId, memberPassword.toCharArray())
    }

    private fun seedProject() {
        projectId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO projects (id, key, deleted_at) VALUES (:id, :key, NULL)",
            mapOf("id" to projectId, "key" to projectKey),
        )
    }

    private fun seedGroup() {
        groupId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO user_groups (id, name) VALUES (:id, :name)",
            mapOf("id" to groupId, "name" to "fr-pm-07-t5-group"),
        )
    }

    private fun seedMembership(
        userId: UUID,
        role: String,
    ) {
        jdbc.update(
            """
            INSERT INTO project_memberships (project_id, user_id, role)
            VALUES (:pid, :uid, :role)
            ON CONFLICT (project_id, user_id) DO UPDATE SET role = :role
            """.trimIndent(),
            mapOf("pid" to projectId, "uid" to userId, "role" to role),
        )
    }

    // ── private 헬퍼 — HTTP ────────────────────────────────────────────────────────

    private fun url(path: String): String = "http://localhost:$port$path"

    private fun loginJwt(
        username: String,
        password: String,
    ): String {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        val body = """{"provider":"local","username":"$username","password":"$password"}"""
        val resp =
            restTemplate.exchange(
                url("/api/v1/auth/login"),
                HttpMethod.POST,
                HttpEntity(body, headers),
                Map::class.java,
            )
        check(resp.statusCode == HttpStatus.OK) { "loginJwt 실패 ($username): ${resp.statusCode}" }
        return (resp.body as Map<*, *>)["access_token"] as String
    }

    private fun authHeaders(token: String): HttpHeaders =
        HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }

    @Suppress("LongParameterList")
    private fun postRule(
        token: String,
        projectIdOrKey: String,
        fieldKind: String,
        fieldKey: String,
        groupId: UUID,
        accessLevel: String,
    ) = restTemplate.exchange(
        url("/api/v1/projects/$projectIdOrKey/field-permissions"),
        HttpMethod.POST,
        HttpEntity(
            """{"fieldKind":"$fieldKind","fieldKey":"$fieldKey","groupId":"$groupId","accessLevel":"$accessLevel"}""",
            authHeaders(token),
        ),
        Map::class.java,
    )

    private fun listRules(
        token: String,
        projectIdOrKey: String,
    ) = restTemplate.exchange(
        url("/api/v1/projects/$projectIdOrKey/field-permissions"),
        HttpMethod.GET,
        HttpEntity<Void>(authHeaders(token)),
        List::class.java,
    )

    private fun listRulesBody(
        token: String,
        projectIdOrKey: String,
    ): List<*> {
        val resp = listRules(token, projectIdOrKey)
        check(resp.statusCode == HttpStatus.OK) { "listRules 실패: ${resp.statusCode}" }
        return resp.body as List<*>
    }

    private fun ruleId(
        token: String,
        projectIdOrKey: String,
        fieldKey: String,
    ): UUID {
        @Suppress("UNCHECKED_CAST")
        val rules = listRulesBody(token, projectIdOrKey) as List<Map<*, *>>
        val target = rules.first { it["fieldKey"] == fieldKey }
        return UUID.fromString(target["id"].toString())
    }
}
