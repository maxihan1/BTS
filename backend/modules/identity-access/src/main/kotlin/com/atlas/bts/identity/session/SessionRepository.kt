// sessions 테이블 접근 인터페이스 + JdbcTemplate 구현체 — SDD 19.5 세션 생명주기 관리

package com.atlas.bts.identity.session

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant
import java.util.UUID

/**
 * sessions 테이블 접근 인터페이스 (FR-AU-09 Task 8 / SDD §19.5).
 *
 * 구현체: [JdbcSessionRepository].
 *
 * ## 읽기 vs 쓰기
 * - 조회 메서드([findById], [findActiveByUserId]) 는 `readOnly = true` 트랜잭션.
 * - 변경 메서드([save], [markRevoked], [revokeAllByUserId], [updateLastSeen]) 는 `REQUIRED` 쓰기 트랜잭션.
 */
interface SessionRepository {

    /**
     * 세션 ID 로 단건 조회.
     *
     * @param id 조회할 세션 ID
     * @return 존재하면 [Session], 없으면 null
     */
    fun findById(id: UUID): Session?

    /**
     * 특정 사용자의 활성 세션 목록 조회.
     *
     * partial index `idx_sessions_user_active` (WHERE revoked_at IS NULL) 를 활용한다.
     * 쿼리에서 `revoked_at IS NULL AND expires_at > NOW()` 조건을 명시하여
     * 폐기된 세션과 만료된 세션 모두 제외한다.
     *
     * @return 활성 세션 목록. 없으면 빈 리스트
     */
    fun findActiveByUserId(userId: UUID): List<Session>

    /**
     * 신규 세션 INSERT.
     *
     * 호출 측에서 [Session.id] (UUID v4) 를 생성하여 전달한다.
     * RETURNING 없이 단순 INSERT.
     *
     * @param session 저장할 세션 엔티티
     * @throws org.springframework.dao.DataIntegrityViolationException [Session.id] 중복 또는 FK 위반 시
     */
    fun save(session: Session)

    /**
     * 단일 세션 폐기 — `revoked_at = NOW()`, `revoke_reason = reason` 설정.
     *
     * 이미 폐기된 세션이나 존재하지 않는 id 는 조용히 무시한다 (0 행 영향).
     *
     * @param id 폐기할 세션 ID
     * @param reason 폐기 사유 코드 (예: "logout", "replay_detected", "password_changed")
     */
    fun markRevoked(
        id: UUID,
        reason: String,
    )

    /**
     * 특정 사용자의 모든 활성 세션 일괄 폐기.
     *
     * `WHERE user_id = :userId AND revoked_at IS NULL` 조건으로 활성 세션만 대상으로 한다.
     *
     * @param userId 폐기 대상 사용자 ID
     * @param reason 폐기 사유 코드 (예: "logout_all", "password_changed")
     * @return 영향받은 행 수
     */
    fun revokeAllByUserId(
        userId: UUID,
        reason: String,
    ): Int

    /**
     * 마지막 활동 시각 갱신 (best-effort — SDD §3 lost update 허용).
     *
     * 존재하지 않는 id 는 조용히 무시한다 (0 행 영향).
     *
     * @param id 갱신할 세션 ID
     */
    fun updateLastSeen(id: UUID)
}

/**
 * [SessionRepository] JDBC 구현체 (FR-AU-09 Task 8).
 *
 * **트랜잭션 경계 (DATA.md §6).**
 * 클래스 레벨 `@Transactional(REQUIRED)` — 호출 측 트랜잭션에 참여하거나 새로 시작.
 * 조회 메서드는 `@Transactional(readOnly = true)` 로 오버라이드.
 *
 * **SQL 인젝션 방어 (DEVELOPMENT.md §1.3).**
 * 모든 파라미터를 [NamedParameterJdbcTemplate] named parameter 바인딩으로 처리.
 * `Connection.createStatement` 직접 사용 절대 금지.
 *
 * **partial index 활용.**
 * [findActiveByUserId] 쿼리는 `WHERE revoked_at IS NULL AND expires_at > :now` 조건으로
 * `idx_sessions_user_active` partial index (V004 — WHERE revoked_at IS NULL) 를 활용한다.
 */
@Repository
@Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED)
class JdbcSessionRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) : SessionRepository {

    @Transactional(readOnly = true)
    override fun findById(id: UUID): Session? =
        jdbc.query(SQL_FIND_BY_ID, mapOf("id" to id), SessionRowMapper).firstOrNull()

    @Transactional(readOnly = true)
    override fun findActiveByUserId(userId: UUID): List<Session> =
        jdbc.query(
            SQL_FIND_ACTIVE_BY_USER_ID,
            mapOf(
                "userId" to userId,
                "now" to Timestamp.from(Instant.now()),
            ),
            SessionRowMapper,
        )

    override fun save(session: Session) {
        jdbc.update(
            SQL_INSERT,
            mapOf(
                "id" to session.id,
                "userId" to session.userId,
                "providerId" to session.providerId,
                "deviceFingerprint" to session.deviceFingerprint,
                "ipAddress" to session.ipAddress,
                "userAgent" to session.userAgent,
                "createdAt" to Timestamp.from(session.createdAt),
                "expiresAt" to Timestamp.from(session.expiresAt),
                "lastSeenAt" to Timestamp.from(session.lastSeenAt),
                "revokedAt" to session.revokedAt?.let { Timestamp.from(it) },
                "revokeReason" to session.revokeReason,
            ),
        )
    }

    override fun markRevoked(
        id: UUID,
        reason: String,
    ) {
        jdbc.update(
            SQL_MARK_REVOKED,
            mapOf(
                "id" to id,
                "reason" to reason,
                "now" to Timestamp.from(Instant.now()),
            ),
        )
    }

    override fun revokeAllByUserId(
        userId: UUID,
        reason: String,
    ): Int =
        jdbc.update(
            SQL_REVOKE_ALL_BY_USER_ID,
            mapOf(
                "userId" to userId,
                "reason" to reason,
                "now" to Timestamp.from(Instant.now()),
            ),
        )

    override fun updateLastSeen(id: UUID) {
        jdbc.update(
            SQL_UPDATE_LAST_SEEN,
            mapOf(
                "id" to id,
                "now" to Timestamp.from(Instant.now()),
            ),
        )
    }

    // ── SQL 상수 ──────────────────────────────────────────────────────────────

    private companion object {
        const val SQL_FIND_BY_ID = """
            SELECT id, user_id, provider_id, device_fingerprint, ip_address, user_agent,
                   created_at, expires_at, last_seen_at, revoked_at, revoke_reason
            FROM sessions
            WHERE id = :id
        """

        /**
         * 활성 세션 조회.
         *
         * `WHERE user_id = :userId AND revoked_at IS NULL AND expires_at > :now` 조건으로
         * partial index `idx_sessions_user_active` (WHERE revoked_at IS NULL) 를 활용한다.
         * `expires_at > :now` 로 만료 세션을 추가 필터링한다.
         */
        const val SQL_FIND_ACTIVE_BY_USER_ID = """
            SELECT id, user_id, provider_id, device_fingerprint, ip_address, user_agent,
                   created_at, expires_at, last_seen_at, revoked_at, revoke_reason
            FROM sessions
            WHERE user_id = :userId
              AND revoked_at IS NULL
              AND expires_at > :now
        """

        const val SQL_INSERT = """
            INSERT INTO sessions (
                id, user_id, provider_id, device_fingerprint, ip_address, user_agent,
                created_at, expires_at, last_seen_at, revoked_at, revoke_reason
            ) VALUES (
                :id, :userId, :providerId, :deviceFingerprint, :ipAddress::INET, :userAgent,
                :createdAt, :expiresAt, :lastSeenAt, :revokedAt, :revokeReason
            )
        """

        /**
         * 단일 세션 폐기.
         *
         * `WHERE id = :id AND revoked_at IS NULL` — 이미 폐기된 세션은 0 행 영향 (멱등).
         */
        const val SQL_MARK_REVOKED = """
            UPDATE sessions
            SET revoked_at   = :now,
                revoke_reason = :reason
            WHERE id = :id
              AND revoked_at IS NULL
        """

        /**
         * 사용자 전체 활성 세션 일괄 폐기.
         *
         * `WHERE user_id = :userId AND revoked_at IS NULL` — 이미 폐기된 세션은 제외 (멱등).
         */
        const val SQL_REVOKE_ALL_BY_USER_ID = """
            UPDATE sessions
            SET revoked_at   = :now,
                revoke_reason = :reason
            WHERE user_id = :userId
              AND revoked_at IS NULL
        """

        /**
         * 마지막 활동 시각 갱신 (best-effort).
         *
         * SDD §3: lost update 허용 — 동시 요청에서 덮어쓰기가 발생해도 통계 목적 데이터이므로 무방.
         */
        const val SQL_UPDATE_LAST_SEEN = """
            UPDATE sessions
            SET last_seen_at = :now
            WHERE id = :id
        """
    }
}

/**
 * sessions RowMapper — ResultSet → [Session] 변환.
 *
 * UUID: `getObject + UUID::class.java` — PostgreSQL JDBC 권장 방식 (UUID.fromString 캐스팅 회피).
 * TIMESTAMPTZ: `getTimestamp(...).toInstant()` — UTC 기준 [Instant] 변환.
 * nullable 컬럼: `getString` 반환 null 을 그대로 허용.
 */
private object SessionRowMapper : RowMapper<Session> {
    override fun mapRow(
        rs: ResultSet,
        rowNum: Int,
    ): Session =
        Session(
            id = rs.getObject("id", UUID::class.java),
            userId = rs.getObject("user_id", UUID::class.java),
            providerId = rs.getString("provider_id"),
            deviceFingerprint = rs.getString("device_fingerprint"),
            ipAddress = rs.getString("ip_address"),
            userAgent = rs.getString("user_agent"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            expiresAt = rs.getTimestamp("expires_at").toInstant(),
            lastSeenAt = rs.getTimestamp("last_seen_at").toInstant(),
            revokedAt = rs.getTimestamp("revoked_at")?.toInstant(),
            revokeReason = rs.getString("revoke_reason"),
        )
}
