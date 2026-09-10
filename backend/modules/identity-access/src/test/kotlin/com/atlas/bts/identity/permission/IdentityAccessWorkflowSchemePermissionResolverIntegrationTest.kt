// 워크플로우 스킴 권한 prod 판정기 통합테스트 — 실제 DB + S1~S6 Guard 검증 (FR-PM-04 Task 3)

package com.atlas.bts.identity.permission

import com.atlas.bts.identity.provider.ldap.AutoProvisionService
import com.atlas.bts.identity.provider.ldap.ExternalAccountRepository
import com.atlas.bts.identity.provider.ldap.LdapProvider
import com.atlas.bts.identity.provider.ldap.LdapProviderConfigService
import com.atlas.bts.identity.support.SharedPostgres
import com.bts.shared.permission.WorkflowDefinitionAccessDeniedException
import com.bts.shared.permission.WorkflowDefinitionPermission
import com.bts.shared.permission.WorkflowDefinitionPermissionResolver
import com.bts.shared.permission.WorkflowSchemeAccessDeniedException
import com.bts.shared.permission.WorkflowSchemePermission
import com.bts.shared.permission.WorkflowSchemePermissionResolver
import com.bts.shared.permission.WorkflowScope
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
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
 * [IdentityAccessWorkflowSchemePermissionResolver] 전수 통합테스트 (FR-PM-04 Task 3).
 *
 * ## 목적
 * - prod 프로파일에서 실제 DB(V001~V013 Flyway 적용) 위에 Guard 패턴(거부 시 예외)의 S1~S6을 검증한다.
 * - 거부 ground-truth: 단위 mock 은 isSystemAdmin/roleHasPermission 반환을 임의로 stub 하므로
 *   prod 거부 경로의 진실 판정이 아니다(메모리 issue-scope-global-prod-hard-deny).
 *   따라서 system_role_assignments(SYSTEM_ADMIN) + project_memberships + V013 시드 위에서 실 DB로 확정한다.
 * - Bean 배타: prod 프로파일에서 [IdentityAccessWorkflowSchemePermissionResolver]가
 *   [WorkflowSchemePermissionResolver]로 주입됨을 확인한다(AlwaysAllow*는 project-workflow @Profile "!prod").
 *
 * ## S1~S6 시나리오 (spec 2026-06-05)
 * | # | actor          | permission     | scope          | 기대                       |
 * |---|----------------|----------------|----------------|----------------------------|
 * | S1| SYSTEM_ADMIN   | MANAGE_SCHEME  | Global         | 통과(예외 없음)            |
 * | S2| 역할 없음      | MANAGE_SCHEME  | Global         | 거부(예외 throw)           |
 * | S3| PROJECT_ADMIN  | ASSIGN_SCHEME  | Project(ATLAS) | 통과(MANAGE_WORKFLOW 보유) |
 * | S4| MEMBER         | ASSIGN_SCHEME  | Project(ATLAS) | 거부(매트릭스 미보유)      |
 * | S5| 비멤버         | ASSIGN_SCHEME  | Project(ATLAS) | 거부(멤버 게이트)          |
 * | S6| 미해석 키      | ASSIGN_SCHEME  | Project(NOPE)  | 거부(key→id 실패)          |
 *
 * @see IdentityAccessWorkflowSchemePermissionResolver
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = [
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration",
    ],
)
@ActiveProfiles("prod")
class IdentityAccessWorkflowSchemePermissionResolverIntegrationTest {
    companion object {
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

        /**
         * 테스트용 임시 RSA 2048 PEM 파일 경로.
         *
         * prod 프로파일에서 PemFileKeyProvider 가 PEM 파일 경로를 @Value 로 요구한다.
         * [configureProperties]보다 먼저 static 초기화되어야 한다.
         */
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

    // LDAP Bean 목킹 — 실제 LDAP 서버 없이 컨텍스트 부팅
    @MockBean lateinit var ldapProvider: LdapProvider

    @MockBean lateinit var ldapProviderConfigService: LdapProviderConfigService

    @MockBean lateinit var externalAccountRepository: ExternalAccountRepository

    @MockBean lateinit var autoProvisionService: AutoProvisionService

    @MockBean lateinit var ldapTemplate: LdapTemplate

    /** prod 프로파일에서 IdentityAccessWorkflowSchemePermissionResolver 가 주입되어야 한다. */
    @Autowired
    private lateinit var resolver: WorkflowSchemePermissionResolver

    /** prod 프로파일에서 IdentityAccessWorkflowDefinitionPermissionResolver 가 주입되어야 한다. */
    @Autowired
    private lateinit var definitionResolver: WorkflowDefinitionPermissionResolver

    @Autowired
    private lateinit var jdbc: NamedParameterJdbcTemplate

    // ── 픽스처 식별값 ──────────────────────────────────────────────────────────

    private val sysAdminId: UUID = UUID.fromString("00000000-aaaa-0000-0000-000000000001")
    private val projectAdminId: UUID = UUID.fromString("00000000-1111-0000-0000-000000000001")
    private val memberId: UUID = UUID.fromString("00000000-2222-0000-0000-000000000001")
    private val nonMemberId: UUID = UUID.fromString("00000000-3333-0000-0000-000000000001")

    /** 기본 스킴 fallback 검증용 프로젝트 — project_permission_scheme 매핑 없음 */
    private val projectId: UUID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001")
    private val projectKey: String = "ATLAS"

    @BeforeEach
    fun setUp() {
        ensureProjectsTableExists()
        cleanTestData()
        seedUsers()
        seedProject()
        seedSystemRole()
        seedMemberships()
    }

    // ── Bean 배타 검증 ─────────────────────────────────────────────────────────

    @Test
    fun `prod 프로파일에서 IdentityAccessWorkflowSchemePermissionResolver가 주입된다`() {
        assertThat(resolver).isInstanceOf(IdentityAccessWorkflowSchemePermissionResolver::class.java)
    }

    // ── S1 ─────────────────────────────────────────────────────────────────────

    @Test
    fun `S1 — SYSTEM_ADMIN의 MANAGE_SCHEME Global은 통과(예외 없음)`() {
        assertThatCode {
            resolver.requirePermission(sysAdminId, WorkflowSchemePermission.MANAGE_SCHEME, WorkflowScope.Global)
        }.doesNotThrowAnyException()
    }

    // ── S2 ─────────────────────────────────────────────────────────────────────

    @Test
    fun `S2 — 전역 역할 없는 사용자의 MANAGE_SCHEME Global은 거부`() {
        assertThatThrownBy {
            resolver.requirePermission(
                projectAdminId,
                WorkflowSchemePermission.MANAGE_SCHEME,
                WorkflowScope.Global,
            )
        }.isInstanceOf(WorkflowSchemeAccessDeniedException::class.java)
    }

    // ── S3 ─────────────────────────────────────────────────────────────────────

    @Test
    fun `S3 — PROJECT_ADMIN 멤버의 ASSIGN_SCHEME Project는 통과`() {
        assertThatCode {
            resolver.requirePermission(
                projectAdminId,
                WorkflowSchemePermission.ASSIGN_SCHEME,
                WorkflowScope.Project(projectKey),
            )
        }.doesNotThrowAnyException()
    }

    // ── S4 ─────────────────────────────────────────────────────────────────────

    @Test
    fun `S4 — MEMBER의 ASSIGN_SCHEME Project는 거부`() {
        assertThatThrownBy {
            resolver.requirePermission(
                memberId,
                WorkflowSchemePermission.ASSIGN_SCHEME,
                WorkflowScope.Project(projectKey),
            )
        }.isInstanceOf(WorkflowSchemeAccessDeniedException::class.java)
    }

    // ── S5 ─────────────────────────────────────────────────────────────────────

    @Test
    fun `S5 — 비멤버의 ASSIGN_SCHEME Project는 거부(멤버 게이트)`() {
        assertThatThrownBy {
            resolver.requirePermission(
                nonMemberId,
                WorkflowSchemePermission.ASSIGN_SCHEME,
                WorkflowScope.Project(projectKey),
            )
        }.isInstanceOf(WorkflowSchemeAccessDeniedException::class.java)
    }

    // ── S6 ─────────────────────────────────────────────────────────────────────

    @Test
    fun `S6 — 미해석 프로젝트 키의 ASSIGN_SCHEME Project는 거부`() {
        assertThatThrownBy {
            resolver.requirePermission(
                projectAdminId,
                WorkflowSchemePermission.ASSIGN_SCHEME,
                WorkflowScope.Project("NOPE"),
            )
        }.isInstanceOf(WorkflowSchemeAccessDeniedException::class.java)
    }

    // ── D6 — 워크플로우 **정의** 판정기 (FR-WF-08) ────────────────────────────
    //
    // ★여기 있는 이유. 정의 판정기의 프로젝트 축은 스킴 판정기와 **같은 부품**
    // (ProjectWorkflowPermissionGate)을 탄다. 그 부품의 실 DB 판정을 재려면 위 S1~S6 이 이미
    // 세워 둔 픽스처(시스템 역할 · 멤버십 · 매트릭스)가 그대로 필요하다. 별도 클래스로 빼면
    // 같은 시드를 복사하게 되고, 두 시드가 갈리는 날 어느 쪽이 진실인지 알 수 없어진다.
    //
    // 표 자체의 전수 대조는 IdentityAccessWorkflowDefinitionPermissionResolverTest 가 맡는다.
    // 여기서 재는 것은 「실 DB 위에서도 그 판정이 성립하는가」다.

    @Test
    fun `D6 — PROJECT_ADMIN 은 자기 프로젝트 워크플로우의 CREATE 를 통과한다`() {
        assertThatCode {
            definitionResolver.requirePermission(
                projectAdminId,
                WorkflowDefinitionPermission.CREATE,
                WorkflowScope.Project(projectKey),
            )
        }.doesNotThrowAnyException()
    }

    @Test
    fun `D6 — PROJECT_ADMIN 도 DELETE 는 거부된다`() {
        assertThatThrownBy {
            definitionResolver.requirePermission(
                projectAdminId,
                WorkflowDefinitionPermission.DELETE,
                WorkflowScope.Project(projectKey),
            )
        }.isInstanceOf(WorkflowDefinitionAccessDeniedException::class.java)
    }

    @Test
    fun `D6 — SYSTEM_ADMIN 은 프로젝트 소유 워크플로우도 지울 수 있다`() {
        // 이 행이 없으면 프로젝트 소유 워크플로우를 **아무도** 못 지운다.
        assertThatCode {
            definitionResolver.requirePermission(
                sysAdminId,
                WorkflowDefinitionPermission.DELETE,
                WorkflowScope.Project(projectKey),
            )
        }.doesNotThrowAnyException()
    }

    @Test
    fun `D6 — 비멤버는 프로젝트 워크플로우의 UPDATE 가 거부된다`() {
        assertThatThrownBy {
            definitionResolver.requirePermission(
                nonMemberId,
                WorkflowDefinitionPermission.UPDATE,
                WorkflowScope.Project(projectKey),
            )
        }.isInstanceOf(WorkflowDefinitionAccessDeniedException::class.java)
    }

    // ── 픽스처 헬퍼 ───────────────────────────────────────────────────────────

    /**
     * projects 테이블을 생성한다.
     *
     * identity-access Flyway 에 없는 cross-BC 테이블이므로 테스트 DB 에 직접 생성한다.
     * ProjectDirectory.resolveKeyToId() 가 id + key + deleted_at 컬럼만 쿼리하므로 name 컬럼은 불요다.
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

    /** 테스트 데이터를 FK 순서대로 초기화한다. */
    private fun cleanTestData() {
        val userIds = listOf(sysAdminId, projectAdminId, memberId, nonMemberId)
        jdbc.update("DELETE FROM system_role_assignments WHERE user_id IN (:ids)", mapOf("ids" to userIds))
        jdbc.update("DELETE FROM project_memberships WHERE project_id = :pid", mapOf("pid" to projectId))
        jdbc.update("DELETE FROM projects WHERE id = :pid", mapOf("pid" to projectId))
        jdbc.update("DELETE FROM users WHERE id IN (:ids)", mapOf("ids" to userIds))
    }

    /** 테스트 사용자 4명을 삽입한다 (FK 충족). */
    private fun seedUsers() {
        listOf(
            Triple(sysAdminId, "itest_wf_sysadmin", "WF SysAdmin"),
            Triple(projectAdminId, "itest_wf_padmin", "WF ProjectAdmin"),
            Triple(memberId, "itest_wf_member", "WF Member"),
            Triple(nonMemberId, "itest_wf_nonmember", "WF NonMember"),
        ).forEach { (id, username, displayName) ->
            jdbc.update(
                "INSERT INTO users (id, username, display_name) VALUES (:id, :username, :displayName)",
                mapOf("id" to id, "username" to username, "displayName" to displayName),
            )
        }
    }

    /** ProjectDirectory.resolveKeyToId(ATLAS) → projectId 해석을 위한 projects 행 시드. */
    private fun seedProject() {
        jdbc.update(
            "INSERT INTO projects (id, key, deleted_at) VALUES (:id, :key, NULL)",
            mapOf("id" to projectId, "key" to projectKey),
        )
    }

    /** sysAdminId 에 전역 SYSTEM_ADMIN 부여 (S1/S2 isSystemAdmin 판정 근거). */
    private fun seedSystemRole() {
        jdbc.update(
            "INSERT INTO system_role_assignments (user_id, role) VALUES (:userId, 'SYSTEM_ADMIN')",
            mapOf("userId" to sysAdminId),
        )
    }

    /** projectAdminId=PROJECT_ADMIN, memberId=MEMBER. 비멤버(nonMemberId)는 시드하지 않는다. */
    private fun seedMemberships() {
        listOf(
            Pair(projectAdminId, "PROJECT_ADMIN"),
            Pair(memberId, "MEMBER"),
        ).forEach { (userId, role) ->
            jdbc.update(
                "INSERT INTO project_memberships (project_id, user_id, role) VALUES (:projectId, :userId, :role)",
                mapOf("projectId" to projectId, "userId" to userId, "role" to role),
            )
        }
    }
}
