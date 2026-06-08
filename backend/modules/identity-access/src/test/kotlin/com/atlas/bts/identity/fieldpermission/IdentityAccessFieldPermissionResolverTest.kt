// 필드 권한 prod 판정기 전수 통합테스트 — 실 DB + V018 + opt-in/그룹 OR/EDIT⊃VIEW/관리자우회없음 (FR-PM-07 PR-A Task 4)

package com.atlas.bts.identity.fieldpermission

import com.atlas.bts.identity.fieldpermission.domain.FieldAccessLevel
import com.atlas.bts.identity.fieldpermission.domain.FieldPermission
import com.atlas.bts.identity.fieldpermission.repository.FieldPermissionRepository
import com.atlas.bts.identity.provider.ldap.AutoProvisionService
import com.atlas.bts.identity.provider.ldap.ExternalAccountRepository
import com.atlas.bts.identity.provider.ldap.LdapProvider
import com.atlas.bts.identity.provider.ldap.LdapProviderConfigService
import com.bts.shared.permission.FieldKind
import com.bts.shared.permission.FieldPermissionResolver
import com.bts.shared.permission.FieldRef
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
 * [IdentityAccessFieldPermissionResolver] 전수 통합테스트 (FR-PM-07 PR-A Task 4).
 *
 * ## 목적
 * prod 프로파일에서 실 PostgreSQL 16(Testcontainers) 위에 V018 마이그레이션 적용 후,
 * 필드 수준 권한 판정 포트([FieldPermissionResolver])의 prod 구현을 ground-truth로 검증한다.
 *
 * ## 검증 시나리오 (spec §6 포트 계약)
 * - (a) **opt-in (EC1)** — 규칙이 없는 필드는 항상 visible/editable.
 * - (b) **VIEW 그룹 게이트 (S1/S2)** — VIEW 규칙이 있으면 그룹 멤버만 visible, 비멤버는 제외.
 * - (c) **EDIT ⊃ VIEW (S7/EC3)** — EDIT 규칙만 있어도 visible·editable 둘 다 허용.
 * - (d) **관리자 우회 없음 (S5/EC10)** — SYSTEM_ADMIN/PROJECT_ADMIN이라도 그룹 비멤버면 제외.
 *   resolver는 isSystemAdmin/role을 절대 참조하지 않으며, 그룹 멤버십만이 유일한 통과 수단이다.
 * - (e) **다중 그룹 OR (EC2)** — actor가 여러 그룹에 속할 때 그중 하나라도 VIEW면 visible.
 *
 * ## Testcontainers 설계
 * 같은 BC [FieldPermissionRepositoryTest] / [IdentityAccessCustomFieldPermissionResolverTest] 와
 * 동일한 prod 부팅 셋업(static @Container + @DynamicPropertySource + PEM 파일 + LDAP @MockBean 5종)을 복제한다.
 * FK 충족을 위해 `users` / `user_groups` / `group_memberships` 행은 테스트에서 직접 INSERT 한다.
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
class IdentityAccessFieldPermissionResolverTest {
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

    /** prod 프로파일에서 IdentityAccessFieldPermissionResolver 가 FieldPermissionResolver 로 주입되어야 한다. */
    @Autowired
    private lateinit var resolver: FieldPermissionResolver

    @Autowired
    private lateinit var fieldPermissionRepo: FieldPermissionRepository

    @Autowired
    private lateinit var jdbc: NamedParameterJdbcTemplate

    // ── 픽스처 식별값 ──────────────────────────────────────────────────────────

    private val projectId: UUID = UUID.fromString("00000000-0707-0004-0000-000000000001")

    // 그룹
    private val groupViewerId: UUID = UUID.fromString("00000000-0707-0004-0000-0000000000a1")
    private val groupEditorId: UUID = UUID.fromString("00000000-0707-0004-0000-0000000000a2")
    private val groupOtherId: UUID = UUID.fromString("00000000-0707-0004-0000-0000000000a3")

    // 사용자
    /** groupViewer 멤버. */
    private val memberId: UUID = UUID.fromString("00000000-0707-0004-0000-0000000000b1")

    /** 어떤 그룹에도 속하지 않음. */
    private val nonMemberId: UUID = UUID.fromString("00000000-0707-0004-0000-0000000000b2")

    /** PROJECT_ADMIN 역할이지만 어떤 그룹에도 속하지 않음 — 관리자 우회 없음 검증용. */
    private val adminNonMemberId: UUID = UUID.fromString("00000000-0707-0004-0000-0000000000b3")

    /** groupEditor 멤버. */
    private val editorId: UUID = UUID.fromString("00000000-0707-0004-0000-0000000000b4")

    /** groupViewer + groupOther 둘 다 소속 — 다중 그룹 OR 검증용. */
    private val multiGroupId: UUID = UUID.fromString("00000000-0707-0004-0000-0000000000b5")

    // 필드 참조
    private val summary = FieldRef(FieldKind.CORE, "summary")
    private val priority = FieldRef(FieldKind.CORE, "priority")
    private val description = FieldRef(FieldKind.CORE, "description")

    @BeforeEach
    fun setUp() {
        cleanTestData()
        seedUsers()
        seedGroups()
        seedMemberships()
    }

    // ── Bean 배타 검증 ─────────────────────────────────────────────────────────

    @Test
    fun `prod 프로파일에서 IdentityAccessFieldPermissionResolver가 FieldPermissionResolver로 주입된다`() {
        assertThat(resolver).isInstanceOf(IdentityAccessFieldPermissionResolver::class.java)
    }

    // ── (a) opt-in: 규칙 없는 필드는 항상 visible/editable (EC1) ───────────────────

    @Test
    fun `규칙이 없는 필드는 모든 actor에게 항상 visible하고 editable하다`() {
        // 규칙 0건. summary/priority 둘 다 제한 없음.
        val candidates = setOf(summary, priority)

        assertThat(resolver.visibleFields(nonMemberId, projectId, candidates)).containsExactlyInAnyOrder(summary, priority)
        assertThat(resolver.editableFields(nonMemberId, projectId, candidates)).containsExactlyInAnyOrder(summary, priority)
    }

    // ── (b) VIEW 그룹 게이트: 멤버만 visible, 비멤버 제외 (S1/S2) ───────────────────

    @Test
    fun `VIEW 규칙이 있으면 그룹 멤버만 visible하고 비멤버는 제외된다`() {
        // summary 에 groupViewer VIEW 규칙 → summary 는 제한 대상.
        fieldPermissionRepo.save(
            FieldPermission.create(projectId, FieldKind.CORE, "summary", groupViewerId, FieldAccessLevel.VIEW),
        )
        val candidates = setOf(summary)

        // 멤버는 visible.
        assertThat(resolver.visibleFields(memberId, projectId, candidates)).containsExactly(summary)
        // 비멤버는 제외.
        assertThat(resolver.visibleFields(nonMemberId, projectId, candidates)).isEmpty()
    }

    @Test
    fun `VIEW 규칙만 있는 필드는 멤버라도 editable하지 않다`() {
        fieldPermissionRepo.save(
            FieldPermission.create(projectId, FieldKind.CORE, "summary", groupViewerId, FieldAccessLevel.VIEW),
        )
        val candidates = setOf(summary)

        // VIEW 만 있으므로 visible 은 되지만 editable 은 아니다.
        assertThat(resolver.visibleFields(memberId, projectId, candidates)).containsExactly(summary)
        assertThat(resolver.editableFields(memberId, projectId, candidates)).isEmpty()
    }

    // ── (c) EDIT ⊃ VIEW: EDIT 행만 있어도 visible·editable 둘 다 (S7/EC3) ─────────

    @Test
    fun `EDIT 규칙만 있어도 그룹 멤버는 visible하고 editable하다`() {
        // priority 에 groupEditor EDIT 규칙(VIEW 규칙 없음).
        fieldPermissionRepo.save(
            FieldPermission.create(projectId, FieldKind.CORE, "priority", groupEditorId, FieldAccessLevel.EDIT),
        )
        val candidates = setOf(priority)

        // editor 는 EDIT⊃VIEW 로 visible·editable 둘 다.
        assertThat(resolver.visibleFields(editorId, projectId, candidates)).containsExactly(priority)
        assertThat(resolver.editableFields(editorId, projectId, candidates)).containsExactly(priority)
        // groupEditor 비멤버는 visible/editable 모두 제외.
        assertThat(resolver.visibleFields(memberId, projectId, candidates)).isEmpty()
        assertThat(resolver.editableFields(memberId, projectId, candidates)).isEmpty()
    }

    // ── (d) 관리자 우회 없음: PROJECT_ADMIN 이라도 그룹 비멤버면 제외 (S5/EC10) ──────

    @Test
    fun `PROJECT_ADMIN 이라도 그룹 비멤버면 제한 필드에서 제외된다 (관리자 우회 없음)`() {
        // adminNonMemberId 는 project_memberships 에 PROJECT_ADMIN 으로 시드되었으나 어떤 그룹에도 속하지 않는다.
        fieldPermissionRepo.save(
            FieldPermission.create(projectId, FieldKind.CORE, "summary", groupViewerId, FieldAccessLevel.VIEW),
        )
        fieldPermissionRepo.save(
            FieldPermission.create(projectId, FieldKind.CORE, "priority", groupEditorId, FieldAccessLevel.EDIT),
        )
        val candidates = setOf(summary, priority)

        // 관리자 우회가 있다면 둘 다 통과하겠지만, resolver 는 role/isSystemAdmin 을 보지 않으므로 모두 제외.
        assertThat(resolver.visibleFields(adminNonMemberId, projectId, candidates)).isEmpty()
        assertThat(resolver.editableFields(adminNonMemberId, projectId, candidates)).isEmpty()
    }

    // ── (e) 다중 그룹 OR: 일부 그룹만 VIEW 여도 visible (EC2) ───────────────────────

    @Test
    fun `actor가 여러 그룹에 속할 때 그중 하나라도 VIEW면 visible하다`() {
        // multiGroupId 는 groupViewer + groupOther 둘 다 소속.
        // summary 규칙은 groupViewer VIEW (속함), groupOther 규칙 없음 → OR 로 통과.
        fieldPermissionRepo.save(
            FieldPermission.create(projectId, FieldKind.CORE, "summary", groupViewerId, FieldAccessLevel.VIEW),
        )
        // description 규칙은 groupEditor 전용(multiGroupId 비소속) → 제외.
        fieldPermissionRepo.save(
            FieldPermission.create(projectId, FieldKind.CORE, "description", groupEditorId, FieldAccessLevel.VIEW),
        )
        val candidates = setOf(summary, description)

        assertThat(resolver.visibleFields(multiGroupId, projectId, candidates)).containsExactly(summary)
    }

    // ── 헬퍼 ────────────────────────────────────────────────────────────────────────

    /** 테스트 데이터를 FK 순서대로 초기화한다(field_permissions → memberships → groups → users). */
    private fun cleanTestData() {
        jdbc.update("DELETE FROM field_permissions WHERE project_id = :id", mapOf("id" to projectId))
        jdbc.update("DELETE FROM project_memberships WHERE project_id = :id", mapOf("id" to projectId))
        val groupIds = listOf(groupViewerId, groupEditorId, groupOtherId)
        jdbc.update("DELETE FROM user_groups WHERE id IN (:ids)", mapOf("ids" to groupIds))
        val userIds = listOf(memberId, nonMemberId, adminNonMemberId, editorId, multiGroupId)
        jdbc.update("DELETE FROM users WHERE id IN (:ids)", mapOf("ids" to userIds))
    }

    private fun seedUsers() {
        listOf(
            Triple(memberId, "itest_fp_member", "FP Member"),
            Triple(nonMemberId, "itest_fp_nonmember", "FP NonMember"),
            Triple(adminNonMemberId, "itest_fp_admin", "FP Admin"),
            Triple(editorId, "itest_fp_editor", "FP Editor"),
            Triple(multiGroupId, "itest_fp_multi", "FP Multi"),
        ).forEach { (id, username, displayName) ->
            jdbc.update(
                "INSERT INTO users (id, username, display_name) VALUES (:id, :username, :displayName)",
                mapOf("id" to id, "username" to username, "displayName" to displayName),
            )
        }
    }

    private fun seedGroups() {
        listOf(
            groupViewerId to "fr-pm-07-t4-viewer",
            groupEditorId to "fr-pm-07-t4-editor",
            groupOtherId to "fr-pm-07-t4-other",
        ).forEach { (id, name) ->
            jdbc.update(
                "INSERT INTO user_groups (id, name) VALUES (:id, :name)",
                mapOf("id" to id, "name" to name),
            )
        }
    }

    /**
     * 그룹 멤버십 + 프로젝트 멤버십을 시드한다.
     * - memberId → groupViewer
     * - editorId → groupEditor
     * - multiGroupId → groupViewer + groupOther
     * - adminNonMemberId → PROJECT_ADMIN(프로젝트 멤버십)이지만 그룹 미소속
     */
    private fun seedMemberships() {
        listOf(
            memberId to groupViewerId,
            editorId to groupEditorId,
            multiGroupId to groupViewerId,
            multiGroupId to groupOtherId,
        ).forEach { (userId, groupId) ->
            jdbc.update(
                "INSERT INTO group_memberships (group_id, user_id) VALUES (:groupId, :userId)",
                mapOf("groupId" to groupId, "userId" to userId),
            )
        }
        // 관리자 우회 없음 검증용 — PROJECT_ADMIN 역할 부여(그러나 그룹 비멤버).
        jdbc.update(
            "INSERT INTO project_memberships (project_id, user_id, role) VALUES (:projectId, :userId, :role)",
            mapOf("projectId" to projectId, "userId" to adminNonMemberId, "role" to "PROJECT_ADMIN"),
        )
    }
}
