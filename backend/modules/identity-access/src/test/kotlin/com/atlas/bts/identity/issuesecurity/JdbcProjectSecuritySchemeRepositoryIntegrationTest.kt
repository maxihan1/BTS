// JdbcProjectSecuritySchemeRepository 통합테스트 (prod 프로파일 + 실 PostgreSQL 16) — FR-PM-06 Task 4

package com.atlas.bts.identity.issuesecurity

import com.atlas.bts.identity.provider.ldap.AutoProvisionService
import com.atlas.bts.identity.provider.ldap.ExternalAccountRepository
import com.atlas.bts.identity.provider.ldap.LdapProvider
import com.atlas.bts.identity.provider.ldap.LdapProviderConfigService
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
 * [JdbcProjectSecuritySchemeRepository] 통합테스트 (FR-PM-06 Task 4).
 *
 * ## 목적
 * prod 프로파일에서 실 PostgreSQL 16(Testcontainers) 위에 V016 마이그레이션이 적용된 뒤,
 * [ProjectSecuritySchemeRepository] 포트의 프로젝트-스킴 적용 연산을 raw SQL 구현으로 검증한다.
 *
 * - **assign** — `INSERT ... ON CONFLICT (project_id) DO UPDATE` 로 프로젝트당 스킴 0~1개를
 *   보장하며, 같은 프로젝트에 다른 스킴을 다시 적용하면 덮어쓴다(교체).
 * - **findByProject** — 적용된 scheme_id 를 반환하고, 미적용 프로젝트는 null 을 반환한다.
 * - **unassign** — 적용을 해제하며, 미적용 프로젝트에 호출해도 예외 없이 멱등하다.
 *
 * ## Testcontainers 설계
 * 같은 모듈 [com.atlas.bts.identity.group.JdbcUserGroupRepositoryIntegrationTest] 와 동일한
 * prod 부팅 셋업(static @Container + @DynamicPropertySource + PEM 파일 + LDAP @MockBean 5종)을
 * 복제한다. project_id 는 cross-BC 참조라 FK 가 없으므로 임의 UUID 를 사용한다. scheme_id 는
 * issue_security_schemes 에 FK(ON DELETE RESTRICT)가 걸려 있어, setUp 에서 스킴을 직접 INSERT 한다.
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
class JdbcProjectSecuritySchemeRepositoryIntegrationTest {
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
    private lateinit var repository: ProjectSecuritySchemeRepository

    @Autowired
    private lateinit var jdbc: NamedParameterJdbcTemplate

    // ── 픽스처 식별값 ──────────────────────────────────────────────────────────

    private val projectAId: UUID = UUID.fromString("00000000-0606-0004-0000-000000000001")
    private val projectBId: UUID = UUID.fromString("00000000-0606-0004-0000-000000000002")
    private val schemeXId: UUID = UUID.fromString("00000000-0606-0004-aaaa-000000000001")
    private val schemeYId: UUID = UUID.fromString("00000000-0606-0004-aaaa-000000000002")

    @BeforeEach
    fun setUp() {
        jdbc.update(
            "DELETE FROM project_issue_security_schemes WHERE project_id IN (:ids)",
            mapOf("ids" to listOf(projectAId, projectBId)),
        )
        jdbc.update(
            "DELETE FROM issue_security_schemes WHERE id IN (:ids)",
            mapOf("ids" to listOf(schemeXId, schemeYId)),
        )
        seedScheme(schemeXId, "fr-pm-06-t4-scheme-x")
        seedScheme(schemeYId, "fr-pm-06-t4-scheme-y")
    }

    // ── assign ────────────────────────────────────────────────────────────────────

    @Test
    fun `assign은 프로젝트에 스킴을 적용하고 findByProject로 재조회된다`() {
        repository.assign(projectAId, schemeXId)

        assertThat(repository.findByProject(projectAId)).isEqualTo(schemeXId)
    }

    @Test
    fun `assign은 같은 프로젝트에 다른 스킴을 다시 적용하면 덮어쓴다`() {
        repository.assign(projectAId, schemeXId)
        repository.assign(projectAId, schemeYId)

        assertThat(repository.findByProject(projectAId)).isEqualTo(schemeYId)
    }

    @Test
    fun `같은 스킴을 같은 프로젝트에 두 번 적용해도 예외 없이 유지된다`() {
        repository.assign(projectAId, schemeXId)
        repository.assign(projectAId, schemeXId)

        assertThat(repository.findByProject(projectAId)).isEqualTo(schemeXId)
    }

    @Test
    fun `여러 프로젝트가 서로 다른 스킴을 독립적으로 적용한다`() {
        repository.assign(projectAId, schemeXId)
        repository.assign(projectBId, schemeYId)

        assertThat(repository.findByProject(projectAId)).isEqualTo(schemeXId)
        assertThat(repository.findByProject(projectBId)).isEqualTo(schemeYId)
    }

    // ── findByProject ─────────────────────────────────────────────────────────────

    @Test
    fun `미적용 프로젝트 findByProject는 null을 반환한다`() {
        assertThat(repository.findByProject(projectAId)).isNull()
    }

    // ── unassign ──────────────────────────────────────────────────────────────────

    @Test
    fun `unassign은 적용을 해제한다`() {
        repository.assign(projectAId, schemeXId)

        repository.unassign(projectAId)

        assertThat(repository.findByProject(projectAId)).isNull()
    }

    @Test
    fun `unassign은 미적용 프로젝트에 호출해도 예외 없이 멱등하다`() {
        repository.unassign(projectAId)

        assertThat(repository.findByProject(projectAId)).isNull()
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────────

    private fun seedScheme(
        id: UUID,
        name: String,
    ) {
        jdbc.update(
            "INSERT INTO issue_security_schemes (id, name) VALUES (:id, :name)",
            mapOf("id" to id, "name" to name),
        )
    }
}
