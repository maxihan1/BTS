// IdentityAccessSystemPermissionResolver 전역권한 판정 통합테스트 — grant OR isSystemAdmin (FR-PM-10 Task 5)

package com.atlas.bts.identity.permission

import com.atlas.bts.identity.systemrole.JdbcSystemRoleAssignmentRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID

/**
 * [IdentityAccessSystemPermissionResolver.hasGlobalPermission] 통합테스트 (FR-PM-10 Task 5).
 *
 * 판정식 `grant 보유 OR isSystemAdmin` (ADR `2026-07-17-global-permission-grants.md` **D-2**) 을 실 PostgreSQL 로 검증한다.
 * [GlobalPermissionGrantRepository.hasGrant] 는 grant 축만 보므로, `OR isSystemAdmin` 항의 합성이
 * 실제로 일어나는지는 이 판정기 층에서만 확인된다.
 *
 * ## 이 테스트가 지키는 것 — override 소실 (ADR 잔여 위험 3)
 * `SystemPermissionResolver.hasGlobalPermission` 의 인터페이스 default 는 `= isSystemAdmin` 이다(ADR D-3).
 * prod 어댑터가 override 를 잃으면 default 가 되살아나 판정이 **SYSTEM_ADMIN 전용으로 조용히 되돌아간다**.
 * fail-closed 라 장애로 드러나지 않으므로 — 아무도 모르게 기능이 사라진다 — 테스트가 유일한 가드다.
 *
 * ## 판별자 — `isSystemAdmin(userId)).isFalse()` 선단언을 지우지 말 것
 * override 유무를 가르는 것은 **"비-SYSTEM_ADMIN + grant"** 조합 하나뿐이다. 행위자가 SYSTEM_ADMIN 이
 * 아님을 먼저 못박지 않으면, true 를 받은 이유가 grant 인지 SYSTEM_ADMIN 인지 구분되지 않아
 * override 를 지워도 초록인 **vacuous 가드**가 된다(ADR D-2 말미).
 *
 * | # | 케이스 | 판별 대상 |
 * |---|---|---|
 * | 1 | 비-SYSTEM_ADMIN + USER grant → true | **override 유무** (default 면 false) |
 * | 2 | SYSTEM_ADMIN + grant 없음 → true | `OR isSystemAdmin` 항 |
 * | 3 | 둘 다 없음 → false | fail-closed |
 * | 4 | 비-SYSTEM_ADMIN + GROUP grant → true | **override 유무** + GROUP 전파 |
 *
 * ## `@ActiveProfiles("prod")` 를 달지 않는 이유
 * non-prod 마스킹 우려의 근거인 항상-`true` 스텁 `NonProdAllowSystemAdminResolver` 는 **issue-tracking 소속**이라
 * identity-access 스캔 경로에 없고, [IdentityAccessSystemPermissionResolver] 는 `@Profile` 이 아예 없어
 * 모든 프로파일에서 실제 판정한다(그 KDoc — "AlwaysAllow stub 을 두지 않는다").
 *
 * ## 테스트 환경
 * `GlobalPermissionGrantRepositoryIntegrationTest`(T4) 의 `@JdbcTest` + Testcontainers 패턴을 복제한다.
 * `@JdbcTest` 는 `@Component`/`@Repository` 를 스캔하지 않으므로 판정기와 협력자 2종을 명시로 `@Import` 한다.
 * Flyway 가 V001~V036 을 전량 적용해 `global_permission_grants`(V036) · `system_role_assignments`(V012) ·
 * `group_memberships`(V015) 가 실 스키마로 존재한다.
 *
 * @see GlobalPermissionGrantRepository grant 축 판정 (SYSTEM_ADMIN 은 여기서 판정되지 않는다)
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(
    IdentityAccessSystemPermissionResolver::class,
    GlobalPermissionGrantRepository::class,
    JdbcSystemRoleAssignmentRepository::class,
)
@Testcontainers
class IdentityAccessSystemPermissionResolverGlobalPermissionTest {
    companion object {
        /** V036 CHECK 가 허용하는 유일한 전역 권한코드 (ADR D-1). */
        private const val CREATE_PROJECT = "CREATE_PROJECT"

        @Container
        @JvmStatic
        val postgres: PostgreSQLContainer<*> =
            PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName("bts_test")
                .withUsername("bts")
                .withPassword("bts_test")

        @DynamicPropertySource
        @JvmStatic
        fun postgresProps(r: DynamicPropertyRegistry) {
            r.add("spring.datasource.url") { postgres.jdbcUrl }
            r.add("spring.datasource.username") { postgres.username }
            r.add("spring.datasource.password") { postgres.password }
            r.add("spring.flyway.enabled") { "true" }
        }
    }

    @Autowired
    private lateinit var resolver: IdentityAccessSystemPermissionResolver

    @Autowired
    private lateinit var grantRepo: GlobalPermissionGrantRepository

    @Autowired
    private lateinit var jdbc: NamedParameterJdbcTemplate

    /**
     * grant 를 부여한 SYSTEM_ADMIN — ADR D-5 감사 흔적의 주체.
     *
     * [GlobalPermissionGrantRepository.grant] 는 `grantedBy` 에 기본값을 두지 않으므로(ADR D-5 —
     * 기본값은 "누가 줬나"를 조용히 위조하는 통로다) 테스트도 매번 명시로 넘긴다.
     */
    private lateinit var adminId: UUID

    @BeforeEach
    fun setUp() {
        adminId = seedUser()
    }

    // ── override 회귀 가드 (핵심) ────────────────────────────────────────────────

    /**
     * **이 PR 에서 가장 중요한 단언이다.** override 가 없으면 default 가 [isSystemAdmin] 에 위임해
     * `false` 가 되고 이 테스트가 red 로 잡는다 (ADR 잔여 위험 3).
     */
    @Test
    fun `grant 보유 비-SYSTEM_ADMIN 이 전역권한을 획득한다 (B8 회귀 가드)`() {
        val userId = seedUser()
        grantRepo.grant(CREATE_PROJECT, GranteeType.USER, userId, grantedBy = adminId)

        // ★ 판별자 — 이 사람이 SYSTEM_ADMIN 이 아님을 먼저 못박는다. 이 줄이 없으면 아래 단언이
        //   grant 때문에 통과한 건지 SYSTEM_ADMIN 이라서 통과한 건지 구분하지 못한다.
        assertThat(resolver.isSystemAdmin(userId)).isFalse()

        assertThat(resolver.hasGlobalPermission(userId, CREATE_PROJECT)).isTrue()
    }

    /** 같은 회귀 가드의 GROUP 경로 — 판별자(비-SYSTEM_ADMIN 선단언)는 동일하다. */
    @Test
    fun `GROUP grant 보유 비-SYSTEM_ADMIN 이 전역권한을 획득한다`() {
        val userId = seedUser()
        val groupId = seedGroup()
        seedGroupMembership(groupId, userId)
        grantRepo.grant(CREATE_PROJECT, GranteeType.GROUP, groupId, grantedBy = adminId)

        assertThat(resolver.isSystemAdmin(userId)).isFalse()

        assertThat(resolver.hasGlobalPermission(userId, CREATE_PROJECT)).isTrue()
    }

    // ── OR isSystemAdmin 항 (부트스트랩) ────────────────────────────────────────

    /**
     * SYSTEM_ADMIN 은 grant 없이 통과한다 (ADR D-2).
     *
     * 이 항이 없으면 빈 DB 에서 `CREATE_PROJECT` 보유자가 0명이 되고, grant API 자체가 SYSTEM_ADMIN
     * 게이트라 아무도 첫 grant 를 줄 수 없는 부트스트랩 공백이 생긴다.
     */
    @Test
    fun `SYSTEM_ADMIN 은 grant 없이도 전역권한을 보유한다`() {
        val adminUserId = seedUser()
        seedSystemAdmin(adminUserId)

        assertThat(resolver.hasGlobalPermission(adminUserId, CREATE_PROJECT)).isTrue()
    }

    // ── fail-closed ─────────────────────────────────────────────────────────────

    /** 판정 불가·미부여는 전부 거부로 수렴한다 (ADR D-2). */
    @Test
    fun `grant 도 SYSTEM_ADMIN 도 아니면 false (fail-closed)`() {
        assertThat(resolver.hasGlobalPermission(seedUser(), CREATE_PROJECT)).isFalse()
    }

    // ── 헬퍼 ────────────────────────────────────────────────────────────────────

    /**
     * users 행 1개를 삽입하고 id 를 반환한다.
     *
     * `grantee_id`/`granted_by` 에는 FK 가 없지만(ADR D-4), `group_memberships.user_id`(V015) 와
     * `system_role_assignments.user_id`(V012) 에는 FK 가 있어 실 users 행이 필요하다.
     */
    private fun seedUser(): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO users (id, username, display_name) VALUES (:id, :username, :displayName)",
            mapOf("id" to id, "username" to "fr-pm-10-t5-$id", "displayName" to "FR-PM-10 T5 User"),
        )
        return id
    }

    /** user_groups 행 1개를 삽입하고 id 를 반환한다 (name 은 전역 UNIQUE 라 id 로 유일화). */
    private fun seedGroup(): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO user_groups (id, name) VALUES (:id, :name)",
            mapOf("id" to id, "name" to "fr-pm-10-t5-group-$id"),
        )
        return id
    }

    private fun seedGroupMembership(
        groupId: UUID,
        userId: UUID,
    ) {
        jdbc.update(
            "INSERT INTO group_memberships (group_id, user_id) VALUES (:groupId, :userId)",
            mapOf("groupId" to groupId, "userId" to userId),
        )
    }

    /** SYSTEM_ADMIN 역할을 직접 INSERT 한다 — 판정기가 읽는 경로(`system_role_assignments`, V012)의 상태를 만든다. */
    private fun seedSystemAdmin(userId: UUID) {
        jdbc.update(
            "INSERT INTO system_role_assignments (user_id, role) VALUES (:userId, 'SYSTEM_ADMIN')",
            mapOf("userId" to userId),
        )
    }
}
