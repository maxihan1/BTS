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
 * **트랜잭션 경계 (DATA.md §6)**:
 * [provisionUser] 는 users INSERT + user_external_accounts INSERT 를 단일 트랜잭션으로 처리한다.
 * 어느 한 쪽이 실패하면 양쪽 모두 rollback 된다 (부분 성공 금지).
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
    fun findByProviderIdAndExternalSubject(providerId: UUID, externalSubject: String): ExternalAccount? {
        val results = jdbc.query(
            SQL_FIND_BY_PROVIDER_AND_SUBJECT,
            mapOf("providerId" to providerId, "externalSubject" to externalSubject),
            rowMapper,
        )
        return results.firstOrNull()
    }

    /**
     * 사용자 자동 프로비저닝 — users INSERT + user_external_accounts INSERT 단일 트랜잭션.
     *
     * **DATA.md §6**: 두 INSERT 는 단일 @Transactional 경계 안에서 처리된다.
     * username 중복 등 제약 위반 시 양쪽 모두 rollback.
     */
    fun provisionUser(
        providerId: UUID,
        externalSubject: String,
        username: String,
        displayName: String,
        email: String?,
        groups: List<String>,
    ): ExternalAccount {
        val userId = UUID.randomUUID()

        // Step 1: users 테이블 INSERT
        jdbc.update(
            SQL_INSERT_USER,
            mapOf(
                "id" to userId,
                "username" to username,
                "email" to email,
                "displayName" to displayName,
            ),
        )

        // Step 2: user_external_accounts INSERT
        val accountId = UUID.randomUUID()
        val groupsJson = objectMapper.writeValueAsString(groups)
        jdbc.update(
            SQL_INSERT_EXTERNAL_ACCOUNT,
            mapOf(
                "id" to accountId,
                "providerId" to providerId,
                "externalSubject" to externalSubject,
                "userId" to userId,
                "groups" to groupsJson,
            ),
        )

        return findByProviderIdAndExternalSubject(providerId, externalSubject)
            ?: error("provisionUser 직후 조회 실패 — subject=$externalSubject")
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
    fun markLockedUntil(id: UUID, until: Instant) {
        jdbc.update(SQL_MARK_LOCKED_UNTIL, mapOf("id" to id, "lockedUntil" to Timestamp.from(until)))
    }

    /**
     * 마지막 로그인 시각 갱신 + failed_attempts 0 reset.
     * 로그인 성공 시 잠금 카운터도 함께 초기화한다.
     */
    fun updateLastLoginAt(id: UUID, now: Instant) {
        jdbc.update(SQL_UPDATE_LAST_LOGIN, mapOf("id" to id, "lastLoginAt" to Timestamp.from(now)))
    }

    // ── SQL 상수 ─────────────────────────────────────────────────────────────

    private companion object {
        const val SQL_FIND_BY_PROVIDER_AND_SUBJECT = """
            SELECT id, provider_id, external_subject, user_id, groups,
                   failed_attempts, locked_until, last_login_at, created_at, updated_at
            FROM user_external_accounts
            WHERE provider_id = :providerId AND external_subject = :externalSubject
        """

        const val SQL_INSERT_USER = """
            INSERT INTO users (id, username, email, display_name)
            VALUES (:id, :username, :email, :displayName)
        """

        const val SQL_INSERT_EXTERNAL_ACCOUNT = """
            INSERT INTO user_external_accounts (id, provider_id, external_subject, user_id, groups)
            VALUES (:id, :providerId, :externalSubject, :userId, :groups::jsonb)
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
    }
}

/** ExternalAccount RowMapper — ResultSet → ExternalAccount 변환 */
private class ExternalAccountRowMapper(
    private val objectMapper: ObjectMapper,
) : RowMapper<ExternalAccount> {
    @Suppress("UNCHECKED_CAST")
    override fun mapRow(rs: ResultSet, rowNum: Int): ExternalAccount {
        val groupsJson = rs.getString("groups") ?: "[]"
        val groups = objectMapper.readValue(groupsJson, List::class.java) as List<String>

        return ExternalAccount(
            id = UUID.fromString(rs.getString("id")),
            providerId = UUID.fromString(rs.getString("provider_id")),
            externalSubject = rs.getString("external_subject"),
            userId = UUID.fromString(rs.getString("user_id")),
            groups = groups,
            failedAttempts = rs.getInt("failed_attempts"),
            lockedUntil = rs.getTimestamp("locked_until")?.toInstant(),
            lastLoginAt = rs.getTimestamp("last_login_at")?.toInstant(),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant(),
        )
    }
}
