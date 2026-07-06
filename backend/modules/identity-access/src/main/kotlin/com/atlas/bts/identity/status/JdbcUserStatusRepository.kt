// user_statuses 테이블 접근 JdbcTemplate 구현체 — replace upsert + 만료 필터 조회 + 삭제 (FR-PR-02)

package com.atlas.bts.identity.status

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
 * [UserStatusRepository] JDBC 구현체 (FR-PR-02).
 *
 * **트랜잭션 경계 (DATA.md §6)**:
 * 클래스 레벨 @Transactional(REQUIRED) — 호출 측 트랜잭션에 참여하거나 새로 시작.
 * 읽기 전용 [findActiveByUserId] 는 @Transactional(readOnly = true) 로 오버라이드.
 *
 * **replace 시맨틱**:
 * [upsert] 는 `INSERT ... ON CONFLICT (user_id) DO UPDATE` 로 전 컬럼(emoji/text/expires_at)을 통짜
 * 교체한다 — FR-PR-01 프로필의 필드별 보존 upsert 와 달리 SET 절에서 아무 컬럼도 제외하지 않는다.
 *
 * **만료 lazy 필터**:
 * [findActiveByUserId] 는 `expires_at IS NULL OR expires_at > NOW()` 로 만료 상태를 SQL 레벨에서 제외한다
 * (PAT `findActiveByUserId` 선례). 스케줄러/배치 삭제 없이 조회 시점에 자연 소멸.
 *
 * **Instant ↔ timestamptz**:
 * 쓰기는 `Timestamp.from(instant)`, 읽기는 `rs.getTimestamp(...).toInstant()` (PAT repository 관례).
 *
 * SQL 인젝션 방어: 모든 파라미터를 NamedParameterJdbcTemplate 에 바인딩 (DEVELOPMENT.md §1.3).
 */
@Repository
@Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED)
class JdbcUserStatusRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) : UserStatusRepository {
    override fun upsert(
        userId: UUID,
        emoji: String?,
        text: String?,
        expiresAt: Instant?,
    ) {
        jdbc.update(
            SQL_UPSERT,
            mapOf(
                "userId" to userId,
                "emoji" to emoji,
                "text" to text,
                "expiresAt" to expiresAt?.let { Timestamp.from(it) },
            ),
        )
    }

    @Transactional(readOnly = true)
    override fun findActiveByUserId(userId: UUID): UserStatus? =
        jdbc.query(SQL_FIND_ACTIVE, mapOf("userId" to userId), UserStatusRowMapper).firstOrNull()

    override fun deleteByUserId(userId: UUID) {
        jdbc.update(SQL_DELETE, mapOf("userId" to userId))
    }

    private companion object {
        /** 전 컬럼 교체(replace) upsert. updated_at 만 NOW() 로 갱신. */
        const val SQL_UPSERT = """
            INSERT INTO user_statuses (user_id, emoji, text, expires_at)
            VALUES (:userId, :emoji, :text, :expiresAt)
            ON CONFLICT (user_id) DO UPDATE
                SET emoji      = EXCLUDED.emoji,
                    text       = EXCLUDED.text,
                    expires_at = EXCLUDED.expires_at,
                    updated_at = NOW()
        """

        /** 활성 상태만 조회 — 만료(expires_at 과거) 행은 제외. */
        const val SQL_FIND_ACTIVE = """
            SELECT user_id, emoji, text, expires_at
            FROM user_statuses
            WHERE user_id = :userId
              AND (expires_at IS NULL OR expires_at > NOW())
        """

        /** 상태 삭제(해제). 행이 없으면 0행 영향으로 멱등. */
        const val SQL_DELETE = "DELETE FROM user_statuses WHERE user_id = :userId"
    }
}

/** user_statuses RowMapper — ResultSet → UserStatus 변환. */
private object UserStatusRowMapper : RowMapper<UserStatus> {
    override fun mapRow(
        rs: ResultSet,
        rowNum: Int,
    ): UserStatus =
        UserStatus(
            // getObject + UUID::class.java — Postgres JDBC 권장 방식 (UUID.fromString 캐스팅 회피)
            userId = rs.getObject("user_id", UUID::class.java),
            emoji = rs.getString("emoji"),
            text = rs.getString("text"),
            expiresAt = rs.getTimestamp("expires_at")?.toInstant(),
        )
}
