// GlobalPermissionGrantRepository 통합테스트 — grant/revoke/list/hasGrant 를 실 PostgreSQL 로 검증 (FR-PM-10)

package com.atlas.bts.identity.permission

import com.atlas.bts.identity.support.SharedPostgres
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.util.UUID

/**
 * [GlobalPermissionGrantRepository] 통합테스트 (FR-PM-10 Task 4).
 *
 * ## 테스트 환경
 * `ProjectMembershipRepositoryIntegrationTest` 의 `@JdbcTest` + Testcontainers PostgreSQL 16 패턴을
 * 복제한다. Flyway 가 V001~V036 을 전량 적용하므로 `global_permission_grants`(V036) 와
 * `group_memberships`(V015) 가 실 스키마로 존재한다.
 *
 * `@JdbcTest` 는 `@Component` 를 스캔하지 않으므로 [GlobalPermissionGrantRepository] 를 `@Import` 한다.
 *
 * ## 검증 시나리오
 * | 메서드 | 케이스 |
 * |---|---|
 * | hasGrant | USER 직접 부여 → true |
 * | hasGrant | GROUP 부여 → 그룹 멤버에게 전파 (true) |
 * | hasGrant | 부여 없음 → false (fail-closed) |
 * | hasGrant | 다른 권한코드로 조회 → false (permission 필터 작동) |
 * | hasGrant | 그룹 탈퇴 → false (권한 회수의 전파) |
 * | revoke | 부여 회수 → true, 이후 hasGrant false, 재회수 false |
 * | grant | 중복 부여 → DuplicateKeyException (409 로 올린다. 조용히 삼키지 않는다) |
 * | list | granted_by 를 포함해 반환 (ADR D-5 감사 흔적) |
 *
 * ## 테스트 격리
 * `@JdbcTest` 는 `@Transactional` 메타라 각 테스트가 자동 롤백된다. `list` 단언이
 * `singleElement()` 인 것은 이 롤백 격리에 기댄 것이며, 격리가 깨지면 **fail 하는 방향**이라 안전하다.
 *
 * ## grantedBy 를 모든 호출이 명시로 넘기는 이유
 * [GlobalPermissionGrantRepository.grant] 는 `grantedBy` 에 기본값을 두지 않는다(ADR D-5).
 * 기본값을 두면 "누가 줬나"를 조용히 위조하는 통로가 되고, 감사 흔적이 무의미해진다.
 * 테스트도 같은 계약을 따라 [adminId] 를 매번 명시한다.
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(GlobalPermissionGrantRepository::class)
class GlobalPermissionGrantRepositoryIntegrationTest {
    companion object {
        /** 전역 권한 판정에 쓰는 권한코드 — V036 CHECK 가 허용하는 유일한 값 (ADR D-1). */
        private const val CREATE_PROJECT = "CREATE_PROJECT"

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
        fun postgresProps(r: DynamicPropertyRegistry) {
            r.add("spring.datasource.url") { postgres.jdbcUrl }
            r.add("spring.datasource.username") { postgres.username }
            r.add("spring.datasource.password") { postgres.password }
            // 템플릿 DB 에서 이미 적용됐다 — 여기서 다시 돌리면 이 최적화가 무의미해진다
            r.add("spring.flyway.enabled") { "false" }
            // 공용 컨테이너라 커넥션 한도도 공유한다. context 캐시가 쌓이면 기본 풀(10)로는
            // max_connections 를 넘긴다 — SharedPostgres 헤더 참조.
            r.add("spring.datasource.hikari.maximum-pool-size") { SharedPostgres.MAX_POOL_SIZE }
        }
    }

    @Autowired
    private lateinit var repo: GlobalPermissionGrantRepository

    @Autowired
    private lateinit var jdbc: NamedParameterJdbcTemplate

    /** grant 를 부여한 SYSTEM_ADMIN — ADR D-5 감사 흔적의 주체. */
    private lateinit var adminId: UUID

    @BeforeEach
    fun setUp() {
        adminId = seedUser()
    }

    // ── hasGrant — USER 직접 부여 ────────────────────────────────────────────────

    @Test
    fun `USER grant 를 부여하면 hasGrant 가 true 를 반환한다`() {
        val userId = seedUser()

        repo.grant(CREATE_PROJECT, GranteeType.USER, userId, grantedBy = adminId)

        assertThat(repo.hasGrant(userId, CREATE_PROJECT)).isTrue()
    }

    // ── hasGrant — GROUP 경유 부여 ───────────────────────────────────────────────

    @Test
    fun `GROUP grant 는 그룹 멤버에게 전파된다`() {
        val userId = seedUser()
        val groupId = seedGroup()
        seedGroupMembership(groupId, userId)

        repo.grant(CREATE_PROJECT, GranteeType.GROUP, groupId, grantedBy = adminId)

        assertThat(repo.hasGrant(userId, CREATE_PROJECT)).isTrue()
    }

    // ── hasGrant — fail-closed ──────────────────────────────────────────────────

    @Test
    fun `grant 가 없으면 false 를 반환한다 (fail-closed)`() {
        assertThat(repo.hasGrant(seedUser(), CREATE_PROJECT)).isFalse()
    }

    /**
     * `permission` 필터 검증 — **방향을 뒤집어** 같은 술어를 확인한다.
     *
     * plan 초안은 `SOME_OTHER_PERMISSION` 을 **부여**한 뒤 `CREATE_PROJECT` 조회가 false 임을 보려 했으나,
     * V036 은 `CHECK (permission IN ('CREATE_PROJECT'))` 라 그 INSERT 자체가 불가능하다
     * (`DataIntegrityViolationException` — 단언에 도달하지 못한다). CHECK 는 ADR D-1 의 의도된 설계라
     * 풀지 않고, 부여 가능한 `CREATE_PROJECT` 를 넣고 **다른 코드로 조회**해 같은 필터를 겨냥했다.
     *
     * 판별자 — `hasGrant` 에서 `g.permission = :permission` 을 지우면 이 조회가 true 가 되어 fail 한다.
     * 조회 인자는 CHECK 대상이 아니므로(읽기 경로) 미등록 코드를 넘겨도 DB 는 거부하지 않는다.
     */
    @Test
    fun `다른 권한코드로 조회하면 false 를 반환한다`() {
        val userId = seedUser()
        repo.grant(CREATE_PROJECT, GranteeType.USER, userId, grantedBy = adminId)

        assertThat(repo.hasGrant(userId, "SOME_OTHER_PERMISSION")).isFalse()
    }

    // ── hasGrant — 그룹 탈퇴 전파 (보안) ────────────────────────────────────────

    /**
     * 그룹 탈퇴가 권한 회수로 전파되는지 — **보안 기능**이다.
     * 조용히 안 되면 뗐다고 믿은 권한이 살아 있다. GROUP 가지가 `group_memberships` 를
     * 경유하기 때문에 성립한다(ADR D-4 표 — GROUP 경로는 탈락한다).
     */
    @Test
    fun `그룹에서 탈퇴하면 grant 가 사라진다`() {
        val userId = seedUser()
        val groupId = seedGroup()
        seedGroupMembership(groupId, userId)
        repo.grant(CREATE_PROJECT, GranteeType.GROUP, groupId, grantedBy = adminId)
        // 탈퇴 전에는 보유한다 — 이 선단언이 없으면 아래 isFalse 가 "원래부터 false" 여도 통과한다
        assertThat(repo.hasGrant(userId, CREATE_PROJECT)).isTrue()

        removeGroupMembership(groupId, userId)

        assertThat(repo.hasGrant(userId, CREATE_PROJECT)).isFalse()
    }

    // ── revoke (보안) ───────────────────────────────────────────────────────────

    @Test
    fun `revoke 하면 hasGrant 가 false 로 돌아가고 true 를 반환한다`() {
        val userId = seedUser()
        val grant = repo.grant(CREATE_PROJECT, GranteeType.USER, userId, grantedBy = adminId)

        assertThat(repo.revoke(grant.id)).isTrue()
        assertThat(repo.hasGrant(userId, CREATE_PROJECT)).isFalse()
        // 없는 id 재회수는 false — 서비스가 404 로 매핑한다. "지웠다고 믿었는데 대상이 없었다"를
        // 조용히 성공으로 만들지 않는다 (ADR D-5)
        assertThat(repo.revoke(grant.id)).isFalse()
    }

    // ── grant — 중복 부여 ───────────────────────────────────────────────────────

    /**
     * 중복 부여가 `DuplicateKeyException` 으로 **올라오는지** 검증한다 (ADR D-1 — 409 로 매핑).
     *
     * plan 의 7건에는 이 가드가 없어 신설했다. 없으면 `grant` 에 `ON CONFLICT DO NOTHING` 이
     * 붙어도 나머지 테스트가 전부 통과한다 — 관리자는 "이미 부여돼 있다"를 알 수 없게 되고,
     * `RETURNING` 이 0행을 내며 500 으로 변질된다.
     * `GlobalPermissionGrantSchemaMigrationTest` 의 UNIQUE 단언은 **스키마**의 성질이고,
     * 이 단언은 **리포지토리가 그 예외를 삼키지 않는다**는 별개의 성질이다.
     */
    @Test
    fun `같은 grantee 에 중복 부여하면 DuplicateKeyException 이 전파된다`() {
        val userId = seedUser()
        repo.grant(CREATE_PROJECT, GranteeType.USER, userId, grantedBy = adminId)

        assertThatThrownBy {
            repo.grant(CREATE_PROJECT, GranteeType.USER, userId, grantedBy = seedUser())
        }.isInstanceOf(DuplicateKeyException::class.java)
    }

    // ── list ────────────────────────────────────────────────────────────────────

    @Test
    fun `list 는 부여한 grant 를 granted_by 와 함께 반환한다`() {
        val userId = seedUser()
        repo.grant(CREATE_PROJECT, GranteeType.USER, userId, grantedBy = adminId)

        assertThat(repo.list()).singleElement().satisfies({
            assertThat(it.permission).isEqualTo(CREATE_PROJECT)
            assertThat(it.granteeType).isEqualTo(GranteeType.USER)
            assertThat(it.granteeId).isEqualTo(userId)
            // ADR D-5 감사 흔적이 실제로 읽힌다 — 컬럼만 있고 매핑이 없으면 여기서 깨진다
            assertThat(it.grantedBy).isEqualTo(adminId)
            assertThat(it.createdAt).isNotNull()
        })
    }

    // ── 헬퍼 ────────────────────────────────────────────────────────────────────

    /**
     * users 행 1개를 삽입하고 id 를 반환한다.
     *
     * `grantee_id`/`granted_by` 에는 FK 가 없지만(ADR D-4), `group_memberships.user_id` 에는
     * V015 FK 가 있어 GROUP 시나리오에 실 users 행이 필요하다. 경로마다 다른 픽스처를 쓰면
     * 시나리오 간 대칭이 깨지므로 전부 실 사용자로 통일한다.
     */
    private fun seedUser(): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO users (id, username, display_name) VALUES (:id, :username, :displayName)",
            mapOf("id" to id, "username" to "fr-pm-10-t4-$id", "displayName" to "FR-PM-10 T4 User"),
        )
        return id
    }

    /** user_groups 행 1개를 삽입하고 id 를 반환한다 (name 은 전역 UNIQUE 라 id 로 유일화). */
    private fun seedGroup(): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "INSERT INTO user_groups (id, name) VALUES (:id, :name)",
            mapOf("id" to id, "name" to "fr-pm-10-t4-group-$id"),
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

    private fun removeGroupMembership(
        groupId: UUID,
        userId: UUID,
    ) {
        jdbc.update(
            "DELETE FROM group_memberships WHERE group_id = :groupId AND user_id = :userId",
            mapOf("groupId" to groupId, "userId" to userId),
        )
    }
}
