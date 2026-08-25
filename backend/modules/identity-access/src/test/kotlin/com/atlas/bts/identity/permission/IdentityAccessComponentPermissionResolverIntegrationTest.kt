// 컴포넌트 권한 prod 판정기 전수 통합테스트 — 실제 DB + V009 + 9케이스 매트릭스 (FR-PM-03 Task 4)

package com.atlas.bts.identity.permission

import com.atlas.bts.identity.provider.ldap.AutoProvisionService
import com.atlas.bts.identity.provider.ldap.ExternalAccountRepository
import com.atlas.bts.identity.provider.ldap.LdapProvider
import com.atlas.bts.identity.provider.ldap.LdapProviderConfigService
import com.atlas.bts.identity.support.SharedPostgres
import com.bts.shared.permission.ComponentPermission
import com.bts.shared.permission.ComponentPermissionResolver
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
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.Security
import java.util.UUID

/**
 * [IdentityAccessComponentPermissionResolver] 전수 통합테스트 (FR-PM-03 Task 4).
 *
 * ## 목적
 * - prod 프로파일에서 실제 DB(V001~V009 Flyway 적용) 위에 9케이스 권한 매트릭스를 검증한다.
 * - V009 시드(기본 스킴 × PROJECT_ADMIN × MANAGE_COMPONENTS) 위에서 거부 경로(MEMBER/비멤버 → false)를
 *   실 DB로 확정한다. 단위 mock(MockK)은 schemeRepo.roleHasPermission 반환을 임의로 stub 하므로
 *   거부 ground-truth가 아니다(메모리 best-effort-loop-permission-exception-nonprod-mask).
 * - Bean 배타: prod 프로파일에서 [IdentityAccessComponentPermissionResolver]가
 *   [ComponentPermissionResolver]로 주입됨을 확인한다(AlwaysAllow*는 @Profile "!prod"이라 미등록).
 *
 * ## 9케이스 매트릭스 (3 actor × CREATE/UPDATE/DELETE)
 * | Actor          | Permission             | 기대  | 근거                         |
 * |----------------|------------------------|-------|------------------------------|
 * | PROJECT_ADMIN  | CREATE/UPDATE/DELETE   | true  | V009 MANAGE_COMPONENTS 부여  |
 * | MEMBER         | CREATE/UPDATE/DELETE   | false | MANAGE_COMPONENTS 미부여     |
 * | 비멤버         | CREATE/UPDATE/DELETE   | false | 멤버 게이트(멤버십 없음)     |
 *
 * ## 시드 (선례 dead-code 복제 금지)
 * 포트가 projectId(UUID)를 직접 받으므로 projects 테이블/ProjectDirectory/projectKey 시드는 불요다.
 * 멤버십(users FK 충족용 users 3명 + project_memberships 2행)만 시드하고, 스킴은 미매핑하여
 * 기본 스킴(00000000-...-001) fallback 경로(V008+V009)를 그대로 사용한다.
 *
 * @see IdentityAccessComponentPermissionResolver
 * @see PermissionSchemeRepository
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = [
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration",
    ],
)
@ActiveProfiles("prod")
class IdentityAccessComponentPermissionResolverIntegrationTest {
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
            // LDAP 자동설정이 URL을 요구하므로 placeholder 제공
            registry.add("spring.ldap.urls") { "ldap://localhost:389" }
            registry.add("spring.ldap.base") { "dc=bts,dc=local" }
            // prod 프로파일에서 PemFileKeyProvider가 PEM 파일 경로를 @Value로 요구한다.
            registry.add("bts.auth.jwt.private-key-pem-path") { pemFilePath }
        }

        /**
         * 테스트용 임시 RSA 2048 PEM 파일 경로.
         *
         * [configureProperties]보다 먼저 static 초기화되어야 한다.
         * PemFileKeyProvider가 BouncyCastle PEMParser를 사용하므로 BC provider를 먼저 등록한다.
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

    // LDAP Bean 목킹 — 실제 LDAP 서버 없이 컨텍스트 부팅
    @MockBean lateinit var ldapProvider: LdapProvider

    @MockBean lateinit var ldapProviderConfigService: LdapProviderConfigService

    @MockBean lateinit var externalAccountRepository: ExternalAccountRepository

    @MockBean lateinit var autoProvisionService: AutoProvisionService

    @MockBean lateinit var ldapTemplate: LdapTemplate

    /** prod 프로파일에서 IdentityAccessComponentPermissionResolver가 주입되어야 한다. */
    @Autowired
    private lateinit var resolver: ComponentPermissionResolver

    @Autowired
    private lateinit var jdbc: NamedParameterJdbcTemplate

    // ── 픽스처 식별값 ──────────────────────────────────────────────────────────

    private val adminId: UUID = UUID.fromString("00000000-1111-0000-0000-000000000001")
    private val memberId: UUID = UUID.fromString("00000000-2222-0000-0000-000000000001")
    private val nonMemberId: UUID = UUID.fromString("00000000-3333-0000-0000-000000000001")

    /** 기본 스킴 fallback 검증용 프로젝트 — project_permission_scheme 매핑 없음 */
    private val projectId: UUID = UUID.fromString("aaaaaaaa-0000-0000-0000-000000000001")

    // ── 설정 ─────────────────────────────────────────────────────────────────

    @BeforeEach
    fun setUp() {
        cleanTestData()
        seedUsers()
        seedMemberships()
    }

    // ── Bean 배타 검증 ─────────────────────────────────────────────────────────

    /**
     * prod 프로파일에서 [IdentityAccessComponentPermissionResolver]가 [ComponentPermissionResolver]로 주입된다.
     * AlwaysAllowComponentPermissionResolver(@Profile "!prod")는 이 컨텍스트에 등록되지 않는다.
     */
    @Test
    fun `prod 프로파일에서 IdentityAccessComponentPermissionResolver가 ComponentPermissionResolver로 주입된다`() {
        assertThat(resolver).isInstanceOf(IdentityAccessComponentPermissionResolver::class.java)
    }

    // ── 9케이스 매트릭스 (@TestFactory) ─────────────────────────────────────

    @TestFactory
    fun `컴포넌트 권한 매트릭스 — 3 actor × CREATE_UPDATE_DELETE 9케이스 전수 검증`(): List<DynamicTest> {
        data class Case(
            val label: String,
            val actorId: UUID,
            val permission: ComponentPermission,
            val expected: Boolean,
        )

        val permissions = ComponentPermission.entries
        val actors =
            listOf(
                Triple("PROJECT_ADMIN", adminId, true),
                Triple("MEMBER", memberId, false),
                Triple("비멤버", nonMemberId, false),
            )

        val cases =
            actors.flatMap { (roleLabel, actorId, expected) ->
                permissions.map { perm ->
                    Case("$roleLabel + $perm → ${if (expected) "허용" else "거부"}", actorId, perm, expected)
                }
            }

        return cases.map { c ->
            dynamicTest(c.label) {
                assertThat(resolver.hasPermission(c.actorId, c.permission, projectId))
                    .`as`(c.label)
                    .isEqualTo(c.expected)
            }
        }
    }

    // ── 픽스처 헬퍼 ───────────────────────────────────────────────────────────

    /** 테스트 데이터를 FK 순서대로 초기화한다. */
    private fun cleanTestData() {
        jdbc.update("DELETE FROM project_memberships", emptyMap<String, Any>())
        jdbc.update("DELETE FROM users WHERE id IN (:ids)", mapOf("ids" to listOf(adminId, memberId, nonMemberId)))
    }

    /** 테스트 사용자 3명을 삽입한다 (project_memberships.user_id FK 충족). */
    private fun seedUsers() {
        listOf(
            Triple(adminId, "itest_comp_admin", "Component Admin"),
            Triple(memberId, "itest_comp_member", "Component Member"),
            Triple(nonMemberId, "itest_comp_nonmember", "Component NonMember"),
        ).forEach { (id, username, displayName) ->
            jdbc.update(
                "INSERT INTO users (id, username, display_name) VALUES (:id, :username, :displayName)",
                mapOf("id" to id, "username" to username, "displayName" to displayName),
            )
        }
    }

    /** adminId=PROJECT_ADMIN, memberId=MEMBER. 비멤버(nonMemberId)는 시드하지 않는다. */
    private fun seedMemberships() {
        listOf(
            Pair(adminId, "PROJECT_ADMIN"),
            Pair(memberId, "MEMBER"),
        ).forEach { (userId, role) ->
            jdbc.update(
                "INSERT INTO project_memberships (project_id, user_id, role) VALUES (:projectId, :userId, :role)",
                mapOf("projectId" to projectId, "userId" to userId, "role" to role),
            )
        }
    }
}
