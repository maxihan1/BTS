// 전역 사용자 그룹 관리 end-to-end 통합테스트 — prod 실 필터체인 + SYSTEM_ADMIN 실판정 (FR-PM-09 Task 6)

package com.atlas.bts.identity.web

import com.atlas.bts.identity.credential.LocalCredentialService
import com.atlas.bts.identity.mfa.MfaSecretEncryptor
import com.atlas.bts.identity.provider.ldap.AutoProvisionService
import com.atlas.bts.identity.provider.ldap.ExternalAccountRepository
import com.atlas.bts.identity.provider.ldap.LdapProvider
import com.atlas.bts.identity.provider.ldap.LdapProviderConfigService
import com.atlas.bts.identity.systemrole.SystemRole
import com.atlas.bts.identity.systemrole.SystemRoleAssignmentRepository
import com.atlas.bts.identity.user.UserRepository
import dev.samstevens.totp.code.DefaultCodeGenerator
import dev.samstevens.totp.code.HashingAlgorithm
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
import java.time.Instant
import java.util.UUID

/**
 * 전역 사용자 그룹 관리 end-to-end 통합테스트 (FR-PM-09 Task 6).
 *
 * ## 목적 (prod ground-truth)
 * Task 1~5에서 구현한 [UserGroupController] → [com.atlas.bts.identity.group.UserGroupService] →
 * [com.atlas.bts.identity.group.JdbcUserGroupRepository] 전 계층을 `@ActiveProfiles("prod")` +
 * 실 내장 Tomcat + Spring Security 필터 체인 위에서 HTTP 왕복으로 검증한다.
 *
 * 특히 **S8 거부 경로**가 핵심이다. non-prod 의 AlwaysAllow resolver 마스킹이 없는 prod 에서
 * 실 [com.atlas.bts.identity.permission.IdentityAccessSystemPermissionResolver] 가 DB 기반으로
 * SYSTEM_ADMIN 을 판정하므로, 비관리자 토큰의 403 이 가짜 그린이 아닌 진짜 거부임을 보장한다.
 *
 * ## 부팅 셋업 합집합 (BLOCKER B1 — prod + RANDOM_PORT 선례 부재)
 * 두 선례의 셋업을 합쳐야 부팅된다.
 * - [com.atlas.bts.identity.systemrole.SystemAdminInfraIntegrationTest] 에서 prod 용 **PEM 키 셋업**
 *   (`bts.auth.jwt.private-key-pem-path` @DynamicPropertySource) + [SystemRoleAssignmentRepository.assign]
 *   시드 패턴.
 * - [com.atlas.bts.identity.integration.ProjectMemberFlowIntegrationTest] 에서 **RANDOM_PORT +
 *   [TestRestTemplate]**, OAuth2ClientAutoConfiguration 제외, **LDAP @MockBean 5종**, `loginJwt(...)`
 *   실 로그인 + [LocalCredentialService.store] 자격 시드 패턴.
 *
 * ## 시나리오
 * | 번호 | 시나리오 | 기대 |
 * |---|---|---|
 * | S1 | 관리자 그룹 생성 | 201 (memberCount=0) |
 * | S2 | 그룹 목록 (memberCount) | 200 |
 * | S3 | 그룹 수정 | 200 |
 * | S4 | 그룹 삭제 | 204 |
 * | S5 | 멤버 추가 멱등 (2회 → 멤버 1명) | 204 |
 * | S6 | 멤버 제거 멱등 (없는 멤버 제거도) | 204 |
 * | S7 | 멤버 목록 | 200 |
 * | S8 | 비관리자 그룹 생성 → 403 / 미인증 → 401 | 403 / 401 |
 * | EC1 | name 중복 생성 | 409 |
 * | EC6 | 그룹 삭제 시 멤버십 CASCADE | 삭제 후 멤버 조회 검증 |
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
class UserGroupIntegrationTest {
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
            // FR-MF-04 게이트 정합 — admin 의 ACTIVE TOTP 시드/검증에 MfaSecretEncryptor 키가 필요하다.
            registry.add("bts.mfa.encryption.key") { MFA_TEST_KEY }
            registry.add("bts.mfa.encryption.salt") { MFA_TEST_SALT }
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

        /** MFA 암호화 테스트 키 — MfaSecretEncryptor 가 secret 암복호화에 요구. */
        private const val MFA_TEST_KEY = "test-app-encryption-key-for-mfa-totp"

        /** MFA salt — `Encryptors.stronger` 가 hex 문자열을 요구하므로 유효 hex 리터럴. */
        private const val MFA_TEST_SALT = "deadbeefcafef00d"

        /**
         * admin ACTIVE TOTP 시드용 고정 base32 secret(테스트 전용).
         * login 2단계(verify) 에서 이 secret 으로 정답 코드를 산출해 게이트 통과 토큰을 받는다.
         */
        private const val ADMIN_TOTP_SECRET = "JBSWY3DPEHPK3PXP"

        /** RFC 6238 time-step 길이(초) — 운영 TotpService.PERIOD_SECONDS 와 동일. */
        private const val TOTP_PERIOD_SECONDS = 30L

        /** RFC 6238 코드 자릿수 — 운영 TotpService.DIGITS 와 동일. */
        private const val TOTP_DIGITS = 6
    }

    // LDAP Bean 목킹 — 실제 LDAP 서버 없이 prod 컨텍스트 부팅 (두 선례 동일)
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
    lateinit var roleAssignmentRepository: SystemRoleAssignmentRepository

    @Autowired
    lateinit var mfaSecretEncryptor: MfaSecretEncryptor

    @Autowired
    lateinit var jdbc: NamedParameterJdbcTemplate

    // ── 픽스처 ──────────────────────────────────────────────────────────────────

    private lateinit var adminId: UUID
    private lateinit var memberId: UUID

    private val adminPassword = "S3cur3@Admin1"
    private val memberPassword = "S3cur3@Member1"

    @BeforeEach
    fun setUp() {
        cleanTables()
        seedUsers()
        // admin 사용자에게만 전역 SYSTEM_ADMIN 부여 (실 repository 경유 — prod resolver 가 DB 로 판정).
        roleAssignmentRepository.assign(adminId, SystemRole.SYSTEM_ADMIN)
        // FR-MF-04 게이트 정합 — SYSTEM_ADMIN 은 MFA 강제 대상이므로, 보호 API 를 쓰려면 MFA 가 등록(ACTIVE)돼
        // 있어야 게이트(MfaEnrollmentGateFilter)를 통과한다. login 토큰 발급 전에 admin 의 TOTP 를 ACTIVE 로
        // 시드해 MfaEnforcementPolicy.evaluate=false → 클레임 mfa_enrollment_required=false 로 만든다.
        seedActiveMfa(adminId)
    }

    // ── S1. 그룹 생성 ─────────────────────────────────────────────────────────────

    @Test
    fun `S1 관리자 토큰으로 그룹 생성 — 201 memberCount 0`() {
        val token = loginJwt("fr_pm_09_admin", adminPassword)

        val resp = createGroupVia(token, "fr-pm-09-t6-s1", "S1 설명")

        assertThat(resp.statusCode).isEqualTo(HttpStatus.CREATED)
        val body = resp.body as Map<*, *>
        assertThat(body["name"]).isEqualTo("fr-pm-09-t6-s1")
        assertThat(body["description"]).isEqualTo("S1 설명")
        assertThat(body["memberCount"]).isEqualTo(0)
    }

    // ── S2. 그룹 목록 ─────────────────────────────────────────────────────────────

    @Test
    fun `S2 그룹 목록 — 200 memberCount 포함`() {
        val token = loginJwt("fr_pm_09_admin", adminPassword)
        val groupId = createGroupId(token, "fr-pm-09-t6-s2")
        addMemberVia(token, groupId, memberId)

        val resp = listGroupsVia(token)

        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
        @Suppress("UNCHECKED_CAST")
        val groups = resp.body as List<Map<*, *>>
        val target = groups.first { it["id"].toString() == groupId.toString() }
        assertThat(target["memberCount"]).isEqualTo(1)
    }

    // ── S3. 그룹 수정 ─────────────────────────────────────────────────────────────

    @Test
    fun `S3 그룹 수정 — 200`() {
        val token = loginJwt("fr_pm_09_admin", adminPassword)
        val groupId = createGroupId(token, "fr-pm-09-t6-s3")

        val resp =
            restTemplate.exchange(
                url("/api/v1/groups/$groupId"),
                HttpMethod.PATCH,
                HttpEntity("""{"name":"fr-pm-09-t6-s3-new","description":"갱신"}""", authHeaders(token)),
                Map::class.java,
            )

        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
        val body = resp.body as Map<*, *>
        assertThat(body["name"]).isEqualTo("fr-pm-09-t6-s3-new")
        assertThat(body["description"]).isEqualTo("갱신")
    }

    // ── S4. 그룹 삭제 ─────────────────────────────────────────────────────────────

    @Test
    fun `S4 그룹 삭제 — 204`() {
        val token = loginJwt("fr_pm_09_admin", adminPassword)
        val groupId = createGroupId(token, "fr-pm-09-t6-s4")

        val resp = deleteGroupVia(token, groupId)

        assertThat(resp.statusCode).isEqualTo(HttpStatus.NO_CONTENT)
        // 삭제 후 단건 조회는 404.
        assertThat(getGroupVia(token, groupId).statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    // ── S5. 멤버 추가 멱등 ─────────────────────────────────────────────────────────

    @Test
    fun `S5 멤버 추가 — 204 멱등 (2회 추가해도 멤버 1명)`() {
        val token = loginJwt("fr_pm_09_admin", adminPassword)
        val groupId = createGroupId(token, "fr-pm-09-t6-s5")

        assertThat(addMemberVia(token, groupId, memberId).statusCode).isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(addMemberVia(token, groupId, memberId).statusCode).isEqualTo(HttpStatus.NO_CONTENT)

        assertThat(memberIds(token, groupId)).containsExactly(memberId.toString())
    }

    // ── S6. 멤버 제거 멱등 ─────────────────────────────────────────────────────────

    @Test
    fun `S6 멤버 제거 — 204 멱등 (없는 멤버 제거도 성공)`() {
        val token = loginJwt("fr_pm_09_admin", adminPassword)
        val groupId = createGroupId(token, "fr-pm-09-t6-s6")
        addMemberVia(token, groupId, memberId)

        assertThat(removeMemberVia(token, groupId, memberId).statusCode).isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(memberIds(token, groupId)).isEmpty()
        // 없는 멤버 제거도 멱등하게 204.
        assertThat(removeMemberVia(token, groupId, memberId).statusCode).isEqualTo(HttpStatus.NO_CONTENT)
    }

    // ── S7. 멤버 목록 ─────────────────────────────────────────────────────────────

    @Test
    fun `S7 멤버 목록 — 200`() {
        val token = loginJwt("fr_pm_09_admin", adminPassword)
        val groupId = createGroupId(token, "fr-pm-09-t6-s7")
        addMemberVia(token, groupId, memberId)

        val resp = listMembersVia(token, groupId)

        assertThat(resp.statusCode).isEqualTo(HttpStatus.OK)
        @Suppress("UNCHECKED_CAST")
        val members = resp.body as List<Map<*, *>>
        assertThat(members).hasSize(1)
        assertThat(members[0]["id"].toString()).isEqualTo(memberId.toString())
        assertThat(members[0]["username"]).isEqualTo("fr_pm_09_member")
    }

    // ── S8. 거부 ground-truth ──────────────────────────────────────────────────────

    /**
     * S8-403: 비관리자(SYSTEM_ADMIN 미보유) 토큰으로 그룹 생성 → 403.
     *
     * prod resolver 실판정이므로 non-prod AlwaysAllow 마스킹이 없다 — 진짜 거부.
     */
    @Test
    fun `S8 비관리자 토큰으로 그룹 생성 — 403 forbidden`() {
        val token = loginJwt("fr_pm_09_member", memberPassword)

        val resp = createGroupVia(token, "fr-pm-09-t6-s8-denied", null)

        assertThat(resp.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        val body = resp.body as Map<*, *>
        assertThat(body["error"]).isEqualTo("forbidden")
    }

    /**
     * S8-401: 토큰 없이 그룹 생성 → 401 (Spring Security 필터 체인 차단).
     */
    @Test
    fun `S8 미인증 그룹 생성 — 401`() {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        val resp =
            restTemplate.exchange(
                url("/api/v1/groups"),
                HttpMethod.POST,
                HttpEntity("""{"name":"fr-pm-09-t6-s8-anon"}""", headers),
                Map::class.java,
            )

        assertThat(resp.statusCode).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    // ── EC1. name 중복 ────────────────────────────────────────────────────────────

    @Test
    fun `EC1 name 중복 그룹 생성 — 409 group_name_conflict`() {
        val token = loginJwt("fr_pm_09_admin", adminPassword)
        createGroupVia(token, "fr-pm-09-t6-ec1", null)

        val resp = createGroupVia(token, "fr-pm-09-t6-ec1", null)

        assertThat(resp.statusCode).isEqualTo(HttpStatus.CONFLICT)
        val body = resp.body as Map<*, *>
        assertThat(body["error"]).isEqualTo("group_name_conflict")
    }

    // ── EC6. 그룹 삭제 시 멤버십 CASCADE ────────────────────────────────────────────

    /**
     * EC6: 멤버가 있는 그룹을 삭제하면 group_memberships 행도 FK CASCADE 로 함께 제거된다.
     *
     * 삭제 후 멤버 조회는 그룹 자체가 없으므로 404 이고, DB 직접 조회로 멤버십 0 행을 확인한다.
     */
    @Test
    fun `EC6 그룹 삭제 시 멤버십 CASCADE — 멤버 0행`() {
        val token = loginJwt("fr_pm_09_admin", adminPassword)
        val groupId = createGroupId(token, "fr-pm-09-t6-ec6")
        addMemberVia(token, groupId, memberId)

        deleteGroupVia(token, groupId)

        val memberRows =
            jdbc.queryForObject(
                "SELECT COUNT(*) FROM group_memberships WHERE group_id = :gid",
                mapOf("gid" to groupId),
                Int::class.java,
            ) ?: -1
        assertThat(memberRows).isZero()
        // 그룹 멤버 조회는 404 (그룹 없음). 404 바디는 error 객체이므로 Map 으로 디코딩한다
        // (성공 경로 전용 listMembersVia(List 바디)는 객체→List 변환에 실패하므로 사용하지 않는다).
        val membersResp =
            restTemplate.exchange(
                url("/api/v1/groups/$groupId/members"),
                HttpMethod.GET,
                HttpEntity<Void>(authHeaders(token)),
                Map::class.java,
            )
        assertThat(membersResp.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
    }

    // ── private 헬퍼 — fixture ─────────────────────────────────────────────────────

    /** 그룹/멤버십/자격/사용자를 FK 순서대로 초기화한다. */
    private fun cleanTables() {
        jdbc.update("DELETE FROM group_memberships", emptyMap<String, Any>())
        jdbc.update(
            "DELETE FROM user_groups WHERE name LIKE :prefix",
            mapOf("prefix" to "fr-pm-09-t6-%"),
        )
        jdbc.update("DELETE FROM system_role_assignments", emptyMap<String, Any>())
        jdbc.update("DELETE FROM totp_secrets", emptyMap<String, Any>())
        jdbc.update("DELETE FROM local_credentials", emptyMap<String, Any>())
        jdbc.update(
            "DELETE FROM users WHERE username IN ('fr_pm_09_admin', 'fr_pm_09_member')",
            emptyMap<String, Any>(),
        )
    }

    /**
     * 사용자의 TOTP secret 을 ACTIVE 상태로 직접 시드한다(FR-MF-04 게이트 통과용).
     *
     * 실제 enable 흐름(setup→ACTIVE 전환) 후의 DB 상태를 그대로 재현한다 — 게이트는 끄지 않고 admin 을
     * enrolled 로 만들어 통과시킨다. secret 은 운영과 동일하게 [MfaSecretEncryptor] 로 암호화해 저장하므로
     * (평문 미저장, §1.1.1), login 2단계 검증에서 서버가 같은 키로 복호화해 코드를 대조할 수 있다.
     */
    private fun seedActiveMfa(userId: UUID) {
        jdbc.update(
            """
            INSERT INTO totp_secrets (user_id, secret_cipher, status, confirmed_at)
            VALUES (:uid, :cipher, 'ACTIVE', now())
            """.trimIndent(),
            mapOf("uid" to userId, "cipher" to mfaSecretEncryptor.encrypt(ADMIN_TOTP_SECRET)),
        )
    }

    /** 관리자 1명 + 비관리자 1명을 생성하고 로그인 자격을 시드한다. */
    private fun seedUsers() {
        val admin = userRepository.save("fr_pm_09_admin", "fr-pm-09-admin@example.com", "FR-PM-09 Admin")
        val member = userRepository.save("fr_pm_09_member", "fr-pm-09-member@example.com", "FR-PM-09 Member")
        adminId = admin.id
        memberId = member.id
        localCredentialService.store(adminId, adminPassword.toCharArray())
        localCredentialService.store(memberId, memberPassword.toCharArray())
    }

    // ── private 헬퍼 — HTTP ────────────────────────────────────────────────────────

    /** 절대 URL 을 구성한다 (RANDOM_PORT 바인딩). */
    private fun url(path: String): String = "http://localhost:$port$path"

    /**
     * POST /api/v1/auth/login 으로 JWT access_token 을 발급받는다.
     *
     * login 은 SecurityConfig 에서 CSRF skip 이므로 X-XSRF-TOKEN 불필요.
     *
     * MFA(ACTIVE TOTP) 사용자는 1단계 login 이 access_token 대신 `mfa_required` 챌린지를 반환하므로
     * (admin 은 게이트 정합 시드로 MFA enrolled), 그 경우 2단계 verify 까지 마쳐 정식 access_token 을 받는다.
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
        val loginBody = resp.body as Map<*, *>
        return if (loginBody["mfa_required"] == true) {
            completeMfaLogin(loginBody["mfa_challenge_token"] as String)
        } else {
            loginBody["access_token"] as String
        }
    }

    /**
     * MFA 2단계 — challenge 토큰 + 현재 코드로 verify 해 정식 access_token 을 받는다(게이트 통과 토큰).
     *
     * 코드는 시드된 [ADMIN_TOTP_SECRET] 으로 운영과 동일 파라미터(SHA1·6자리·30초)에서 산출한다.
     */
    private fun completeMfaLogin(challengeToken: String): String {
        val headers = HttpHeaders().apply { contentType = MediaType.APPLICATION_JSON }
        val body = """{"mfa_challenge_token":"$challengeToken","code":"${currentTotpCode(ADMIN_TOTP_SECRET)}"}"""
        val resp =
            restTemplate.exchange(
                url("/api/v1/auth/mfa/verify"),
                HttpMethod.POST,
                HttpEntity(body, headers),
                Map::class.java,
            )
        check(resp.statusCode == HttpStatus.OK) { "mfa verify 실패: ${resp.statusCode}" }
        return (resp.body as Map<*, *>)["access_token"] as String
    }

    /** 서버의 현재 시각 기준으로 [secret] 의 정답 TOTP 코드를 산출한다(운영과 동일 SHA1·6자리·30초). */
    private fun currentTotpCode(secret: String): String {
        val timeStep = Instant.now().epochSecond / TOTP_PERIOD_SECONDS
        return DefaultCodeGenerator(HashingAlgorithm.SHA1, TOTP_DIGITS).generate(secret, timeStep)
    }

    /** Authorization: Bearer + JSON Content-Type 헤더를 만든다. */
    private fun authHeaders(token: String): HttpHeaders =
        HttpHeaders().apply {
            contentType = MediaType.APPLICATION_JSON
            set(HttpHeaders.AUTHORIZATION, "Bearer $token")
        }

    /** POST /api/v1/groups — 그룹 생성 요청. */
    private fun createGroupVia(
        token: String,
        name: String,
        description: String?,
    ) = restTemplate
        .exchange(
            url("/api/v1/groups"),
            HttpMethod.POST,
            HttpEntity(createBody(name, description), authHeaders(token)),
            Map::class.java,
        )

    /** 그룹을 생성하고 그 식별자를 반환한다 (생성 성공을 단언). */
    private fun createGroupId(
        token: String,
        name: String,
    ): UUID {
        val resp = createGroupVia(token, name, null)
        check(resp.statusCode == HttpStatus.CREATED) { "createGroupId 실패: ${resp.statusCode} ${resp.body}" }
        return UUID.fromString((resp.body as Map<*, *>)["id"].toString())
    }

    /** PUT /api/v1/groups/{groupId}/members/{userId} — 멤버 추가. */
    private fun addMemberVia(
        token: String,
        groupId: UUID,
        userId: UUID,
    ) = restTemplate
        .exchange(
            url("/api/v1/groups/$groupId/members/$userId"),
            HttpMethod.PUT,
            HttpEntity<Void>(authHeaders(token)),
            Void::class.java,
        )

    /** DELETE /api/v1/groups/{groupId}/members/{userId} — 멤버 제거. */
    private fun removeMemberVia(
        token: String,
        groupId: UUID,
        userId: UUID,
    ) = restTemplate
        .exchange(
            url("/api/v1/groups/$groupId/members/$userId"),
            HttpMethod.DELETE,
            HttpEntity<Void>(authHeaders(token)),
            Void::class.java,
        )

    /** DELETE /api/v1/groups/{groupId} — 그룹 삭제. */
    private fun deleteGroupVia(
        token: String,
        groupId: UUID,
    ) = restTemplate
        .exchange(
            url("/api/v1/groups/$groupId"),
            HttpMethod.DELETE,
            HttpEntity<Void>(authHeaders(token)),
            Void::class.java,
        )

    /** GET /api/v1/groups/{groupId} — 단건 그룹 조회. */
    private fun getGroupVia(
        token: String,
        groupId: UUID,
    ) = restTemplate
        .exchange(
            url("/api/v1/groups/$groupId"),
            HttpMethod.GET,
            HttpEntity<Void>(authHeaders(token)),
            Map::class.java,
        )

    /** GET /api/v1/groups — 그룹 목록 조회(List 바디). */
    private fun listGroupsVia(token: String) =
        restTemplate.exchange(
            url("/api/v1/groups"),
            HttpMethod.GET,
            HttpEntity<Void>(authHeaders(token)),
            List::class.java,
        )

    /** GET /api/v1/groups/{groupId}/members — 멤버 목록 조회(List 바디). */
    private fun listMembersVia(
        token: String,
        groupId: UUID,
    ) = restTemplate
        .exchange(
            url("/api/v1/groups/$groupId/members"),
            HttpMethod.GET,
            HttpEntity<Void>(authHeaders(token)),
            List::class.java,
        )

    /** GET /api/v1/groups/{groupId}/members 의 멤버 식별자 문자열 목록을 반환한다. */
    private fun memberIds(
        token: String,
        groupId: UUID,
    ): List<String> {
        val resp = listMembersVia(token, groupId)
        check(resp.statusCode == HttpStatus.OK) { "memberIds 실패: ${resp.statusCode}" }
        @Suppress("UNCHECKED_CAST")
        val members = resp.body as List<Map<*, *>>
        return members.map { it["id"].toString() }
    }

    /** 그룹 생성 요청 바디 JSON 을 만든다 (description null 이면 키 생략). */
    private fun createBody(
        name: String,
        description: String?,
    ): String =
        if (description == null) {
            """{"name":"$name"}"""
        } else {
            """{"name":"$name","description":"$description"}"""
        }
}
