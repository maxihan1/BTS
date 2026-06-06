// IssueSecuritySchemeRepository JDBC 구현체 — raw SQL + NamedParameterJdbcTemplate (FR-PM-06 PR-A Task 3)

package com.atlas.bts.identity.issuesecurity

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.util.UUID

/**
 * [IssueSecuritySchemeRepository] JDBC 구현체 (FR-PM-06 PR-A Task 3).
 *
 * **SQL 인젝션 방어:**
 * 모든 파라미터를 NamedParameterJdbcTemplate `:param` 바인딩(prepared statement)으로 처리하며,
 * SQL 문자열 결합은 하지 않는다 (DEVELOPMENT.md §1.1-3, DATA.md §5 파라미터 바인딩 예외).
 *
 * **RETURNING 사용 규칙:**
 * 단건 신규/확정 연산([create]/[update]/[addLevel]/[updateLevel])만 `RETURNING` 으로 DB 가
 * 채운 값을 회수한다. 멤버 멱등 추가([addMember])는 `ON CONFLICT DO NOTHING` 이 0행을 반환할 수
 * 있어 `RETURNING` 대신 별도 SELECT 로 정규 행을 회수한다(EmptyResultDataAccessException 회피).
 *
 * **등급 동반 로드:**
 * [findAll] 은 스킴과 등급을 각각 한 번씩 조회해 메모리에서 묶는다(다중 LEFT JOIN cartesian
 * product 회피, `cartesian-product-jooq-leftjoin-count` 교훈).
 *
 * **트랜잭션 경계:**
 * 클래스 수준 `@Transactional` 로 각 연산을 한 트랜잭션으로 묶는다(ArchUnit `TransactionalServiceArchTest`).
 */
@Repository
@Transactional
class JdbcIssueSecuritySchemeRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) : IssueSecuritySchemeRepository {
    // ── 스킴 ──────────────────────────────────────────────────────────────────────

    override fun create(scheme: IssueSecurityScheme): IssueSecurityScheme =
        requireNotNull(
            jdbc.query(
                SQL_SCHEME_CREATE,
                mapOf("name" to scheme.name, "description" to scheme.description),
                SchemeRowMapper,
            ).firstOrNull(),
        ) { "INSERT ... RETURNING 이 행을 반환하지 않았습니다 (스킴 생성 실패)." }

    override fun findById(id: UUID): IssueSecuritySchemeDetail? {
        val scheme = jdbc.query(SQL_SCHEME_FIND_BY_ID, mapOf("id" to id), SchemeRowMapper).firstOrNull()
            ?: return null
        return IssueSecuritySchemeDetail(scheme, listLevels(id))
    }

    override fun findAll(): List<IssueSecuritySchemeDetail> {
        val schemes = jdbc.query(SQL_SCHEME_FIND_ALL, SchemeRowMapper)
        val levelsByScheme = jdbc.query(SQL_LEVEL_FIND_ALL, LevelRowMapper).groupBy { it.schemeId }
        return schemes.map { IssueSecuritySchemeDetail(it, levelsByScheme[it.id] ?: emptyList()) }
    }

    override fun update(
        id: UUID,
        name: String,
        description: String?,
    ): IssueSecurityScheme? =
        jdbc.query(
            SQL_SCHEME_UPDATE,
            mapOf("id" to id, "name" to name, "description" to description),
            SchemeRowMapper,
        ).firstOrNull()

    override fun delete(id: UUID): Boolean = jdbc.update(SQL_SCHEME_DELETE, mapOf("id" to id)) > 0

    // ── 등급 ──────────────────────────────────────────────────────────────────────

    override fun addLevel(level: IssueSecurityLevel): IssueSecurityLevel =
        requireNotNull(
            jdbc.query(
                SQL_LEVEL_CREATE,
                mapOf(
                    "schemeId" to level.schemeId,
                    "name" to level.name,
                    "description" to level.description,
                    "isDefault" to level.isDefault,
                ),
                LevelRowMapper,
            ).firstOrNull(),
        ) { "INSERT ... RETURNING 이 행을 반환하지 않았습니다 (등급 생성 실패)." }

    override fun listLevels(schemeId: UUID): List<IssueSecurityLevel> =
        jdbc.query(SQL_LEVEL_LIST, mapOf("schemeId" to schemeId), LevelRowMapper)

    override fun updateLevel(
        id: UUID,
        name: String,
        description: String?,
        isDefault: Boolean,
    ): IssueSecurityLevel? =
        jdbc.query(
            SQL_LEVEL_UPDATE,
            mapOf("id" to id, "name" to name, "description" to description, "isDefault" to isDefault),
            LevelRowMapper,
        ).firstOrNull()

    override fun deleteLevel(id: UUID): Boolean = jdbc.update(SQL_LEVEL_DELETE, mapOf("id" to id)) > 0

    // ── 멤버 ──────────────────────────────────────────────────────────────────────

    override fun addMember(member: SecurityLevelMember): SecurityLevelMember {
        // ON CONFLICT DO NOTHING 은 충돌 시 0행 → RETURNING 사용 불가. 멱등 추가 후 정규 행을 별도 SELECT.
        jdbc.update(
            SQL_MEMBER_INSERT,
            mapOf(
                "levelId" to member.levelId,
                "memberType" to member.memberType.name,
                "memberValue" to member.memberValue,
            ),
        )
        return requireNotNull(
            jdbc.query(
                SQL_MEMBER_FIND_ONE,
                mapOf(
                    "levelId" to member.levelId,
                    "memberType" to member.memberType.name,
                    "memberValue" to member.memberValue,
                ),
                MemberRowMapper,
            ).firstOrNull(),
        ) { "멤버 추가 후 정규 행 조회에 실패했습니다 (level=${member.levelId}, type=${member.memberType})." }
    }

    override fun listMembers(levelId: UUID): List<SecurityLevelMember> =
        jdbc.query(SQL_MEMBER_LIST, mapOf("levelId" to levelId), MemberRowMapper)

    override fun removeMemberById(id: UUID): Boolean = jdbc.update(SQL_MEMBER_DELETE, mapOf("id" to id)) > 0

    // ── SQL 상수 ─────────────────────────────────────────────────────────────────

    private companion object {
        /** 신규 스킴 INSERT — DB 기본값(id/타임스탬프)까지 RETURNING 으로 회수. */
        const val SQL_SCHEME_CREATE = """
            INSERT INTO issue_security_schemes (name, description)
            VALUES (:name, :description)
            RETURNING id, name, description, created_at, updated_at
        """

        /** id 단건 스킴 조회. */
        const val SQL_SCHEME_FIND_BY_ID = """
            SELECT id, name, description, created_at, updated_at
            FROM issue_security_schemes
            WHERE id = :id
        """

        /** 전체 스킴 조회(등급은 별도 쿼리로 묶음 — cartesian 회피). */
        const val SQL_SCHEME_FIND_ALL = """
            SELECT id, name, description, created_at, updated_at
            FROM issue_security_schemes
            ORDER BY name
        """

        /** 스킴 이름/설명 갱신 — updated_at 갱신 후 갱신 행 RETURNING. */
        const val SQL_SCHEME_UPDATE = """
            UPDATE issue_security_schemes
            SET name = :name,
                description = :description,
                updated_at = NOW()
            WHERE id = :id
            RETURNING id, name, description, created_at, updated_at
        """

        /** 스킴 삭제 — 등급/멤버는 FK ON DELETE CASCADE 로 동반 삭제. */
        const val SQL_SCHEME_DELETE = """
            DELETE FROM issue_security_schemes
            WHERE id = :id
        """

        /** 신규 등급 INSERT — id/created_at RETURNING. */
        const val SQL_LEVEL_CREATE = """
            INSERT INTO issue_security_levels (scheme_id, name, description, is_default)
            VALUES (:schemeId, :name, :description, :isDefault)
            RETURNING id, scheme_id, name, description, is_default, created_at
        """

        /** 스킴별 등급 목록. */
        const val SQL_LEVEL_LIST = """
            SELECT id, scheme_id, name, description, is_default, created_at
            FROM issue_security_levels
            WHERE scheme_id = :schemeId
            ORDER BY name
        """

        /** 전체 등급(findAll 에서 스킴별 그룹핑용). */
        const val SQL_LEVEL_FIND_ALL = """
            SELECT id, scheme_id, name, description, is_default, created_at
            FROM issue_security_levels
            ORDER BY name
        """

        /** 등급 갱신 — 갱신 행 RETURNING. */
        const val SQL_LEVEL_UPDATE = """
            UPDATE issue_security_levels
            SET name = :name,
                description = :description,
                is_default = :isDefault
            WHERE id = :id
            RETURNING id, scheme_id, name, description, is_default, created_at
        """

        /** 등급 삭제 — 멤버는 FK ON DELETE CASCADE 로 동반 삭제. */
        const val SQL_LEVEL_DELETE = """
            DELETE FROM issue_security_levels
            WHERE id = :id
        """

        /** 멤버 멱등 INSERT — (level_id, member_type, member_value) 유니크 위반 시 무시. RETURNING 없음. */
        const val SQL_MEMBER_INSERT = """
            INSERT INTO issue_security_level_members (level_id, member_type, member_value)
            VALUES (:levelId, :memberType, :memberValue)
            ON CONFLICT (level_id, member_type, member_value) DO NOTHING
        """

        /** 멱등 추가 후 정규 행 회수 — member_value 가 NULL 일 수 있어 IS NOT DISTINCT FROM 으로 비교. */
        const val SQL_MEMBER_FIND_ONE = """
            SELECT id, level_id, member_type, member_value, created_at
            FROM issue_security_level_members
            WHERE level_id = :levelId
              AND member_type = :memberType
              AND member_value IS NOT DISTINCT FROM :memberValue
        """

        /** 등급별 멤버 목록. */
        const val SQL_MEMBER_LIST = """
            SELECT id, level_id, member_type, member_value, created_at
            FROM issue_security_level_members
            WHERE level_id = :levelId
            ORDER BY created_at
        """

        /** 멤버 id 단건 삭제. */
        const val SQL_MEMBER_DELETE = """
            DELETE FROM issue_security_level_members
            WHERE id = :id
        """
    }
}

/** issue_security_schemes 행을 [IssueSecurityScheme] 도메인 모델로 변환하는 RowMapper. */
private object SchemeRowMapper : RowMapper<IssueSecurityScheme> {
    override fun mapRow(
        rs: ResultSet,
        rowNum: Int,
    ): IssueSecurityScheme =
        IssueSecurityScheme(
            id = rs.getObject("id", UUID::class.java),
            name = rs.getString("name"),
            description = rs.getString("description"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant(),
        )
}

/** issue_security_levels 행을 [IssueSecurityLevel] 도메인 모델로 변환하는 RowMapper. */
private object LevelRowMapper : RowMapper<IssueSecurityLevel> {
    override fun mapRow(
        rs: ResultSet,
        rowNum: Int,
    ): IssueSecurityLevel =
        IssueSecurityLevel(
            id = rs.getObject("id", UUID::class.java),
            schemeId = rs.getObject("scheme_id", UUID::class.java),
            name = rs.getString("name"),
            description = rs.getString("description"),
            isDefault = rs.getBoolean("is_default"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
        )
}

/** issue_security_level_members 행을 [SecurityLevelMember] 도메인 모델로 변환하는 RowMapper. */
private object MemberRowMapper : RowMapper<SecurityLevelMember> {
    override fun mapRow(
        rs: ResultSet,
        rowNum: Int,
    ): SecurityLevelMember =
        SecurityLevelMember(
            id = rs.getObject("id", UUID::class.java),
            levelId = rs.getObject("level_id", UUID::class.java),
            memberType = MemberType.valueOf(rs.getString("member_type")),
            memberValue = rs.getString("member_value"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
        )
}
