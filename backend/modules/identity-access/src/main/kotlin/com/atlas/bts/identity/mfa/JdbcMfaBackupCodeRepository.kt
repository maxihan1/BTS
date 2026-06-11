// user_mfa_backup_codes 테이블 접근 JDBC 구현 — 재발급(DELETE+INSERT)/atomic 소진/카운트/삭제. raw SQL + named param

package com.atlas.bts.identity.mfa

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.core.namedparam.SqlParameterSource
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * [MfaBackupCodeRepository] 의 `user_mfa_backup_codes` 테이블 접근 구현(FR-MF-02 Task 4). SDD §19.7.
 *
 * **SQL 인젝션 방어 (DEVELOPMENT.md §1.1-3)**:
 * 모든 파라미터를 [NamedParameterJdbcTemplate] `:param` 바인딩(prepared statement)으로 처리하며,
 * SQL 문자열 결합은 하지 않는다.
 *
 * **재발급 트랜잭션 경계 ([replaceAll], DATA.md §6)**:
 * 교체는 "기존 전량 DELETE" 와 "새 묶음 INSERT" 두 문장으로 이뤄지므로 단일 트랜잭션으로 묶는다.
 * 중간 실패 시 이전 코드가 부분 삭제된 채 남거나 옛/새 묶음이 섞이지 않도록 원자성을 보장한다.
 *
 * **소진 TOCTOU 방어 ([consumeIfUnused])**:
 * "미사용인지 읽고 → 사용 표시" 를 두 문장으로 나누면, 두 요청이 같은 코드를 동시에 통과시키는
 * 경쟁(시간차 공격, TOCTOU)이 생길 수 있다. 따라서 `WHERE ... used_at IS NULL` 조건을 포함한
 * 단일 atomic UPDATE 한 문장으로 처리하고, 그 영향 행 수(0 또는 1)로 소진 성공 여부를 판정한다.
 * lock 밖에서 읽은 값으로 판단하지 않는다(advisory-lock-bigint-toctou 교훈 — read-then-write 경쟁 차단).
 *
 * **비밀값 보안 (DEVELOPMENT.md §1.1.2)**:
 * 다루는 값은 SHA-256 해시뿐이며 평문 코드는 다루지 않고, 어떤 값도 로깅하지 않는다.
 */
@Repository
@Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED)
class JdbcMfaBackupCodeRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) : MfaBackupCodeRepository {
    override fun replaceAll(
        userId: UUID,
        codeHashes: List<String>,
    ) {
        jdbc.update(SQL_DELETE_ALL, mapOf("userId" to userId))
        if (codeHashes.isEmpty()) return

        val batch: Array<SqlParameterSource> =
            codeHashes
                .map { hash ->
                    MapSqlParameterSource()
                        .addValue("userId", userId)
                        .addValue("codeHash", hash)
                }.toTypedArray()
        jdbc.batchUpdate(SQL_INSERT, batch)
    }

    override fun consumeIfUnused(
        userId: UUID,
        codeHash: String,
    ): Boolean =
        jdbc.update(
            SQL_CONSUME_IF_UNUSED,
            mapOf("userId" to userId, "codeHash" to codeHash),
        ) > 0

    @Transactional(readOnly = true)
    override fun countUnused(userId: UUID): Int {
        return jdbc.queryForObject(SQL_COUNT_UNUSED, mapOf("userId" to userId), Int::class.java) ?: 0
    }

    @Transactional(readOnly = true)
    override fun countTotal(userId: UUID): Int {
        return jdbc.queryForObject(SQL_COUNT_TOTAL, mapOf("userId" to userId), Int::class.java) ?: 0
    }

    override fun deleteAllByUser(userId: UUID) {
        jdbc.update(SQL_DELETE_ALL, mapOf("userId" to userId))
    }

    // ── SQL 상수 ─────────────────────────────────────────────────────────────

    private companion object {
        /** 사용자 백업 코드 전량 삭제(사용/미사용 무관) — 재발급 1단계 + disable. */
        const val SQL_DELETE_ALL = """
            DELETE FROM user_mfa_backup_codes
            WHERE user_id = :userId
        """

        /** 백업 코드 해시 1건 삽입 — 재발급 2단계(batch 로 다건 처리). */
        const val SQL_INSERT = """
            INSERT INTO user_mfa_backup_codes (user_id, code_hash)
            VALUES (:userId, :codeHash)
        """

        /**
         * 미사용 코드 atomic 소진 — `used_at IS NULL` 조건을 포함한 단일 UPDATE.
         * 이미 사용됐거나 없는 해시는 0행이 갱신되어 false 로 수렴(TOCTOU 차단).
         */
        const val SQL_CONSUME_IF_UNUSED = """
            UPDATE user_mfa_backup_codes
            SET used_at = NOW()
            WHERE user_id = :userId
              AND code_hash = :codeHash
              AND used_at IS NULL
        """

        /** 미사용(used_at IS NULL) 코드 수. */
        const val SQL_COUNT_UNUSED = """
            SELECT COUNT(*)
            FROM user_mfa_backup_codes
            WHERE user_id = :userId
              AND used_at IS NULL
        """

        /** 전체(사용+미사용) 코드 수. */
        const val SQL_COUNT_TOTAL = """
            SELECT COUNT(*)
            FROM user_mfa_backup_codes
            WHERE user_id = :userId
        """
    }
}
