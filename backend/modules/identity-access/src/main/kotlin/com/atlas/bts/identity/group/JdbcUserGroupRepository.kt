// UserGroupRepository JDBC 구현체 — raw SQL + NamedParameterJdbcTemplate (FR-PM-09 Task 3)

package com.atlas.bts.identity.group

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.util.UUID

/**
 * [UserGroupRepository] JDBC 구현체 (FR-PM-09 Task 3).
 *
 * **SQL 인젝션 방어:**
 * 모든 파라미터를 NamedParameterJdbcTemplate `:param` 바인딩(prepared statement)으로 처리하며,
 * SQL 문자열 결합은 하지 않는다 (DEVELOPMENT.md §1.1-3).
 *
 * **RETURNING 사용 규칙:**
 * 단건 신규/확정 연산([create]/[update])만 `RETURNING` 으로 DB 가 채운 값을 회수한다.
 * 멤버십 멱등 연산([addMember]/[removeMember])은 `RETURNING` 을 쓰지 않고 `jdbc.update` 로
 * 영향 행 수와 무관하게 처리한다 — `ON CONFLICT DO NOTHING` 이 0행을 반환하면
 * `queryForObject` 가 `EmptyResultDataAccessException` 으로 깨지기 때문이다.
 *
 * **멤버 수 집계:**
 * [findAll] 의 memberCount 는 LEFT JOIN 대신 스칼라 서브쿼리로 계산해
 * cartesian product 를 회피한다.
 *
 * **트랜잭션 경계:**
 * 자체 트랜잭션을 열지 않으며, 호출하는 상위 서비스의 트랜잭션에 참여한다.
 */
@Repository
class JdbcUserGroupRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) : UserGroupRepository {
    override fun create(
        name: String,
        description: String?,
    ): UserGroup =
        requireNotNull(
            jdbc.query(
                SQL_CREATE,
                mapOf("name" to name, "description" to description),
                GroupRowMapper,
            ).firstOrNull(),
        ) { "INSERT ... RETURNING 이 행을 반환하지 않았습니다 (그룹 생성 실패)." }

    override fun findById(id: UUID): UserGroup? =
        jdbc.query(
            SQL_FIND_BY_ID,
            mapOf("id" to id),
            GroupRowMapper,
        ).firstOrNull()

    override fun findAll(): List<UserGroupWithCount> = jdbc.query(SQL_FIND_ALL, GroupWithCountRowMapper)

    override fun update(
        id: UUID,
        name: String,
        description: String?,
    ): UserGroup? =
        jdbc.query(
            SQL_UPDATE,
            mapOf("id" to id, "name" to name, "description" to description),
            GroupRowMapper,
        ).firstOrNull()

    override fun delete(id: UUID): Boolean = jdbc.update(SQL_DELETE, mapOf("id" to id)) > 0

    override fun addMember(
        groupId: UUID,
        userId: UUID,
    ) {
        jdbc.update(SQL_ADD_MEMBER, mapOf("groupId" to groupId, "userId" to userId))
    }

    override fun removeMember(
        groupId: UUID,
        userId: UUID,
    ) {
        jdbc.update(SQL_REMOVE_MEMBER, mapOf("groupId" to groupId, "userId" to userId))
    }

    override fun listMemberIds(groupId: UUID): List<UUID> =
        jdbc.query(SQL_LIST_MEMBER_IDS, mapOf("groupId" to groupId)) { rs, _ ->
            rs.getObject("user_id", UUID::class.java)
        }

    override fun existsById(id: UUID): Boolean =
        jdbc.queryForObject(
            SQL_EXISTS_BY_ID,
            mapOf("id" to id),
            Boolean::class.java,
        ) ?: false

    override fun isMemberOf(
        groupId: UUID,
        userId: UUID,
    ): Boolean =
        jdbc.queryForObject(
            SQL_IS_MEMBER_OF,
            mapOf("groupId" to groupId, "userId" to userId),
            Boolean::class.java,
        ) ?: false

    // ── SQL 상수 ─────────────────────────────────────────────────────────────────

    private companion object {
        /** 신규 그룹 INSERT — DB 기본값(id/타임스탬프)까지 RETURNING 으로 회수. */
        const val SQL_CREATE = """
            INSERT INTO user_groups (name, description)
            VALUES (:name, :description)
            RETURNING id, name, description, created_at, updated_at
        """

        /** id 단건 조회. */
        const val SQL_FIND_BY_ID = """
            SELECT id, name, description, created_at, updated_at
            FROM user_groups
            WHERE id = :id
        """

        /** 전체 그룹 + 멤버 수(스칼라 서브쿼리, LEFT JOIN 금지) 조회. */
        const val SQL_FIND_ALL = """
            SELECT
                ug.id,
                ug.name,
                ug.description,
                ug.created_at,
                ug.updated_at,
                (
                    SELECT count(*)
                    FROM group_memberships gm
                    WHERE gm.group_id = ug.id
                ) AS member_count
            FROM user_groups ug
            ORDER BY ug.name
        """

        /** 이름/설명 갱신 — updated_at 갱신 후 갱신 행 RETURNING. */
        const val SQL_UPDATE = """
            UPDATE user_groups
            SET name = :name,
                description = :description,
                updated_at = NOW()
            WHERE id = :id
            RETURNING id, name, description, created_at, updated_at
        """

        /** 그룹 삭제 — 멤버십은 FK ON DELETE CASCADE 로 동반 삭제. */
        const val SQL_DELETE = """
            DELETE FROM user_groups
            WHERE id = :id
        """

        /** 멤버 추가 — 복합 PK(group_id, user_id) 위반 시 멱등하게 무시. */
        const val SQL_ADD_MEMBER = """
            INSERT INTO group_memberships (group_id, user_id)
            VALUES (:groupId, :userId)
            ON CONFLICT (group_id, user_id) DO NOTHING
        """

        /** 멤버 제거 — 없는 멤버여도 no-op. */
        const val SQL_REMOVE_MEMBER = """
            DELETE FROM group_memberships
            WHERE group_id = :groupId AND user_id = :userId
        """

        /** 그룹 멤버 식별자 목록. */
        const val SQL_LIST_MEMBER_IDS = """
            SELECT user_id
            FROM group_memberships
            WHERE group_id = :groupId
        """

        /** 그룹 존재 여부 — EXISTS 단락 평가. */
        const val SQL_EXISTS_BY_ID = """
            SELECT EXISTS(
                SELECT 1
                FROM user_groups
                WHERE id = :id
            )
        """

        /** 사용자가 그룹 멤버인지 — 복합 PK 단건 EXISTS(없는 그룹이면 행 없어 false). */
        const val SQL_IS_MEMBER_OF = """
            SELECT EXISTS(
                SELECT 1
                FROM group_memberships
                WHERE group_id = :groupId
                  AND user_id = :userId
            )
        """
    }
}

/** user_groups 행을 [UserGroup] 도메인 모델로 변환하는 RowMapper. */
private object GroupRowMapper : RowMapper<UserGroup> {
    override fun mapRow(
        rs: ResultSet,
        rowNum: Int,
    ): UserGroup = mapUserGroup(rs)
}

/** user_groups 행 + member_count 컬럼을 [UserGroupWithCount] 로 변환하는 RowMapper. */
private object GroupWithCountRowMapper : RowMapper<UserGroupWithCount> {
    override fun mapRow(
        rs: ResultSet,
        rowNum: Int,
    ): UserGroupWithCount =
        UserGroupWithCount(
            group = mapUserGroup(rs),
            memberCount = rs.getInt("member_count"),
        )
}

/** user_groups 공통 컬럼(id/name/description/타임스탬프)을 [UserGroup] 으로 매핑한다. */
private fun mapUserGroup(rs: ResultSet): UserGroup =
    UserGroup(
        id = rs.getObject("id", UUID::class.java),
        name = rs.getString("name"),
        description = rs.getString("description"),
        createdAt = rs.getTimestamp("created_at").toInstant(),
        updatedAt = rs.getTimestamp("updated_at").toInstant(),
    )
