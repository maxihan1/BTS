// ProjectSecuritySchemeRepository JDBC 구현체 — raw SQL + NamedParameterJdbcTemplate (FR-PM-06 Task 4)

package com.atlas.bts.identity.issuesecurity

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * [ProjectSecuritySchemeRepository] JDBC 구현체 (FR-PM-06 Task 4).
 *
 * **SQL 인젝션 방어:**
 * 모든 파라미터를 NamedParameterJdbcTemplate `:param` 바인딩(prepared statement)으로 처리하며,
 * SQL 문자열 결합은 하지 않는다 (DEVELOPMENT.md §1.1-3).
 *
 * **프로젝트당 단일 스킴:**
 * [assign] 은 `INSERT ... ON CONFLICT (project_id) DO UPDATE` 로 프로젝트당 행 1개를 보장한다.
 * 이미 적용된 스킴이 있으면 새 scheme_id 로 덮어쓴다(교체).
 *
 * **멱등 해제:**
 * [unassign] 은 `jdbc.update` 로 영향 행 수와 무관하게 처리해, 미적용 프로젝트여도 no-op 이다.
 */
@Repository
@Transactional
class JdbcProjectSecuritySchemeRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) : ProjectSecuritySchemeRepository {
    override fun assign(
        projectId: UUID,
        schemeId: UUID,
    ) {
        jdbc.update(SQL_ASSIGN, mapOf(PARAM_PROJECT_ID to projectId, PARAM_SCHEME_ID to schemeId))
    }

    @Transactional(readOnly = true)
    override fun findByProject(projectId: UUID): UUID? =
        jdbc.query(SQL_FIND_BY_PROJECT, mapOf(PARAM_PROJECT_ID to projectId)) { rs, _ ->
            rs.getObject("scheme_id", UUID::class.java)
        }.firstOrNull()

    override fun unassign(projectId: UUID) {
        jdbc.update(SQL_UNASSIGN, mapOf(PARAM_PROJECT_ID to projectId))
    }

    // ── SQL 상수 ─────────────────────────────────────────────────────────────────

    private companion object {
        /** 바인딩 파라미터 이름 — SQL `:projectId` / `:schemeId` 와 일치(오타 방지). */
        const val PARAM_PROJECT_ID = "projectId"
        const val PARAM_SCHEME_ID = "schemeId"

        /** 프로젝트 스킴 적용 — project_id PK 충돌 시 새 scheme_id 로 교체(덮어쓰기). */
        const val SQL_ASSIGN = """
            INSERT INTO project_issue_security_schemes (project_id, scheme_id)
            VALUES (:projectId, :schemeId)
            ON CONFLICT (project_id) DO UPDATE SET scheme_id = EXCLUDED.scheme_id
        """

        /** 프로젝트에 적용된 scheme_id 조회 — 미적용이면 0행. */
        const val SQL_FIND_BY_PROJECT = """
            SELECT scheme_id
            FROM project_issue_security_schemes
            WHERE project_id = :projectId
        """

        /** 프로젝트 스킴 적용 해제 — 미적용이어도 no-op. */
        const val SQL_UNASSIGN = """
            DELETE FROM project_issue_security_schemes
            WHERE project_id = :projectId
        """
    }
}
