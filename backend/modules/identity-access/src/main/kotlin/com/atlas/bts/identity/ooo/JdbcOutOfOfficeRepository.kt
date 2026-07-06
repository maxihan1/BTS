// user_ooo 테이블 접근 JdbcTemplate 구현체 — replace upsert + raw·활성 필터 조회 + 삭제 (FR-PR-03)

package com.atlas.bts.identity.ooo

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * [OutOfOfficeRepository] JDBC 구현체 (FR-PR-03).
 *
 * **트랜잭션 경계 (DATA.md §6)**:
 * 클래스 레벨 @Transactional(REQUIRED) — 호출 측 트랜잭션에 참여하거나 새로 시작.
 * 읽기 전용 [findByUserId]/[findActiveByUserId] 는 @Transactional(readOnly = true) 로 오버라이드.
 *
 * **replace 시맨틱**:
 * [upsert] 는 `INSERT ... ON CONFLICT (user_id) DO UPDATE` 로 전 컬럼(starts_at/ends_at/
 * delegate_user_id/message)을 통짜 교체한다(FR-PR-02 `JdbcUserStatusRepository` 미러).
 *
 * **활성 필터의 Clock 파라미터**:
 * [findActiveByUserId] 는 DB `NOW()` 대신 호출 측이 전달한 [Clock] 로부터 유도한 시각을 바인딩한다.
 * whoami view-layer(Task 5)와 검증 로직(Task 3 [OutOfOfficeService])이 동일한 [Clock] 인스턴스를
 * 공유할 수 있게 해 테스트 결정성과 시각 일관성을 확보한다.
 *
 * **Instant ↔ timestamptz**:
 * 쓰기는 `Timestamp.from(instant)`, 읽기는 `rs.getTimestamp(...).toInstant()` (PAT repository 관례).
 *
 * SQL 인젝션 방어: 모든 파라미터를 NamedParameterJdbcTemplate 에 바인딩 (DEVELOPMENT.md §1.3).
 */
@Repository
@Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED)
class JdbcOutOfOfficeRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) : OutOfOfficeRepository {
    override fun upsert(
        userId: UUID,
        startsAt: Instant,
        endsAt: Instant,
        delegateUserId: UUID?,
        message: String?,
    ) {
        jdbc.update(
            SQL_UPSERT,
            mapOf(
                "userId" to userId,
                "startsAt" to Timestamp.from(startsAt),
                "endsAt" to Timestamp.from(endsAt),
                "delegateUserId" to delegateUserId,
                "message" to message,
            ),
        )
    }

    @Transactional(readOnly = true)
    override fun findByUserId(userId: UUID): OutOfOffice? =
        jdbc.query(SQL_FIND, mapOf("userId" to userId), OutOfOfficeRowMapper).firstOrNull()

    @Transactional(readOnly = true)
    override fun findActiveByUserId(
        userId: UUID,
        clock: Clock,
    ): OutOfOffice? =
        jdbc.query(
            SQL_FIND_ACTIVE,
            mapOf("userId" to userId, "now" to Timestamp.from(Instant.now(clock))),
            OutOfOfficeRowMapper,
        ).firstOrNull()

    override fun deleteByUserId(userId: UUID) {
        jdbc.update(SQL_DELETE, mapOf("userId" to userId))
    }

    private companion object {
        /** 전 컬럼 교체(replace) upsert. updated_at 만 NOW() 로 갱신. */
        const val SQL_UPSERT = """
            INSERT INTO user_ooo (user_id, starts_at, ends_at, delegate_user_id, message)
            VALUES (:userId, :startsAt, :endsAt, :delegateUserId, :message)
            ON CONFLICT (user_id) DO UPDATE
                SET starts_at        = EXCLUDED.starts_at,
                    ends_at          = EXCLUDED.ends_at,
                    delegate_user_id = EXCLUDED.delegate_user_id,
                    message          = EXCLUDED.message,
                    updated_at       = NOW()
        """

        /** raw 조회 — 종료 여부 무필터. delegateName 은 users LEFT JOIN 파생(대리자 미지정/삭제 시 NULL). */
        const val SQL_FIND = """
            SELECT o.user_id, o.starts_at, o.ends_at, o.delegate_user_id, o.message,
                   d.display_name AS delegate_name
            FROM user_ooo o
            LEFT JOIN users d ON d.id = o.delegate_user_id
            WHERE o.user_id = :userId
        """

        /** 활성(startsAt<=:now<endsAt) 행만 조회 — :now 는 호출 측 Clock 에서 유도(DB NOW() 미사용). */
        const val SQL_FIND_ACTIVE = """
            SELECT o.user_id, o.starts_at, o.ends_at, o.delegate_user_id, o.message,
                   d.display_name AS delegate_name
            FROM user_ooo o
            LEFT JOIN users d ON d.id = o.delegate_user_id
            WHERE o.user_id = :userId
              AND o.starts_at <= :now
              AND o.ends_at > :now
        """

        /** OOO 삭제(해제). 행이 없으면 0행 영향으로 멱등. */
        const val SQL_DELETE = "DELETE FROM user_ooo WHERE user_id = :userId"
    }
}

/** user_ooo RowMapper — ResultSet → OutOfOffice 변환(delegate_name 은 LEFT JOIN 파생 컬럼). */
private object OutOfOfficeRowMapper : RowMapper<OutOfOffice> {
    override fun mapRow(
        rs: ResultSet,
        rowNum: Int,
    ): OutOfOffice =
        OutOfOffice(
            // getObject + UUID::class.java — Postgres JDBC 권장 방식 (UUID.fromString 캐스팅 회피)
            userId = rs.getObject("user_id", UUID::class.java),
            startsAt = rs.getTimestamp("starts_at").toInstant(),
            endsAt = rs.getTimestamp("ends_at").toInstant(),
            delegateUserId = rs.getObject("delegate_user_id", UUID::class.java),
            delegateName = rs.getString("delegate_name"),
            message = rs.getString("message"),
        )
}
