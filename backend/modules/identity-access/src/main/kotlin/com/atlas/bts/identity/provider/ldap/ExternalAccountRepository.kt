// user_external_accounts + users 테이블 접근 Repository — plain JDBC + NamedParameterJdbcTemplate

package com.atlas.bts.identity.provider.ldap

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
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
 * user_external_accounts + users 테이블 접근 Repository (FR-AU-02).
 *
 * **공통 컴포넌트 (LDAP 전용 아님)**:
 * LDAP 전용이 아니라 외부 IdP(Identity Provider) 프로비저닝 공통 컴포넌트다.
 * SAML SSO(FR-AU-03)도 이 Repository 를 이동·복제 없이 그대로 import 해 재사용한다
 * (게이트1 옵션 C — 이동 없이 재사용).
 *
 * **트랜잭션 경계 (DATA.md §6)**:
 * [provisionUser] 는 users UPSERT + user_external_accounts UPSERT 를 단일 트랜잭션으로 처리한다.
 * 어느 한 쪽이 실패하면 양쪽 모두 rollback 된다 (부분 성공 금지).
 *
 * **UPSERT 설계 (DATA.md §5 idempotency)**:
 * user_external_accounts 는 INSERT ... ON CONFLICT (provider_id, external_subject) DO UPDATE 로
 * 멱등성을 보장한다. RETURNING 절로 DB 저장 행을 그대로 반환 — 추가 SELECT 불필요.
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
class ExternalAccountRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    // ObjectMapper는 Bean 의존 없이 독립 인스턴스 사용 — @JdbcTest 슬라이스 호환
    private val objectMapper: ObjectMapper = ObjectMapper().registerKotlinModule()
    private val rowMapper = ExternalAccountRowMapper(objectMapper)

    /**
     * provider_id + external_subject 로 매핑 조회.
     * 매핑 없으면 null 반환.
     */
    fun findByProviderIdAndExternalSubject(
        providerId: UUID,
        externalSubject: String,
    ): ExternalAccount? {
        val results =
            jdbc.query(
                SQL_FIND_BY_PROVIDER_AND_SUBJECT,
                mapOf("providerId" to providerId, "externalSubject" to externalSubject),
                rowMapper,
            )
        return results.firstOrNull()
    }

    /**
     * user_external_accounts UPSERT.
     *
     * **단일 책임 (AutoProvisionService.kt KDoc)**: users UPSERT 는 호출 측이 선행한다.
     * 본 메서드는 user_external_accounts 만 처리하며 [userId] 는 이미 users 에 존재하는 row 의 id 여야 한다.
     *
     * **멱등성**: 동일 (provider_id, external_subject) 로 재호출 시 같은 row 를 반환한다.
     * ON CONFLICT 시 groups / updated_at 만 갱신, user_id 는 보존.
     */
    @Suppress("LongParameterList")
    fun provisionUser(
        providerId: UUID,
        externalSubject: String,
        userId: UUID,
        groups: List<String>,
    ): ExternalAccount {
        val groupsJson = objectMapper.writeValueAsString(groups)
        val accountId = UUID.randomUUID()
        return jdbc.queryForObject(
            SQL_UPSERT_EXTERNAL_ACCOUNT,
            mapOf(
                "id" to accountId,
                "providerId" to providerId,
                "externalSubject" to externalSubject,
                "userId" to userId,
                "groups" to groupsJson,
            ),
            rowMapper,
        ) ?: error("UPSERT RETURNING 결과 없음 — subject=$externalSubject")
    }

    /** 연속 실패 횟수 +1 */
    fun incrementFailedAttempts(id: UUID) {
        jdbc.update(SQL_INCREMENT_FAILED_ATTEMPTS, mapOf("id" to id))
    }

    /** 연속 실패 횟수 0 으로 reset */
    fun resetFailedAttempts(id: UUID) {
        jdbc.update(SQL_RESET_FAILED_ATTEMPTS, mapOf("id" to id))
    }

    /** 잠금 만료 시각 설정 */
    fun markLockedUntil(
        id: UUID,
        until: Instant,
    ) {
        jdbc.update(SQL_MARK_LOCKED_UNTIL, mapOf("id" to id, "lockedUntil" to Timestamp.from(until)))
    }

    /**
     * 마지막 로그인 시각 갱신 + failed_attempts 0 reset.
     * 로그인 성공 시 잠금 카운터도 함께 초기화한다.
     */
    fun updateLastLoginAt(
        id: UUID,
        now: Instant,
    ) {
        jdbc.update(SQL_UPDATE_LAST_LOGIN, mapOf("id" to id, "lastLoginAt" to Timestamp.from(now)))
    }

    // ── FR-AU-08 계정 연결 관리 (조회/삭제/락) ──────────────────────────

    /**
     * 한 사용자에 연결된 모든 external account 조회 (FR-AU-08).
     * 연결이 없으면 빈 리스트를 반환한다.
     */
    fun findByUserId(userId: UUID): List<ExternalAccount> {
        return jdbc.query(SQL_FIND_BY_USER_ID, mapOf("userId" to userId), rowMapper)
    }

    /**
     * 소유 검증 겸 external account 삭제 (FR-AU-08).
     *
     * **소유 검증**: WHERE 절에 id + user_id 를 함께 둬, 타인의 [userId] 로는
     * 다른 사용자의 매핑을 삭제할 수 없다 (소유 불일치 시 0행).
     *
     * @return 영향 행 수 (1 = 삭제 성공, 0 = 소유 불일치 또는 미존재)
     */
    fun deleteByIdAndUserId(
        id: UUID,
        userId: UUID,
    ): Int = jdbc.update(SQL_DELETE_BY_ID_AND_USER_ID, mapOf("id" to id, "userId" to userId))

    /**
     * 사용자 단위 advisory lock 획득 (FR-AU-08 동시성 제어).
     *
     * **advisory-lock-bigint-toctou 선례**: lock 후 호출자가 count 재조회 → delete 를
     * 반드시 **같은 트랜잭션** 안에서 수행해야 TOCTOU(읽고-나서-쓰기 사이 변경) 가 막힌다.
     * lock 밖에서 읽은 값으로 판단하면 lock 이 무력화된다.
     *
     * **UUID → bigint 변환**: 상위 64bit 절단 금지(충돌 과다). `hashtextextended` 로
     * UUID 텍스트 전폭을 해시해 bigint 를 만든다. 해시 충돌은 무관 사용자의 거짓 직렬화일
     * 뿐 안전하다. `hashtextextended` 가 bigint 를 반환하므로
     * `pg_advisory_xact_lock(bigint)` 단일 시그니처와 정합한다 (bigint,bigint 시그니처 없음).
     */
    fun acquireUserLock(userId: UUID) {
        // pg_advisory_xact_lock 은 void 반환 — 결과 행은 단순 소비(discard)한다.
        // queryForObject 로 특정 타입 변환을 강제하면 void 매핑이 실패하므로 queryForList 로 받아 버린다.
        // userId 는 hashtextextended(text, int8) 입력에 맞춰 문자열로 바인딩한다.
        jdbc.queryForList(SQL_ACQUIRE_USER_LOCK, mapOf("userId" to userId.toString()))
    }

    // ── FR-AU-08b SSO 연결 (순수 INSERT + subject advisory lock) ──────────────

    /** RED 골격 — 아직 미구현. */
    @Suppress("LongParameterList")
    fun insertLink(
        providerId: UUID,
        externalSubject: String,
        userId: UUID,
        groups: List<String>,
    ): ExternalAccount = error("insertLink 미구현 (RED)")

    /** RED 골격 — 아직 미구현. */
    fun acquireSubjectLock(
        providerId: UUID,
        externalSubject: String,
    ) {
        // 미구현
    }

    // ── SQL 상수 ─────────────────────────────────────────────────────────────

    private companion object {
        const val SQL_FIND_BY_PROVIDER_AND_SUBJECT = """
            SELECT id, provider_id, external_subject, user_id, groups,
                   failed_attempts, locked_until, last_login_at, created_at, updated_at
            FROM user_external_accounts
            WHERE provider_id = :providerId AND external_subject = :externalSubject
        """

        /**
         * user_external_accounts UPSERT + RETURNING.
         * ON CONFLICT (provider_id, external_subject) 시 groups/updated_at 만 갱신.
         * user_id, id, failed_attempts, locked_until 등은 기존 값을 보존한다.
         * RETURNING 으로 추가 SELECT 없이 최신 상태 반환.
         */
        const val SQL_UPSERT_EXTERNAL_ACCOUNT = """
            INSERT INTO user_external_accounts (id, provider_id, external_subject, user_id, groups)
            VALUES (:id, :providerId, :externalSubject, :userId, :groups::jsonb)
            ON CONFLICT (provider_id, external_subject) DO UPDATE
                SET groups     = EXCLUDED.groups,
                    updated_at = NOW()
            RETURNING id, provider_id, external_subject, user_id, groups,
                      failed_attempts, locked_until, last_login_at, created_at, updated_at
        """

        const val SQL_INCREMENT_FAILED_ATTEMPTS = """
            UPDATE user_external_accounts
            SET failed_attempts = failed_attempts + 1, updated_at = NOW()
            WHERE id = :id
        """

        const val SQL_RESET_FAILED_ATTEMPTS = """
            UPDATE user_external_accounts
            SET failed_attempts = 0, updated_at = NOW()
            WHERE id = :id
        """

        const val SQL_MARK_LOCKED_UNTIL = """
            UPDATE user_external_accounts
            SET locked_until = :lockedUntil, updated_at = NOW()
            WHERE id = :id
        """

        const val SQL_UPDATE_LAST_LOGIN = """
            UPDATE user_external_accounts
            SET last_login_at = :lastLoginAt, failed_attempts = 0, locked_until = NULL, updated_at = NOW()
            WHERE id = :id
        """

        /** 한 사용자에 연결된 모든 external account 조회 (FR-AU-08). */
        const val SQL_FIND_BY_USER_ID = """
            SELECT id, provider_id, external_subject, user_id, groups,
                   failed_attempts, locked_until, last_login_at, created_at, updated_at
            FROM user_external_accounts
            WHERE user_id = :userId
        """

        /**
         * external account 삭제 + 소유 검증 (FR-AU-08).
         * id 와 user_id 를 함께 WHERE 에 둬, 타인 user_id 로는 삭제되지 않는다 (소유 불일치 시 0행).
         */
        const val SQL_DELETE_BY_ID_AND_USER_ID = """
            DELETE FROM user_external_accounts
            WHERE id = :id AND user_id = :userId
        """

        /**
         * 사용자 단위 advisory lock 획득 (FR-AU-08).
         * hashtextextended 로 UUID 텍스트 전폭을 bigint 로 해시 — 절단 금지(충돌 과다).
         * pg_advisory_xact_lock(bigint) 단일 시그니처와 정합 (bigint,bigint 시그니처 없음).
         */
        const val SQL_ACQUIRE_USER_LOCK = """
            SELECT pg_advisory_xact_lock(hashtextextended(:userId, 0))
        """
    }
}

/** ExternalAccount RowMapper — ResultSet → ExternalAccount 변환 */
private class ExternalAccountRowMapper(
    private val objectMapper: ObjectMapper,
) : RowMapper<ExternalAccount> {
    @Suppress("UNCHECKED_CAST")
    override fun mapRow(
        rs: ResultSet,
        rowNum: Int,
    ): ExternalAccount {
        val groupsJson = rs.getString("groups") ?: "[]"
        val groups = objectMapper.readValue(groupsJson, List::class.java) as List<String>

        return ExternalAccount(
            // getObject + UUID::class.java — Postgres JDBC 권장 방식 (UUID.fromString 캐스팅 회피)
            id = rs.getObject("id", UUID::class.java),
            providerId = rs.getObject("provider_id", UUID::class.java),
            externalSubject = rs.getString("external_subject"),
            userId = rs.getObject("user_id", UUID::class.java),
            groups = groups,
            failedAttempts = rs.getInt("failed_attempts"),
            lockedUntil = rs.getTimestamp("locked_until")?.toInstant(),
            lastLoginAt = rs.getTimestamp("last_login_at")?.toInstant(),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant(),
        )
    }
}
