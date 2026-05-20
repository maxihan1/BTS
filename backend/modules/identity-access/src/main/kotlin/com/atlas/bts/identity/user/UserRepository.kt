// users 테이블 접근 인터페이스 + JdbcTemplate 구현체 — SDD 19.2 User 핵심 엔티티

package com.atlas.bts.identity.user

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
 * users 테이블 접근 인터페이스 (FR-AU-09 Task 32 / SDD §19.2).
 *
 * Task 13 LocalProvider / Task 15 LdapProvider Auto-provisioning 에서 사용한다.
 * 구현체: [JdbcUserRepository].
 */
interface UserRepository {

    /**
     * 내부 id 로 사용자 조회.
     *
     * @return 존재하면 [User], 없으면 null
     */
    fun findById(id: UUID): User?

    /**
     * username 으로 사용자 조회.
     *
     * @return 존재하면 [User], 없으면 null
     */
    fun findByUsername(username: String): User?

    /**
     * 신규 사용자 INSERT 또는 기존 사용자 UPSERT.
     *
     * username 충돌 시 email / displayName 을 최신값으로 갱신한다 (id 보존).
     *
     * @return DB 에 반영된 최신 상태의 [User]
     */
    fun save(
        username: String,
        email: String?,
        displayName: String,
    ): User

    /**
     * 외부 IdP Auto-provisioning 전용 UPSERT (FR-AU-09 §29).
     *
     * [save] 와 동일한 UPSERT 동작이지만 호출 의도를 명확히 하기 위해 별도로 선언한다.
     * Race condition(EC-11) 은 ON CONFLICT 로 방어한다.
     *
     * @return DB 에 반영된 최신 상태의 [User]
     */
    fun provisionFromExternal(
        username: String,
        email: String?,
        displayName: String,
    ): User

    /**
     * 마지막 로그인 시각 갱신 — users.updated_at 을 NOW() 로 갱신한다.
     *
     * 존재하지 않는 id 는 조용히 무시한다 (0 행 영향).
     */
    fun updateLastLogin(id: UUID)
}

/**
 * [UserRepository] JDBC 구현체 (FR-AU-09 Task 32).
 *
 * **트랜잭션 경계 (DATA.md §6)**:
 * 클래스 레벨 @Transactional(REQUIRED) — 호출 측 트랜잭션에 참여하거나 새로 시작.
 * 읽기 전용 메서드는 @Transactional(readOnly = true) 로 오버라이드.
 *
 * **UPSERT 설계 (DATA.md §5)**:
 * INSERT ... ON CONFLICT (username) DO UPDATE 로 멱등성 보장.
 * RETURNING 절로 추가 SELECT 없이 최신 행 반환.
 *
 * **UUID RowMapper**:
 * ResultSet.getObject + UUID::class.java — Postgres JDBC 권장 방식.
 *
 * SQL 인젝션 방어: 모든 파라미터를 NamedParameterJdbcTemplate 에 바인딩 (DEVELOPMENT.md §1.3).
 */
@Repository
@Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED)
class JdbcUserRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) : UserRepository {

    @Transactional(readOnly = true)
    override fun findById(id: UUID): User? =
        jdbc.query(SQL_FIND_BY_ID, mapOf("id" to id), UserRowMapper).firstOrNull()

    @Transactional(readOnly = true)
    override fun findByUsername(username: String): User? =
        jdbc.query(SQL_FIND_BY_USERNAME, mapOf("username" to username), UserRowMapper).firstOrNull()

    override fun save(
        username: String,
        email: String?,
        displayName: String,
    ): User = upsert(username, email, displayName)

    override fun provisionFromExternal(
        username: String,
        email: String?,
        displayName: String,
    ): User = upsert(username, email, displayName)

    override fun updateLastLogin(id: UUID) {
        jdbc.update(
            SQL_UPDATE_LAST_LOGIN,
            mapOf("id" to id, "now" to Timestamp.from(Instant.now())),
        )
    }

    private fun upsert(
        username: String,
        email: String?,
        displayName: String,
    ): User {
        val id = UUID.randomUUID()
        return jdbc.queryForObject(
            SQL_UPSERT,
            mapOf(
                "id" to id,
                "username" to username,
                "email" to email,
                "displayName" to displayName,
            ),
            UserRowMapper,
        ) ?: error("UPSERT RETURNING 결과 없음 — username=$username")
    }

    // ── SQL 상수 ─────────────────────────────────────────────────────────────

    private companion object {
        const val SQL_FIND_BY_ID = """
            SELECT id, username, email, display_name, created_at, updated_at
            FROM users
            WHERE id = :id
        """

        const val SQL_FIND_BY_USERNAME = """
            SELECT id, username, email, display_name, created_at, updated_at
            FROM users
            WHERE username = :username
        """

        /**
         * users UPSERT — 신규 INSERT, username 충돌 시 email / displayName 갱신 (id 보존).
         * EC-11 race condition 은 ON CONFLICT 로 방어.
         * RETURNING 으로 DB now() 기준 타임스탬프 반환 — 클라이언트 측 now() 의존 없음.
         */
        const val SQL_UPSERT = """
            INSERT INTO users (id, username, email, display_name)
            VALUES (:id, :username, :email, :displayName)
            ON CONFLICT (username) DO UPDATE
                SET email        = EXCLUDED.email,
                    display_name = EXCLUDED.display_name,
                    updated_at   = NOW()
            RETURNING id, username, email, display_name, created_at, updated_at
        """

        const val SQL_UPDATE_LAST_LOGIN = """
            UPDATE users
            SET updated_at = :now
            WHERE id = :id
        """
    }
}

/** users RowMapper — ResultSet → User 변환 */
private object UserRowMapper : RowMapper<User> {
    override fun mapRow(
        rs: ResultSet,
        rowNum: Int,
    ): User =
        User(
            // getObject + UUID::class.java — Postgres JDBC 권장 방식 (UUID.fromString 캐스팅 회피)
            id = rs.getObject("id", UUID::class.java),
            username = rs.getString("username"),
            email = rs.getString("email"),
            displayName = rs.getString("display_name"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant(),
        )
}
