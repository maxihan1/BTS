// 인증 감사 로그 관리자 전역 조회 JDBC 구현 — 동적 WHERE(named param)+users LEFT JOIN+페이지네이션 (FR-AU-10 D6/D7)

package com.atlas.bts.identity.audit

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID

/**
 * [AuthAuditLogAdminQueryRepository] DB 조회 구현체 (FR-AU-10 D6/D7).
 *
 * 마이그레이션 0 — 기존 auth_audit_logs(V021) 테이블만 조회한다(새 DDL 없음).
 *
 * **SQL 인젝션 방어 (DEVELOPMENT.md §1.3).**
 * 동적 WHERE 는 criteria 의 비-null 항목별로 **정적 절 문자열**을 추가하고, 값은 전부
 * [NamedParameterJdbcTemplate] named parameter 로 바인딩한다. 사용자 입력을 SQL 에 문자열
 * 연결하는 일은 절대 없다. `Connection.createStatement` 직접 사용 금지.
 *
 * **JOIN.**
 * 목록 SQL 만 users 를 LEFT JOIN 하여 username/display_name 을 노출한다(미존재 user_id → null).
 * count SQL 은 JOIN 없이 auth_audit_logs 컬럼만으로 필터한다(필터 컬럼이 전부 auth_audit_logs 소속이므로
 * JOIN 으로 인한 행 증식·불필요 비용을 회피).
 *
 * **정렬/페이지네이션.**
 * created_at DESC, id DESC tiebreaker(EC-10). offset = page * size, LIMIT = size.
 *
 * **metadata JSONB.**
 * JdbcAuthAuditLogService 의 역직렬화 패턴 재사용 — getString + ObjectMapper.
 *
 * **트랜잭션.**
 * 조회 전용이므로 `readOnly = true`.
 */
@Repository
class JdbcAuthAuditLogAdminQueryRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val objectMapper: ObjectMapper,
) : AuthAuditLogAdminQueryRepository {
    @Transactional(readOnly = true)
    override fun search(criteria: AuthAuditLogSearchCriteria): AuthAuditLogAdminPage {
        val whereClause = buildWhereClause(criteria)
        val filterParams = buildFilterParams(criteria)

        val total =
            jdbc.queryForObject(
                "$SQL_COUNT_PREFIX$whereClause",
                filterParams,
                Long::class.java,
            ) ?: 0L

        val offset = criteria.page.toLong() * criteria.size.toLong()
        val pageParams = HashMap<String, Any?>(filterParams).apply {
            put("size", criteria.size)
            put("offset", offset)
        }
        val items =
            jdbc.query(
                "$SQL_SELECT_PREFIX$whereClause$SQL_SELECT_SUFFIX",
                pageParams,
                rowMapper,
            )

        return AuthAuditLogAdminPage(items = items, totalElements = total)
    }

    // ── 동적 WHERE 구성 (정적 절 + named param 만) ────────────────────────────────

    /**
     * criteria 의 비-null 항목별 정적 절을 모아 `WHERE a AND b ...` 문자열을 만든다.
     * 절이 없으면 빈 문자열을 반환한다. 값은 절대 문자열에 끼워넣지 않는다.
     */
    private fun buildWhereClause(criteria: AuthAuditLogSearchCriteria): String {
        val clauses = mutableListOf<String>()
        if (criteria.eventType != null) clauses += "al.event_type = :eventType"
        if (criteria.userId != null) clauses += "al.user_id = :userId"
        if (criteria.from != null) clauses += "al.created_at >= :from"
        if (criteria.to != null) clauses += "al.created_at <= :to"
        return if (clauses.isEmpty()) "" else " WHERE " + clauses.joinToString(" AND ")
    }

    private fun buildFilterParams(criteria: AuthAuditLogSearchCriteria): Map<String, Any?> {
        val params = HashMap<String, Any?>()
        criteria.eventType?.let { params["eventType"] = it.name }
        criteria.userId?.let { params["userId"] = it }
        criteria.from?.let { params["from"] = Timestamp.from(it) }
        criteria.to?.let { params["to"] = Timestamp.from(it) }
        return params
    }

    // ── 역직렬화 ───────────────────────────────────────────────────────────────────

    private fun deserializeMetadata(json: String?): Map<String, String> {
        if (json == null) return emptyMap()
        return objectMapper.readValue(json, METADATA_TYPE_REF)
    }

    // ── RowMapper ──────────────────────────────────────────────────────────────────

    private val rowMapper: RowMapper<AuthAuditLogAdminEntry> = RowMapper { rs, _ -> mapRow(rs) }

    private fun mapRow(rs: ResultSet): AuthAuditLogAdminEntry =
        AuthAuditLogAdminEntry(
            id = rs.getLong("id"),
            userId = rs.getObject("user_id", UUID::class.java),
            username = rs.getString("username"),
            displayName = rs.getString("display_name"),
            eventType = AuthEventType.valueOf(rs.getString("event_type")),
            providerId = rs.getString("provider_id"),
            ipAddress = rs.getString("ip_address"),
            userAgent = rs.getString("user_agent"),
            metadata = deserializeMetadata(rs.getString("metadata")),
            createdAt = rs.getTimestamp("created_at").toInstant(),
        )

    private companion object {
        /** metadata Map<String,String> 역직렬화 TypeReference — ObjectMapper reuse. */
        val METADATA_TYPE_REF: TypeReference<Map<String, String>> =
            object : TypeReference<Map<String, String>>() {}

        /**
         * 목록 조회 SELECT 접두부 — users LEFT JOIN 으로 username/display_name 노출.
         * WHERE 절은 [buildWhereClause] 가 동적으로 붙이고, 뒤에 [SQL_SELECT_SUFFIX] 가 정렬/페이지 한정을 붙인다.
         */
        const val SQL_SELECT_PREFIX = """
            SELECT al.id, al.user_id, u.username, u.display_name,
                   al.event_type, al.provider_id, al.ip_address, al.user_agent,
                   al.metadata, al.created_at
            FROM auth_audit_logs al
            LEFT JOIN users u ON al.user_id = u.id
        """

        /** 목록 정렬(created_at DESC, id DESC) + 페이지 한정. */
        const val SQL_SELECT_SUFFIX = """
            ORDER BY al.created_at DESC, al.id DESC
            LIMIT :size OFFSET :offset
        """

        /** count SELECT 접두부 — JOIN 없음(필터 컬럼이 전부 auth_audit_logs 소속). */
        const val SQL_COUNT_PREFIX = """
            SELECT COUNT(*) FROM auth_audit_logs al
        """
    }
}
