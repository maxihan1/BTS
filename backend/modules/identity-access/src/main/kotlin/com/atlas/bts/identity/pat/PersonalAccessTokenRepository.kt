// personal_access_tokens 테이블 접근 Repository — findByTokenHash / findActiveByUserId / updateLastUsed / revoke (EC-26/EC-27)

package com.atlas.bts.identity.pat

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
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
 * personal_access_tokens 테이블 접근 인터페이스 (FR-AU-09 Task 10).
 *
 * 구현체: [JdbcPersonalAccessTokenRepository].
 *
 * ## 보안 계약 (EC-26)
 * - [tokenHash] 는 `SHA-256("pat_" + body)` 64자 소문자 hex.
 * - raw token 은 이 Repository 에 절대 전달되지 않는다. 해시만 저장.
 *
 * ## 토큰 상태 판별
 * - revoke/만료 판별은 [PersonalAccessToken.isActive] 엔티티 책임.
 * - [findActiveByUserId] 는 SQL WHERE 로 revoked_at IS NULL + 만료 미도래를 1차 필터하고,
 *   결과 리스트를 반환한다.
 */
interface PersonalAccessTokenRepository {

    /**
     * PAT 저장 (신규 INSERT).
     *
     * 동일 token_hash 중복 시 DB UNIQUE 제약 위반 — 호출 측 책임으로 처리.
     *
     * @return DB 에 반영된 최신 상태의 [PersonalAccessToken]
     */
    fun save(pat: PersonalAccessToken): PersonalAccessToken

    /**
     * token_hash 로 PAT 조회.
     *
     * revoke/만료 상태와 무관하게 hash 가 일치하는 행을 반환한다.
     * 활성 여부 판별은 [PersonalAccessToken.isActive] 로 추가 확인.
     *
     * @return 존재하면 [PersonalAccessToken], 없으면 null
     */
    fun findByTokenHash(tokenHash: String): PersonalAccessToken?

    /**
     * 특정 사용자의 활성 PAT 목록 조회.
     *
     * `revoked_at IS NULL AND (expires_at IS NULL OR expires_at > NOW())` 조건 적용.
     * partial index `idx_pat_user_active` 를 활용한다.
     *
     * @return 활성 [PersonalAccessToken] 리스트 (빈 리스트 허용)
     */
    fun findActiveByUserId(userId: UUID): List<PersonalAccessToken>

    /**
     * PAT 마지막 사용 시각 갱신.
     *
     * 존재하지 않는 id 는 조용히 무시한다 (0 행 영향).
     *
     * @param id 갱신할 PAT UUID
     */
    fun updateLastUsed(id: UUID)

    /**
     * PAT 폐기 (revoke) — **내부 전용, user_id 미검사 (IDOR 위험)**.
     *
     * `revoked_at = NOW()` 를 설정한다. 이미 revoke 된 경우 idempotent 처리
     * — `WHERE revoked_at IS NULL` 조건으로 이미 revoke 된 행은 영향 없이 넘어간다.
     * 존재하지 않는 id 도 조용히 무시한다.
     *
     * 소유권 검증이 필요 없는 내부 경로(관리자 강제 폐기 등)에서만 사용한다.
     * 사용자 self-service 취소는 반드시 [findByIdAndUserId] + [revokeOwned] 조합을 사용해
     * 본인 소유 여부를 확인해야 한다 (FR-API-04).
     *
     * @param id 폐기할 PAT UUID
     */
    fun revoke(id: UUID)

    /**
     * 특정 사용자의 PAT 목록 조회 — revoke 만 제외, **만료 포함** (FR-API-04).
     *
     * `WHERE user_id = :userId AND revoked_at IS NULL ORDER BY created_at DESC`.
     * [findActiveByUserId] 와 달리 만료(expires_at 과거) PAT 도 결과에 포함한다.
     * 자기 PAT 목록 화면은 만료된 토큰도 보여줘야 하므로 이 메서드를 사용한다.
     *
     * @return 미취소 [PersonalAccessToken] 리스트 (created_at 최신순, 빈 리스트 허용)
     */
    fun listByUserIncludingExpired(userId: UUID): List<PersonalAccessToken>

    /**
     * id + user_id 동시 일치 PAT 조회 — 소유권 확인용 (IDOR 차단, FR-API-04).
     *
     * `WHERE id = :id AND user_id = :userId` — 상태 필터 없음(revoke/만료 무관).
     * null 이면 미존재 또는 타인 소유(둘 다 404 처리 대상), non-null 이면 본인 소유 확정.
     * 취소 요청의 멱등/IDOR 구분에 사용한다.
     *
     * @return 본인 소유이면 [PersonalAccessToken], 아니면 null
     */
    fun findByIdAndUserId(
        id: UUID,
        userId: UUID,
    ): PersonalAccessToken?

    /**
     * 본인 소유 활성 PAT 만 revoke — 소유권 + 활성 조건 원자 검증 (FR-API-04).
     *
     * `UPDATE ... SET revoked_at = :now WHERE id = :id AND user_id = :userId AND revoked_at IS NULL`.
     * user_id 조건으로 타인 PAT 폐기(IDOR)를 차단하고, revoked_at IS NULL 로 이미 취소된 행은 건너뛴다.
     *
     * @param now revoke 시각 — 호출 측 Clock 기준 주입(테스트 시각 제어 가능).
     * @return 영향 행 수. 1 = 취소 성공, 0 = 이미 취소/미존재/타인 소유
     */
    fun revokeOwned(
        id: UUID,
        userId: UUID,
        now: Instant,
    ): Int

    /**
     * 사용자의 활성 PAT 개수 — 미취소 + 미만료 (FR-API-04, 개수 상한 검사용).
     *
     * `WHERE user_id = :userId AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at > :now)`.
     *
     * @param now 만료 판정 기준 시각 — 호출 측 Clock 기준 주입.
     * @return 활성 PAT 개수
     */
    fun countActiveByUser(
        userId: UUID,
        now: Instant,
    ): Long
}

/**
 * [PersonalAccessTokenRepository] JDBC 구현체 (FR-AU-09 Task 10).
 *
 * **트랜잭션 경계 (DATA.md §6)**:
 * 클래스 레벨 @Transactional(REQUIRED) — 호출 측 트랜잭션에 참여하거나 새로 시작.
 * 읽기 전용 메서드는 @Transactional(readOnly = true) 로 오버라이드.
 *
 * **scopes JSONB**:
 * PostgreSQL JSONB 컬럼은 JDBC getString 으로 읽고 [ObjectMapper] 로 `List<String>` 역직렬화.
 * 저장 시에도 [ObjectMapper] 로 JSON 문자열 변환 후 setObject 로 바인딩.
 *
 * **UUID RowMapper**:
 * ResultSet.getObject + UUID::class.java — Postgres JDBC 권장 방식.
 *
 * SQL 인젝션 방어: 모든 파라미터를 NamedParameterJdbcTemplate 에 바인딩 (DEVELOPMENT.md §1.3).
 */
@Repository
@Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED)
@Suppress("TooManyFunctions") // FR-API-04 셀프서비스 4메서드 추가로 12개(임계 11) — 단일 엔티티 CRUD 응집, 분리 시 오히려 산개
class JdbcPersonalAccessTokenRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val objectMapper: ObjectMapper,
) : PersonalAccessTokenRepository {

    override fun save(pat: PersonalAccessToken): PersonalAccessToken {
        val scopesJson = serializeScopes(pat.scopes)
        val params = mapOf(
            "id" to pat.id,
            "userId" to pat.userId,
            "name" to pat.name,
            "tokenHash" to pat.tokenHash,
            "scopes" to scopesJson,
            "expiresAt" to pat.expiresAt?.let { Timestamp.from(it) },
        )
        return jdbc.queryForObject(SQL_INSERT, params, rowMapper)
            ?: error("INSERT RETURNING 결과 없음 — id=${pat.id}")
    }

    @Transactional(readOnly = true)
    override fun findByTokenHash(tokenHash: String): PersonalAccessToken? =
        jdbc.query(SQL_FIND_BY_TOKEN_HASH, mapOf("tokenHash" to tokenHash), rowMapper)
            .firstOrNull()

    @Transactional(readOnly = true)
    override fun findActiveByUserId(userId: UUID): List<PersonalAccessToken> =
        jdbc.query(SQL_FIND_ACTIVE_BY_USER_ID, mapOf("userId" to userId), rowMapper)

    override fun updateLastUsed(id: UUID) {
        jdbc.update(SQL_UPDATE_LAST_USED, mapOf("id" to id, "now" to Timestamp.from(Instant.now())))
    }

    override fun revoke(id: UUID) {
        jdbc.update(SQL_REVOKE, mapOf("id" to id, "now" to Timestamp.from(Instant.now())))
    }

    @Transactional(readOnly = true)
    override fun listByUserIncludingExpired(userId: UUID): List<PersonalAccessToken> =
        jdbc.query(SQL_LIST_BY_USER_INCLUDING_EXPIRED, mapOf("userId" to userId), rowMapper)

    @Transactional(readOnly = true)
    override fun findByIdAndUserId(
        id: UUID,
        userId: UUID,
    ): PersonalAccessToken? =
        jdbc.query(SQL_FIND_BY_ID_AND_USER_ID, mapOf("id" to id, "userId" to userId), rowMapper)
            .firstOrNull()

    override fun revokeOwned(
        id: UUID,
        userId: UUID,
        now: Instant,
    ): Int =
        jdbc.update(
            SQL_REVOKE_OWNED,
            mapOf("id" to id, "userId" to userId, "now" to Timestamp.from(now)),
        )

    @Transactional(readOnly = true)
    override fun countActiveByUser(
        userId: UUID,
        now: Instant,
    ): Long =
        jdbc.queryForObject(
            SQL_COUNT_ACTIVE_BY_USER,
            mapOf("userId" to userId, "now" to Timestamp.from(now)),
            Long::class.java,
        ) ?: 0L

    // ── 직렬화 헬퍼 ──────────────────────────────────────────────────────────

    private fun serializeScopes(scopes: List<String>): String =
        objectMapper.writeValueAsString(scopes)

    private fun deserializeScopes(json: String?): List<String> {
        if (json == null) return emptyList()
        return objectMapper.readValue(json, SCOPES_TYPE_REF)
    }

    // ── RowMapper ─────────────────────────────────────────────────────────────

    /**
     * ResultSet → [PersonalAccessToken] 변환기.
     * 인스턴스당 한 번만 생성되어 재사용된다.
     */
    private val rowMapper: RowMapper<PersonalAccessToken> = RowMapper { rs, _ -> mapRow(rs) }

    private fun mapRow(rs: ResultSet): PersonalAccessToken =
        PersonalAccessToken(
            id = rs.getObject("id", UUID::class.java),
            userId = rs.getObject("user_id", UUID::class.java),
            name = rs.getString("name"),
            tokenHash = rs.getString("token_hash"),
            scopes = deserializeScopes(rs.getString("scopes")),
            expiresAt = rs.getTimestamp("expires_at")?.toInstant(),
            lastUsedAt = rs.getTimestamp("last_used_at")?.toInstant(),
            revokedAt = rs.getTimestamp("revoked_at")?.toInstant(),
            createdAt = rs.getTimestamp("created_at").toInstant(),
        )

    // ── SQL 상수 ─────────────────────────────────────────────────────────────

    private companion object {

        /** scopes List<String> 역직렬화 TypeReference — ObjectMapper reuse. */
        val SCOPES_TYPE_REF: TypeReference<List<String>> = object : TypeReference<List<String>>() {}

        /**
         * PAT 신규 INSERT — token_hash UNIQUE 제약으로 중복 시 예외 발생.
         * scopes 는 JSONB 캐스팅 (`::jsonb`) 필요.
         * RETURNING 으로 DB now() 기준 created_at 반환.
         */
        const val SQL_INSERT = """
            INSERT INTO personal_access_tokens
                (id, user_id, name, token_hash, scopes, expires_at)
            VALUES
                (:id, :userId, :name, :tokenHash, :scopes::jsonb, :expiresAt)
            RETURNING id, user_id, name, token_hash, scopes, expires_at, last_used_at, revoked_at, created_at
        """

        /**
         * token_hash 정확 일치 조회.
         * revoke/만료 여부 무관 — 상태 판별은 [PersonalAccessToken.isActive] 엔티티 책임.
         */
        const val SQL_FIND_BY_TOKEN_HASH = """
            SELECT id, user_id, name, token_hash, scopes, expires_at, last_used_at, revoked_at, created_at
            FROM personal_access_tokens
            WHERE token_hash = :tokenHash
        """

        /**
         * 활성 PAT 목록 — revoked_at IS NULL + expires_at 미도래 조건.
         * partial index `idx_pat_user_active` (WHERE revoked_at IS NULL) 를 활용한다.
         */
        const val SQL_FIND_ACTIVE_BY_USER_ID = """
            SELECT id, user_id, name, token_hash, scopes, expires_at, last_used_at, revoked_at, created_at
            FROM personal_access_tokens
            WHERE user_id = :userId
              AND revoked_at IS NULL
              AND (expires_at IS NULL OR expires_at > NOW())
        """

        /**
         * last_used_at 갱신 — 존재하지 않는 id 는 0 행 영향 (예외 없음).
         * NOW() 를 서버 측에서 계산하지 않고 :now 파라미터로 주입 — 테스트 시각 제어 가능.
         */
        const val SQL_UPDATE_LAST_USED = """
            UPDATE personal_access_tokens
            SET last_used_at = :now
            WHERE id = :id
        """

        /**
         * PAT revoke — revoked_at IS NULL 조건으로 멱등 처리.
         * 이미 revoke 된 행은 영향 없이 넘어간다 (0 행 영향, 예외 없음).
         */
        const val SQL_REVOKE = """
            UPDATE personal_access_tokens
            SET revoked_at = :now
            WHERE id = :id
              AND revoked_at IS NULL
        """

        /**
         * 사용자 PAT 목록 — revoke 만 제외(revoked_at IS NULL), 만료는 포함 (FR-API-04).
         * [SQL_FIND_ACTIVE_BY_USER_ID] 와 달리 expires_at 필터가 없다.
         * created_at DESC 최신순 정렬.
         */
        const val SQL_LIST_BY_USER_INCLUDING_EXPIRED = """
            SELECT id, user_id, name, token_hash, scopes, expires_at, last_used_at, revoked_at, created_at
            FROM personal_access_tokens
            WHERE user_id = :userId
              AND revoked_at IS NULL
            ORDER BY created_at DESC
        """

        /**
         * id + user_id 동시 일치 조회 — 소유권 확인(IDOR 차단, FR-API-04).
         * 상태 필터 없음: revoke/만료 여부와 무관하게 본인 소유이면 반환.
         */
        const val SQL_FIND_BY_ID_AND_USER_ID = """
            SELECT id, user_id, name, token_hash, scopes, expires_at, last_used_at, revoked_at, created_at
            FROM personal_access_tokens
            WHERE id = :id
              AND user_id = :userId
        """

        /**
         * 본인 소유 활성 PAT revoke — user_id 로 IDOR 차단, revoked_at IS NULL 로 멱등 (FR-API-04).
         * :now 를 파라미터로 주입해 호출 측 Clock 기준 시각을 사용한다.
         */
        const val SQL_REVOKE_OWNED = """
            UPDATE personal_access_tokens
            SET revoked_at = :now
            WHERE id = :id
              AND user_id = :userId
              AND revoked_at IS NULL
        """

        /**
         * 활성 PAT 개수 — 미취소(revoked_at IS NULL) + 미만료(expires_at IS NULL OR > :now) (FR-API-04).
         * 개수 상한 검사에 사용. :now 파라미터로 만료 기준 시각 주입.
         */
        const val SQL_COUNT_ACTIVE_BY_USER = """
            SELECT COUNT(*)
            FROM personal_access_tokens
            WHERE user_id = :userId
              AND revoked_at IS NULL
              AND (expires_at IS NULL OR expires_at > :now)
        """
    }
}
