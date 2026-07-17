// global_permission_grants 테이블 접근 JdbcTemplate 리포지토리 — 부여/회수/목록/판정 (FR-PM-10)

package com.atlas.bts.identity.permission

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.util.UUID

@Repository
@Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED)
class GlobalPermissionGrantRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    fun grant(
        permission: String,
        granteeType: GranteeType,
        granteeId: UUID,
        grantedBy: UUID,
    ): GlobalPermissionGrant =
        requireNotNull(
            jdbc.query(
                """
                INSERT INTO global_permission_grants (permission, grantee_type, grantee_id, granted_by)
                VALUES (:permission, :granteeType, :granteeId, :grantedBy)
                RETURNING id, permission, grantee_type, grantee_id, granted_by, created_at
                """,
                mapOf(
                    "permission" to permission,
                    "granteeType" to granteeType.name,
                    "granteeId" to granteeId,
                    "grantedBy" to grantedBy,
                ),
                GrantRowMapper,
            ).firstOrNull(),
        ) { "INSERT ... RETURNING 이 행을 반환하지 않았습니다 (전역 권한 부여 실패)." }

    fun revoke(id: UUID): Boolean =
        jdbc.update(
            "DELETE FROM global_permission_grants WHERE id = :id",
            mapOf("id" to id),
        ) > 0

    @Transactional(readOnly = true)
    fun list(): List<GlobalPermissionGrant> =
        jdbc.query(
            """
            SELECT id, permission, grantee_type, grantee_id, granted_by, created_at
            FROM global_permission_grants
            ORDER BY created_at, id
            """,
            GrantRowMapper,
        )

    @Transactional(readOnly = true)
    fun hasGrant(
        actorId: UUID,
        permission: String,
    ): Boolean =
        jdbc.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                FROM global_permission_grants g
                WHERE g.permission = :permission
                  AND (
                        (g.grantee_type = 'USER'  AND g.grantee_id = :actorId)
                     OR (g.grantee_type = 'GROUP' AND g.grantee_id IN (
                            SELECT gm.group_id FROM group_memberships gm WHERE gm.user_id = :actorId
                        ))
                  )
            )
            """,
            mapOf("permission" to permission, "actorId" to actorId),
            Boolean::class.java,
        ) ?: false
}

/** global_permission_grants 행을 [GlobalPermissionGrant] 도메인 모델로 변환하는 RowMapper. */
private object GrantRowMapper : RowMapper<GlobalPermissionGrant> {
    override fun mapRow(
        rs: ResultSet,
        rowNum: Int,
    ): GlobalPermissionGrant =
        GlobalPermissionGrant(
            id = rs.getObject("id", UUID::class.java),
            permission = rs.getString("permission"),
            granteeType = GranteeType.valueOf(rs.getString("grantee_type")),
            granteeId = rs.getObject("grantee_id", UUID::class.java),
            grantedBy = rs.getObject("granted_by", UUID::class.java),
            createdAt = rs.getTimestamp("created_at").toInstant(),
        )
}
