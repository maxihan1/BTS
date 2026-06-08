// field_permissions 테이블 접근 — raw SQL + NamedParameterJdbcTemplate (FR-PM-07 PR-A Task 3)

package com.atlas.bts.identity.fieldpermission.repository

import com.atlas.bts.identity.fieldpermission.domain.FieldAccessLevel
import com.atlas.bts.identity.fieldpermission.domain.FieldPermission
import com.bts.shared.permission.FieldKind
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID

/**
 * `field_permissions` 테이블 접근 Repository (FR-PM-07 PR-A Task 3).
 *
 * 필드 수준 권한 규칙(어떤 프로젝트의 어떤 필드를 어떤 그룹에 어떤 수준으로 허용할지)을
 * 저장/조회/삭제한다.
 *
 * **SQL 인젝션 방어:**
 * 모든 파라미터를 NamedParameterJdbcTemplate `:param` 바인딩(prepared statement)으로 처리하며,
 * SQL 문자열 결합은 하지 않는다 (DEVELOPMENT.md §1.1-3).
 *
 * **save 멱등:**
 * `INSERT ... ON CONFLICT DO NOTHING` 으로, 동일 `(project_id, field_kind, field_key, group_id,
 * access_level)` 조합(UNIQUE 제약) 재저장 시 중복 행을 만들지 않는다.
 *
 * **트랜잭션 경계:**
 * 자체 트랜잭션을 열지 않으며, 호출하는 상위 서비스(FR-PM-07 PR-A Task 5)의 트랜잭션에 참여한다.
 *
 * **group CASCADE:**
 * `group_id` FK 는 `ON DELETE CASCADE`(V018) 이므로, 그룹 삭제 시 참조 규칙이 자동 제거된다.
 */
@Repository
class FieldPermissionRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    /**
     * 필드 권한 규칙을 저장한다(멱등).
     *
     * 동일 규칙이 이미 있으면 `ON CONFLICT DO NOTHING` 으로 아무 변화 없이 반환한다.
     * [FieldPermission.id] 는 무시하며 DB 가 채운다.
     *
     * @param rule 저장할 규칙([FieldPermission.create] 로 검증된 도메인 모델).
     */
    fun save(rule: FieldPermission) {
        jdbc.update(
            SQL_SAVE,
            mapOf(
                "projectId" to rule.projectId,
                "fieldKind" to rule.fieldKind.name,
                "fieldKey" to rule.fieldKey,
                "groupId" to rule.groupId,
                "accessLevel" to rule.accessLevel.name,
            ),
        )
    }

    /**
     * 프로젝트의 모든 필드 권한 규칙을 조회한다.
     *
     * @param projectId 프로젝트 식별자.
     * @return 해당 프로젝트의 규칙 목록(필드별·그룹별 행). 규칙이 없으면 빈 목록.
     */
    fun findByProject(projectId: UUID): List<FieldPermission> =
        jdbc.query(SQL_FIND_BY_PROJECT, mapOf("projectId" to projectId), FieldPermissionRowMapper)

    /**
     * 규칙을 식별자로 삭제한다.
     *
     * @param id 삭제할 규칙 식별자.
     * @return 실제로 삭제된 행이 있으면 `true`, 없으면 `false`.
     */
    fun deleteById(id: UUID): Boolean = jdbc.update(SQL_DELETE_BY_ID, mapOf("id" to id)) > 0

    // ── SQL 상수 ─────────────────────────────────────────────────────────────────

    private companion object {
        /** SELECT 컬럼 목록 — [FieldPermissionRowMapper] 가 읽는 컬럼과 동기화 유지용. */
        const val SELECT_COLUMNS = "id, project_id, field_kind, field_key, group_id, access_level"

        /** 규칙 저장 — UNIQUE 조합 위반 시 멱등하게 무시(ON CONFLICT DO NOTHING). */
        const val SQL_SAVE = """
            INSERT INTO field_permissions (project_id, field_kind, field_key, group_id, access_level)
            VALUES (:projectId, :fieldKind, :fieldKey, :groupId, :accessLevel)
            ON CONFLICT (project_id, field_kind, field_key, group_id, access_level) DO NOTHING
        """

        /** 프로젝트 단위 규칙 조회 — idx_field_permissions_project 인덱스 활용. */
        const val SQL_FIND_BY_PROJECT = """
            SELECT $SELECT_COLUMNS
            FROM field_permissions
            WHERE project_id = :projectId
            ORDER BY field_kind, field_key, access_level
        """

        /** 규칙 단건 삭제. */
        const val SQL_DELETE_BY_ID = """
            DELETE FROM field_permissions
            WHERE id = :id
        """
    }
}

/** field_permissions 행을 [FieldPermission] 도메인 모델로 변환하는 RowMapper. */
private object FieldPermissionRowMapper : RowMapper<FieldPermission> {
    override fun mapRow(
        rs: ResultSet,
        rowNum: Int,
    ): FieldPermission =
        FieldPermission(
            id = rs.getObject("id", UUID::class.java),
            projectId = rs.getObject("project_id", UUID::class.java),
            fieldKind = FieldKind.valueOf(rs.getString("field_kind")),
            fieldKey = rs.getString("field_key"),
            groupId = rs.getObject("group_id", UUID::class.java),
            accessLevel = FieldAccessLevel.valueOf(rs.getString("access_level")),
        )
}
