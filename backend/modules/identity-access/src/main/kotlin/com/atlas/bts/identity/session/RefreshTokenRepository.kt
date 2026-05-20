// refresh_tokens 테이블 접근 인터페이스 + JdbcTemplate 구현체 — rotation chain + EC-23 race-safe markUsedAndChain

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
 * refresh_tokens 테이블 접근 인터페이스 (FR-AU-09 Task 9 / SDD §19.5).
 *
 * 구현체: [JdbcRefreshTokenRepository].
 *
 * ## 읽기 vs 쓰기
 * - 조회 메서드([findByTokenHash]) 는 `readOnly = true` 트랜잭션.
 * - 변경 메서드([save], [markUsedAndChain], [revokeChainFromSession]) 는 `REQUIRED` 쓰기 트랜잭션.
 *
 * ## 보안 정책
 * - token_hash 는 로그에 절대 기록하지 않는다 (DEVELOPMENT.md §1.1 규칙 2).
 * - raw token 은 발급 응답에 한 번만 포함되며 DB 에 저장되지 않는다.
 */
interface RefreshTokenRepository {

    /**
     * token_hash (SHA-256 hex 64자) 로 단건 조회.
     *
     * 사용 완료([RefreshToken.usedAt] != null) 된 토큰도 조회할 수 있다.
     * 호출 측에서 [RefreshToken.isUsable] 로 상태를 확인해야 한다 (reuse detection 용).
     *
     * @param tokenHash SHA-256 hex 64자. **로그 기록 금지.**
     * @return 존재하면 [RefreshToken], 없으면 null
     */
    fun findByTokenHash(tokenHash: String): RefreshToken?

    /**
     * 신규 refresh token INSERT.
     *
     * 호출 측에서 [RefreshToken.id] (UUID v4) 를 생성하여 전달한다.
     * [RefreshToken.tokenHash] UNIQUE constraint 위반 시 예외가 전파된다.
     *
     * @param token 저장할 refresh token 엔티티
     * @throws org.springframework.dao.DataIntegrityViolationException id 또는 tokenHash 중복 시
     */
    fun save(token: RefreshToken)

    /**
     * Rotation chain 연결 — oldId 토큰을 사용 완료 처리하고 newId 를 후계자로 기록한다.
     *
     * ## EC-23 optimistic locking
     * `UPDATE refresh_tokens SET used_at = NOW(), replaced_by = :newId
     *  WHERE id = :oldId AND used_at IS NULL RETURNING id`
     *
     * - **race winner**: `used_at IS NULL` 조건을 충족한 첫 번째 요청 → [oldId] 반환.
     * - **race loser**: 이미 `used_at IS NOT NULL` → 0 행 영향 → null 반환.
     *
     * 호출 측은 반환값이 null 이면 토큰 재사용(replay) 으로 간주하여 세션 전체를 폐기해야 한다.
     *
     * @param oldId 현재 소비되는 (구) 토큰 ID
     * @param newId rotation 으로 발급된 (신) 토큰 ID
     * @return race winner: [oldId], race loser 또는 존재하지 않는 id: null
     */
    fun markUsedAndChain(oldId: UUID, newId: UUID): UUID?

    /**
     * 세션 단위 폐기 — sessionId 에 속한 모든 미사용 토큰을 `used_at = NOW()` 로 설정한다.
     *
     * `WHERE session_id = :sessionId AND used_at IS NULL` 조건으로 미사용 토큰만 대상으로 한다.
     * 이미 사용 완료된 토큰은 영향받지 않는다 (멱등 보장).
     *
     * @param sessionId 폐기 대상 세션 ID
     * @return 영향받은 행 수 (폐기된 미사용 토큰 수)
     */
    fun revokeChainFromSession(sessionId: UUID): Int
}

/**
 * [RefreshTokenRepository] JDBC 구현체 (FR-AU-09 Task 9).
 *
 * **트랜잭션 경계 (DATA.md §6).**
 * 클래스 레벨 `@Transactional(REQUIRED)` — 호출 측 트랜잭션에 참여하거나 새로 시작.
 * 조회 메서드는 `@Transactional(readOnly = true)` 로 오버라이드.
 *
 * **SQL 인젝션 방어 (DEVELOPMENT.md §1.3).**
 * 모든 파라미터를 [NamedParameterJdbcTemplate] named parameter 바인딩으로 처리.
 * `Connection.createStatement` 직접 사용 절대 금지.
 *
 * **EC-23 race-safe.**
 * [markUsedAndChain] 은 단일 UPDATE + RETURNING 으로 optimistic locking 을 구현한다.
 * `used_at IS NULL` 조건이 DB 레벨에서 원자적으로 평가되므로 애플리케이션 레벨 락 불필요.
 */
@Repository
@Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED)
class JdbcRefreshTokenRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) : RefreshTokenRepository {

    @Transactional(readOnly = true)
    override fun findByTokenHash(tokenHash: String): RefreshToken? =
        jdbc.query(SQL_FIND_BY_TOKEN_HASH, mapOf("tokenHash" to tokenHash), RefreshTokenRowMapper)
            .firstOrNull()

    override fun save(token: RefreshToken) {
        jdbc.update(
            SQL_INSERT,
            mapOf(
                "id" to token.id,
                "sessionId" to token.sessionId,
                "tokenHash" to token.tokenHash,
                "issuedAt" to Timestamp.from(token.issuedAt),
                "expiresAt" to Timestamp.from(token.expiresAt),
                "usedAt" to token.usedAt?.let { Timestamp.from(it) },
                "replacedBy" to token.replacedBy,
            ),
        )
    }

    override fun markUsedAndChain(oldId: UUID, newId: UUID): UUID? {
        val result = jdbc.queryForList(
            SQL_MARK_USED_AND_CHAIN,
            mapOf(
                "oldId" to oldId,
                "newId" to newId,
                "now" to Timestamp.from(Instant.now()),
            ),
        )
        return if (result.isEmpty()) null else result[0]["id"] as UUID
    }

    override fun revokeChainFromSession(sessionId: UUID): Int =
        jdbc.update(
            SQL_REVOKE_CHAIN_FROM_SESSION,
            mapOf(
                "sessionId" to sessionId,
                "now" to Timestamp.from(Instant.now()),
            ),
        )

    // ── SQL 상수 ──────────────────────────────────────────────────────────────

    private companion object {

        /**
         * token_hash 로 단건 조회.
         *
         * UNIQUE constraint 로 인해 최대 1 행만 반환된다.
         * 사용 완료 토큰([RefreshToken.usedAt] IS NOT NULL) 도 반환한다 — reuse detection 용.
         */
        const val SQL_FIND_BY_TOKEN_HASH = """
            SELECT id, session_id, token_hash, issued_at, expires_at, used_at, replaced_by
            FROM refresh_tokens
            WHERE token_hash = :tokenHash
        """

        const val SQL_INSERT = """
            INSERT INTO refresh_tokens (
                id, session_id, token_hash, issued_at, expires_at, used_at, replaced_by
            ) VALUES (
                :id, :sessionId, :tokenHash, :issuedAt, :expiresAt, :usedAt, :replacedBy
            )
        """

        /**
         * EC-23 race-safe rotation chain 연결.
         *
         * `WHERE id = :oldId AND used_at IS NULL` 조건으로 DB 레벨 원자적 평가.
         * - race winner: 1 행 영향 → RETURNING id → oldId UUID 반환.
         * - race loser / 미존재: 0 행 영향 → 빈 결과 → null 반환.
         *
         * 두 업데이트(used_at + replaced_by)를 한 트랜잭션 내 단일 UPDATE 로 처리한다.
         */
        const val SQL_MARK_USED_AND_CHAIN = """
            UPDATE refresh_tokens
            SET used_at     = :now,
                replaced_by = :newId
            WHERE id = :oldId
              AND used_at IS NULL
            RETURNING id
        """

        /**
         * 세션 단위 미사용 토큰 일괄 폐기.
         *
         * `WHERE session_id = :sessionId AND used_at IS NULL` — 이미 사용 완료된 토큰은 제외 (멱등).
         * ON DELETE CASCADE 와는 별개로, 세션은 유지하되 토큰만 폐기할 때 사용한다.
         */
        const val SQL_REVOKE_CHAIN_FROM_SESSION = """
            UPDATE refresh_tokens
            SET used_at = :now
            WHERE session_id = :sessionId
              AND used_at IS NULL
        """
    }
}

/**
 * refresh_tokens RowMapper — ResultSet → [RefreshToken] 변환.
 *
 * UUID: `getObject + UUID::class.java` — PostgreSQL JDBC 권장 방식.
 * TIMESTAMPTZ: `getTimestamp(...).toInstant()` — UTC 기준 [Instant] 변환.
 * nullable 컬럼 (used_at, replaced_by): null 허용.
 *
 * **보안: token_hash 를 로그에 절대 기록하지 않는다.**
 */
private object RefreshTokenRowMapper : RowMapper<RefreshToken> {
    override fun mapRow(
        rs: ResultSet,
        rowNum: Int,
    ): RefreshToken =
        RefreshToken(
            id = rs.getObject("id", UUID::class.java),
            sessionId = rs.getObject("session_id", UUID::class.java),
            tokenHash = rs.getString("token_hash"),
            issuedAt = rs.getTimestamp("issued_at").toInstant(),
            expiresAt = rs.getTimestamp("expires_at").toInstant(),
            usedAt = rs.getTimestamp("used_at")?.toInstant(),
            replacedBy = rs.getObject("replaced_by", UUID::class.java),
        )
}
