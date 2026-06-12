// webauthn_credentials 테이블 접근 JDBC 구현 — 등록/조회/소유삭제/카운터 조건부전진/사용시각. raw SQL + named param

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
 * [WebAuthnCredentialRepository] 의 `webauthn_credentials` 테이블 접근 구현(FR-MF-03 Task 3). SDD §19.8.
 *
 * **SQL 인젝션 방어 (DEVELOPMENT.md §1.1-3)**:
 * 모든 파라미터를 [NamedParameterJdbcTemplate] `:param` 바인딩(prepared statement)으로 처리하며,
 * SQL 문자열 결합은 하지 않는다.
 *
 * **트랜잭션 경계 (DATA.md §6)**:
 * 각 연산은 단일 INSERT/SELECT/DELETE/UPDATE 이므로 독립 트랜잭션으로 충분하다.
 * 호출 측 트랜잭션이 있으면 참여(REQUIRED), 없으면 새로 시작한다.
 *
 * **소유 검증 ([deleteByIdAndUser])**:
 * 삭제는 `WHERE id AND user_id` 복합 조건으로만 수행해 타인 자격증명 삭제를 차단하고,
 * 영향 행 수(0/1)로 소유 일치 여부를 판정한다.
 *
 * **clone 방어 / TOCTOU ([advanceSignCount])**:
 * 서명 카운터 전진은 `WHERE id AND (sign_count < :newCount OR (sign_count = 0 AND :newCount = 0))`
 * 조건부 단일 atomic UPDATE 로만 처리한다. lock 밖에서 읽은 값으로 판단하지 않고, 영향 행 수로
 * 단조 증가(또는 0→0) 여부를 결정한다(advisory-lock-bigint-toctou 교훈 — read-then-write 경쟁 차단).
 */
@Repository
@Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED)
class JdbcWebAuthnCredentialRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) : WebAuthnCredentialRepository {
    override fun insert(credential: WebAuthnCredential) {
        jdbc.update(
            SQL_INSERT,
            mapOf(
                "id" to credential.id,
                "userId" to credential.userId,
                "credentialId" to credential.credentialId,
                "attestedCredentialData" to credential.attestedCredentialData,
                "signCount" to credential.signCount,
                "name" to credential.name,
                "aaguid" to credential.aaguid,
            ),
        )
    }

    @Transactional(readOnly = true)
    override fun findByUser(userId: UUID): List<WebAuthnCredential> =
        jdbc.query(SQL_FIND_BY_USER, mapOf("userId" to userId), rowMapper)

    @Transactional(readOnly = true)
    override fun findByCredentialId(credentialId: String): WebAuthnCredential? =
        jdbc.query(SQL_FIND_BY_CREDENTIAL_ID, mapOf("credentialId" to credentialId), rowMapper).firstOrNull()

    override fun deleteByIdAndUser(
        id: UUID,
        userId: UUID,
    ): Boolean = jdbc.update(SQL_DELETE_BY_ID_AND_USER, mapOf("id" to id, "userId" to userId)) > 0

    override fun advanceSignCount(
        id: UUID,
        newCount: Long,
    ): Boolean = jdbc.update(SQL_ADVANCE_SIGN_COUNT, mapOf("id" to id, "newCount" to newCount)) > 0

    override fun touchLastUsed(
        id: UUID,
        at: Instant,
    ) {
        jdbc.update(SQL_TOUCH_LAST_USED, mapOf("id" to id, "at" to Timestamp.from(at)))
    }

    // ── SQL 상수 ─────────────────────────────────────────────────────────────

    private companion object {
        /** 자격증명 등록 — 검증 통과 후 즉시 활성(status 컬럼 없음). credential_id 전역 UNIQUE. */
        const val SQL_INSERT = """
            INSERT INTO webauthn_credentials
                (id, user_id, credential_id, attested_credential_data, sign_count, name, aaguid)
            VALUES
                (:id, :userId, :credentialId, :attestedCredentialData, :signCount, :name, :aaguid)
        """

        /** 사용자별 자격증명 전체 조회(목록/관리 화면). */
        const val SQL_FIND_BY_USER = """
            SELECT id, user_id, credential_id, attested_credential_data, sign_count,
                   name, aaguid, last_used_at, created_at, updated_at
            FROM webauthn_credentials
            WHERE user_id = :userId
        """

        /** 전역 UNIQUE credential_id 단건 조회(assertion 검증 진입점). */
        const val SQL_FIND_BY_CREDENTIAL_ID = """
            SELECT id, user_id, credential_id, attested_credential_data, sign_count,
                   name, aaguid, last_used_at, created_at, updated_at
            FROM webauthn_credentials
            WHERE credential_id = :credentialId
        """

        /** 소유 검증 삭제 — id+user_id 복합 조건으로 타인 자격증명 차단. */
        const val SQL_DELETE_BY_ID_AND_USER = """
            DELETE FROM webauthn_credentials
            WHERE id = :id
              AND user_id = :userId
        """

        /**
         * 서명 카운터 조건부 전진 — 더 큰 값만 전진(clone 방어).
         * `sign_count = 0 AND :newCount = 0` 은 항상 0 을 보고하는 정상 인증기를 위한 예외(0→0 허용).
         * 같거나 작은 값(역행/정체)은 0행 갱신 → clone/replay 의심으로 거부.
         */
        const val SQL_ADVANCE_SIGN_COUNT = """
            UPDATE webauthn_credentials
            SET sign_count = :newCount,
                updated_at = now()
            WHERE id = :id
              AND (sign_count < :newCount OR (sign_count = 0 AND :newCount = 0))
        """

        /** 마지막 사용 시각 갱신(assertion 성공 기록). */
        const val SQL_TOUCH_LAST_USED = """
            UPDATE webauthn_credentials
            SET last_used_at = :at,
                updated_at   = now()
            WHERE id = :id
        """

        val rowMapper: RowMapper<WebAuthnCredential> = WebAuthnCredentialRowMapper()
    }
}

/** webauthn_credentials 행을 [WebAuthnCredential] 도메인 모델로 변환하는 RowMapper. */
private class WebAuthnCredentialRowMapper : RowMapper<WebAuthnCredential> {
    override fun mapRow(
        rs: ResultSet,
        rowNum: Int,
    ): WebAuthnCredential =
        WebAuthnCredential(
            // getObject + UUID::class.java — Postgres JDBC 권장(UUID.fromString 캐스팅 회피)
            id = rs.getObject("id", UUID::class.java),
            userId = rs.getObject("user_id", UUID::class.java),
            credentialId = rs.getString("credential_id"),
            attestedCredentialData = rs.getString("attested_credential_data"),
            signCount = rs.getLong("sign_count"),
            // nullable 컬럼 — getString/getTimestamp 는 SQL NULL 을 그대로 null 로 매핑
            name = rs.getString("name"),
            aaguid = rs.getString("aaguid"),
            lastUsedAt = rs.getTimestamp("last_used_at")?.toInstant(),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant(),
        )
}
