// JdbcSystemRoleAssignmentRepository 통합 테스트 — 멱등 부여/조회/존재여부 검증 (FR-PM-08 Task 2)

package com.atlas.bts.identity.systemrole

import com.atlas.bts.identity.support.SharedPostgres
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
import java.util.UUID

/**
 * JdbcSystemRoleAssignmentRepository 통합 테스트 (FR-PM-08 Task 2).
 *
 * ## 테스트 환경
 * - `@JdbcTest` + Testcontainers PostgreSQL 16 — Flyway가 V001~V010을 자동 적용한다.
 * - `system_role_assignments.user_id`는 `users.id` FK이므로 [setUp]에서 테스트 사용자를 사전 삽입한다.
 *
 * ## 검증 시나리오
 * | 메서드 | 케이스 |
 * |---|---|
 * | assign + findRolesByUser | 부여 후 SYSTEM_ADMIN이 조회된다 |
 * | assign (중복) | 같은 (user, role) 두 번 부여해도 예외 없이 멱등 (ON CONFLICT DO NOTHING, EC4) |
 * | existsByRole | 비어있을 때 false, 부여 후 true |
 */
@JdbcTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(JdbcSystemRoleAssignmentRepository::class)
class JdbcSystemRoleAssignmentRepositoryTest {
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
    private lateinit var repo: SystemRoleAssignmentRepository

    @Autowired
    private lateinit var jdbc: NamedParameterJdbcTemplate

    /** 테스트마다 재사용할 고정 사용자 ID */
    private val userId = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        // FK 순서 준수: system_role_assignments → users 순으로 삭제
        jdbc.update("DELETE FROM system_role_assignments", emptyMap<String, Any>())
        jdbc.update("DELETE FROM local_credentials", emptyMap<String, Any>())
        jdbc.update("DELETE FROM user_external_accounts", emptyMap<String, Any>())
        jdbc.update("DELETE FROM users", emptyMap<String, Any>())

        // users.id FK 충족을 위해 테스트 사용자 사전 삽입
        jdbc.update(
            """
            INSERT INTO users (id, username, display_name)
            VALUES (:id, :username, :displayName)
            """,
            mapOf("id" to userId, "username" to "admin1", "displayName" to "Admin One"),
        )
    }

    // ── assign + findRolesByUser ──────────────────────────────────────────────

    @Test
    fun `assign — SYSTEM_ADMIN 부여 후 findRolesByUser가 SYSTEM_ADMIN을 포함한다`() {
        repo.assign(userId, SystemRole.SYSTEM_ADMIN)

        val roles = repo.findRolesByUser(userId)

        assertThat(roles).contains(SystemRole.SYSTEM_ADMIN)
    }

    @Test
    fun `findRolesByUser — 부여된 역할이 없으면 빈 집합을 반환한다`() {
        val roles = repo.findRolesByUser(userId)

        assertThat(roles).isEmpty()
    }

    // ── assign 멱등 (EC4) ─────────────────────────────────────────────────────

    @Test
    fun `assign — 같은 user role을 두 번 부여해도 예외 없이 행은 1개만 남는다`() {
        repo.assign(userId, SystemRole.SYSTEM_ADMIN)
        repo.assign(userId, SystemRole.SYSTEM_ADMIN)

        val count =
            jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM system_role_assignments
                WHERE user_id = :userId
                  AND role = :role
                """,
                mapOf("userId" to userId, "role" to SystemRole.SYSTEM_ADMIN.name),
                Int::class.java,
            )

        assertThat(count).isEqualTo(1)
    }

    // ── existsByRole ──────────────────────────────────────────────────────────

    @Test
    fun `existsByRole — 부여된 할당이 없으면 false를 반환한다`() {
        assertThat(repo.existsByRole(SystemRole.SYSTEM_ADMIN)).isFalse()
    }

    @Test
    fun `existsByRole — 역할이 한 건이라도 부여되면 true를 반환한다`() {
        repo.assign(userId, SystemRole.SYSTEM_ADMIN)

        assertThat(repo.existsByRole(SystemRole.SYSTEM_ADMIN)).isTrue()
    }
}
