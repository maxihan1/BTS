// 전역 SYSTEM_ADMIN 인프라 end-to-end 통합테스트 — prod 프로파일 실 wire 검증 (FR-PM-08 Task 6)

package com.atlas.bts.identity.systemrole

import com.atlas.bts.identity.jwt.JwtIssuer
import com.atlas.bts.identity.permission.IdentityAccessSystemPermissionResolver
import com.atlas.bts.identity.provider.ldap.AutoProvisionService
import com.atlas.bts.identity.provider.ldap.ExternalAccountRepository
import com.atlas.bts.identity.provider.ldap.LdapProvider
import com.atlas.bts.identity.provider.ldap.LdapProviderConfigService
import com.bts.shared.permission.SystemPermissionResolver
import com.nimbusds.jwt.SignedJWT
import org.assertj.core.api.Assertions.assertThat
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
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
 * 전역 SYSTEM_ADMIN 인프라 end-to-end 통합테스트 (FR-PM-08 Task 6).
 *
 * ## 목적
 * Task 1~5에서 구현한 전역 시스템 역할 인프라가 prod 프로파일에서 실 컴포넌트
 * (mock 없이)로 조립·동작하는지 끝에서 끝까지 검증한다.
 *
 * - **S4 (판정)**: 실 [JdbcSystemRoleAssignmentRepository] + [IdentityAccessSystemPermissionResolver]
 *   wire 위에서 SYSTEM_ADMIN 부여 사용자는 [SystemPermissionResolver.isSystemAdmin]=`true`,
 *   일반 사용자는 `false`. (메모리 교훈: resolver 는 실 wire 로 end-to-end 검증 — mock 금지.)
 * - **S3 (토큰 클레임)**: 실 [JwtIssuer] 로 SYSTEM_ADMIN 사용자 토큰을 발급하면 디코드 시
 *   `roles` claim 이 `["SYSTEM_ADMIN"]` 를 포함한다. (converter authority 변환은 Task 4 단위검증
 *   완료 — 여기서는 발급 클레임까지만 검증해 HTTP 왕복 중복을 피한다.)
 * - **NFR5 (프로파일 안전)**: 이 통합테스트가 `@ActiveProfiles("prod")` 컨텍스트 부팅에
 *   성공하는 것 자체가 prod 빈 조립 검증이다. non-prod 안전은 기존 모듈 통합테스트들이
 *   non-prod 로 이미 부팅하므로(Task 1~5 통과) 추가로 검증하지 않는다.
 *
 * ## Testcontainers 설계
 * companion object static @Container + @DynamicPropertySource + prod PEM 파일 생성.
 * IdentityAccessIssuePermissionResolverIntegrationTest 선례와 동일한 부팅 셋업을 따른다.
 *
 * @see IdentityAccessSystemPermissionResolver
 * @see JdbcSystemRoleAssignmentRepository
 * @see JwtIssuer
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = [
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration",
    ],
)
@ActiveProfiles("prod")
@Testcontainers
class SystemAdminInfraIntegrationTest {
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
            // LDAP 자동설정이 URL 을 요구하므로 placeholder 제공
            registry.add("spring.ldap.urls") { "ldap://localhost:389" }
            registry.add("spring.ldap.base") { "dc=bts,dc=local" }
            // prod 프로파일에서 PemFileKeyProvider 가 PEM 파일 경로를 @Value 로 요구한다.
            registry.add("bts.auth.jwt.private-key-pem-path") { pemFilePath }
        }

        /**
         * 테스트용 임시 RSA 2048 PEM 파일 경로.
         *
         * PemFileKeyProvider 가 BouncyCastle PEMParser 를 사용하므로 BC provider 를 먼저 등록한다.
         * PKCS#8(PRIVATE KEY) 형식으로 PEM 파일을 생성한다.
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

    // LDAP Bean 목킹 — 실제 LDAP 서버 없이 컨텍스트 부팅 (선례 동일)
    @MockBean lateinit var ldapProvider: LdapProvider

    @MockBean lateinit var ldapProviderConfigService: LdapProviderConfigService

    @MockBean lateinit var externalAccountRepository: ExternalAccountRepository

    @MockBean lateinit var autoProvisionService: AutoProvisionService

    @MockBean lateinit var ldapTemplate: LdapTemplate

    /** 실 [IdentityAccessSystemPermissionResolver] 가 prod 컨텍스트에 주입되어야 한다(mock 아님). */
    @Autowired
    private lateinit var systemPermissionResolver: SystemPermissionResolver

    /** 실 [JdbcSystemRoleAssignmentRepository] 가 주입되어야 한다(mock 아님). */
    @Autowired
    private lateinit var roleAssignmentRepository: SystemRoleAssignmentRepository

    /** 실 [JwtIssuer] — S3 토큰 발급 클레임 검증용. */
    @Autowired
    private lateinit var jwtIssuer: JwtIssuer

    @Autowired
    private lateinit var jdbc: NamedParameterJdbcTemplate

    // ── 픽스처 식별값 ──────────────────────────────────────────────────────────

    private val adminId: UUID = UUID.fromString("00000000-aaaa-0000-0000-000000000001")
    private val normalUserId: UUID = UUID.fromString("00000000-bbbb-0000-0000-000000000001")

    @BeforeEach
    fun setUp() {
        // FK 순서 준수: system_role_assignments → 보조 테이블 → users 순으로 삭제
        jdbc.update(
            "DELETE FROM system_role_assignments WHERE user_id IN (:ids)",
            mapOf("ids" to listOf(adminId, normalUserId)),
        )
        jdbc.update("DELETE FROM users WHERE id IN (:ids)", mapOf("ids" to listOf(adminId, normalUserId)))

        seedUsers()
        // adminId 에게만 전역 SYSTEM_ADMIN 부여 (실 repository 경유 — 직접 INSERT 아님)
        roleAssignmentRepository.assign(adminId, SystemRole.SYSTEM_ADMIN)
    }

    // ── Bean 배타 검증 (NFR5 보조) ───────────────────────────────────────────────

    /**
     * prod 프로파일에서 [SystemPermissionResolver] 가 실 [IdentityAccessSystemPermissionResolver] 로
     * 주입된다. 이 판정기는 @Profile 분리가 없으므로(AlwaysAllow stub 없음) 모든 프로파일에서 동일하다.
     */
    @Test
    fun `prod 프로파일에서 IdentityAccessSystemPermissionResolver가 SystemPermissionResolver로 주입된다`() {
        assertThat(systemPermissionResolver).isInstanceOf(IdentityAccessSystemPermissionResolver::class.java)
    }

    // ── S4: 전역 판정 (실 wire) ─────────────────────────────────────────────────

    @Test
    fun `S4 — SYSTEM_ADMIN 부여 사용자는 isSystemAdmin이 true`() {
        assertThat(systemPermissionResolver.isSystemAdmin(adminId)).isTrue()
    }

    @Test
    fun `S4 — 일반 사용자는 isSystemAdmin이 false (deny-by-default)`() {
        assertThat(systemPermissionResolver.isSystemAdmin(normalUserId)).isFalse()
    }

    // ── S3: 토큰 roles claim (실 JwtIssuer) ───────────────────────────────────────

    @Test
    fun `S3 — SYSTEM_ADMIN 사용자 토큰은 roles claim에 SYSTEM_ADMIN을 포함한다`() {
        // repository 에서 조회한 실제 역할을 그대로 claim 으로 전달 (로그인 왕복의 핵심 경로)
        val roles = roleAssignmentRepository.findRolesByUser(adminId).map { it.name }
        val token =
            jwtIssuer.issue(
                userId = adminId,
                sessionId = UUID.randomUUID(),
                providerId = "local",
                scopes = listOf("issues:read"),
                roles = roles,
            )

        val claims = SignedJWT.parse(token).jwtClaimsSet

        @Suppress("UNCHECKED_CAST")
        val claimRoles = claims.getListClaim("roles") as List<String>
        assertThat(claimRoles).containsExactly("SYSTEM_ADMIN")
    }

    @Test
    fun `S3 — 일반 사용자 토큰은 roles claim이 없다`() {
        val roles = roleAssignmentRepository.findRolesByUser(normalUserId).map { it.name }
        val token =
            jwtIssuer.issue(
                userId = normalUserId,
                sessionId = UUID.randomUUID(),
                providerId = "local",
                scopes = listOf("issues:read"),
                roles = roles,
            )

        val claims = SignedJWT.parse(token).jwtClaimsSet

        assertThat(claims.getListClaim("roles")).isNull()
    }

    // ── 픽스처 헬퍼 ───────────────────────────────────────────────────────────

    /** 테스트 사용자 2명을 삽입한다 (system_role_assignments.user_id FK 충족). */
    private fun seedUsers() {
        listOf(
            Triple(adminId, "infra_admin", "Infra Admin"),
            Triple(normalUserId, "infra_user", "Infra User"),
        ).forEach { (id, username, displayName) ->
            jdbc.update(
                "INSERT INTO users (id, username, display_name) VALUES (:id, :username, :displayName)",
                mapOf("id" to id, "username" to username, "displayName" to displayName),
            )
        }
    }
}
