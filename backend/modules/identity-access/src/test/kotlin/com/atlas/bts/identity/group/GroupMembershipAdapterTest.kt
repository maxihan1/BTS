// GroupMembershipAdapter 통합테스트 (prod 프로파일 + 실 PostgreSQL 16) — FR-SR-03 PR2 Task 4

package com.atlas.bts.identity.group

import com.atlas.bts.identity.provider.ldap.AutoProvisionService
import com.atlas.bts.identity.provider.ldap.ExternalAccountRepository
import com.atlas.bts.identity.provider.ldap.LdapProvider
import com.atlas.bts.identity.provider.ldap.LdapProviderConfigService
import com.atlas.bts.identity.support.SharedPostgres
import com.bts.shared.membership.GroupMembershipPort
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
 * [GroupMembershipAdapter] 통합테스트 (FR-SR-03 PR2 Task 4).
 *
 * ## 목적
 * prod 프로파일에서 실 PostgreSQL 16(Testcontainers) 위에 V015 마이그레이션(group_memberships)이
 * 적용된 뒤, shared-kernel 가시성 보안 포트 [GroupMembershipPort] 의 identity-access 구현
 * ([GroupMembershipAdapter])을 end-to-end 검증한다.
 *
 * - **(a) N개 그룹 소속** — 사용자가 속한 모든 그룹 id 집합을 반환하고, 타 사용자 멤버십은 섞이지 않는다.
 * - **(b) 미소속 사용자** — 빈 Set 을 반환한다(fail-closed 방향, allow-all 아님).
 * - **(c) 반환 형식** — 각 원소가 정확히 `UUID.toString()` canonical 소문자 형식이어야 한다.
 *   saved_filter_shares.target_id(GROUP)에 저장될 형식과 일치해야 조용한 매칭 실패(C2)를 막는다.
 *
 * ## Testcontainers 설계
 * 같은 패키지 [JdbcUserGroupRepositoryIntegrationTest] 와 동일한 prod 부팅 셋업(static @Container +
 * @DynamicPropertySource + PEM 파일 + LDAP @MockBean 5종)을 따른다. users/user_groups/group_memberships
 * 행은 FK 충족을 위해 테스트에서 직접 INSERT 한다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = [
        "spring.autoconfigure.exclude=" +
            "org.springframework.boot.autoconfigure.security.oauth2.client.servlet.OAuth2ClientAutoConfiguration",
    ],
)
@ActiveProfiles("prod")
class GroupMembershipAdapterTest {
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
            // 공용 컨테이너라 커넥션 한도도 공유한다. context 캐시가 쌓이면
            // 기본 풀(10)로는 max_connections 를 넘긴다 — SharedPostgres 헤더 참조.
            registry.add("spring.datasource.hikari.maximum-pool-size") { SharedPostgres.MAX_POOL_SIZE }
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
    private lateinit var adapter: GroupMembershipPort

    @Autowired
    private lateinit var jdbc: NamedParameterJdbcTemplate

    // ── 픽스처 식별값 ──────────────────────────────────────────────────────────

    private val userAId: UUID = UUID.fromString("00000000-5003-0004-0000-000000000001")
    private val userBId: UUID = UUID.fromString("00000000-5003-0004-0000-000000000002")

    @BeforeEach
    fun setUp() {
        jdbc.update(
            "DELETE FROM group_memberships WHERE user_id IN (:ids)",
            mapOf("ids" to listOf(userAId, userBId)),
        )
        jdbc.update("DELETE FROM user_groups WHERE name LIKE :prefix", mapOf("prefix" to "fr-sr-03-t4-%"))
        jdbc.update("DELETE FROM users WHERE id IN (:ids)", mapOf("ids" to listOf(userAId, userBId)))
        seedUsers()
    }

    // ── (a) N개 그룹 소속 ─────────────────────────────────────────────────────────

    @Test
    fun `groupIdsOf는 사용자가 속한 모든 그룹 id 집합을 반환한다`() {
        val g1 = UUID.randomUUID()
        val g2 = UUID.randomUUID()
        val g3 = UUID.randomUUID()
        insertGroup(g1, "fr-sr-03-t4-g1")
        insertGroup(g2, "fr-sr-03-t4-g2")
        insertGroup(g3, "fr-sr-03-t4-g3")
        insertMembership(g1, userAId)
        insertMembership(g2, userAId)
        insertMembership(g3, userAId)
        // userB 는 g3 에만 소속 — 사용자 기준 필터링이 동작하는지 확인
        insertMembership(g3, userBId)

        val result = adapter.groupIdsOf(userAId)

        assertThat(result).containsExactlyInAnyOrder(g1.toString(), g2.toString(), g3.toString())
    }

    // ── (b) 미소속 사용자 → 빈 Set (fail-closed) ──────────────────────────────────

    @Test
    fun `groupIdsOf는 어느 그룹에도 속하지 않은 사용자에게 빈 Set을 반환한다 (fail-closed)`() {
        val result = adapter.groupIdsOf(userBId)

        assertThat(result).isEmpty()
    }

    @Test
    fun `groupIdsOf는 존재하지 않는 사용자에게도 빈 Set을 반환한다 (fail-closed)`() {
        val result = adapter.groupIdsOf(UUID.randomUUID())

        assertThat(result).isEmpty()
    }

    // ── (c) 반환 형식 = UUID.toString() canonical 소문자 ──────────────────────────

    @Test
    fun `groupIdsOf 반환 원소는 정확히 UUID toString canonical 소문자 형식이다`() {
        val groupId = UUID.randomUUID()
        insertGroup(groupId, "fr-sr-03-t4-format")
        insertMembership(groupId, userAId)

        val result = adapter.groupIdsOf(userAId)

        val canonicalLowercase = "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$"
        assertThat(result).contains(groupId.toString())
        assertThat(result).allSatisfy { id ->
            // saved_filter_shares.target_id(GROUP) 저장 형식과 동일해야 한다 (C2 형식 drift 차단)
            assertThat(id).matches(canonicalLowercase)
            assertThat(id).isEqualTo(UUID.fromString(id).toString())
        }
    }

    // ── 헬퍼 ──────────────────────────────────────────────────────────────────────

    private fun seedUsers() {
        listOf(
            Triple(userAId, "fr_sr_03_t4_user_a", "FR-SR-03 T4 User A"),
            Triple(userBId, "fr_sr_03_t4_user_b", "FR-SR-03 T4 User B"),
        ).forEach { (id, username, displayName) ->
            jdbc.update(
                "INSERT INTO users (id, username, display_name) VALUES (:id, :username, :displayName)",
                mapOf("id" to id, "username" to username, "displayName" to displayName),
            )
        }
    }

    private fun insertGroup(
        id: UUID,
        name: String,
    ) {
        jdbc.update(
            "INSERT INTO user_groups (id, name) VALUES (:id, :name)",
            mapOf("id" to id, "name" to name),
        )
    }

    private fun insertMembership(
        groupId: UUID,
        userId: UUID,
    ) {
        jdbc.update(
            "INSERT INTO group_memberships (group_id, user_id) VALUES (:groupId, :userId)",
            mapOf("groupId" to groupId, "userId" to userId),
        )
    }
}
