// 권한 스킴 JDBC 구현체 — 유효 스킴 fallback + EXISTS 서브쿼리로 권한 판정

package com.atlas.bts.identity.permission

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * [PermissionSchemeRepository] JDBC 구현체 (FR-PM-02 Task 3).
 *
 * ## 유효 스킴 해석 전략
 * `project_permission_scheme` LEFT JOIN으로 프로젝트 매핑 scheme_id 를 조회하고,
 * 매핑이 없으면 `COALESCE(매핑 scheme_id, 기본 scheme_id)` 로 기본 스킴을 fallback으로 사용한다.
 * 기본 스킴은 `WHERE is_default = TRUE` 서브쿼리로 동적 조회 — UUID 하드코딩 없음.
 *
 * ## SQL 인젝션 방어
 * 모든 파라미터는 NamedParameterJdbcTemplate `:param` 바인딩으로 처리 (DEVELOPMENT.md §1.3).
 *
 * ## count 대신 EXISTS
 * 다중 JOIN + count는 cartesian product 위험이 있으므로 EXISTS 스칼라 서브쿼리를 사용한다
 * (learnings [[cartesian-product-jooq-leftjoin-count]]).
 */
@Repository
@Transactional(readOnly = true)
class JdbcPermissionSchemeRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) : PermissionSchemeRepository {
    /**
     * 프로젝트의 유효 스킴에서 지정된 role이 permissionCode를 보유하는지 판정한다.
     *
     * 단일 SQL로 유효 스킴 해석(fallback 포함)과 EXISTS 권한 판정을 동시에 수행한다.
     *
     * @param projectId      판정 대상 프로젝트 UUID
     * @param role           역할 문자열 (예: "PROJECT_ADMIN", "MEMBER")
     * @param permissionCode 권한 코드 (예: "CREATE_ISSUE", "EDIT_ISSUE", "DELETE_ISSUE")
     * @return role_permissions 에 해당 행이 존재하면 true, 아니면 false
     */
    override fun roleHasPermission(
        projectId: UUID,
        role: String,
        permissionCode: String,
    ): Boolean =
        jdbc.queryForObject(
            SQL_ROLE_HAS_PERMISSION,
            mapOf(
                "projectId" to projectId,
                "role" to role,
                "permissionCode" to permissionCode,
            ),
            Boolean::class.java,
        ) ?: false

    private companion object {
        /**
         * 유효 스킴 fallback + EXISTS 권한 판정 단일 SQL.
         *
         * 1. `project_permission_scheme` LEFT JOIN으로 projectId 에 매핑된 scheme_id 를 시도한다.
         * 2. 매핑이 없으면 COALESCE가 `permission_schemes WHERE is_default = TRUE` 서브쿼리로 기본 스킴을 선택한다.
         * 3. 결정된 effective_scheme_id 와 role + permission_code 로 role_permissions 를 EXISTS 조회한다.
         *
         * cartesian product 위험을 피하기 위해 count(*) 대신 EXISTS 스칼라 서브쿼리를 사용한다.
         */
        const val SQL_ROLE_HAS_PERMISSION = """
            SELECT EXISTS (
                SELECT 1
                FROM role_permissions rp
                WHERE rp.scheme_id = COALESCE(
                    (SELECT pps.scheme_id
                     FROM project_permission_scheme pps
                     WHERE pps.project_id = :projectId),
                    (SELECT ps.id
                     FROM permission_schemes ps
                     WHERE ps.is_default = TRUE)
                )
                  AND rp.role = :role
                  AND rp.permission_code = :permissionCode
            )
        """
    }
}
