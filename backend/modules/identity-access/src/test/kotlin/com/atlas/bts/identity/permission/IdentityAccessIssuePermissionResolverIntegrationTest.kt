// prod 프로파일 권한 매트릭스 전수 통합테스트 — 실제 DB + 13케이스 + 스킴 공유 (FR-PM-02 Task 5)

package com.atlas.bts.identity.permission

import com.atlas.bts.identity.provider.ldap.AutoProvisionService
import com.atlas.bts.identity.provider.ldap.ExternalAccountRepository
import com.atlas.bts.identity.provider.ldap.LdapProvider
import com.atlas.bts.identity.provider.ldap.LdapProviderConfigService
import com.bts.shared.permission.IssuePermission
import com.bts.shared.permission.IssuePermissionResolver
import com.bts.shared.permission.IssueScope
import org.assertj.core.api.Assertions.assertThat
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
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
 * [IdentityAccessIssuePermissionResolver] 전수 통합테스트 (FR-PM-02 Task 5).
 *
 * ## 목적
 * - prod 프로파일에서 실제 DB(V001~V008 Flyway 적용) 위에 13케이스 권한 매트릭스를 검증한다.
 * - 스킴 공유: 두 프로젝트가 동일 scheme_id를 project_permission_scheme에 매핑해도 같은 판정이 반환됨을 확인한다.
 * - 미매핑 프로젝트는 기본 스킴(00000000-0000-0000-0000-000000000001) fallback 경로를 검증한다.
 * - Bean 배타: prod 프로파일에서 [IdentityAccessIssuePermissionResolver]가 [IssuePermissionResolver]로 주입됨을 확인한다.
 *
 * ## 권한 매트릭스 (FR-PM-05 BROWSE/VIEW 매트릭스 이관 반영)
 * | Actor    | Permission  | Scope         | 기대  | 사유                         |
 * |----------|-------------|---------------|-------|------------------------------|
 * | ADMIN    | CREATE      | Issue(key)    | true  | CREATE_ISSUE 매트릭스        |
 * | ADMIN    | UPDATE      | Issue(key)    | true  | UPDATE_ISSUE 매트릭스        |
 * | ADMIN    | SOFT_DELETE | Issue(key)    | true  | DELETE_ISSUE 매트릭스        |
 * | MEMBER   | CREATE      | Issue(key)    | true  | CREATE_ISSUE 매트릭스        |
 * | MEMBER   | UPDATE      | Issue(key)    | true  | UPDATE_ISSUE 매트릭스        |
 * | MEMBER   | SOFT_DELETE | Issue(key)    | false | DELETE_ISSUE 미보유          |
 * | 비멤버   | CREATE      | Issue(key)    | false | 멤버 게이트 차단             |
 * | 비멤버   | UPDATE      | Issue(key)    | false | 멤버 게이트 차단             |
 * | 비멤버   | SOFT_DELETE | Issue(key)    | false | 멤버 게이트 차단             |
 * | MEMBER   | VIEW        | Issue(key)    | true  | VIEW_ISSUE 매트릭스 보유     |
 * | MEMBER   | TRANSITION  | Issue(key)    | true  | 범위 밖(미위임) → 멤버 통과  |
 * | 비멤버   | VIEW        | Issue(key)    | false | 멤버 게이트 차단             |
 * | 비멤버   | TRANSITION  | Issue(key)    | false | 멤버 게이트 차단             |
 * | MEMBER   | BROWSE      | Project(key)  | true  | BROWSE_PROJECT 매트릭스 보유 |
 * | 비멤버   | BROWSE      | Project(key)  | false | 멤버 게이트 차단             |
 * | ADMIN    | BROWSE      | Project(key)  | true  | BROWSE_PROJECT 매트릭스 보유 |
 *
 * ## Testcontainers 설계
 * companion object에 static @Container 선언 + @DynamicPropertySource 패턴
 * (ProjectMembershipRepositoryIntegrationTest / ProjectMemberFlowIntegrationTest 선례 동일).
 *
 * @see IdentityAccessIssuePermissionResolver
 * @see JdbcPermissionSchemeRepository
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
class IdentityAccessIssuePermissionResolverIntegrationTest {
    companion object {
        // 보안 등급 멤버 타입 문자열(issue_security_level_members.member_type CHECK 제약값).
        const val MEMBER_TYPE_USER = "USER"
        const val MEMBER_TYPE_REPORTER = "REPORTER"
        const val MEMBER_TYPE_PROJECT_ROLE = "PROJECT_ROLE"

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
            // LDAP 자동설정이 URL을 요구하므로 placeholder 제공
            registry.add("spring.ldap.urls") { "ldap://localhost:389" }
            registry.add("spring.ldap.base") { "dc=bts,dc=local" }
            // prod 프로파일에서 PemFileKeyProvider가 PEM 파일 경로를 @Value로 요구한다.
            // 테스트용 RSA 2048 키를 PKCS#1 PEM 파일로 생성해 임시 경로에 저장한다.
            registry.add("bts.auth.jwt.private-key-pem-path") { pemFilePath }
        }

        /**
         * 테스트용 임시 RSA 2048 PEM 파일 경로.
         *
         * [configureProperties]보다 먼저 static 초기화 블록에서 생성되어야 한다.
         * PemFileKeyProvider가 BouncyCastle PEMParser를 사용하므로 BC provider를 먼저 등록한다.
         * PKCS#8(PRIVATE KEY) 형식으로 PEM 파일을 생성한다.
         */
        val pemFilePath: String =
            run {
                // BouncyCastle provider 등록 — PemFileKeyProvider의 JcaPEMKeyConverter.setProvider("BC")에 필요
                if (Security.getProvider("BC") == null) {
                    Security.addProvider(BouncyCastleProvider())
                }
                val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
                val privKey = keyPair.private
                // PKCS#8 DER bytes → Base64 PEM 형식 (64자 줄바꿈, MIME encoding)
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

    // LDAP Bean 목킹 — 실제 LDAP 서버 없이 컨텍스트 부팅 (ProjectMemberFlowIntegrationTest 선례)
    @MockBean lateinit var ldapProvider: LdapProvider

    @MockBean lateinit var ldapProviderConfigService: LdapProviderConfigService

    @MockBean lateinit var externalAccountRepository: ExternalAccountRepository

    @MockBean lateinit var autoProvisionService: AutoProvisionService

    @MockBean lateinit var ldapTemplate: LdapTemplate

    /** prod 프로파일에서 IdentityAccessIssuePermissionResolver가 주입되어야 한다. */
    @Autowired
    private lateinit var resolver: IssuePermissionResolver

    @Autowired
    private lateinit var jdbc: NamedParameterJdbcTemplate

    // ── 픽스처 식별값 ──────────────────────────────────────────────────────────

    private val adminId: UUID = UUID.fromString("00000000-1111-0000-0000-000000000001")
    private val memberId: UUID = UUID.fromString("00000000-2222-0000-0000-000000000001")
    private val nonMemberId: UUID = UUID.fromString("00000000-3333-0000-0000-000000000001")

    /** 기본 스킴 fallback 검증용 프로젝트 — project_permission_scheme 매핑 없음 */
    private val projectId: UUID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001")
    private val projectKey = "ITEST"
    private val issueKey = "ITEST-1"

    /** 스킴 공유 검증용 두 번째 프로젝트 — projectId와 같은 scheme_id 매핑 */
    private val sharedProjectId: UUID = UUID.fromString("bbbbbbbb-0000-0000-0000-000000000001")
    private val sharedProjectKey = "SHARE"
    private val sharedIssueKey = "SHARE-1"

    // 기본 스킴 UUID — V008 시드 고정값
    private val defaultSchemeId: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")

    // ── 보안 게이트(Task 9) 픽스처 ───────────────────────────────────────────────

    /** 보안 등급 멤버 판정용 제3의 사용자(보고자/타 멤버로 사용). */
    private val otherId: UUID = UUID.fromString("00000000-4444-0000-0000-000000000001")

    /** 보안 스킴/등급 식별자. */
    private val securitySchemeId: UUID = UUID.fromString("cccccccc-0000-0000-0000-000000000001")
    private val levelId: UUID = UUID.fromString("dddddddd-0000-0000-0000-000000000001")

    /** 보안 등급이 지정된 이슈 / 공개(등급 미지정) 이슈 키. */
    private val securedIssueKey = "$projectKey-100"
    private val publicIssueKey = "$projectKey-101"

    // ── 설정/해제 ─────────────────────────────────────────────────────────────

    @BeforeEach
    fun setUp() {
        ensureProjectsTableExists()
        ensureIssuesTableExists()
        cleanTestData()
        seedUsers()
        seedProjects()
        seedMemberships()
        seedSharedSchemeMapping()
        // 기존 매트릭스 VIEW 케이스(issueKey/sharedIssueKey)는 보안 게이트 도입 후 이슈 행이 있어야
        // lookup 이 컨텍스트를 반환한다. 등급 미지정(공개)으로 시드해 매트릭스 판정만 검증되게 한다.
        seedSecuredIssue(issueKey, reporterId = otherId, assigneeId = null, levelId = null)
        seedSecuredIssue(sharedIssueKey, reporterId = otherId, assigneeId = null, levelId = null)
    }

    // ── Bean 배타 검증 ─────────────────────────────────────────────────────────

    /**
     * prod 프로파일에서 [IdentityAccessIssuePermissionResolver]가 [IssuePermissionResolver]로 주입된다.
     * AlwaysAllowIssuePermissionResolver(@Profile "!prod")는 이 컨텍스트에 등록되지 않는다.
     */
    @Test
    fun `prod 프로파일에서 IdentityAccessIssuePermissionResolver가 IssuePermissionResolver로 주입된다`() {
        assertThat(resolver).isInstanceOf(IdentityAccessIssuePermissionResolver::class.java)
    }

    // ── 권한 매트릭스 (@TestFactory) ──────────────────────────────────────────

    // LongMethod: 매트릭스 데이터 테이블을 한 @TestFactory에 모으는 것이 의도(전수 매트릭스 가독성).
    @Suppress("LongMethod")
    @TestFactory
    fun `권한 매트릭스 — 역할 × 권한 전수 검증`(): List<DynamicTest> {
        data class Case(
            val label: String,
            val actorId: UUID,
            val permission: IssuePermission,
            val scope: IssueScope,
            val expected: Boolean,
        )

        // 반복되는 scope 인자(issueKey/projectKey)를 고정하는 케이스 팩토리.
        fun issueCase(
            label: String,
            actorId: UUID,
            permission: IssuePermission,
            expected: Boolean,
        ) = Case(label, actorId, permission, IssueScope.Issue(issueKey), expected)

        fun projectCase(
            label: String,
            actorId: UUID,
            permission: IssuePermission,
            expected: Boolean,
        ) = Case(label, actorId, permission, IssueScope.Project(projectKey), expected)

        val cases =
            listOf(
                // ── PROJECT_ADMIN × 범위 내 3종 → 전부 허용 ─────────────────────
                issueCase("PROJECT_ADMIN + CREATE → 허용", adminId, IssuePermission.CREATE, true),
                issueCase("PROJECT_ADMIN + UPDATE → 허용", adminId, IssuePermission.UPDATE, true),
                issueCase("PROJECT_ADMIN + SOFT_DELETE → 허용", adminId, IssuePermission.SOFT_DELETE, true),
                // ── MEMBER × 범위 내 3종 → CREATE/UPDATE 허용, SOFT_DELETE 거부 ─
                issueCase("MEMBER + CREATE → 허용", memberId, IssuePermission.CREATE, true),
                issueCase("MEMBER + UPDATE → 허용", memberId, IssuePermission.UPDATE, true),
                issueCase("MEMBER + SOFT_DELETE → 거부 (DELETE_ISSUE 미보유)", memberId, IssuePermission.SOFT_DELETE, false),
                // ── 비멤버 × 범위 내 3종 → 전부 거부 (멤버 게이트) ────────────
                issueCase("비멤버 + CREATE → 거부", nonMemberId, IssuePermission.CREATE, false),
                issueCase("비멤버 + UPDATE → 거부", nonMemberId, IssuePermission.UPDATE, false),
                issueCase("비멤버 + SOFT_DELETE → 거부", nonMemberId, IssuePermission.SOFT_DELETE, false),
                // ── VIEW — FR-PM-05로 VIEW_ISSUE 매트릭스 위임 (더 이상 범위 밖 멤버 통과 아님) ─
                issueCase("MEMBER + VIEW(Issue) → 허용 (VIEW_ISSUE 매트릭스 보유) [S3]", memberId, IssuePermission.VIEW, true),
                issueCase("비멤버 + VIEW(Issue) → 거부 (멤버 게이트) [S4]", nonMemberId, IssuePermission.VIEW, false),
                // ── TRANSITION — 아직 미위임(범위 밖) → 멤버 통과 / 비멤버 거부 ──
                issueCase("MEMBER + TRANSITION(범위 밖) → 멤버 통과 허용", memberId, IssuePermission.TRANSITION, true),
                issueCase("비멤버 + TRANSITION(범위 밖) → 거부", nonMemberId, IssuePermission.TRANSITION, false),
                // ── BROWSE — FR-PM-05 BROWSE_PROJECT 매트릭스 위임 (Project 범위) ──
                projectCase(
                    "MEMBER + BROWSE → 허용 (BROWSE_PROJECT 매트릭스 보유) [S1]",
                    memberId,
                    IssuePermission.BROWSE,
                    true,
                ),
                projectCase("비멤버 + BROWSE → 거부 (멤버 게이트) [S2]", nonMemberId, IssuePermission.BROWSE, false),
                projectCase(
                    "PROJECT_ADMIN + BROWSE → 허용 (BROWSE_PROJECT 매트릭스 보유)",
                    adminId,
                    IssuePermission.BROWSE,
                    true,
                ),
            )

        return cases.map { c ->
            dynamicTest(c.label) {
                assertThat(resolver.hasPermission(c.actorId, c.permission, c.scope))
                    .`as`(c.label)
                    .isEqualTo(c.expected)
            }
        }
    }

    // ── 스킴 공유 검증 ─────────────────────────────────────────────────────────

    /**
     * 두 프로젝트가 같은 scheme_id를 project_permission_scheme에 매핑하면
     * 동일한 권한 판정이 반환되어야 한다.
     *
     * sharedProjectId는 defaultSchemeId에 명시적으로 매핑되어 있다(setUp에서 INSERT).
     * projectId는 매핑 없이 기본 스킴 fallback 경로를 사용한다.
     * 두 경로 모두 같은 스킴(defaultSchemeId)을 참조하므로 판정이 동일해야 한다.
     */
    @Test
    fun `스킴 공유 — 같은 scheme_id 매핑 시 동일 판정 반환`() {
        // projectId: fallback 경로 (매핑 없음)
        val unmappedResult =
            resolver.hasPermission(
                adminId,
                IssuePermission.SOFT_DELETE,
                IssueScope.Issue(issueKey),
            )
        // sharedProjectId: 명시적 매핑 경로 (defaultSchemeId 직접 매핑)
        val mappedResult =
            resolver.hasPermission(
                adminId,
                IssuePermission.SOFT_DELETE,
                IssueScope.Issue(sharedIssueKey),
            )

        assertThat(unmappedResult).isTrue()
        assertThat(mappedResult).isTrue()
        assertThat(unmappedResult).isEqualTo(mappedResult)
    }

    @Test
    fun `스킴 공유 — MEMBER SOFT_DELETE 거부도 두 프로젝트에서 동일`() {
        val unmappedResult =
            resolver.hasPermission(
                memberId,
                IssuePermission.SOFT_DELETE,
                IssueScope.Issue(issueKey),
            )
        val mappedResult =
            resolver.hasPermission(
                memberId,
                IssuePermission.SOFT_DELETE,
                IssueScope.Issue(sharedIssueKey),
            )

        assertThat(unmappedResult).isFalse()
        assertThat(mappedResult).isFalse()
        assertThat(unmappedResult).isEqualTo(mappedResult)
    }

    // ── Scope.Project 경로 검증 ────────────────────────────────────────────────

    @Test
    fun `IssueScope_Project 경로 — ADMIN CREATE 허용`() {
        assertThat(
            resolver.hasPermission(adminId, IssuePermission.CREATE, IssueScope.Project(projectKey)),
        ).isTrue()
    }

    @Test
    fun `IssueScope_Project 경로 — 비멤버 CREATE 거부`() {
        assertThat(
            resolver.hasPermission(nonMemberId, IssuePermission.CREATE, IssueScope.Project(projectKey)),
        ).isFalse()
    }

    // ── VIEW 보안등급 게이트 (FR-PM-06 PR-B Task 9) ──────────────────────────────
    //
    // VIEW_ISSUE 매트릭스를 통과한 actor에게 보안 등급 멤버십 게이트를 추가로 적용한다.
    // 멤버이면 통과(S1/S4~S6), 비멤버이면 false(S2). 관리자라도 등급 멤버가 아니면 false(S7).
    // 이 prod 단언이 거부 경로의 유일한 ground-truth다(non-prod AlwaysAllow가 마스킹, B2).

    /**
     * USER 멤버로 등급에 등록된 actor는 VIEW 보안 게이트를 통과한다(S5).
     *
     * memberId는 VIEW_ISSUE 매트릭스를 통과(MEMBER 역할)하고, USER 멤버로 등급에 등록돼 있어
     * 보안 게이트도 통과한다.
     */
    @Test
    fun `VIEW 게이트 — USER 멤버는 보안 등급 이슈를 통과한다 S5`() {
        seedSecurityScheme()
        seedSecuredIssue(securedIssueKey, reporterId = otherId, assigneeId = null, levelId = levelId)
        addLevelMember(MEMBER_TYPE_USER, memberId.toString())

        assertThat(
            resolver.hasPermission(memberId, IssuePermission.VIEW, IssueScope.Issue(securedIssueKey)),
        ).isTrue()
    }

    /**
     * 등급 멤버가 아닌 actor는 VIEW_ISSUE 매트릭스를 통과해도 보안 게이트에서 차단된다(S2).
     *
     * memberId는 MEMBER 역할로 VIEW_ISSUE 매트릭스는 통과하지만, 등급에 어떤 멤버로도
     * 등록돼 있지 않아 보안 게이트에서 false가 된다 → 컨트롤러가 404.
     */
    @Test
    fun `VIEW 게이트 — 등급 비멤버는 매트릭스 통과해도 차단된다 S2`() {
        seedSecurityScheme()
        seedSecuredIssue(securedIssueKey, reporterId = otherId, assigneeId = null, levelId = levelId)
        // 멤버 미등록 → 고아 등급은 아니나(다른 멤버 존재) memberId 자신은 비멤버.
        addLevelMember(MEMBER_TYPE_USER, otherId.toString())

        assertThat(
            resolver.hasPermission(memberId, IssuePermission.VIEW, IssueScope.Issue(securedIssueKey)),
        ).isFalse()
    }

    /**
     * SYSTEM_ADMIN(전역 관리자)이라도 등급 멤버가 아니면 보안 게이트에서 차단된다(S7 — 관리자 우회 없음).
     *
     * adminId는 PROJECT_ADMIN으로 VIEW_ISSUE 매트릭스를 통과하지만, 등급 멤버가 아니므로
     * 보안 게이트에서 false가 된다. resolver는 isSystemAdmin을 호출하지 않으며 admin 단락 경로가 없다.
     * decider 호출이 누락되면 이 단언만이 회귀를 잡는다(non-prod는 항상 통과, isomorphic-clone 교훈).
     */
    @Test
    fun `VIEW 게이트 — 관리자도 등급 비멤버면 차단된다 S7 우회 없음`() {
        seedSecurityScheme()
        seedSecuredIssue(securedIssueKey, reporterId = otherId, assigneeId = null, levelId = levelId)
        addLevelMember(MEMBER_TYPE_USER, otherId.toString())

        assertThat(
            resolver.hasPermission(adminId, IssuePermission.VIEW, IssueScope.Issue(securedIssueKey)),
        ).isFalse()
    }

    /**
     * 등급 미지정(security_level_id IS NULL) 이슈는 VIEW_ISSUE 매트릭스 통과자에게 공개된다(S1).
     *
     * 보안 게이트는 등급이 NULL이면 무조건 통과시킨다(공개 이슈).
     */
    @Test
    fun `VIEW 게이트 — 등급 미지정 이슈는 매트릭스 통과자에게 공개된다 S1`() {
        seedSecuredIssue(publicIssueKey, reporterId = otherId, assigneeId = null, levelId = null)

        assertThat(
            resolver.hasPermission(memberId, IssuePermission.VIEW, IssueScope.Issue(publicIssueKey)),
        ).isTrue()
    }

    /**
     * REPORTER 멤버 타입 — actor가 이슈 보고자이면 보안 게이트를 통과한다(S4).
     */
    @Test
    fun `VIEW 게이트 — REPORTER 멤버 타입은 보고자에게 통과한다 S4`() {
        seedSecurityScheme()
        seedSecuredIssue(securedIssueKey, reporterId = memberId, assigneeId = null, levelId = levelId)
        addLevelMember(MEMBER_TYPE_REPORTER, null)

        assertThat(
            resolver.hasPermission(memberId, IssuePermission.VIEW, IssueScope.Issue(securedIssueKey)),
        ).isTrue()
    }

    /**
     * PROJECT_ROLE 멤버 타입 — actor의 프로젝트 역할이 멤버 값과 일치하면 통과한다(S6).
     */
    @Test
    fun `VIEW 게이트 — PROJECT_ROLE 멤버 타입은 역할 일치 시 통과한다 S6`() {
        seedSecurityScheme()
        seedSecuredIssue(securedIssueKey, reporterId = otherId, assigneeId = null, levelId = levelId)
        addLevelMember(MEMBER_TYPE_PROJECT_ROLE, "MEMBER")

        assertThat(
            resolver.hasPermission(memberId, IssuePermission.VIEW, IssueScope.Issue(securedIssueKey)),
        ).isTrue()
    }

    /**
     * 존재하지 않는 이슈(lookup null)는 false다 — 기존 404 일관.
     */
    @Test
    fun `VIEW 게이트 — 존재하지 않는 이슈는 false다`() {
        assertThat(
            resolver.hasPermission(memberId, IssuePermission.VIEW, IssueScope.Issue("$projectKey-9999")),
        ).isFalse()
    }

    // ── 픽스처 헬퍼 ───────────────────────────────────────────────────────────

    /**
     * projects 테이블을 생성한다.
     *
     * identity-access Flyway에 없는 cross-BC 테이블이므로 테스트 DB에 직접 생성한다.
     * ProjectDirectory.resolveKeyToId()가 id + key + deleted_at 컬럼을 쿼리한다.
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

    /**
     * issues 테이블을 생성한다(Task 9 보안 게이트의 IssueSecurityLookup이 조회).
     *
     * issues는 issue-tracking BC 소유라 identity-access Flyway에 없으므로 테스트 DB에 직접 만든다.
     * 보안 판정에 필요한 의존 컬럼(key/reporter_id/assignee_id/security_level_id/deleted_at)만 둔다(ADR D2).
     */
    private fun ensureIssuesTableExists() {
        jdbc.jdbcTemplate.execute(
            """
            CREATE TABLE IF NOT EXISTS issues (
                id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
                key               VARCHAR(20)  NOT NULL UNIQUE,
                reporter_id       UUID         NOT NULL,
                assignee_id       UUID         NULL,
                security_level_id UUID         NULL,
                deleted_at        TIMESTAMPTZ  NULL
            )
            """.trimIndent(),
        )
    }

    /** 테스트 데이터를 FK 순서대로 초기화한다. */
    private fun cleanTestData() {
        jdbc.update("DELETE FROM issue_security_level_members", emptyMap<String, Any>())
        jdbc.update("DELETE FROM project_issue_security_schemes", emptyMap<String, Any>())
        jdbc.update("DELETE FROM issue_security_levels", emptyMap<String, Any>())
        jdbc.update("DELETE FROM issue_security_schemes WHERE id = :id", mapOf("id" to securitySchemeId))
        jdbc.update("DELETE FROM issues", emptyMap<String, Any>())
        jdbc.update("DELETE FROM project_permission_scheme", emptyMap<String, Any>())
        jdbc.update("DELETE FROM project_memberships", emptyMap<String, Any>())
        jdbc.update(
            "DELETE FROM users WHERE id IN (:ids)",
            mapOf("ids" to listOf(adminId, memberId, nonMemberId, otherId)),
        )
        jdbc.update("DELETE FROM projects WHERE id IN (:ids)", mapOf("ids" to listOf(projectId, sharedProjectId)))
    }

    /** 테스트 사용자 4명을 삽입한다 (FK 충족). */
    private fun seedUsers() {
        listOf(
            Triple(adminId, "itest_admin", "Integration Admin"),
            Triple(memberId, "itest_member", "Integration Member"),
            Triple(nonMemberId, "itest_nonmember", "Integration NonMember"),
            Triple(otherId, "itest_other", "Integration Other"),
        ).forEach { (id, username, displayName) ->
            jdbc.update(
                "INSERT INTO users (id, username, display_name) VALUES (:id, :username, :displayName)",
                mapOf("id" to id, "username" to username, "displayName" to displayName),
            )
        }
    }

    /** 두 프로젝트를 삽입한다. */
    private fun seedProjects() {
        listOf(
            Pair(projectId, projectKey),
            Pair(sharedProjectId, sharedProjectKey),
        ).forEach { (id, key) ->
            jdbc.update(
                "INSERT INTO projects (id, key, deleted_at) VALUES (:id, :key, NULL)",
                mapOf("id" to id, "key" to key),
            )
        }
    }

    /**
     * adminId=PROJECT_ADMIN, memberId=MEMBER으로 두 프로젝트에 멤버십을 삽입한다.
     *
     * BROWSE/VIEW 매트릭스 판정의 권한 행(BROWSE_PROJECT·VIEW_ISSUE)은 별도 시드가 아니라
     * V014__browse_view_permissions.sql(FR-PM-05)이 기본 스킴 PROJECT_ADMIN·MEMBER에 부여한 것을 탄다.
     * 따라서 멤버십만 시드하면 BROWSE(S1)·VIEW(S3)가 매트릭스로 true가 된다.
     */
    private fun seedMemberships() {
        val now = java.time.Instant.now()
        listOf(projectId, sharedProjectId).forEach { pId ->
            listOf(
                Pair(adminId, "PROJECT_ADMIN"),
                Pair(memberId, "MEMBER"),
            ).forEach { (userId, role) ->
                jdbc.update(
                    """
                    INSERT INTO project_memberships (project_id, user_id, role, created_at, updated_at)
                    VALUES (:projectId, :userId, :role, :createdAt, :updatedAt)
                    """,
                    mapOf(
                        "projectId" to pId,
                        "userId" to userId,
                        "role" to role,
                        "createdAt" to java.sql.Timestamp.from(now),
                        "updatedAt" to java.sql.Timestamp.from(now),
                    ),
                )
            }
        }
    }

    /**
     * sharedProjectId를 defaultSchemeId에 명시적으로 매핑한다.
     *
     * 스킴 공유 검증: projectId(fallback 경로)와 sharedProjectId(명시적 매핑 경로) 모두
     * 같은 defaultSchemeId를 참조하므로 판정이 동일해야 한다.
     */
    private fun seedSharedSchemeMapping() {
        val now = java.time.Instant.now()
        jdbc.update(
            """
            INSERT INTO project_permission_scheme (project_id, scheme_id, created_at, updated_at)
            VALUES (:projectId, :schemeId, :createdAt, :updatedAt)
            """,
            mapOf(
                "projectId" to sharedProjectId,
                "schemeId" to defaultSchemeId,
                "createdAt" to java.sql.Timestamp.from(now),
                "updatedAt" to java.sql.Timestamp.from(now),
            ),
        )
    }

    // ── 보안 게이트(Task 9) 시드 헬퍼 ───────────────────────────────────────────

    /**
     * 보안 스킴 1개 + 그 소속 등급 1개를 시드하고, projectId에 스킴을 적용한다.
     *
     * 등급 멤버는 [addLevelMember]로 케이스별 추가한다. 멤버를 한 명도 추가하지 않으면
     * 고아 등급(decider가 보수적으로 차단)이 된다.
     */
    private fun seedSecurityScheme() {
        jdbc.update(
            "INSERT INTO issue_security_schemes (id, name) VALUES (:id, :name)",
            mapOf("id" to securitySchemeId, "name" to "ITEST Security Scheme"),
        )
        jdbc.update(
            "INSERT INTO issue_security_levels (id, scheme_id, name, is_default) VALUES (:id, :schemeId, :name, FALSE)",
            mapOf("id" to levelId, "schemeId" to securitySchemeId, "name" to "Confidential"),
        )
        jdbc.update(
            "INSERT INTO project_issue_security_schemes (project_id, scheme_id) VALUES (:projectId, :schemeId)",
            mapOf("projectId" to projectId, "schemeId" to securitySchemeId),
        )
    }

    /** [levelId] 등급에 멤버 한 명을 추가한다. */
    private fun addLevelMember(
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

    /** projectKey 프로젝트에 속한 보안 판정용 이슈를 시드한다([levelId] 또는 NULL). */
    private fun seedSecuredIssue(
        key: String,
        reporterId: UUID,
        assigneeId: UUID?,
        levelId: UUID?,
    ) {
        jdbc.update(
            """
            INSERT INTO issues (key, reporter_id, assignee_id, security_level_id, deleted_at)
            VALUES (:key, :reporterId, :assigneeId, :levelId, NULL)
            """.trimIndent(),
            mapOf("key" to key, "reporterId" to reporterId, "assigneeId" to assigneeId, "levelId" to levelId),
        )
    }
}
