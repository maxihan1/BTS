// IdentityAccessIssueSecurityDirectory prod 통합테스트 — 접근가능 등급집합 산출·스킴소속·빠른경로 (FR-PM-06 PR-B T9)

package com.atlas.bts.identity.issuesecurity

import com.atlas.bts.identity.provider.ldap.AutoProvisionService
import com.atlas.bts.identity.provider.ldap.ExternalAccountRepository
import com.atlas.bts.identity.provider.ldap.LdapProvider
import com.atlas.bts.identity.provider.ldap.LdapProviderConfigService
import com.atlas.bts.identity.support.SharedPostgres
import com.bts.shared.permission.IssueSecurityDirectory
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
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.Security
import java.util.UUID

/**
 * [IdentityAccessIssueSecurityDirectory] prod 통합테스트 (FR-PM-06 PR-B Task 9).
 *
 * ## 목적
 * 목록 필터(T10)와 등급 지정 422(T6)의 ground-truth가 되는 두 메서드를 실제 DB로 검증한다.
 * - [IssueSecurityDirectory.accessibleLevels] — 실 멤버십 기준 정적/REPORTER/ASSIGNEE 등급집합 정확 산출.
 *   non-prod AlwaysAllow stub은 unrestricted=true로 필터를 끄므로(B2 마스킹), 등급집합 정확성은
 *   여기 prod에서만 진짜로 검증된다.
 * - [IssueSecurityDirectory.levelBelongsToProjectScheme] — 적용 스킴 소속/미소속 정·오 케이스.
 * - unrestricted 빠른경로(C5) — 프로젝트에 적용 스킴이 없으면 unrestricted=true, 등급집합은 빈 집합.
 *
 * ## boot 레시피
 * Directory는 `@Profile("prod")`이므로 prod 프로파일에서만 주입된다
 * (IdentityAccessIssuePermissionResolverIntegrationTest 동일 레시피 — PEM 키 + OAuth2 exclude + LDAP MockBean 5종).
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = [
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration",
    ],
)
@ActiveProfiles("prod")
class IdentityAccessIssueSecurityDirectoryIntegrationTest {
    companion object {
        // 보안 등급 멤버 타입 문자열(issue_security_level_members.member_type CHECK 제약값).
        const val TYPE_USER = "USER"
        const val TYPE_GROUP = "GROUP"
        const val TYPE_PROJECT_ROLE = "PROJECT_ROLE"
        const val TYPE_REPORTER = "REPORTER"
        const val TYPE_ASSIGNEE = "ASSIGNEE"

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
            // 공용 컨테이너라 커넥션 한도도 공유한다. context 캐시가 쌓이면 기본 풀(10)로는
            // max_connections 를 넘긴다 — SharedPostgres 헤더 참조.
            registry.add("spring.datasource.hikari.maximum-pool-size") { SharedPostgres.MAX_POOL_SIZE }
            registry.add("bts.auth.issuer-uri") { "http://localhost:8090" }
            registry.add("bts.security.cors.allowed-origins") { "http://localhost:5173" }
            registry.add("spring.ldap.urls") { "ldap://localhost:389" }
            registry.add("spring.ldap.base") { "dc=bts,dc=local" }
            registry.add("bts.auth.jwt.private-key-pem-path") { pemFilePath }
        }

        /** 테스트용 임시 RSA 2048 PEM 파일 경로(prod PemFileKeyProvider 요구). */
        val pemFilePath: String =
            run {
                if (Security.getProvider("BC") == null) {
                    Security.addProvider(BouncyCastleProvider())
                }
                val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
                val pemContent =
                    buildString {
                        appendLine("-----BEGIN PRIVATE KEY-----")
                        val mimeEncoder = java.util.Base64.getMimeEncoder(64, "\n".toByteArray())
                        appendLine(mimeEncoder.encodeToString(keyPair.private.encoded))
                        append("-----END PRIVATE KEY-----")
                    }
                val tmpFile = Files.createTempFile("bts-test-key-", ".pem")
                Files.writeString(tmpFile, pemContent)
                tmpFile.toAbsolutePath().toString()
            }
    }

    @MockBean lateinit var ldapProvider: LdapProvider

    @MockBean lateinit var ldapProviderConfigService: LdapProviderConfigService

    @MockBean lateinit var externalAccountRepository: ExternalAccountRepository

    @MockBean lateinit var autoProvisionService: AutoProvisionService

    @MockBean lateinit var ldapTemplate: LdapTemplate

    /** prod 프로파일에서 IdentityAccessIssueSecurityDirectory가 주입되어야 한다. */
    @Autowired
    private lateinit var directory: IssueSecurityDirectory

    @Autowired
    private lateinit var jdbc: NamedParameterJdbcTemplate

    @Autowired
    private lateinit var groupRepo: com.atlas.bts.identity.group.UserGroupRepository

    // ── 픽스처 식별값 ──────────────────────────────────────────────────────────

    private val actorId: UUID = UUID.fromString("00000000-aaaa-0000-0000-000000000001")
    private val groupId: UUID = UUID.fromString("00000000-bbbb-0000-0000-000000000001")

    private val projectId: UUID = UUID.fromString("eeeeeeee-0000-0000-0000-000000000001")
    private val projectKey = "SECDIR"

    /** 적용 스킴이 없는 프로젝트(unrestricted 빠른경로 검증용). */
    private val unscopedProjectId: UUID = UUID.fromString("ffffffff-0000-0000-0000-000000000001")
    private val unscopedProjectKey = "NOSCHEME"

    private val schemeId: UUID = UUID.fromString("11111111-0000-0000-0000-000000000001")

    // 등급별 식별자 — 멤버 타입 조합 검증용.
    private val userLevelId: UUID = UUID.fromString("22222222-0000-0000-0000-000000000001")
    private val groupLevelId: UUID = UUID.fromString("22222222-0000-0000-0000-000000000002")
    private val roleLevelId: UUID = UUID.fromString("22222222-0000-0000-0000-000000000003")
    private val reporterLevelId: UUID = UUID.fromString("22222222-0000-0000-0000-000000000004")
    private val assigneeLevelId: UUID = UUID.fromString("22222222-0000-0000-0000-000000000005")
    private val orphanLevelId: UUID = UUID.fromString("22222222-0000-0000-0000-000000000006")
    private val otherUserLevelId: UUID = UUID.fromString("22222222-0000-0000-0000-000000000007")

    @BeforeEach
    fun setUp() {
        ensureProjectsTableExists()
        cleanTestData()
        seedUserAndGroup()
        seedProjects()
        seedMembership()
    }

    // ── 주입 검증 ─────────────────────────────────────────────────────────────

    @Test
    fun `prod 프로파일에서 IdentityAccessIssueSecurityDirectory가 주입된다`() {
        assertThat(directory).isInstanceOf(IdentityAccessIssueSecurityDirectory::class.java)
    }

    // ── accessibleLevels — 정적 등급집합(USER/GROUP/PROJECT_ROLE) ───────────────

    /**
     * actor가 USER/GROUP/PROJECT_ROLE 멤버로 충족하는 등급은 staticLevelIds에 포함되고,
     * 충족 못 하는 등급(타 사용자 USER 멤버)·고아 등급은 제외된다.
     */
    @Test
    fun `accessibleLevels — actor가 충족하는 정적 등급만 staticLevelIds에 포함된다`() {
        seedScheme()
        seedLevel(userLevelId, "User")
        seedLevel(groupLevelId, "Group")
        seedLevel(roleLevelId, "Role")
        seedLevel(orphanLevelId, "Orphan")
        seedLevel(otherUserLevelId, "OtherUser")
        addMember(userLevelId, TYPE_USER, actorId.toString())
        addMember(groupLevelId, TYPE_GROUP, groupId.toString())
        addMember(roleLevelId, TYPE_PROJECT_ROLE, "MEMBER")
        addMember(otherUserLevelId, TYPE_USER, UUID.randomUUID().toString())
        // orphanLevelId — 멤버 0(고아) → 어느 집합에도 안 들어감.

        val access = directory.accessibleLevels(actorId, projectKey)

        assertThat(access.unrestricted).isFalse()
        assertThat(access.staticLevelIds).containsExactlyInAnyOrder(userLevelId, groupLevelId, roleLevelId)
        assertThat(access.staticLevelIds).doesNotContain(orphanLevelId, otherUserLevelId)
    }

    /**
     * REPORTER/ASSIGNEE 등급은 정적 집합이 아니라 별도 reporterLevelIds/assigneeLevelIds로 산출된다.
     */
    @Test
    fun `accessibleLevels — REPORTER ASSIGNEE 등급은 전용 집합으로 분리 산출된다`() {
        seedScheme()
        seedLevel(reporterLevelId, "Reporter")
        seedLevel(assigneeLevelId, "Assignee")
        addMember(reporterLevelId, TYPE_REPORTER, null)
        addMember(assigneeLevelId, TYPE_ASSIGNEE, null)

        val access = directory.accessibleLevels(actorId, projectKey)

        assertThat(access.reporterLevelIds).containsExactly(reporterLevelId)
        assertThat(access.assigneeLevelIds).containsExactly(assigneeLevelId)
        // REPORTER/ASSIGNEE 는 동적 조건이므로 정적 집합에는 안 들어간다.
        assertThat(access.staticLevelIds).doesNotContain(reporterLevelId, assigneeLevelId)
    }

    // ── unrestricted 빠른경로(C5) ──────────────────────────────────────────────

    @Test
    fun `accessibleLevels — 적용 스킴 없는 프로젝트는 unrestricted=true 빠른경로다`() {
        val access = directory.accessibleLevels(actorId, unscopedProjectKey)

        assertThat(access.unrestricted).isTrue()
        assertThat(access.staticLevelIds).isEmpty()
        assertThat(access.reporterLevelIds).isEmpty()
        assertThat(access.assigneeLevelIds).isEmpty()
    }

    // ── findLevelNames — cross-BC 표시명 박제 (FR-HS-01 보강 Task 3) ────────────

    /**
     * 시드한 등급 id 들은 이름으로 역방향 일괄 조회되고, 미존재 id 는 결과 맵에서 제외된다.
     *
     * 이슈 변경 이력(audit trail) 기록 시점에 securityLevel 표시명을 박제하기 위한 prod 구현이다.
     * non-prod AlwaysAllow stub 은 default(빈 맵)를 상속하므로, 실 조회 정확성은 여기 prod 에서만 검증된다.
     */
    @Test
    fun `findLevelNames — 시드한 등급 id 는 이름으로 조회되고 미존재 id 는 제외된다`() {
        seedScheme()
        seedLevel(userLevelId, "임원 전용")
        seedLevel(groupLevelId, "내부용")
        val missingLevelId = UUID.fromString("22222222-0000-0000-0000-0000000000ff")

        val names = directory.findLevelNames(setOf(userLevelId, groupLevelId, missingLevelId))

        assertThat(names).containsOnly(
            org.assertj.core.api.Assertions.entry(userLevelId, "임원 전용"),
            org.assertj.core.api.Assertions.entry(groupLevelId, "내부용"),
        )
        assertThat(names).doesNotContainKey(missingLevelId)
    }

    /** 빈 입력은 빈 맵을 반환한다(쿼리 단락). */
    @Test
    fun `findLevelNames — 빈 입력은 emptyMap`() {
        assertThat(directory.findLevelNames(emptySet())).isEmpty()
    }

    // ── levelBelongsToProjectScheme ────────────────────────────────────────────

    @Test
    fun `levelBelongsToProjectScheme — 적용 스킴 소속 등급은 true`() {
        seedScheme()
        seedLevel(userLevelId, "User")

        assertThat(directory.levelBelongsToProjectScheme(userLevelId, projectKey)).isTrue()
    }

    @Test
    fun `levelBelongsToProjectScheme — 미적용 프로젝트의 등급은 false`() {
        seedScheme()
        seedLevel(userLevelId, "User")

        assertThat(directory.levelBelongsToProjectScheme(userLevelId, unscopedProjectKey)).isFalse()
    }

    @Test
    fun `levelBelongsToProjectScheme — 존재하지 않는 프로젝트 키는 false`() {
        seedScheme()
        seedLevel(userLevelId, "User")

        assertThat(directory.levelBelongsToProjectScheme(userLevelId, "GHOST")).isFalse()
    }

    // ── 픽스처 헬퍼 ───────────────────────────────────────────────────────────

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
        jdbc.update("DELETE FROM issue_security_level_members", emptyMap<String, Any>())
        jdbc.update("DELETE FROM project_issue_security_schemes", emptyMap<String, Any>())
        jdbc.update("DELETE FROM issue_security_levels", emptyMap<String, Any>())
        jdbc.update("DELETE FROM issue_security_schemes WHERE id = :id", mapOf("id" to schemeId))
        jdbc.update("DELETE FROM project_memberships", emptyMap<String, Any>())
        jdbc.update("DELETE FROM group_memberships", emptyMap<String, Any>())
        jdbc.update("DELETE FROM user_groups WHERE id = :id", mapOf("id" to groupId))
        jdbc.update("DELETE FROM users WHERE id = :id", mapOf("id" to actorId))
        jdbc.update("DELETE FROM projects WHERE id IN (:ids)", mapOf("ids" to listOf(projectId, unscopedProjectId)))
    }

    private fun seedUserAndGroup() {
        jdbc.update(
            "INSERT INTO users (id, username, display_name) VALUES (:id, :username, :displayName)",
            mapOf("id" to actorId, "username" to "secdir_actor", "displayName" to "SecDir Actor"),
        )
        groupRepo.create("SecDir Group", null).let { created ->
            // create()는 DB가 채운 id로 그룹을 만든다. 고정 id가 필요하므로 직접 INSERT로 대체한다.
            jdbc.update("DELETE FROM user_groups WHERE id = :id", mapOf("id" to created.id))
        }
        jdbc.update(
            "INSERT INTO user_groups (id, name) VALUES (:id, :name)",
            mapOf("id" to groupId, "name" to "SecDir Group"),
        )
        groupRepo.addMember(groupId, actorId)
    }

    private fun seedProjects() {
        listOf(
            Pair(projectId, projectKey),
            Pair(unscopedProjectId, unscopedProjectKey),
        ).forEach { (id, key) ->
            jdbc.update(
                "INSERT INTO projects (id, key, deleted_at) VALUES (:id, :key, NULL)",
                mapOf("id" to id, "key" to key),
            )
        }
    }

    private fun seedMembership() {
        val now = java.sql.Timestamp.from(java.time.Instant.now())
        jdbc.update(
            """
            INSERT INTO project_memberships (project_id, user_id, role, created_at, updated_at)
            VALUES (:projectId, :userId, 'MEMBER', :now, :now)
            """.trimIndent(),
            mapOf("projectId" to projectId, "userId" to actorId, "now" to now),
        )
    }

    private fun seedScheme() {
        jdbc.update(
            "INSERT INTO issue_security_schemes (id, name) VALUES (:id, :name)",
            mapOf("id" to schemeId, "name" to "SecDir Scheme"),
        )
        jdbc.update(
            "INSERT INTO project_issue_security_schemes (project_id, scheme_id) VALUES (:projectId, :schemeId)",
            mapOf("projectId" to projectId, "schemeId" to schemeId),
        )
    }

    private fun seedLevel(
        id: UUID,
        name: String,
    ) {
        jdbc.update(
            "INSERT INTO issue_security_levels (id, scheme_id, name, is_default) VALUES (:id, :schemeId, :name, FALSE)",
            mapOf("id" to id, "schemeId" to schemeId, "name" to name),
        )
    }

    private fun addMember(
        levelId: UUID,
        memberType: String,
        memberValue: String?,
    ) {
        jdbc.update(
            """
            INSERT INTO issue_security_level_members (level_id, member_type, member_value)
            VALUES (:levelId, :memberType, :memberValue)
            """.trimIndent(),
            mapOf("levelId" to levelId, "memberType" to memberType, "memberValue" to memberValue),
        )
    }
}
