// SystemRoleAssignmentRepository JDBC 구현체 (FR-PM-08 Task 2)

package com.atlas.bts.identity.systemrole

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID

/**
 * [SystemRoleAssignmentRepository] JDBC 구현체 (FR-PM-08 Task 2).
 *
 * **SQL 인젝션 방어:**
 * 모든 파라미터를 NamedParameterJdbcTemplate `:param` 바인딩으로 처리하며,
 * SQL 문자열 결합은 하지 않는다 (DEVELOPMENT.md §1.3).
 *
 * **트랜잭션 경계:**
 * 자체 트랜잭션을 열지 않으며, 호출하는 상위 서비스의 트랜잭션에 참여한다
 * ([JdbcProjectMembershipRepository]와 달리 멱등 단건 연산만 다루므로 별도 격리 설정이 없다).
 */
@Repository
class JdbcSystemRoleAssignmentRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) : SystemRoleAssignmentRepository {
    /**
     * 전역 역할을 부여한다.
     *
     * `(user_id, role)` UNIQUE 제약 위반 시 `ON CONFLICT DO NOTHING`으로 멱등 처리한다 (EC4).
     * 이미 존재하면 어떤 행도 변경하지 않고 예외도 던지지 않는다.
     */
    override fun assign(
        userId: UUID,
        role: SystemRole,
    ) {
        jdbc.update(
            SQL_ASSIGN,
            mapOf("userId" to userId, "role" to role.name),
        )
    }

    /**
     * 사용자에게 부여된 전역 역할을 [Set]으로 반환한다.
     *
     * 동일 역할이 중복 저장될 수 없으므로(UNIQUE 제약) 결과 집합에 중복이 없다.
     */
    override fun findRolesByUser(userId: UUID): Set<SystemRole> =
        jdbc.query(
            SQL_FIND_ROLES_BY_USER,
            mapOf("userId" to userId),
            RoleRowMapper,
        ).toSet()

    /**
     * 해당 전역 역할 보유자가 한 명이라도 존재하는지 `EXISTS`로 확인한다.
     *
     * 전체 행을 세지 않고 첫 일치 행에서 단락 평가되므로 부트스트랩 검사에 적합하다.
     */
    override fun existsByRole(role: SystemRole): Boolean =
        jdbc.queryForObject(
            SQL_EXISTS_BY_ROLE,
            mapOf("role" to role.name),
            Boolean::class.java,
        ) ?: false

    // ── SQL 상수 ─────────────────────────────────────────────────────────────

    private companion object {
        /** 전역 역할 부여 — UNIQUE(user_id, role) 위반 시 멱등하게 무시 (EC4). */
        const val SQL_ASSIGN = """
            INSERT INTO system_role_assignments (user_id, role)
            VALUES (:userId, :role)
            ON CONFLICT (user_id, role) DO NOTHING
        """

        /** 사용자별 전역 역할 목록 조회. */
        const val SQL_FIND_ROLES_BY_USER = """
            SELECT role
            FROM system_role_assignments
            WHERE user_id = :userId
        """

        /** 특정 전역 역할 보유자 존재 여부 — EXISTS 단락 평가. */
        const val SQL_EXISTS_BY_ROLE = """
            SELECT EXISTS(
                SELECT 1
                FROM system_role_assignments
                WHERE role = :role
            )
        """
    }
}

/** system_role_assignments.role RowMapper — ResultSet의 role 컬럼을 [SystemRole]로 변환 */
private object RoleRowMapper : RowMapper<SystemRole> {
    override fun mapRow(
        rs: ResultSet,
        rowNum: Int,
    ): SystemRole = SystemRole.from(rs.getString("role"))
}
