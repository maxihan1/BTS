// JdbcUserGroupRepository 통합테스트 (prod 프로파일 + 실 PostgreSQL 16) — FR-PM-09 Task 3

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
import org.springframework.dao.DuplicateKeyException
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
 * [JdbcUserGroupRepository] 통합테스트 (FR-PM-09 Task 3).
 *
 * ## 목적
 * prod 프로파일에서 실 PostgreSQL 16(Testcontainers) 위에 V015 마이그레이션이 적용된 뒤,
 * [UserGroupRepository] 포트의 모든 연산을 raw SQL 구현으로 end-to-end 검증한다.
 *
 * - **create / findById** — RETURNING 으로 DB 가 채운 id/타임스탬프를 회수해 매핑한다.
 * - **findAll(memberCount)** — 스칼라 서브쿼리로 그룹별 멤버 수를 함께 반환한다(LEFT JOIN 금지).
 * - **update / delete** — 존재하는 행만 갱신/삭제하고, 없는 id 는 null/false 를 반환한다.
 * - **addMember 멱등** — 같은 멤버를 두 번 추가해도 예외 없이 멤버 1명만 유지된다(ON CONFLICT DO NOTHING).
 * - **removeMember 멱등** — 없는 멤버를 제거해도 no-op 이다.
 * - **listMemberIds / existsById** — 멤버 식별자 목록 / 그룹 존재 여부.
 * - **name UNIQUE** — 중복 이름 create 시 [DuplicateKeyException] 이 전파된다.
 *
 * ## Testcontainers 설계
 * 같은 패키지 [UserGroupSchemaMigrationTest] 와 동일한 prod 부팅 셋업(static @Container +
 * @DynamicPropertySource + PEM 파일 + LDAP @MockBean 5종)을 복제한다. users 행은 FK 충족을 위해
 * 테스트에서 직접 INSERT 한다.
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
class JdbcUserGroupRepositoryIntegrationTest {
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
    private lateinit var repository: UserGroupRepository

    @Autowired
    private lateinit var jdbc: NamedParameterJdbcTemplate

    // ── 픽스처 식별값 ──────────────────────────────────────────────────────────

    private val userAId: UUID = UUID.fromString("00000000-0909-0003-0000-000000000001")
    private val userBId: UUID = UUID.fromString("00000000-0909-0003-0000-000000000002")

    @BeforeEach
    fun setUp() {
        jdbc.update(
            "DELETE FROM group_memberships WHERE user_id IN (:ids)",
            mapOf("ids" to listOf(userAId, userBId)),
        )
        jdbc.update("DELETE FROM user_groups WHERE name LIKE :prefix", mapOf("prefix" to "fr-pm-09-t3-%"))
        jdbc.update("DELETE FROM users WHERE id IN (:ids)", mapOf("ids" to listOf(userAId, userBId)))
        seedUsers()
    }

    // ── create / findById ───────────────────────────────────────────────────────

    @Test
    fun `create는 id와 타임스탬프가 채워진 그룹을 반환하고 findById로 재조회된다`() {
        val created = repository.create("fr-pm-09-t3-create", "설명")

        assertThat(created.id).isNotNull()
        assertThat(created.name).isEqualTo("fr-pm-09-t3-create")
        assertThat(created.description).isEqualTo("설명")
        assertThat(created.createdAt).isNotNull()
        assertThat(created.updatedAt).isNotNull()

        val found = repository.findById(created.id!!)
        assertThat(found).isNotNull()
        assertThat(found!!.name).isEqualTo("fr-pm-09-t3-create")
        assertThat(found.description).isEqualTo("설명")
    }

    @Test
    fun `description이 null인 그룹도 생성된다`() {
        val created = repository.create("fr-pm-09-t3-null-desc", null)

        assertThat(created.description).isNull()
        assertThat(repository.findById(created.id!!)!!.description).isNull()
    }

    @Test
    fun `존재하지 않는 id 조회 시 null을 반환한다`() {
        assertThat(repository.findById(UUID.randomUUID())).isNull()
    }

    // ── findAll(memberCount) ──────────────────────────────────────────────────────

    @Test
    fun `findAll은 그룹별 멤버 수를 함께 반환한다`() {
        val g1 = repository.create("fr-pm-09-t3-all-1", null)
        val g2 = repository.create("fr-pm-09-t3-all-2", null)
        repository.addMember(g1.id!!, userAId)
        repository.addMember(g1.id!!, userBId)
        repository.addMember(g2.id!!, userAId)

        val all = repository.findAll().filter { it.group.name.startsWith("fr-pm-09-t3-all-") }
        val counts = all.associate { it.group.name to it.memberCount }

        assertThat(counts["fr-pm-09-t3-all-1"]).isEqualTo(2)
        assertThat(counts["fr-pm-09-t3-all-2"]).isEqualTo(1)
    }

    @Test
    fun `멤버가 없는 그룹은 memberCount가 0이다`() {
        repository.create("fr-pm-09-t3-empty", null)

        val empty = repository.findAll().first { it.group.name == "fr-pm-09-t3-empty" }
        assertThat(empty.memberCount).isZero()
    }

    // ── update / delete ───────────────────────────────────────────────────────────

    @Test
    fun `update는 이름과 설명을 갱신한 그룹을 반환한다`() {
        val created = repository.create("fr-pm-09-t3-upd", "old")

        val updated = repository.update(created.id!!, "fr-pm-09-t3-upd-new", "new")

        assertThat(updated).isNotNull()
        assertThat(updated!!.name).isEqualTo("fr-pm-09-t3-upd-new")
        assertThat(updated.description).isEqualTo("new")
        assertThat(repository.findById(created.id!!)!!.name).isEqualTo("fr-pm-09-t3-upd-new")
    }

    @Test
    fun `존재하지 않는 id update 시 null을 반환한다`() {
        assertThat(repository.update(UUID.randomUUID(), "x", null)).isNull()
    }

    @Test
    fun `delete는 존재하는 그룹 삭제 시 true, 없으면 false를 반환한다`() {
        val created = repository.create("fr-pm-09-t3-del", null)

        assertThat(repository.delete(created.id!!)).isTrue()
        assertThat(repository.findById(created.id!!)).isNull()
        assertThat(repository.delete(UUID.randomUUID())).isFalse()
    }

    // ── addMember 멱등 ────────────────────────────────────────────────────────────

    @Test
    fun `addMember는 같은 멤버를 두 번 추가해도 예외 없이 멤버 1명만 유지한다`() {
        val created = repository.create("fr-pm-09-t3-idem-add", null)

        repository.addMember(created.id!!, userAId)
        repository.addMember(created.id!!, userAId)

        assertThat(repository.listMemberIds(created.id!!)).containsExactly(userAId)
    }

    // ── removeMember 멱등 ─────────────────────────────────────────────────────────

    @Test
    fun `removeMember는 없는 멤버를 제거해도 no-op이다`() {
        val created = repository.create("fr-pm-09-t3-idem-remove", null)
        repository.addMember(created.id!!, userAId)

        repository.removeMember(created.id!!, userBId)
        assertThat(repository.listMemberIds(created.id!!)).containsExactly(userAId)

        repository.removeMember(created.id!!, userAId)
        assertThat(repository.listMemberIds(created.id!!)).isEmpty()
        // 두 번째 제거(이미 없음)도 예외 없이 no-op
        repository.removeMember(created.id!!, userAId)
        assertThat(repository.listMemberIds(created.id!!)).isEmpty()
    }

    // ── listMemberIds / existsById ────────────────────────────────────────────────

    @Test
    fun `listMemberIds는 그룹의 모든 멤버 식별자를 반환한다`() {
        val created = repository.create("fr-pm-09-t3-list", null)
        repository.addMember(created.id!!, userAId)
        repository.addMember(created.id!!, userBId)

        assertThat(repository.listMemberIds(created.id!!)).containsExactlyInAnyOrder(userAId, userBId)
    }

    @Test
    fun `existsById는 그룹 존재 여부를 반환한다`() {
        val created = repository.create("fr-pm-09-t3-exists", null)

        assertThat(repository.existsById(created.id!!)).isTrue()
        assertThat(repository.existsById(UUID.randomUUID())).isFalse()
    }

    // ── name UNIQUE ───────────────────────────────────────────────────────────────

    @Test
    fun `중복 이름 create 시 DuplicateKeyException이 전파된다`() {
        repository.create("fr-pm-09-t3-dup", null)

        assertThatThrownBy {
            repository.create("fr-pm-09-t3-dup", null)
        }.isInstanceOf(DuplicateKeyException::class.java)
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────────

    private fun seedUsers() {
        listOf(
            Triple(userAId, "fr_pm_09_t3_user_a", "FR-PM-09 T3 User A"),
            Triple(userBId, "fr_pm_09_t3_user_b", "FR-PM-09 T3 User B"),
        ).forEach { (id, username, displayName) ->
            jdbc.update(
                "INSERT INTO users (id, username, display_name) VALUES (:id, :username, :displayName)",
                mapOf("id" to id, "username" to username, "displayName" to displayName),
            )
        }
    }
}
