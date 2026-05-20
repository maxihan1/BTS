// refresh_tokens 테이블 접근 인터페이스 + JdbcTemplate 구현체 — rotation chain + EC-23 race-safe markUsedAndChain

package com.atlas.bts.identity.session

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

interface RefreshTokenRepository {

    fun findByTokenHash(tokenHash: String): RefreshToken?

    fun save(token: RefreshToken)

    fun markUsedAndChain(oldId: UUID, newId: UUID): UUID?

    fun revokeChainFromSession(sessionId: UUID): Int
}

@Repository
@Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED)
class JdbcRefreshTokenRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) : RefreshTokenRepository {

    @Transactional(readOnly = true)
    override fun findByTokenHash(tokenHash: String): RefreshToken? =
        jdbc.query(SQL_FIND_BY_TOKEN_HASH, mapOf("tokenHash" to tokenHash), RefreshTokenRowMapper)
            .firstOrNull()

    override fun save(token: RefreshToken) {
        jdbc.update(
            SQL_INSERT,
            mapOf(
                "id" to token.id,
                "sessionId" to token.sessionId,
                "tokenHash" to token.tokenHash,
                "issuedAt" to Timestamp.from(token.issuedAt),
                "expiresAt" to Timestamp.from(token.expiresAt),
                "usedAt" to token.usedAt?.let { Timestamp.from(it) },
                "replacedBy" to token.replacedBy,
            ),
        )
    }

    override fun markUsedAndChain(oldId: UUID, newId: UUID): UUID? {
        val result = jdbc.queryForList(
            SQL_MARK_USED_AND_CHAIN,
            mapOf(
                "oldId" to oldId,
                "newId" to newId,
                "now" to Timestamp.from(Instant.now()),
            ),
        )
        return if (result.isEmpty()) null else result[0]["id"] as UUID
    }

    override fun revokeChainFromSession(sessionId: UUID): Int =
        jdbc.update(
            SQL_REVOKE_CHAIN_FROM_SESSION,
            mapOf(
                "sessionId" to sessionId,
                "now" to Timestamp.from(Instant.now()),
            ),
        )

    private companion object {

        const val SQL_FIND_BY_TOKEN_HASH = """
            SELECT id, session_id, token_hash, issued_at, expires_at, used_at, replaced_by
            FROM refresh_tokens
            WHERE token_hash = :tokenHash
        """

        const val SQL_INSERT = """
            INSERT INTO refresh_tokens (
                id, session_id, token_hash, issued_at, expires_at, used_at, replaced_by
            ) VALUES (
                :id, :sessionId, :tokenHash, :issuedAt, :expiresAt, :usedAt, :replacedBy
            )
        """

        const val SQL_MARK_USED_AND_CHAIN = """
            UPDATE refresh_tokens
            SET used_at     = :now,
                replaced_by = :newId
            WHERE id = :oldId
              AND used_at IS NULL
            RETURNING id
        """

        const val SQL_REVOKE_CHAIN_FROM_SESSION = """
            UPDATE refresh_tokens
            SET used_at = :now
            WHERE session_id = :sessionId
              AND used_at IS NULL
        """
    }
}

private object RefreshTokenRowMapper : RowMapper<RefreshToken> {
    override fun mapRow(
        rs: ResultSet,
        rowNum: Int,
    ): RefreshToken =
        RefreshToken(
            id = rs.getObject("id", UUID::class.java),
            sessionId = rs.getObject("session_id", UUID::class.java),
            tokenHash = rs.getString("token_hash"),
            issuedAt = rs.getTimestamp("issued_at").toInstant(),
            expiresAt = rs.getTimestamp("expires_at").toInstant(),
            usedAt = rs.getTimestamp("used_at")?.toInstant(),
            replacedBy = rs.getObject("replaced_by", UUID::class.java),
        )
}
