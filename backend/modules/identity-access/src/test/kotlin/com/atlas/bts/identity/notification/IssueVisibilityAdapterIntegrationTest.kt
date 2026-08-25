// IssueVisibilityAdapter prod 통합테스트 — VIEW_ISSUE 매트릭스 + 보안등급 게이트 결합 가시성 필터 (FR-NT-03 Task 6)

package com.atlas.bts.identity.notification

import com.atlas.bts.identity.group.UserGroupRepository
import com.atlas.bts.identity.provider.ldap.AutoProvisionService
import com.atlas.bts.identity.provider.ldap.ExternalAccountRepository
import com.atlas.bts.identity.provider.ldap.LdapProvider
import com.atlas.bts.identity.provider.ldap.LdapProviderConfigService
import com.atlas.bts.identity.support.SharedPostgres
import com.bts.shared.permission.IssueVisibilityPort
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
 * [IssueVisibilityAdapter] prod 통합테스트 (FR-NT-03 Task 6).
 *
 * ## 목적 (prod ground-truth)
 * 알림 수신자 후보군에서 "실제로 이슈를 볼 수 있는 사용자"만 남기는 가시성 필터를 실제 DB 로 검증한다.
 * 단건 VIEW 가시성의 source of truth 는 **VIEW_ISSUE 매트릭스 권한 + 보안등급 게이트의 결합**이며
 * (ADR 2026-06-06 §결정4·5), adapter 는 새 보안 경로를 만들지 않고 검증된
 * [com.atlas.bts.identity.permission.IdentityAccessIssuePermissionResolver] 의 단건 VIEW 판정을 재사용한다.
 *
 * non-prod 의 `DevAllowIssuePermissionResolver`(`@Profile("!prod")`)는 모든 VIEW 를 허용해 게이트를
 * 마스킹하므로, 매트릭스/보안등급 게이트의 진짜 작동은 여기 `@ActiveProfiles("prod")` 에서만 검증된다.
 *
 * ## 판정 규칙 (ADR §결정4·5)
 * - VIEW_ISSUE 매트릭스 통과 AND (보안등급 없음 OR 등급 멤버 충족).
 * - **관리자 우회 없음** — SYSTEM_ADMIN/PROJECT_ADMIN 도 등급 멤버가 아니면 못 본다.
 * - 고아 등급(멤버 0) → 보수적 차단. 소프트삭제/미존재 이슈 → 빈.
 *
 * ## boot 레시피
 * resolver/adapter 의 prod 빈을 주입받기 위해 prod 프로파일로 부팅한다
 * (IdentityAccessIssueSecurityDirectoryIntegrationTest 동일 레시피 — PEM 키 + OAuth2 exclude + LDAP MockBean 5종).
 * `issues` 테이블은 issue-tracking 소유라 identity-access Flyway 에 없으므로 공유 리소스
 * (`issuesecurity/issues_lookup_schema.sql`)로 직접 생성한다(JdbcIssueSecurityLookupIntegrationTest 동형).
 *
 * ## 시나리오
 * | 케이스 | 조건 | 기대 |
 * |---|---|---|
 * | (a) | 보안수준 미설정 + VIEW_ISSUE 권한 없는 멤버 | 제외(매트릭스 게이트) |
 * | (b) | 보안수준 미설정 + VIEW 권한 있는 멤버 | 통과 |
 * | (c) | 보안수준 설정 + 등급 멤버(USER/GROUP/PROJECT_ROLE) | 멤버만 통과 |
 * | (d) | reporter/assignee 조건 등급 | 이 이슈의 reporter/assignee 만 통과 |
 * | (e) | 고아 등급(멤버 0) | 전원 차단 |
 * | (f) | 소프트삭제 이슈 | 빈 |
 * | (g) | 미존재 이슈 | 빈 |
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = [
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration",
    ],
)
@ActiveProfiles("prod")
class IssueVisibilityAdapterIntegrationTest {
    companion object {
        const val TYPE_USER = "USER"
        const val TYPE_GROUP = "GROUP"
        const val TYPE_PROJECT_ROLE = "PROJECT_ROLE"
        const val TYPE_REPORTER = "REPORTER"
        const val TYPE_ASSIGNEE = "ASSIGNEE"

        /** Flyway V008 기본 권한 스킴 id — PROJECT_ADMIN/MEMBER 에 VIEW_ISSUE 부여(V014). */
        const val DEFAULT_SCHEME_ID = "00000000-0000-0000-0000-000000000001"

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

    /** prod 프로파일에서 IssueVisibilityAdapter 가 주입되어야 한다. */
    @Autowired
    private lateinit var port: IssueVisibilityPort

    @Autowired
    private lateinit var jdbc: NamedParameterJdbcTemplate

    @Autowired
    private lateinit var groupRepo: UserGroupRepository

    // ── 픽스처 식별값 ──────────────────────────────────────────────────────────

    private val memberAlice: UUID = UUID.fromString("00000000-aaaa-0000-0000-000000000001")
    private val memberBob: UUID = UUID.fromString("00000000-aaaa-0000-0000-000000000002")
    private val groupCarol: UUID = UUID.fromString("00000000-aaaa-0000-0000-000000000003")
    private val nonMemberDave: UUID = UUID.fromString("00000000-aaaa-0000-0000-000000000004")
    private val groupId: UUID = UUID.fromString("00000000-bbbb-0000-0000-000000000001")

    /** VIEW_ISSUE 매트릭스 통과 프로젝트(기본 스킴 — MEMBER 가 VIEW_ISSUE 보유). */
    private val projectId: UUID = UUID.fromString("eeeeeeee-0000-0000-0000-000000000001")
    private val projectKey = "NTVIS"

    /** VIEW_ISSUE 미부여 커스텀 스킴이 적용된 프로젝트(매트릭스 게이트 차단 검증용). */
    private val noViewProjectId: UUID = UUID.fromString("eeeeeeee-0000-0000-0000-000000000002")
    private val noViewProjectKey = "NTNOV"
    private val noViewSchemeId: UUID = UUID.fromString("11111111-0000-0000-0000-0000000000aa")

    private val schemeId: UUID = UUID.fromString("11111111-0000-0000-0000-000000000001")

    private val userLevelId: UUID = UUID.fromString("22222222-0000-0000-0000-000000000001")
    private val groupLevelId: UUID = UUID.fromString("22222222-0000-0000-0000-000000000002")
    private val roleLevelId: UUID = UUID.fromString("22222222-0000-0000-0000-000000000003")
    private val reporterLevelId: UUID = UUID.fromString("22222222-0000-0000-0000-000000000004")
    private val assigneeLevelId: UUID = UUID.fromString("22222222-0000-0000-0000-000000000005")
    private val orphanLevelId: UUID = UUID.fromString("22222222-0000-0000-0000-000000000006")

    @BeforeEach
    fun setUp() {
        ensureProjectsTableExists()
        ensureIssuesTableExists()
        cleanTestData()
        seedUsersAndGroup()
        seedProjects()
        seedMemberships()
        seedNoViewScheme()
        seedSecurityScheme()
    }

    // ── (a) 매트릭스 게이트 — VIEW_ISSUE 미보유 멤버는 제외 ───────────────────────

    /**
     * 보안수준 미설정이라도 VIEW_ISSUE 매트릭스가 없으면 후보에서 제외된다(매트릭스 게이트 작동).
     *
     * noView 스킴(VIEW_ISSUE 미부여)이 적용된 프로젝트의 멤버는, 보안등급이 없어도 단건 VIEW 가 거부되므로
     * 가시성 필터에서 빠진다. accessibleLevels 단독 재사용이라면 놓쳤을 누출을 막는다(리뷰 BLOCKER).
     */
    @Test
    fun `(a) 보안수준 미설정이라도 VIEW_ISSUE 미보유 멤버는 제외된다`() {
        insertIssue("NTNOV-1", reporterId = memberBob, assigneeId = null, levelId = null, deleted = false)

        val visible = port.filterVisibleUserIds("NTNOV-1", setOf(memberAlice))

        assertThat(visible).isEmpty()
    }

    // ── (b) 보안수준 미설정 + VIEW 권한 멤버 → 통과 ──────────────────────────────

    /**
     * 보안수준이 없고 VIEW_ISSUE 매트릭스를 가진 멤버는 통과한다. 비멤버는 함께 제외된다.
     */
    @Test
    fun `(b) 보안수준 미설정 + VIEW 권한 있는 멤버는 통과하고 비멤버는 제외된다`() {
        insertIssue("NTVIS-1", reporterId = memberBob, assigneeId = null, levelId = null, deleted = false)

        val visible =
            port.filterVisibleUserIds(
                "NTVIS-1",
                setOf(memberAlice, memberBob, nonMemberDave),
            )

        assertThat(visible).containsExactlyInAnyOrder(memberAlice, memberBob)
    }

    // ── (c) 보안수준 설정 — 등급 멤버(USER/GROUP/PROJECT_ROLE)만 통과 ────────────

    /**
     * USER 멤버 등급 — 명시된 사용자만 통과하고, VIEW 권한이 있는 다른 멤버도 등급 비멤버면 제외된다.
     */
    @Test
    fun `(c) USER 등급 — 명시 사용자만 통과하고 VIEW 권한 보유 타 멤버도 제외된다`() {
        addMember(userLevelId, TYPE_USER, memberAlice.toString())
        insertIssue("NTVIS-2", reporterId = memberBob, assigneeId = null, levelId = userLevelId, deleted = false)

        val visible = port.filterVisibleUserIds("NTVIS-2", setOf(memberAlice, memberBob))

        assertThat(visible).containsExactly(memberAlice)
    }

    /**
     * GROUP 멤버 등급 — 그룹에 속한 사용자만 통과한다(그룹 비소속 멤버는 제외).
     */
    @Test
    fun `(c) GROUP 등급 — 그룹 소속 사용자만 통과한다`() {
        addMember(groupLevelId, TYPE_GROUP, groupId.toString())
        insertIssue("NTVIS-3", reporterId = memberBob, assigneeId = null, levelId = groupLevelId, deleted = false)

        val visible = port.filterVisibleUserIds("NTVIS-3", setOf(groupCarol, memberAlice))

        assertThat(visible).containsExactly(groupCarol)
    }

    /**
     * PROJECT_ROLE 멤버 등급 — 해당 역할 멤버 전원이 통과한다(MEMBER 역할 등급).
     */
    @Test
    fun `(c) PROJECT_ROLE 등급 — 해당 역할 멤버가 통과한다`() {
        addMember(roleLevelId, TYPE_PROJECT_ROLE, "MEMBER")
        insertIssue("NTVIS-4", reporterId = memberBob, assigneeId = null, levelId = roleLevelId, deleted = false)

        val visible = port.filterVisibleUserIds("NTVIS-4", setOf(memberAlice, memberBob, groupCarol))

        assertThat(visible).containsExactlyInAnyOrder(memberAlice, memberBob, groupCarol)
    }

    // ── (d) reporter/assignee 조건 등급 — 이 이슈의 reporter/assignee 만 ──────────

    /**
     * REPORTER 등급 — 이 이슈의 보고자만 통과하고, 다른 멤버는 보고자가 아니면 제외된다.
     */
    @Test
    fun `(d) REPORTER 등급 — 이 이슈의 보고자만 통과한다`() {
        addMember(reporterLevelId, TYPE_REPORTER, null)
        insertIssue("NTVIS-5", reporterId = memberAlice, assigneeId = null, levelId = reporterLevelId, deleted = false)

        val visible = port.filterVisibleUserIds("NTVIS-5", setOf(memberAlice, memberBob))

        assertThat(visible).containsExactly(memberAlice)
    }

    /**
     * ASSIGNEE 등급 — 이 이슈의 담당자만 통과하고, 보고자라도 담당자가 아니면 제외된다.
     */
    @Test
    fun `(d) ASSIGNEE 등급 — 이 이슈의 담당자만 통과한다`() {
        addMember(assigneeLevelId, TYPE_ASSIGNEE, null)
        insertIssue(
            "NTVIS-6",
            reporterId = memberAlice,
            assigneeId = memberBob,
            levelId = assigneeLevelId,
            deleted = false,
        )

        val visible = port.filterVisibleUserIds("NTVIS-6", setOf(memberAlice, memberBob))

        assertThat(visible).containsExactly(memberBob)
    }

    // ── (e) 고아 등급(멤버 0) → 전원 차단 ───────────────────────────────────────

    /**
     * 멤버가 0인 고아 등급은 공개와 구분해 보수적으로 차단한다 — VIEW 권한이 있어도 전원 제외.
     */
    @Test
    fun `(e) 고아 등급은 VIEW 권한 보유자도 전원 차단한다`() {
        insertIssue("NTVIS-7", reporterId = memberBob, assigneeId = null, levelId = orphanLevelId, deleted = false)

        val visible = port.filterVisibleUserIds("NTVIS-7", setOf(memberAlice, memberBob))

        assertThat(visible).isEmpty()
    }

    // ── (f) 소프트삭제 이슈 → 빈 ───────────────────────────────────────────────

    @Test
    fun `(f) 소프트삭제 이슈는 빈 집합을 반환한다`() {
        insertIssue("NTVIS-8", reporterId = memberAlice, assigneeId = null, levelId = null, deleted = true)

        val visible = port.filterVisibleUserIds("NTVIS-8", setOf(memberAlice, memberBob))

        assertThat(visible).isEmpty()
    }

    // ── (g) 미존재 이슈 → 빈 ───────────────────────────────────────────────────

    @Test
    fun `(g) 미존재 이슈는 빈 집합을 반환한다`() {
        val visible = port.filterVisibleUserIds("NTVIS-999", setOf(memberAlice, memberBob))

        assertThat(visible).isEmpty()
    }

    /** 빈 후보 집합은 빈 집합을 반환한다(불필요 쿼리 단락). */
    @Test
    fun `빈 후보 집합은 빈 집합을 반환한다`() {
        insertIssue("NTVIS-10", reporterId = memberBob, assigneeId = null, levelId = null, deleted = false)

        assertThat(port.filterVisibleUserIds("NTVIS-10", emptySet())).isEmpty()
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

    /**
     * issues 테이블을 공유 리소스 스키마로 생성한다.
     *
     * issues 는 issue-tracking 소유라 identity-access Flyway 에 없으므로, JdbcIssueSecurityLookup 통합테스트와
     * 동일한 공유 리소스(`issuesecurity/issues_lookup_schema.sql`)로 의존 컬럼만 최소 생성한다(ADR D2).
     */
    private fun ensureIssuesTableExists() {
        jdbc.jdbcTemplate.execute(loadIssuesSchemaSql())
    }

    private fun loadIssuesSchemaSql(): String =
        requireNotNull(javaClass.getResource("/issuesecurity/issues_lookup_schema.sql")) {
            "테스트 리소스 issuesecurity/issues_lookup_schema.sql 를 찾을 수 없습니다."
        }.readText()

    private fun cleanTestData() {
        jdbc.update("DELETE FROM issues", emptyMap<String, Any>())
        jdbc.update("DELETE FROM issue_security_level_members", emptyMap<String, Any>())
        jdbc.update("DELETE FROM project_issue_security_schemes", emptyMap<String, Any>())
        jdbc.update("DELETE FROM issue_security_levels", emptyMap<String, Any>())
        jdbc.update("DELETE FROM issue_security_schemes WHERE id = :id", mapOf("id" to schemeId))
        jdbc.update("DELETE FROM project_permission_scheme", emptyMap<String, Any>())
        jdbc.update(
            "DELETE FROM role_permissions WHERE scheme_id = :id",
            mapOf("id" to noViewSchemeId),
        )
        jdbc.update("DELETE FROM permission_schemes WHERE id = :id", mapOf("id" to noViewSchemeId))
        jdbc.update("DELETE FROM project_memberships", emptyMap<String, Any>())
        jdbc.update("DELETE FROM group_memberships", emptyMap<String, Any>())
        jdbc.update("DELETE FROM user_groups WHERE id = :id", mapOf("id" to groupId))
        jdbc.update(
            "DELETE FROM users WHERE id IN (:ids)",
            mapOf("ids" to listOf(memberAlice, memberBob, groupCarol, nonMemberDave)),
        )
        jdbc.update(
            "DELETE FROM projects WHERE id IN (:ids)",
            mapOf("ids" to listOf(projectId, noViewProjectId)),
        )
    }

    private fun seedUsersAndGroup() {
        listOf(
            Triple(memberAlice, "ntvis_alice", "NTVIS Alice"),
            Triple(memberBob, "ntvis_bob", "NTVIS Bob"),
            Triple(groupCarol, "ntvis_carol", "NTVIS Carol"),
            Triple(nonMemberDave, "ntvis_dave", "NTVIS Dave"),
        ).forEach { (id, username, displayName) ->
            jdbc.update(
                "INSERT INTO users (id, username, display_name) VALUES (:id, :username, :displayName)",
                mapOf("id" to id, "username" to username, "displayName" to displayName),
            )
        }
        groupRepo.create("NTVIS Group", null).let { created ->
            jdbc.update("DELETE FROM user_groups WHERE id = :id", mapOf("id" to created.id))
        }
        jdbc.update(
            "INSERT INTO user_groups (id, name) VALUES (:id, :name)",
            mapOf("id" to groupId, "name" to "NTVIS Group"),
        )
        groupRepo.addMember(groupId, groupCarol)
    }

    private fun seedProjects() {
        listOf(
            Pair(projectId, projectKey),
            Pair(noViewProjectId, noViewProjectKey),
        ).forEach { (id, key) ->
            jdbc.update(
                "INSERT INTO projects (id, key, deleted_at) VALUES (:id, :key, NULL)",
                mapOf("id" to id, "key" to key),
            )
        }
    }

    /** 두 프로젝트 모두에 후보 4인 중 alice/bob/carol 을 MEMBER 로 시드한다(dave 는 비멤버). */
    private fun seedMemberships() {
        listOf(projectId, noViewProjectId).forEach { pid ->
            listOf(memberAlice, memberBob, groupCarol).forEach { uid ->
                jdbc.update(
                    """
                    INSERT INTO project_memberships (project_id, user_id, role)
                    VALUES (:pid, :uid, 'MEMBER')
                    """.trimIndent(),
                    mapOf("pid" to pid, "uid" to uid),
                )
            }
        }
    }

    /**
     * VIEW_ISSUE 를 부여하지 않은 커스텀 스킴을 만들어 noView 프로젝트에 적용한다(매트릭스 게이트 차단 검증용).
     *
     * 기본 스킴(V008/V014)은 MEMBER 에게 VIEW_ISSUE 를 주므로, 매트릭스 차단을 보려면 VIEW_ISSUE 가 없는
     * 별도 스킴이 필요하다. role_permissions 를 비워 둬 MEMBER 의 VIEW_ISSUE 를 의도적으로 누락시킨다.
     */
    private fun seedNoViewScheme() {
        jdbc.update(
            "INSERT INTO permission_schemes (id, name, is_default) VALUES (:id, :name, FALSE)",
            mapOf("id" to noViewSchemeId, "name" to "NTVIS NoView Scheme"),
        )
        jdbc.update(
            "INSERT INTO project_permission_scheme (project_id, scheme_id) VALUES (:pid, :sid)",
            mapOf("pid" to noViewProjectId, "sid" to noViewSchemeId),
        )
    }

    /**
     * 보안 스킴 + 등급 6종을 생성하고 기본 권한 프로젝트(projectKey)에 적용한다.
     *
     * 등급 멤버는 각 테스트가 시나리오별로 추가한다(orphan 등급은 멤버 0 유지).
     */
    private fun seedSecurityScheme() {
        jdbc.update(
            "INSERT INTO issue_security_schemes (id, name) VALUES (:id, :name)",
            mapOf("id" to schemeId, "name" to "NTVIS Security Scheme"),
        )
        jdbc.update(
            "INSERT INTO project_issue_security_schemes (project_id, scheme_id) VALUES (:projectId, :schemeId)",
            mapOf("projectId" to projectId, "schemeId" to schemeId),
        )
        listOf(
            Pair(userLevelId, "User"),
            Pair(groupLevelId, "Group"),
            Pair(roleLevelId, "Role"),
            Pair(reporterLevelId, "Reporter"),
            Pair(assigneeLevelId, "Assignee"),
            Pair(orphanLevelId, "Orphan"),
        ).forEach { (id, name) -> seedLevel(id, name) }
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

    private fun insertIssue(
        key: String,
        reporterId: UUID,
        assigneeId: UUID?,
        levelId: UUID?,
        deleted: Boolean,
    ) {
        jdbc.update(
            """
            INSERT INTO issues (key, reporter_id, assignee_id, security_level_id, deleted_at)
            VALUES (:key, :reporterId, :assigneeId, :levelId, :deletedAt)
            """.trimIndent(),
            mapOf(
                "key" to key,
                "reporterId" to reporterId,
                "assigneeId" to assigneeId,
                "levelId" to levelId,
                "deletedAt" to if (deleted) java.sql.Timestamp.from(java.time.Instant.now()) else null,
            ),
        )
    }
}
