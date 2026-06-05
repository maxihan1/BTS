// V015 user_groups + group_memberships 마이그레이션 스키마 검증 통합테스트 — FR-PM-09 Task 2

package com.atlas.bts.identity.group

import com.atlas.bts.identity.provider.ldap.AutoProvisionService
import com.atlas.bts.identity.provider.ldap.ExternalAccountRepository
import com.atlas.bts.identity.provider.ldap.LdapProvider
import com.atlas.bts.identity.provider.ldap.LdapProviderConfigService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.dao.DataIntegrityViolationException
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
 * V015 (user_groups + group_memberships) 마이그레이션 스키마 검증 통합테스트 (FR-PM-09 Task 2).
 *
 * ## 목적
 * Flyway 마이그레이션이 prod 프로파일에서 실 PostgreSQL 16 위에 적용된 뒤, 다음 스키마 불변식을
 * 직접 INSERT/DELETE 로 검증한다.
 *
 * - **(a) 테이블 존재** — user_groups / group_memberships 두 테이블이 생성된다.
 * - **(b) name UNIQUE** — user_groups.name 중복 INSERT 시 무결성 예외가 발생한다.
 * - **(c) 그룹 삭제 CASCADE** — user_groups 행 삭제 시 그 그룹의 group_memberships 가 연쇄 삭제된다.
 * - **(d) 사용자 삭제 CASCADE** — users 행 삭제 시 그 사용자의 group_memberships 가 연쇄 삭제된다.
 *
 * ## Testcontainers 설계
 * SystemAdminInfraIntegrationTest 선례와 동일한 prod 부팅 셋업(static @Container +
 * @DynamicPropertySource + PEM 파일 + LDAP @MockBean 5종)을 복제한다. users 행은 V001 이
 * 자동 생성하지 않으므로 테스트에서 직접 INSERT 한다.
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
class UserGroupSchemaMigrationTest {
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

    @Autowired
    private lateinit var jdbc: NamedParameterJdbcTemplate

    // ── 픽스처 식별값 ──────────────────────────────────────────────────────────

    private val userAId: UUID = UUID.fromString("00000000-0909-0000-0000-000000000001")
    private val userBId: UUID = UUID.fromString("00000000-0909-0000-0000-000000000002")
    private val groupId: UUID = UUID.fromString("00000000-0909-0000-0000-0000000000a1")
    private val groupName: String = "fr-pm-09-test-group"

    @BeforeEach
    fun setUp() {
        // FK 순서 준수: group_memberships → user_groups / users 순으로 정리
        jdbc.update(
            "DELETE FROM group_memberships WHERE user_id IN (:ids)",
            mapOf("ids" to listOf(userAId, userBId)),
        )
        jdbc.update("DELETE FROM user_groups WHERE id = :id OR name = :name", mapOf("id" to groupId, "name" to groupName))
        jdbc.update("DELETE FROM users WHERE id IN (:ids)", mapOf("ids" to listOf(userAId, userBId)))

        seedUsers()
    }

    // ── (a) 테이블 존재 ──────────────────────────────────────────────────────────

    @Test
    fun `user_groups와 group_memberships 테이블이 존재한다`() {
        assertThat(tableExists("user_groups")).isTrue()
        assertThat(tableExists("group_memberships")).isTrue()
    }

    // ── (b) name UNIQUE ─────────────────────────────────────────────────────────

    @Test
    fun `user_groups name 중복 INSERT 시 무결성 예외가 발생한다`() {
        insertGroup(groupId, groupName)

        assertThatThrownBy {
            insertGroup(UUID.randomUUID(), groupName)
        }.isInstanceOf(DataIntegrityViolationException::class.java)
    }

    // ── (c) 그룹 삭제 CASCADE ────────────────────────────────────────────────────

    @Test
    fun `그룹 삭제 시 group_memberships가 CASCADE 삭제된다`() {
        insertGroup(groupId, groupName)
        insertMembership(groupId, userAId)
        insertMembership(groupId, userBId)
        assertThat(membershipCount(groupId)).isEqualTo(2)

        jdbc.update("DELETE FROM user_groups WHERE id = :id", mapOf("id" to groupId))

        assertThat(membershipCount(groupId)).isZero()
    }

    // ── (d) 사용자 삭제 CASCADE ──────────────────────────────────────────────────

    @Test
    fun `사용자 삭제 시 group_memberships가 CASCADE 삭제된다`() {
        insertGroup(groupId, groupName)
        insertMembership(groupId, userAId)
        insertMembership(groupId, userBId)

        jdbc.update("DELETE FROM users WHERE id = :id", mapOf("id" to userAId))

        assertThat(userMembershipCount(userAId)).isZero()
        // 다른 사용자(userB) 멤버십은 영향 없음
        assertThat(userMembershipCount(userBId)).isEqualTo(1)
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────

    private fun seedUsers() {
        listOf(
            Triple(userAId, "fr_pm_09_user_a", "FR-PM-09 User A"),
            Triple(userBId, "fr_pm_09_user_b", "FR-PM-09 User B"),
        ).forEach { (id, username, displayName) ->
            jdbc.update(
                "INSERT INTO users (id, username, display_name) VALUES (:id, :username, :displayName)",
                mapOf("id" to id, "username" to username, "displayName" to displayName),
            )
        }
    }

    private fun insertGroup(id: UUID, name: String) {
        jdbc.update(
            "INSERT INTO user_groups (id, name) VALUES (:id, :name)",
            mapOf("id" to id, "name" to name),
        )
    }

    private fun insertMembership(group: UUID, user: UUID) {
        jdbc.update(
            "INSERT INTO group_memberships (group_id, user_id) VALUES (:group, :user)",
            mapOf("group" to group, "user" to user),
        )
    }

    private fun membershipCount(group: UUID): Int =
        jdbc.queryForObject(
            "SELECT count(*) FROM group_memberships WHERE group_id = :group",
            mapOf("group" to group),
            Int::class.java,
        ) ?: 0

    private fun userMembershipCount(user: UUID): Int =
        jdbc.queryForObject(
            "SELECT count(*) FROM group_memberships WHERE user_id = :user",
            mapOf("user" to user),
            Int::class.java,
        ) ?: 0

    private fun tableExists(table: String): Boolean =
        jdbc.queryForObject(
            "SELECT to_regclass(:table) IS NOT NULL",
            mapOf("table" to "public.$table"),
            Boolean::class.java,
        ) ?: false
}
