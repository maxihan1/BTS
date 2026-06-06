// 이슈 보안 수준 관리 end-to-end 통합테스트 — prod 실 필터체인 + SYSTEM_ADMIN/PROJECT_ADMIN 실판정 (FR-PM-06 PR-A Task 9)

package com.atlas.bts.identity.web

import com.atlas.bts.identity.credential.LocalCredentialService
import com.atlas.bts.identity.group.UserGroupRepository
import com.atlas.bts.identity.provider.ldap.AutoProvisionService
import com.atlas.bts.identity.provider.ldap.ExternalAccountRepository
import com.atlas.bts.identity.provider.ldap.LdapProvider
import com.atlas.bts.identity.provider.ldap.LdapProviderConfigService
import com.atlas.bts.identity.systemrole.SystemRole
import com.atlas.bts.identity.systemrole.SystemRoleAssignmentRepository
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
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.Security
import java.util.UUID

/**
 * 이슈 보안 수준 관리 end-to-end 통합테스트 (FR-PM-06 PR-A Task 9).
 *
 * ## 목적 (prod ground-truth)
 * Task 7/8 컨트롤러([IssueSecuritySchemeController]·[ProjectSecuritySchemeController]) → 서비스 →
 * Repository 전 계층을 `@ActiveProfiles("prod")` + 실 내장 Tomcat + Spring Security 필터 체인 위에서
 * HTTP 왕복으로 검증한다. 단위/MockMvc 테스트가 가리는 두 거부 경로를 prod 실판정으로 잠근다.
 * - **SYSTEM_ADMIN 거부**: 비관리자 토큰의 스킴 관리 403 이 실 [com.atlas.bts.identity.permission
 *   .IdentityAccessSystemPermissionResolver] DB 판정에 의한 진짜 거부임을 보장(non-prod AlwaysAllow 마스킹 없음).
 * - **PROJECT_ADMIN 거부**: 비-PROJECT_ADMIN(MEMBER) 토큰의 프로젝트 스킴 적용 403 이 실 멤버십 DB
 *   판정에 의한 진짜 거부임을 보장.
 *
 * ## 부팅 셋업 합집합 (UserGroupIntegrationTest 레시피 복제)
 * prod + RANDOM_PORT 부팅에는 PEM 키(@DynamicPropertySource) + OAuth2ClientAutoConfiguration 제외 +
 * LDAP @MockBean 5종 + `loginJwt(...)` 실 로그인 + [LocalCredentialService.store] 자격 시드가 필요하다.
 * S6/S7(프로젝트 스킴 적용)은 [SystemRoleAssignmentRepository.assign] 외에 `projects` 행 +
 * `project_memberships` PROJECT_ADMIN/MEMBER 행을 추가로 시드한다(ProjectMemberFlowIntegrationTest 선례).
 *
 * ## 시나리오
 * | 번호 | 시나리오 | 기대 |
 * |---|---|---|
 * | S1 | 스킴 생성/조회/수정/삭제 (SYSTEM_ADMIN) | 201/200/200/204 |
 * | S2 | 등급 추가 + 둘째 기본등급 위반 | 201 / 409 또는 400 |
 * | S3 | 멤버 추가 다형(USER/GROUP/PROJECT_ROLE/REPORTER) + 멱등 | 201, 목록 4종 |
 * | S4 | 멤버 목록/제거(멱등) | 200/204 |
 * | S5 | 스킴 삭제 CASCADE(등급/멤버 연쇄) + 없는 등급 멤버 404 | 검증 |
 * | S6 | 프로젝트 스킴 적용/조회/해제 (PROJECT_ADMIN) | 204/200/204 |
 * | S7 | 비-PROJECT_ADMIN(MEMBER) 적용 403 / 미인증 401 / 없는 프로젝트 404 | 403/401/404 |
 * | DENY | 비-SYSTEM_ADMIN 스킴 관리 403 / 미인증 401 | 403/401 |
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration",
    ],
)
@ActiveProfiles("prod")
@Testcontainers
@Suppress("LargeClass") // 7 시나리오 그룹 + 권한 거부 ground-truth + 부팅 레시피 헬퍼(명세 요구 범위).
class IssueSecurityIntegrationTest {
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
            // prod 프로파일에서 PemFileKeyProvider 가 PEM 파일 경로를 @Value 로 요구한다.
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

    // LDAP Bean 목킹 — 실제 LDAP 서버 없이 prod 컨텍스트 부팅 (UserGroupIntegrationTest 동일).
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
    lateinit var userGroupRepository: UserGroupRepository

    @Autowired
    lateinit var localCredentialService: LocalCredentialService

    @Autowired
    lateinit var roleAssignmentRepository: SystemRoleAssignmentRepository

    @Autowired
    lateinit var jdbc: NamedParameterJdbcTemplate

    // ── 픽스처 ──────────────────────────────────────────────────────────────────

    private lateinit var adminId: UUID
    private lateinit var memberId: UUID
    private lateinit var projectAdminId: UUID
    private lateinit var groupId: UUID
    private lateinit var projectId: UUID

    private val adminPassword = "S3cur3@Admin1"
    private val memberPassword = "S3cur3@Member1"
    private val projectAdminPassword = "S3cur3@PrjAdm1"

    private val projectKey = "ISEC"

    @BeforeEach
    fun setUp() {
        ensureProjectsTableExists()
        cleanTables()
        seedUsers()
        seedProjectAndMemberships()
        // admin 에게만 전역 SYSTEM_ADMIN 부여 (실 repository 경유 — prod resolver 가 DB 로 판정).
        roleAssignmentRepository.assign(adminId, SystemRole.SYSTEM_ADMIN)
    }

    // ── S1. 스킴 CRUD ─────────────────────────────────────────────────────────────

    @Test
    fun `S1 SYSTEM_ADMIN 스킴 생성-조회-수정-삭제`() {
        val token = loginJwt("isec_admin", adminPassword)

        val createResp = createScheme(token, "ISEC-S1", "스킴 설명")
        assertThat(createResp.statusCode).isEqualTo(HttpStatus.CREATED)
        val schemeId = UUID.fromString((createResp.body as Map<*, *>)["id"].toString())

        val getResp = getScheme(token, schemeId)
        assertThat(getResp.statusCode).isEqualTo(HttpStatus.OK)
        assertThat((getResp.body as Map<*, *>)["name"]).isEqualTo("ISEC-S1")

        val patchResp =
            restTemplate.exchange(
                url("/api/v1/issue-security-schemes/$schemeId"),
                HttpMethod.PATCH,
                HttpEntity("""{"name":"ISEC-S1-new","description":"갱신"}""", authHeaders(token)),
                Map::class.java,
            )
        assertThat(patchResp.statusCode).isEqualTo(HttpStatus.OK)
        assertThat((patchResp.body as Map<*, *>)["name"]).isEqualTo("ISEC-S1-new")

        assertThat(deleteScheme(token, schemeId).statusCode).isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(getScheme(token, schemeId).statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    // ── S2. 등급 추가 + 둘째 기본등급 위반 ───────────────────────────────────────────

    @Test
    fun `S2 등급 추가 — 201, 둘째 기본등급은 위반`() {
        val token = loginJwt("isec_admin", adminPassword)
        val schemeId = createSchemeId(token, "ISEC-S2")

        val first = addLevel(token, schemeId, "기본등급", isDefault = true)
        assertThat(first.statusCode).isEqualTo(HttpStatus.CREATED)
        assertThat((first.body as Map<*, *>)["isDefault"]).isEqualTo(true)

        // 둘째 기본 등급은 부분 유니크 인덱스 위반 → 409(scheme_in_use 가 아닌 무결성) 으로 매핑.
        val second = addLevel(token, schemeId, "둘째기본", isDefault = true)
        assertThat(second.statusCode).isEqualTo(HttpStatus.CONFLICT)
    }

    // ── S3. 멤버 다형 추가 ──────────────────────────────────────────────────────────

    @Test
    fun `S3 멤버 다형 추가 — USER GROUP PROJECT_ROLE REPORTER + 멱등`() {
        val token = loginJwt("isec_admin", adminPassword)
        val schemeId = createSchemeId(token, "ISEC-S3")
        val levelId = addLevelId(token, schemeId, "내부용")

        assertThat(addMember(token, levelId, "USER", memberId.toString()).statusCode)
            .isEqualTo(HttpStatus.CREATED)
        assertThat(addMember(token, levelId, "GROUP", groupId.toString()).statusCode)
            .isEqualTo(HttpStatus.CREATED)
        assertThat(addMember(token, levelId, "PROJECT_ROLE", "PROJECT_ADMIN").statusCode)
            .isEqualTo(HttpStatus.CREATED)
        assertThat(addMember(token, levelId, "REPORTER", null).statusCode)
            .isEqualTo(HttpStatus.CREATED)
        // 멱등 — 같은 USER 재추가는 새 행을 만들지 않는다.
        addMember(token, levelId, "USER", memberId.toString())

        val members = listMembers(token, levelId)
        assertThat(members.statusCode).isEqualTo(HttpStatus.OK)
        @Suppress("UNCHECKED_CAST")
        val rows = members.body as List<Map<*, *>>
        assertThat(rows).hasSize(4)
        assertThat(rows.map { it["memberType"] })
            .containsExactlyInAnyOrder("USER", "GROUP", "PROJECT_ROLE", "REPORTER")
    }

    // ── S4. 멤버 제거 멱등 ──────────────────────────────────────────────────────────

    @Test
    fun `S4 멤버 제거 — 204 멱등`() {
        val token = loginJwt("isec_admin", adminPassword)
        val schemeId = createSchemeId(token, "ISEC-S4")
        val levelId = addLevelId(token, schemeId, "등급4")
        val memberRecordId =
            UUID.fromString(
                (addMember(token, levelId, "USER", memberId.toString()).body as Map<*, *>)["id"].toString(),
            )

        assertThat(removeMember(token, memberRecordId).statusCode).isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(listMembersRows(token, levelId)).isEmpty()
        // 없는 멤버 제거도 멱등하게 204.
        assertThat(removeMember(token, memberRecordId).statusCode).isEqualTo(HttpStatus.NO_CONTENT)
    }

    // ── S5. 스킴 삭제 CASCADE + 없는 등급 멤버 404 ──────────────────────────────────

    @Test
    fun `S5 스킴 삭제 — 등급 멤버 CASCADE 연쇄 제거`() {
        val token = loginJwt("isec_admin", adminPassword)
        val schemeId = createSchemeId(token, "ISEC-S5")
        val levelId = addLevelId(token, schemeId, "등급5")
        addMember(token, levelId, "REPORTER", null)

        deleteScheme(token, schemeId)

        // 등급/멤버 DB 직접 조회로 CASCADE 연쇄 제거 확인.
        val levelRows =
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM issue_security_levels WHERE scheme_id = :sid",
                mapOf("sid" to schemeId),
                Int::class.java,
            ) ?: -1
        assertThat(levelRows).isZero()
        val memberRows =
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM issue_security_level_members WHERE level_id = :lid",
                mapOf("lid" to levelId),
                Int::class.java,
            ) ?: -1
        assertThat(memberRows).isZero()
    }

    @Test
    fun `S5 없는 등급 멤버 목록 — 404 level_not_found`() {
        val token = loginJwt("isec_admin", adminPassword)

        val resp = listMembers(token, UUID.randomUUID())

        assertThat(resp.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat((resp.body as Map<*, *>)["error"]).isEqualTo("level_not_found")
    }

    // ── S6. 프로젝트 스킴 적용 (PROJECT_ADMIN) ──────────────────────────────────────

    @Test
    fun `S6 PROJECT_ADMIN 프로젝트 스킴 적용-조회-해제`() {
        val adminToken = loginJwt("isec_admin", adminPassword)
        val schemeId = createSchemeId(adminToken, "ISEC-S6")
        val projectAdminToken = loginJwt("isec_prjadmin", projectAdminPassword)

        assertThat(assignScheme(projectAdminToken, projectKey, schemeId).statusCode)
            .isEqualTo(HttpStatus.NO_CONTENT)

        val findResp = findProjectScheme(projectAdminToken, projectKey)
        assertThat(findResp.statusCode).isEqualTo(HttpStatus.OK)
        assertThat((findResp.body as Map<*, *>)["schemeId"].toString()).isEqualTo(schemeId.toString())

        assertThat(unassignScheme(projectAdminToken, projectKey).statusCode).isEqualTo(HttpStatus.NO_CONTENT)
        assertThat((findProjectScheme(projectAdminToken, projectKey).body as Map<*, *>)["schemeId"]).isNull()
    }

    // ── S7. 프로젝트 스킴 거부 ground-truth ─────────────────────────────────────────

    @Test
    fun `S7 비-PROJECT_ADMIN 적용 — 403 forbidden`() {
        val adminToken = loginJwt("isec_admin", adminPassword)
        val schemeId = createSchemeId(adminToken, "ISEC-S7")
        val memberToken = loginJwt("isec_member", memberPassword)

        val resp = assignScheme(memberToken, projectKey, schemeId)

        assertThat(resp.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat((resp.body as Map<*, *>)["error"]).isEqualTo("forbidden")
    }

    @Test
    fun `S7 미인증 적용 — 401`() {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        val resp =
            restTemplate.exchange(
                url("/api/v1/projects/$projectKey/issue-security-scheme"),
                HttpMethod.PUT,
                HttpEntity("""{"schemeId":"${UUID.randomUUID()}"}""", headers),
                Map::class.java,
            )

        assertThat(resp.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `S7 없는 프로젝트 적용 — 404 project_not_found`() {
        val adminToken = loginJwt("isec_admin", adminPassword)
        val schemeId = createSchemeId(adminToken, "ISEC-S7b")
        val projectAdminToken = loginJwt("isec_prjadmin", projectAdminPassword)

        val resp = assignScheme(projectAdminToken, "NOPE", schemeId)

        assertThat(resp.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat((resp.body as Map<*, *>)["error"]).isEqualTo("project_not_found")
    }

    // ── DENY. 스킴 관리 거부 ground-truth ───────────────────────────────────────────

    /**
     * DENY-403: 비-SYSTEM_ADMIN(memberId, 역할 미부여) 토큰으로 스킴 생성 → 403.
     *
     * prod resolver 실판정이므로 non-prod AlwaysAllow 마스킹이 없다 — 진짜 거부.
     */
    @Test
    fun `DENY 비-SYSTEM_ADMIN 스킴 생성 — 403 forbidden`() {
        val token = loginJwt("isec_member", memberPassword)

        val resp = createScheme(token, "ISEC-denied", null)

        assertThat(resp.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat((resp.body as Map<*, *>)["error"]).isEqualTo("forbidden")
    }

    @Test
    fun `DENY 미인증 스킴 생성 — 401`() {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        val resp =
            restTemplate.exchange(
                url("/api/v1/issue-security-schemes"),
                HttpMethod.POST,
                HttpEntity("""{"name":"ISEC-anon"}""", headers),
                Map::class.java,
            )

        assertThat(resp.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    // ── private 헬퍼 — fixture ───────────────────────────────────────────────────

    /**
     * projects 테이블을 생성한다.
     *
     * identity-access Flyway 에 없는 cross-BC 테이블이므로 테스트 DB 에 직접 생성한다.
     * [com.atlas.bts.identity.project.ProjectDirectory] 가 id/key/deleted_at 컬럼을 쿼리한다.
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

    /** 보안 스킴/등급/멤버 + 프로젝트/멤버십/자격/사용자를 FK 순서대로 초기화한다. */
    private fun cleanTables() {
        jdbc.update("DELETE FROM project_issue_security_schemes", emptyMap<String, Any>())
        jdbc.update(
            "DELETE FROM issue_security_schemes WHERE name LIKE :prefix",
            mapOf("prefix" to "ISEC-%"),
        )
        jdbc.update("DELETE FROM group_memberships", emptyMap<String, Any>())
        jdbc.update("DELETE FROM user_groups WHERE name LIKE :prefix", mapOf("prefix" to "isec-%"))
        jdbc.update("DELETE FROM project_memberships", emptyMap<String, Any>())
        jdbc.update("DELETE FROM system_role_assignments", emptyMap<String, Any>())
        jdbc.update("DELETE FROM local_credentials", emptyMap<String, Any>())
        jdbc.update(
            "DELETE FROM users WHERE username IN ('isec_admin', 'isec_member', 'isec_prjadmin')",
            emptyMap<String, Any>(),
        )
        jdbc.update("DELETE FROM projects WHERE key = :key", mapOf("key" to projectKey))
    }

    /** 관리자/일반/프로젝트관리자 3명 + 그룹 1개를 생성하고 로그인 자격을 시드한다. */
    private fun seedUsers() {
        val admin = userRepository.save("isec_admin", "isec-admin@example.com", "ISEC Admin")
        val member = userRepository.save("isec_member", "isec-member@example.com", "ISEC Member")
        val projectAdmin = userRepository.save("isec_prjadmin", "isec-prjadmin@example.com", "ISEC ProjectAdmin")
        adminId = admin.id
        memberId = member.id
        projectAdminId = projectAdmin.id
        localCredentialService.store(adminId, adminPassword.toCharArray())
        localCredentialService.store(memberId, memberPassword.toCharArray())
        localCredentialService.store(projectAdminId, projectAdminPassword.toCharArray())
        groupId = requireNotNull(userGroupRepository.create("isec-group", "ISEC 그룹").id) {
            "영속 그룹은 id 를 가져야 한다."
        }
    }

    /** 활성 프로젝트 1개 + PROJECT_ADMIN(projectAdmin)/MEMBER(member) 멤버십을 시드한다. */
    private fun seedProjectAndMemberships() {
        projectId = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO projects (id, key, deleted_at) VALUES (:id, :key, NULL)",
            mapOf("id" to projectId, "key" to projectKey),
        )
        insertMembership(projectAdminId, "PROJECT_ADMIN")
        insertMembership(memberId, "MEMBER")
    }

    /** project_memberships 에 직접 멤버십 행을 INSERT 한다(서비스 우회 시드). */
    private fun insertMembership(
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

    // ── private 헬퍼 — HTTP ──────────────────────────────────────────────────────

    /** 절대 URL 을 구성한다 (RANDOM_PORT 바인딩). */
    private fun url(path: String): String = "http://localhost:$port$path"

    /**
     * POST /api/v1/auth/login 으로 JWT access_token 을 발급받는다.
     *
     * login 은 SecurityConfig 에서 CSRF skip 이므로 X-XSRF-TOKEN 불필요.
     */
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

    /** Authorization: Bearer + JSON Content-Type 헤더를 만든다. */
    private fun authHeaders(token: String): HttpHeaders =
        HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }

    /** POST /api/v1/issue-security-schemes — 스킴 생성 요청. */
    private fun createScheme(
        token: String,
        name: String,
        description: String?,
    ) = restTemplate
        .exchange(
            url("/api/v1/issue-security-schemes"),
            HttpMethod.POST,
            HttpEntity(schemeBody(name, description), authHeaders(token)),
            Map::class.java,
        )

    /** 스킴을 생성하고 그 식별자를 반환한다(생성 성공 단언). */
    private fun createSchemeId(
        token: String,
        name: String,
    ): UUID {
        val resp = createScheme(token, name, null)
        check(resp.statusCode == HttpStatus.CREATED) { "createSchemeId 실패: ${resp.statusCode} ${resp.body}" }
        return UUID.fromString((resp.body as Map<*, *>)["id"].toString())
    }

    /** GET /api/v1/issue-security-schemes/{id} — 단건 스킴 조회. */
    private fun getScheme(
        token: String,
        schemeId: UUID,
    ) = restTemplate
        .exchange(
            url("/api/v1/issue-security-schemes/$schemeId"),
            HttpMethod.GET,
            HttpEntity<Void>(authHeaders(token)),
            Map::class.java,
        )

    /** DELETE /api/v1/issue-security-schemes/{id} — 스킴 삭제. */
    private fun deleteScheme(
        token: String,
        schemeId: UUID,
    ) = restTemplate
        .exchange(
            url("/api/v1/issue-security-schemes/$schemeId"),
            HttpMethod.DELETE,
            HttpEntity<Void>(authHeaders(token)),
            Map::class.java,
        )

    /** POST /api/v1/issue-security-schemes/{schemeId}/levels — 등급 추가. */
    private fun addLevel(
        token: String,
        schemeId: UUID,
        name: String,
        isDefault: Boolean,
    ) = restTemplate
        .exchange(
            url("/api/v1/issue-security-schemes/$schemeId/levels"),
            HttpMethod.POST,
            HttpEntity("""{"name":"$name","isDefault":$isDefault}""", authHeaders(token)),
            Map::class.java,
        )

    /** 등급을 추가하고 그 식별자를 반환한다(추가 성공 단언). */
    private fun addLevelId(
        token: String,
        schemeId: UUID,
        name: String,
    ): UUID {
        val resp = addLevel(token, schemeId, name, isDefault = false)
        check(resp.statusCode == HttpStatus.CREATED) { "addLevelId 실패: ${resp.statusCode} ${resp.body}" }
        return UUID.fromString((resp.body as Map<*, *>)["id"].toString())
    }

    /** POST /api/v1/issue-security-levels/{levelId}/members — 멤버 추가. */
    private fun addMember(
        token: String,
        levelId: UUID,
        memberType: String,
        memberValue: String?,
    ) = restTemplate
        .exchange(
            url("/api/v1/issue-security-levels/$levelId/members"),
            HttpMethod.POST,
            HttpEntity(memberBody(memberType, memberValue), authHeaders(token)),
            Map::class.java,
        )

    /** GET /api/v1/issue-security-levels/{levelId}/members — 멤버 목록(List 바디). */
    private fun listMembers(
        token: String,
        levelId: UUID,
    ) = restTemplate
        .exchange(
            url("/api/v1/issue-security-levels/$levelId/members"),
            HttpMethod.GET,
            HttpEntity<Void>(authHeaders(token)),
            // 성공 시 List, 404 시 error 객체이므로 Object 로 받아 호출 측에서 분기한다.
            Object::class.java,
        )

    /** 멤버 목록을 행 목록으로 반환한다(성공 단언). */
    @Suppress("UNCHECKED_CAST")
    private fun listMembersRows(
        token: String,
        levelId: UUID,
    ): List<Map<*, *>> {
        val resp = listMembers(token, levelId)
        check(resp.statusCode == HttpStatus.OK) { "listMembersRows 실패: ${resp.statusCode}" }
        return resp.body as List<Map<*, *>>
    }

    /** DELETE /api/v1/issue-security-level-members/{memberId} — 멤버 제거. */
    private fun removeMember(
        token: String,
        memberId: UUID,
    ) = restTemplate
        .exchange(
            url("/api/v1/issue-security-level-members/$memberId"),
            HttpMethod.DELETE,
            HttpEntity<Void>(authHeaders(token)),
            Map::class.java,
        )

    /** PUT /api/v1/projects/{key}/issue-security-scheme — 프로젝트 스킴 적용. */
    private fun assignScheme(
        token: String,
        key: String,
        schemeId: UUID,
    ) = restTemplate
        .exchange(
            url("/api/v1/projects/$key/issue-security-scheme"),
            HttpMethod.PUT,
            HttpEntity("""{"schemeId":"$schemeId"}""", authHeaders(token)),
            Map::class.java,
        )

    /** DELETE /api/v1/projects/{key}/issue-security-scheme — 프로젝트 스킴 해제. */
    private fun unassignScheme(
        token: String,
        key: String,
    ) = restTemplate
        .exchange(
            url("/api/v1/projects/$key/issue-security-scheme"),
            HttpMethod.DELETE,
            HttpEntity<Void>(authHeaders(token)),
            Map::class.java,
        )

    /** GET /api/v1/projects/{key}/issue-security-scheme — 적용 스킴 조회. */
    private fun findProjectScheme(
        token: String,
        key: String,
    ) = restTemplate
        .exchange(
            url("/api/v1/projects/$key/issue-security-scheme"),
            HttpMethod.GET,
            HttpEntity<Void>(authHeaders(token)),
            Map::class.java,
        )

    /** 스킴 요청 바디 JSON 을 만든다(description null 이면 키 생략). */
    private fun schemeBody(
        name: String,
        description: String?,
    ): String =
        if (description == null) {
            """{"name":"$name"}"""
        } else {
            """{"name":"$name","description":"$description"}"""
        }

    /** 멤버 요청 바디 JSON 을 만든다(memberValue null 이면 키 생략). */
    private fun memberBody(
        memberType: String,
        memberValue: String?,
    ): String =
        if (memberValue == null) {
            """{"memberType":"$memberType"}"""
        } else {
            """{"memberType":"$memberType","memberValue":"$memberValue"}"""
        }
}
