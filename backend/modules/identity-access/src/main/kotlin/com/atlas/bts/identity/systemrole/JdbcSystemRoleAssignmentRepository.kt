// SystemRoleAssignmentRepository JDBC 구현체 (FR-PM-08 Task 2) — RED 스텁

package com.atlas.bts.identity.systemrole

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * [SystemRoleAssignmentRepository] JDBC 구현체 (FR-PM-08 Task 2).
 *
 * RED 단계 스텁 — GREEN 단계에서 NamedParameterJdbcTemplate 기반 실제 구현으로 교체한다.
 */
@Repository
class JdbcSystemRoleAssignmentRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) : SystemRoleAssignmentRepository {

    override fun assign(userId: UUID, role: SystemRole): Unit = TODO("GREEN 단계 구현")

    override fun findRolesByUser(userId: UUID): Set<SystemRole> = TODO("GREEN 단계 구현")

    override fun existsByRole(role: SystemRole): Boolean = TODO("GREEN 단계 구현")
}
