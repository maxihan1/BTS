// SystemRoleAssignmentRepository JDBC 구현체 (FR-PM-08 Task 2)

package com.atlas.bts.identity.systemrole

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * [SystemRoleAssignmentRepository] JDBC 구현체 (FR-PM-08 Task 2).
 *
 * NamedParameterJdbcTemplate `:param` 바인딩만 사용하며 SQL 문자열 결합은 하지 않는다 (DEVELOPMENT.md §1.3).
 * 트랜잭션 경계를 자체적으로 열지 않으며, 호출하는 상위 서비스의 트랜잭션에 참여한다.
 */
@Repository
class JdbcSystemRoleAssignmentRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) : SystemRoleAssignmentRepository {

    /**
     * `(user_id, role)` UNIQUE 제약 위반 시 `ON CONFLICT DO NOTHING`으로 멱등 처리한다 (EC4).
     */
    override fun assign(userId: UUID, role: SystemRole) {
        jdbc.update(
            """
            INSERT INTO system_role_assignments (user_id, role)
            VALUES (:userId, :role)
            ON CONFLICT (user_id, role) DO NOTHING
            """,
            mapOf("userId" to userId, "role" to role.name),
        )
    }

    override fun findRolesByUser(userId: UUID): Set<SystemRole> =
        jdbc.query(
            """
            SELECT role
            FROM system_role_assignments
            WHERE user_id = :userId
            """,
            mapOf("userId" to userId),
        ) { rs, _ -> SystemRole.from(rs.getString("role")) }
            .toSet()

    override fun existsByRole(role: SystemRole): Boolean =
        jdbc.queryForObject(
            """
            SELECT EXISTS(
                SELECT 1
                FROM system_role_assignments
                WHERE role = :role
            )
            """,
            mapOf("role" to role.name),
            Boolean::class.java,
        ) ?: false
}
