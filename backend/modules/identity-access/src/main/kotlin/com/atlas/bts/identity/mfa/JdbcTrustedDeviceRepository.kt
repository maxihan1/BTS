// trusted_devices 테이블 접근 JDBC 구현 — 등록/해시조회/사용시각갱신/미만료목록/소유삭제/전체폐기. raw SQL + named param (FR-MF-05)

package com.atlas.bts.identity.mfa

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
 * [TrustedDeviceRepository] 의 `trusted_devices` 테이블 접근 구현(FR-MF-05 Task 3). SDD §19 / V026.
 *
 * **SQL 인젝션 방어 (DEVELOPMENT.md §1.3).**
 * 모든 파라미터를 [NamedParameterJdbcTemplate] `:param` 바인딩(prepared statement)으로 처리하며,
 * SQL 문자열 결합은 하지 않는다. `Connection.createStatement` 직접 사용 절대 금지.
 *
 * **트랜잭션 경계 (DATA.md §6).**
 * 클래스 레벨 `@Transactional(REQUIRED)` — 호출 측 트랜잭션에 참여하거나 새로 시작한다.
 * 조회 메서드는 `@Transactional(readOnly = true)` 로 오버라이드한다.
 *
 * **시각 주입.**
 * 만료 술어/사용시각 갱신의 `now` 는 호출 측이 주입 [java.time.Clock] 으로 산출해 전달한다.
 * 본 repo 는 `Instant.now()` 를 직접 호출하지 않는다(time-bomb 회귀 방지).
 *
 * **소유 검증 ([deleteByIdAndUser]).**
 * 삭제는 `WHERE id AND user_id` 복합 조건으로만 수행해 타인 디바이스 삭제(IDOR)를 차단하고,
 * 영향 행 수(0/1)로 소유 일치 여부를 판정한다.
 *
 * **보안: token_hash 를 로그에 절대 기록하지 않는다.**
 */
@Repository
@Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED)
class JdbcTrustedDeviceRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) : TrustedDeviceRepository {
    override fun insert(device: TrustedDevice) {
        jdbc.update(
            SQL_INSERT,
            mapOf(
                "id" to device.id,
                "userId" to device.userId,
                "tokenHash" to device.tokenHash,
                "label" to device.label,
                "createdAt" to Timestamp.from(device.createdAt),
                "expiresAt" to Timestamp.from(device.expiresAt),
                "lastUsedAt" to device.lastUsedAt?.let { Timestamp.from(it) },
            ),
        )
    }

    @Transactional(readOnly = true)
    override fun findByTokenHash(tokenHash: String): TrustedDevice? =
        jdbc.query(SQL_FIND_BY_TOKEN_HASH, mapOf("tokenHash" to tokenHash), rowMapper).firstOrNull()

    override fun updateLastUsedAt(
        id: UUID,
        now: Instant,
    ) {
        jdbc.update(SQL_UPDATE_LAST_USED_AT, mapOf("id" to id, "now" to Timestamp.from(now)))
    }

    @Transactional(readOnly = true)
    override fun listByUser(
        userId: UUID,
        now: Instant,
    ): List<TrustedDevice> {
        val params = mapOf("userId" to userId, "now" to Timestamp.from(now))
        return jdbc.query(SQL_LIST_BY_USER, params, rowMapper)
    }

    override fun deleteByIdAndUser(
        userId: UUID,
        id: UUID,
    ): Boolean = jdbc.update(SQL_DELETE_BY_ID_AND_USER, mapOf("id" to id, "userId" to userId)) > 0

    override fun deleteAllByUser(userId: UUID): Int = jdbc.update(SQL_DELETE_ALL_BY_USER, mapOf("userId" to userId))

    // ── SQL 상수 ─────────────────────────────────────────────────────────────

    private companion object {
        /** 신뢰 디바이스 등록 — token_hash 전역 UNIQUE(재신뢰 충돌 차단). created_at/expires_at 은 주입 Clock 기반. */
        const val SQL_INSERT = """
            INSERT INTO trusted_devices
                (id, user_id, token_hash, label, created_at, expires_at, last_used_at)
            VALUES
                (:id, :userId, :tokenHash, :label, :createdAt, :expiresAt, :lastUsedAt)
        """

        /** token_hash 단건 조회(쿠키 rawToken 해시 → 행). UNIQUE 로 최대 1행. 만료 행도 반환(판정은 호출측). */
        const val SQL_FIND_BY_TOKEN_HASH = """
            SELECT id, user_id, token_hash, label, created_at, expires_at, last_used_at
            FROM trusted_devices
            WHERE token_hash = :tokenHash
        """

        /** 마지막 사용 시각 갱신(우회 로그인 성공 기록). expires_at 은 갱신하지 않음(고정 30일). */
        const val SQL_UPDATE_LAST_USED_AT = """
            UPDATE trusted_devices
            SET last_used_at = :now
            WHERE id = :id
        """

        /** 사용자별 미만료 목록 — `expires_at > :now`(정각 만료 제외, EC10 경계). */
        const val SQL_LIST_BY_USER = """
            SELECT id, user_id, token_hash, label, created_at, expires_at, last_used_at
            FROM trusted_devices
            WHERE user_id = :userId
              AND expires_at > :now
            ORDER BY created_at
        """

        /** 소유 검증 단건 삭제 — id+user_id 복합 조건으로 타인 디바이스 차단(IDOR). */
        const val SQL_DELETE_BY_ID_AND_USER = """
            DELETE FROM trusted_devices
            WHERE id = :id
              AND user_id = :userId
        """

        /** 사용자 전체 폐기(보안 이벤트 자동 폐기). 만료 행 포함 전량 삭제, 반환=삭제 행 수. */
        const val SQL_DELETE_ALL_BY_USER = """
            DELETE FROM trusted_devices
            WHERE user_id = :userId
        """

        val rowMapper: RowMapper<TrustedDevice> = TrustedDeviceRowMapper()
    }
}

/**
 * trusted_devices 행을 [TrustedDevice] 도메인 모델로 변환하는 RowMapper.
 *
 * UUID: `getObject + UUID::class.java` — PostgreSQL JDBC 권장 방식(UUID.fromString 캐스팅 회피).
 * TIMESTAMPTZ: `getTimestamp(...).toInstant()` — UTC 기준 [Instant] 변환.
 * nullable 컬럼(label, last_used_at): SQL NULL 을 그대로 null 로 매핑.
 *
 * **보안: token_hash 를 로그에 절대 기록하지 않는다.**
 */
private class TrustedDeviceRowMapper : RowMapper<TrustedDevice> {
    override fun mapRow(
        rs: ResultSet,
        rowNum: Int,
    ): TrustedDevice =
        TrustedDevice(
            id = rs.getObject("id", UUID::class.java),
            userId = rs.getObject("user_id", UUID::class.java),
            tokenHash = rs.getString("token_hash"),
            label = rs.getString("label"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            expiresAt = rs.getTimestamp("expires_at").toInstant(),
            lastUsedAt = rs.getTimestamp("last_used_at")?.toInstant(),
        )
}
