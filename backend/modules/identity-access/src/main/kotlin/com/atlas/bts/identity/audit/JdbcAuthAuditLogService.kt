// 인증 감사 로그 DB 영속 구현체 — auth_audit_logs 테이블(V021) append-only INSERT/SELECT — FR-AU-10

package com.atlas.bts.identity.audit

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID

/**
 * [AuthAuditLogService] DB 영속 구현체 (FR-AU-10 Task 3).
 *
 * `auth_audit_logs` 테이블(V021)에 인증 감사 이벤트를 **append-only**로 기록한다.
 * 프로세스 재시작에도 보존되며(인메모리 대체), 삭제 메서드는 없다(DATA.md §3 — 영구 보존).
 *
 * **트랜잭션 경계 (DATA.md §6).**
 * 클래스 레벨 `@Transactional(REQUIRED)` — 호출 측 트랜잭션에 참여하거나 새로 시작.
 * service 레이어 emit(TOKEN_REFRESHED 등)은 호출자 트랜잭션에 합류해 원자적으로 커밋된다.
 * 조회 메서드 [findRecent] 는 `readOnly = true` 로 오버라이드.
 *
 * **빈 등록 (EC-6).**
 * `@Service` 단일 등록. [InMemoryAuthAuditLogService] 는 `@Service` 가 제거되어
 * 단위테스트 헬퍼로 강등되므로 동일 인터페이스 2 빈 충돌이 없다.
 * `@Transactional` 보유 클래스는 Spring 빈 어노테이션이 필수다(TransactionalServiceArchTest 가드).
 *
 * **metadata JSONB (EC-9).**
 * `Map<String,String>` ↔ JSONB. 저장은 [ObjectMapper] JSON 문자열 변환 후 `:metadata::jsonb` 캐스팅,
 * 조회는 `getString` + [ObjectMapper] 역직렬화 (JdbcPersonalAccessTokenRepository scopes 패턴 재사용).
 *
 * **SQL 인젝션 방어 (DEVELOPMENT.md §1.3).**
 * 모든 파라미터를 [NamedParameterJdbcTemplate] named parameter 바인딩으로 처리.
 * `Connection.createStatement` 직접 사용 절대 금지.
 *
 * **PII (NFR-1).**
 * ip/userAgent/deviceFingerprint 는 DB에 저장하되(감사 목적), logback 출력은 하지 않는다
 * (PII 마스킹 — [InMemoryAuthAuditLogService] 의 logback 정책과 동일 의도).
 */
@Service
@Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED)
class JdbcAuthAuditLogService(
    private val jdbc: NamedParameterJdbcTemplate,
    private val objectMapper: ObjectMapper,
) : AuthAuditLogService {
    override fun record(event: AuthAuditLog) {
        jdbc.update(
            SQL_INSERT,
            mapOf(
                "userId" to event.userId,
                "eventType" to event.eventType.name,
                "providerId" to event.providerId,
                "ipAddress" to event.ipAddress,
                "userAgent" to event.userAgent,
                "deviceFingerprint" to event.deviceFingerprint,
                "metadata" to serializeMetadata(event.metadata),
                "createdAt" to Timestamp.from(event.createdAt),
            ),
        )
    }

    @Transactional(readOnly = true)
    override fun findRecent(
        userId: UUID,
        limit: Int,
    ): List<AuthAuditLog> {
        return jdbc.query(
            SQL_FIND_RECENT,
            mapOf("userId" to userId, "limit" to limit),
            rowMapper,
        )
    }

    // ── 직렬화 헬퍼 ──────────────────────────────────────────────────────────────

    private fun serializeMetadata(metadata: Map<String, String>): String = objectMapper.writeValueAsString(metadata)

    private fun deserializeMetadata(json: String?): Map<String, String> {
        if (json == null) return emptyMap()
        return objectMapper.readValue(json, METADATA_TYPE_REF)
    }

    // ── RowMapper ────────────────────────────────────────────────────────────────

    /**
     * ResultSet → [AuthAuditLog] 변환기. 인스턴스당 한 번만 생성되어 재사용된다.
     *
     * UUID: `getObject + UUID::class.java` — PostgreSQL JDBC 권장 방식.
     * TIMESTAMPTZ: `getTimestamp(...).toInstant()` — UTC 기준 Instant 변환.
     * user_id: nullable — `getObject` 가 null 을 그대로 반환.
     */
    private val rowMapper: RowMapper<AuthAuditLog> = RowMapper { rs, _ -> mapRow(rs) }

    private fun mapRow(rs: ResultSet): AuthAuditLog =
        AuthAuditLog(
            userId = rs.getObject("user_id", UUID::class.java),
            eventType = AuthEventType.valueOf(rs.getString("event_type")),
            providerId = rs.getString("provider_id"),
            ipAddress = rs.getString("ip_address"),
            userAgent = rs.getString("user_agent"),
            deviceFingerprint = rs.getString("device_fingerprint"),
            metadata = deserializeMetadata(rs.getString("metadata")),
            createdAt = rs.getTimestamp("created_at").toInstant(),
        )

    // ── SQL 상수 ─────────────────────────────────────────────────────────────────

    private companion object {
        /** metadata Map<String,String> 역직렬화 TypeReference — ObjectMapper reuse. */
        val METADATA_TYPE_REF: TypeReference<Map<String, String>> =
            object : TypeReference<Map<String, String>>() {}

        /**
         * 감사 이벤트 append-only INSERT.
         * id 는 DB IDENTITY 가 생성, metadata 는 JSONB 캐스팅(`::jsonb`) 필요.
         */
        const val SQL_INSERT = """
            INSERT INTO auth_audit_logs
                (user_id, event_type, provider_id, ip_address, user_agent,
                 device_fingerprint, metadata, created_at)
            VALUES
                (:userId, :eventType, :providerId, :ipAddress, :userAgent,
                 :deviceFingerprint, :metadata::jsonb, :createdAt)
        """

        /**
         * 특정 사용자의 최근 감사 이벤트 최신순 조회.
         * created_at DESC, 동률 시 id DESC tiebreaker(EC-10) — idx_auth_audit_logs_user_created 활용.
         */
        const val SQL_FIND_RECENT = """
            SELECT user_id, event_type, provider_id, ip_address, user_agent,
                   device_fingerprint, metadata, created_at
            FROM auth_audit_logs
            WHERE user_id = :userId
            ORDER BY created_at DESC, id DESC
            LIMIT :limit
        """
    }
}
