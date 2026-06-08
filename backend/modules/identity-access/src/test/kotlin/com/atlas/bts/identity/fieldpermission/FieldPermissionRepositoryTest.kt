// FieldPermissionRepository 통합테스트 (prod 프로파일 + 실 PostgreSQL 16) — FR-PM-07 PR-A Task 3

package com.atlas.bts.identity.fieldpermission

import com.atlas.bts.identity.fieldpermission.domain.FieldAccessLevel
import com.atlas.bts.identity.fieldpermission.domain.FieldPermission
import com.atlas.bts.identity.fieldpermission.repository.FieldPermissionRepository
import com.atlas.bts.identity.provider.ldap.AutoProvisionService
import com.atlas.bts.identity.provider.ldap.ExternalAccountRepository
import com.atlas.bts.identity.provider.ldap.LdapProvider
import com.atlas.bts.identity.provider.ldap.LdapProviderConfigService
import com.bts.shared.permission.FieldKind
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
 * [FieldPermissionRepository] 통합테스트 (FR-PM-07 PR-A Task 3).
 *
 * ## 목적
 * prod 프로파일에서 실 PostgreSQL 16(Testcontainers) 위에 V018 마이그레이션이 적용된 뒤,
 * `field_permissions` 테이블 접근을 raw SQL 구현으로 end-to-end 검증한다.
 *
 * - **save / findByProject** — 신규 규칙을 저장하고 프로젝트 단위로 재조회한다.
 * - **save 멱등** — 동일 `(project, kind, key, group, access)` 재저장 시 ON CONFLICT DO NOTHING(중복 행 없음).
 * - **findByProject** — 프로젝트의 모든 규칙(필드별·그룹별 행)을 반환한다.
 * - **deleteById** — 규칙 단건 삭제.
 * - **group CASCADE** — `user_groups` 행 삭제 시 참조하는 `field_permissions` 행이 FK ON DELETE CASCADE 로 동반 삭제된다.
 *
 * ## Testcontainers 설계
 * 같은 BC [com.atlas.bts.identity.group.JdbcUserGroupRepositoryIntegrationTest] 와 동일한 prod
 * 부팅 셋업(static @Container + @DynamicPropertySource + PEM 파일 + LDAP @MockBean 5종)을 복제한다.
 * FK 충족을 위해 `user_groups` 행은 테스트에서 직접 INSERT 한다.
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
class FieldPermissionRepositoryTest {
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
    private lateinit var repository: FieldPermissionRepository

    @Autowired
    private lateinit var jdbc: NamedParameterJdbcTemplate

    // ── 픽스처 식별값 ──────────────────────────────────────────────────────────

    private val projectId: UUID = UUID.fromString("00000000-0707-0003-0000-000000000001")
    private val otherProjectId: UUID = UUID.fromString("00000000-0707-0003-0000-000000000002")
    private val groupAId: UUID = UUID.fromString("00000000-0707-0003-0000-00000000000a")
    private val groupBId: UUID = UUID.fromString("00000000-0707-0003-0000-00000000000b")

    @BeforeEach
    fun setUp() {
        jdbc.update(
            "DELETE FROM field_permissions WHERE project_id IN (:ids)",
            mapOf("ids" to listOf(projectId, otherProjectId)),
        )
        jdbc.update("DELETE FROM user_groups WHERE id IN (:ids)", mapOf("ids" to listOf(groupAId, groupBId)))
        seedGroups()
    }

    // ── save / findByProject ──────────────────────────────────────────────────────

    @Test
    fun `save는 신규 규칙을 저장하고 findByProject로 재조회된다`() {
        repository.save(FieldPermission.create(projectId, FieldKind.CORE, "summary", groupAId, FieldAccessLevel.VIEW))

        val rules = repository.findByProject(projectId)
        assertThat(rules).hasSize(1)
        val saved = rules.first()
        assertThat(saved.id).isNotNull()
        assertThat(saved.projectId).isEqualTo(projectId)
        assertThat(saved.fieldKind).isEqualTo(FieldKind.CORE)
        assertThat(saved.fieldKey).isEqualTo("summary")
        assertThat(saved.groupId).isEqualTo(groupAId)
        assertThat(saved.accessLevel).isEqualTo(FieldAccessLevel.VIEW)
    }

    @Test
    fun `CUSTOM 종류와 EDIT 수준 규칙도 저장된다`() {
        repository.save(FieldPermission.create(projectId, FieldKind.CUSTOM, "cf_42", groupBId, FieldAccessLevel.EDIT))

        val saved = repository.findByProject(projectId).single()
        assertThat(saved.fieldKind).isEqualTo(FieldKind.CUSTOM)
        assertThat(saved.fieldKey).isEqualTo("cf_42")
        assertThat(saved.accessLevel).isEqualTo(FieldAccessLevel.EDIT)
    }

    // ── save 멱등 ──────────────────────────────────────────────────────────────────

    @Test
    fun `save는 동일 규칙 재저장 시 중복 행을 만들지 않는다`() {
        val rule = FieldPermission.create(projectId, FieldKind.CORE, "priority", groupAId, FieldAccessLevel.EDIT)

        repository.save(rule)
        repository.save(rule)

        assertThat(repository.findByProject(projectId)).hasSize(1)
    }

    @Test
    fun `access_level만 다른 규칙은 별도 행으로 저장된다`() {
        repository.save(FieldPermission.create(projectId, FieldKind.CORE, "summary", groupAId, FieldAccessLevel.VIEW))
        repository.save(FieldPermission.create(projectId, FieldKind.CORE, "summary", groupAId, FieldAccessLevel.EDIT))

        assertThat(repository.findByProject(projectId)).hasSize(2)
    }

    // ── findByProject 스코프 ────────────────────────────────────────────────────────

    @Test
    fun `findByProject는 해당 프로젝트의 모든 필드별 그룹별 규칙을 반환한다`() {
        repository.save(FieldPermission.create(projectId, FieldKind.CORE, "summary", groupAId, FieldAccessLevel.VIEW))
        repository.save(FieldPermission.create(projectId, FieldKind.CORE, "priority", groupBId, FieldAccessLevel.EDIT))
        repository.save(FieldPermission.create(projectId, FieldKind.CUSTOM, "cf_1", groupAId, FieldAccessLevel.VIEW))
        // 다른 프로젝트 규칙은 섞여 나오면 안 된다.
        repository.save(
            FieldPermission.create(otherProjectId, FieldKind.CORE, "summary", groupAId, FieldAccessLevel.VIEW),
        )

        val rules = repository.findByProject(projectId)
        assertThat(rules).hasSize(3)
        assertThat(rules.map { it.fieldKey }).containsExactlyInAnyOrder("summary", "priority", "cf_1")
    }

    @Test
    fun `규칙이 없는 프로젝트는 빈 목록을 반환한다`() {
        assertThat(repository.findByProject(UUID.randomUUID())).isEmpty()
    }

    // ── deleteById ──────────────────────────────────────────────────────────────────

    @Test
    fun `deleteById는 존재하는 규칙 삭제 시 true, 없으면 false를 반환한다`() {
        repository.save(FieldPermission.create(projectId, FieldKind.CORE, "summary", groupAId, FieldAccessLevel.VIEW))
        val id = repository.findByProject(projectId).single().id!!

        assertThat(repository.deleteById(id)).isTrue()
        assertThat(repository.findByProject(projectId)).isEmpty()
        assertThat(repository.deleteById(UUID.randomUUID())).isFalse()
    }

    // ── group 삭제 시 CASCADE ───────────────────────────────────────────────────────

    @Test
    fun `user_groups 행 삭제 시 참조하는 field_permissions 규칙이 CASCADE로 동반 삭제된다`() {
        repository.save(FieldPermission.create(projectId, FieldKind.CORE, "summary", groupAId, FieldAccessLevel.VIEW))
        repository.save(FieldPermission.create(projectId, FieldKind.CORE, "priority", groupBId, FieldAccessLevel.EDIT))
        assertThat(repository.findByProject(projectId)).hasSize(2)

        jdbc.update("DELETE FROM user_groups WHERE id = :id", mapOf("id" to groupAId))

        val remaining = repository.findByProject(projectId)
        assertThat(remaining).hasSize(1)
        assertThat(remaining.single().groupId).isEqualTo(groupBId)
    }

    // ── 헬퍼 ────────────────────────────────────────────────────────────────────────

    private fun seedGroups() {
        listOf(
            groupAId to "fr-pm-07-t3-group-a",
            groupBId to "fr-pm-07-t3-group-b",
        ).forEach { (id, name) ->
            jdbc.update(
                "INSERT INTO user_groups (id, name) VALUES (:id, :name)",
                mapOf("id" to id, "name" to name),
            )
        }
    }
}
