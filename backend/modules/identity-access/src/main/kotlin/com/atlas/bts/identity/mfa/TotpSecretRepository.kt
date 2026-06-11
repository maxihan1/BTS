// totp_secrets 테이블 접근 Repository — UPSERT(PENDING)/조회/activate/step 단조증가/삭제. raw SQL + named param

package com.atlas.bts.identity.mfa

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.util.UUID

/**
 * `totp_secrets` 테이블 접근 Repository (FR-MF-01 Task 4). SDD §19.7.
 *
 * **트랜잭션 경계 (DATA.md §6)**:
 * 각 연산은 단일 UPSERT/UPDATE/DELETE 이므로 독립 트랜잭션으로 충분하다.
 * 호출 측 트랜잭션이 있으면 참여(REQUIRED), 없으면 새로 시작한다.
 *
 * **SQL 인젝션 방어 (DEVELOPMENT.md §1.1-3)**:
 * 모든 파라미터를 [NamedParameterJdbcTemplate] `:param` 바인딩(prepared statement)으로 처리하며,
 * SQL 문자열 결합은 하지 않는다.
 *
 * **replay / TOCTOU 방어 ([advanceVerifiedStep])**:
 * 검증 성공 step 은 조건부 UPDATE(`WHERE last_verified_step IS NULL OR last_verified_step < :step`)로만
 * 전진시킨다. lock 밖에서 읽은 값으로 판단하지 않고, 단일 atomic UPDATE 의 영향 행 수로
 * 단조 증가 여부를 결정한다(advisory-lock-bigint-toctou 교훈 — read-then-write 경쟁 차단).
 *
 * **secret 보안**:
 * [TotpSecret.secretCipher] 는 암호문이며, 본 Repository 는 로그를 출력하지 않는다(DEVELOPMENT.md §1.1-2).
 */
@Repository
@Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED)
class TotpSecretRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    /**
     * 신규 PENDING secret 을 저장하거나, 기존 행(PENDING/ACTIVE 무관)을 새 PENDING 으로 덮어쓴다(re-setup).
     *
     * `ON CONFLICT (user_id) DO UPDATE` 로 멱등하게 처리하며, 재설정 시 검증 진행 상태를 초기화한다
     * (status='PENDING', last_verified_step=NULL, confirmed_at=NULL).
     *
     * @param userId 사용자 식별자(`users.id`).
     * @param secretCipher 새 TOTP secret 암호문.
     */
    fun upsertPending(
        userId: UUID,
        secretCipher: String,
    ) {
        jdbc.update(SQL_UPSERT_PENDING, mapOf("userId" to userId, "secretCipher" to secretCipher))
    }

    /**
     * user_id 로 단건 secret 을 조회한다.
     *
     * @param userId 사용자 식별자.
     * @return 일치하는 [TotpSecret], 없으면 `null`.
     */
    @Transactional(readOnly = true)
    fun findByUser(userId: UUID): TotpSecret? {
        return jdbc.query(SQL_FIND_BY_USER, mapOf("userId" to userId), rowMapper).firstOrNull()
    }

    /**
     * PENDING secret 을 ACTIVE 로 전이하고 confirmed_at 을 채운다(enable 확인 완료).
     *
     * @param userId 사용자 식별자.
     */
    fun activate(userId: UUID) {
        jdbc.update(SQL_ACTIVATE, mapOf("userId" to userId))
    }

    /**
     * 마지막 검증 성공 step 을 단조 증가시킨다(코드 replay / TOCTOU 방어).
     *
     * `WHERE last_verified_step IS NULL OR last_verified_step < :step` 조건부 UPDATE 로,
     * 이미 같거나 더 큰 step 이 기록돼 있으면(=replay) 아무 행도 갱신하지 않는다.
     *
     * @param userId 사용자 식별자.
     * @param step 이번 검증에 성공한 time-step(RFC 6238).
     * @return step 이 전진해 1행이 갱신되면 `true`, 같거나 낮아(replay) 갱신이 없으면 `false`.
     */
    fun advanceVerifiedStep(
        userId: UUID,
        step: Long,
    ): Boolean = jdbc.update(SQL_ADVANCE_STEP, mapOf("userId" to userId, "step" to step)) > 0

    /**
     * user_id 에 해당하는 secret 을 삭제한다(disable).
     *
     * @param userId 사용자 식별자.
     * @return 실제로 삭제된 행이 있으면 `true`, 없으면 `false`.
     */
    fun deleteByUser(userId: UUID): Boolean = jdbc.update(SQL_DELETE, mapOf("userId" to userId)) > 0

    // ── SQL 상수 ─────────────────────────────────────────────────────────────

    private companion object {
        /** 신규 PENDING INSERT 또는 기존 행을 새 PENDING 으로 덮어쓰기(검증 진행상태 초기화). */
        const val SQL_UPSERT_PENDING = """
            INSERT INTO totp_secrets (user_id, secret_cipher, status)
            VALUES (:userId, :secretCipher, 'PENDING')
            ON CONFLICT (user_id) DO UPDATE
                SET secret_cipher      = EXCLUDED.secret_cipher,
                    status             = 'PENDING',
                    last_verified_step = NULL,
                    confirmed_at       = NULL,
                    updated_at         = now()
        """

        /** user_id 단건 조회. */
        const val SQL_FIND_BY_USER = """
            SELECT user_id, secret_cipher, status, last_verified_step, confirmed_at, created_at, updated_at
            FROM totp_secrets
            WHERE user_id = :userId
        """

        /** PENDING → ACTIVE 전이 + confirmed_at 설정. */
        const val SQL_ACTIVATE = """
            UPDATE totp_secrets
            SET status       = 'ACTIVE',
                confirmed_at = now(),
                updated_at   = now()
            WHERE user_id = :userId
        """

        /** 검증 step 단조 증가 — 이미 같거나 큰 step 이면 0행(replay 차단). */
        const val SQL_ADVANCE_STEP = """
            UPDATE totp_secrets
            SET last_verified_step = :step,
                updated_at         = now()
            WHERE user_id = :userId
              AND (last_verified_step IS NULL OR last_verified_step < :step)
        """

        /** disable — secret 행 삭제. */
        const val SQL_DELETE = """
            DELETE FROM totp_secrets
            WHERE user_id = :userId
        """

        val rowMapper: RowMapper<TotpSecret> = TotpSecretRowMapper()
    }
}

/** totp_secrets 행을 [TotpSecret] 도메인 모델로 변환하는 RowMapper. */
private class TotpSecretRowMapper : RowMapper<TotpSecret> {
    override fun mapRow(
        rs: ResultSet,
        rowNum: Int,
    ): TotpSecret =
        TotpSecret(
            // getObject + UUID::class.java — Postgres JDBC 권장(UUID.fromString 캐스팅 회피)
            userId = rs.getObject("user_id", UUID::class.java),
            secretCipher = rs.getString("secret_cipher"),
            status = TotpStatus.valueOf(rs.getString("status")),
            // nullable BIGINT — 박싱 타입으로 받아 SQL NULL 을 그대로 null 로 매핑
            lastVerifiedStep = rs.getObject("last_verified_step", Long::class.javaObjectType),
            confirmedAt = rs.getTimestamp("confirmed_at")?.toInstant(),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant(),
        )
}
