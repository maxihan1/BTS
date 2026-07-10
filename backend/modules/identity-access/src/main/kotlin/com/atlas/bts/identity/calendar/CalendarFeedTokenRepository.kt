// user_calendar_tokens 테이블 접근 JdbcTemplate 리포지토리 — upsert(rotate)/해시로 userId 조회/삭제/created_at 조회 (FR-CA-02)

package com.atlas.bts.identity.calendar

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/**
 * `user_calendar_tokens` 테이블 접근 리포지토리 (FR-CA-02 Task 2).
 *
 * 사용자당 활성 캘린더 피드 토큰 1개를 관리한다. [upsert] 는 `ON CONFLICT (user_id) DO UPDATE` 로
 * 재발급 시 토큰을 rotate 하고, 취소는 [deleteByUserId] 로 하드 삭제한다(임시 자격증명, ADR D4).
 * 익명 피드 조회는 [findUserIdByHash] 로 SHA-256 해시 → 소유자 userId 를 역매핑한다.
 *
 * **보안 계약**: [upsert] 에는 이미 해시된 값([CalendarFeedToken.hash])만 전달된다.
 * rawToken 평문은 이 리포지토리·DB·로그에 절대 저장/기록하지 않는다(DEVELOPMENT.md §1.1.1).
 *
 * **트랜잭션 경계 (DATA.md §6)**: 클래스 레벨 @Transactional(REQUIRED) — 호출 측 트랜잭션에
 * 참여하거나 새로 시작. 읽기 전용 [findUserIdByHash]/[findByUserId] 는 readOnly = true 로 오버라이드.
 *
 * SQL 인젝션 방어: 모든 파라미터를 NamedParameterJdbcTemplate 에 바인딩 (DEVELOPMENT.md §1.3).
 */
@Repository
@Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED)
class CalendarFeedTokenRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    /**
     * 캘린더 피드 토큰 발급/재발급 — 사용자당 1개 rotate.
     *
     * `ON CONFLICT (user_id) DO UPDATE` 로 기존 행이 있으면 token_hash 를 새 값으로 교체하고
     * created_at 을 NOW() 로 갱신한다(이전 해시는 즉시 조회 불가 → rotate).
     *
     * @param tokenHash `SHA-256(rawToken)` 64자 소문자 hex(해시된 값만 전달).
     */
    fun upsert(
        userId: UUID,
        tokenHash: String,
    ) {
        jdbc.update(SQL_UPSERT, mapOf("userId" to userId, "tokenHash" to tokenHash))
    }

    /**
     * token_hash 로 소유자 userId 조회 — 익명 피드(`GET /ical/feed/{token}.ics`) 역매핑.
     *
     * @return 일치하는 토큰이 있으면 소유자 userId, 없으면 null(호출 측 404 수렴).
     */
    @Transactional(readOnly = true)
    fun findUserIdByHash(tokenHash: String): UUID? {
        return jdbc.query(SQL_FIND_USER_ID_BY_HASH, mapOf("tokenHash" to tokenHash)) { rs, _ ->
            rs.getObject("user_id", UUID::class.java)
        }.firstOrNull()
    }

    /**
     * 사용자의 활성 토큰 발급 시각(created_at) 조회 — 발급 상태/시각 표시용.
     *
     * @return 토큰이 있으면 발급(rotate) 시각, 없으면 null(= 미발급).
     */
    @Transactional(readOnly = true)
    fun findByUserId(userId: UUID): Instant? {
        return jdbc.query(SQL_FIND_CREATED_AT_BY_USER, mapOf("userId" to userId)) { rs, _ ->
            rs.getTimestamp("created_at").toInstant()
        }.firstOrNull()
    }

    /** 캘린더 피드 토큰 취소(하드 삭제). 행이 없으면 0행 영향으로 멱등. */
    fun deleteByUserId(userId: UUID) {
        jdbc.update(SQL_DELETE, mapOf("userId" to userId))
    }

    private companion object {
        /** 발급/재발급 upsert — 재발급 시 token_hash 교체 + created_at NOW() 갱신(rotate). */
        const val SQL_UPSERT = """
            INSERT INTO user_calendar_tokens (user_id, token_hash)
            VALUES (:userId, :tokenHash)
            ON CONFLICT (user_id) DO UPDATE
                SET token_hash = EXCLUDED.token_hash,
                    created_at = NOW()
        """

        /** token_hash 정확 일치 → 소유자 user_id. UNIQUE 인덱스(V034)가 조회 인덱스 겸용. */
        const val SQL_FIND_USER_ID_BY_HASH = """
            SELECT user_id FROM user_calendar_tokens WHERE token_hash = :tokenHash
        """

        /** 사용자의 토큰 발급 시각. token_hash 는 비밀값이라 조회하지 않는다. */
        const val SQL_FIND_CREATED_AT_BY_USER = """
            SELECT created_at FROM user_calendar_tokens WHERE user_id = :userId
        """

        /** 토큰 취소(하드 삭제). 행이 없으면 0행 영향으로 멱등. */
        const val SQL_DELETE = "DELETE FROM user_calendar_tokens WHERE user_id = :userId"
    }
}
