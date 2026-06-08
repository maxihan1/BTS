// local_credentials 테이블 접근 Repository — INSERT...RETURNING UPSERT + NamedParameterJdbcTemplate

package com.atlas.bts.identity.credential

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.util.UUID

/**
 * local_credentials 테이블 접근 Repository (FR-IS-03).
 *
 * **트랜잭션 경계 (DATA.md §6)**:
 * [save] 는 단일 UPSERT 이므로 독립 트랜잭션으로 충분하다.
 * 호출 측 트랜잭션이 있으면 참여(REQUIRED), 없으면 새로 시작한다.
 *
 * **UPSERT 설계 (DATA.md §5 idempotency)**:
 * INSERT ... ON CONFLICT (user_id) DO UPDATE 로 멱등성을 보장한다.
 * RETURNING 절로 DB 저장 시각을 그대로 반환 — 클라이언트 측 now() 의존 없음.
 *
 * **UUID RowMapper**:
 * [ResultSet.getObject] + UUID::class.java 를 사용한다.
 * UUID.fromString(getString(...)) 캐스팅은 Postgres JDBC가 비권장하는 방식이며
 * 향후 드라이버 업그레이드 시 문제가 될 수 있어 이 패턴으로 통일한다.
 *
 * SQL 인젝션 방어: 모든 파라미터를 NamedParameterJdbcTemplate 에 바인딩.
 * 문자열 결합 금지 (DEVELOPMENT.md §1.3).
 */
@Repository
@Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED)
class StoredPasswordCredentialRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    /**
     * 자격증명 저장 (신규 INSERT 또는 기존 행 UPSERT).
     *
     * - 신규: [StoredPasswordCredential.createdAt] + [StoredPasswordCredential.updatedAt] 모두 DB now() 로 설정.
     * - 갱신: [StoredPasswordCredential.passwordHash] + [StoredPasswordCredential.algoVersion] +
     *   [StoredPasswordCredential.updatedAt] 갱신. [StoredPasswordCredential.createdAt] 보존.
     *
     * @return DB에 반영된 최신 상태의 [StoredPasswordCredential]
     */
    fun save(credential: StoredPasswordCredential): StoredPasswordCredential {
        val params =
            mapOf(
                "userId" to credential.userId,
                "passwordHash" to credential.passwordHash,
                "algoVersion" to credential.algoVersion,
                "mustChangePassword" to credential.mustChangePassword,
            )
        return jdbc.queryForObject(SQL_UPSERT, params, rowMapper)
            ?: error("UPSERT RETURNING 결과 없음 — userId=${credential.userId}")
    }

    /**
     * user_id 로 자격증명 조회.
     *
     * @return 존재하면 [StoredPasswordCredential], 없으면 null
     */
    @Transactional(readOnly = true)
    fun findByUserId(userId: UUID): StoredPasswordCredential? {
        val results = jdbc.query(SQL_FIND, mapOf("userId" to userId), rowMapper)
        return results.firstOrNull()
    }

    /**
     * user_id 에 해당하는 자격증명 삭제.
     *
     * @return 삭제된 행 수 (존재했으면 1, 없었으면 0)
     */
    fun deleteByUserId(userId: UUID): Int = jdbc.update(SQL_DELETE, mapOf("userId" to userId))

    // ── SQL 상수 ─────────────────────────────────────────────────────────────

    private companion object {
        const val SQL_UPSERT = """
            INSERT INTO local_credentials (user_id, password_hash, algo_version, must_change_password)
            VALUES (:userId, :passwordHash, :algoVersion, :mustChangePassword)
            ON CONFLICT (user_id) DO UPDATE
                SET password_hash        = EXCLUDED.password_hash,
                    algo_version         = EXCLUDED.algo_version,
                    must_change_password = EXCLUDED.must_change_password,
                    updated_at           = now()
            RETURNING user_id, password_hash, algo_version, created_at, updated_at, must_change_password
        """

        const val SQL_FIND = """
            SELECT user_id, password_hash, algo_version, created_at, updated_at, must_change_password
            FROM local_credentials
            WHERE user_id = :userId
        """

        const val SQL_DELETE = """
            DELETE FROM local_credentials
            WHERE user_id = :userId
        """

        val rowMapper: RowMapper<StoredPasswordCredential> = StoredPasswordCredentialRowMapper()
    }
}

/** StoredPasswordCredential RowMapper — ResultSet → StoredPasswordCredential 변환 */
private class StoredPasswordCredentialRowMapper : RowMapper<StoredPasswordCredential> {
    override fun mapRow(
        rs: ResultSet,
        rowNum: Int,
    ): StoredPasswordCredential =
        StoredPasswordCredential(
            // getObject + UUID::class.java — Postgres JDBC 권장 방식 (UUID.fromString 캐스팅 회피)
            userId = rs.getObject("user_id", UUID::class.java),
            passwordHash = rs.getString("password_hash"),
            algoVersion = rs.getString("algo_version"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant(),
            mustChangePassword = rs.getBoolean("must_change_password"),
        )
}
