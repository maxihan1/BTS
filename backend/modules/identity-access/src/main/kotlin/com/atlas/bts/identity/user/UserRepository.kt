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

    /**
     * id 목록으로 사용자 다건 조회 (FR-IS-03 Task 6 — 현재 담당자 이름 안정 해소).
     *
     * 존재하지 않는 id 는 결과에서 조용히 제외한다 (404 아님 — 부분 결과 허용).
     * 빈 리스트 입력 시 빈 리스트 반환 (DB 호출 없음).
     *
     * SQL 인젝션 방어: `WHERE id IN (:ids)` named parameter 바인딩 (DEVELOPMENT.md §1.3).
     *
     * @param ids 조회할 사용자 UUID 목록
     * @return 존재하는 사용자 목록 (순서 미보장)
     */
    fun findByIds(ids: List<UUID>): List<User>

    /**
     * 활성 사용자 목록 조회 (FR-IS-03 Task 4 — 담당자 셀렉터 typeahead 용).
     *
     * [query] 가 null 이면 전체 사용자를 반환한다.
     * [query] 가 있으면 username 또는 display_name 에 대해 ILIKE 부분일치 필터를 적용한다.
     * 결과는 최대 [limit] 건으로 제한한다.
     *
     * SQL 인젝션 방어: query 값은 named parameter 바인딩 + ILIKE 패턴 조합으로 처리한다.
     * 문자열 결합 없음 (DEVELOPMENT.md §1.3).
     *
     * @param query username/display_name 부분일치 검색어 (null 이면 전체)
     * @param limit 반환 상한 건수
     * @return 사용자 목록 (최대 [limit] 건)
     */
    fun findAll(
        query: String?,
        limit: Int,
    ): List<User>
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
    override fun findById(id: UUID): User? = jdbc.query(SQL_FIND_BY_ID, mapOf("id" to id), UserRowMapper).firstOrNull()

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

    /**
     * 마지막 로그인 시각으로 users.updated_at 을 갱신한다 (FR-AU-09 §29).
     *
     * 존재하지 않는 id 는 조용히 무시한다 (0 행 영향 — 예외 없음).
     * 호출 측에서 id 검증이 필요한 경우 [findById] 로 선조회한다.
     */
    override fun updateLastLogin(id: UUID) {
        jdbc.update(
            SQL_UPDATE_LAST_LOGIN,
            mapOf("id" to id, "now" to Timestamp.from(Instant.now())),
        )
    }

    /**
     * id 목록으로 사용자 다건 조회 (FR-IS-03 Task 6).
     *
     * 빈 리스트 입력 시 DB 호출 없이 빈 리스트를 즉시 반환한다.
     * 존재하지 않는 id 는 조용히 제외된다 (WHERE id IN (:ids) 결과에 포함 안 됨).
     *
     * @param ids 조회할 사용자 UUID 목록
     */
    @Transactional(readOnly = true)
    override fun findByIds(ids: List<UUID>): List<User> {
        if (ids.isEmpty()) return emptyList()
        return jdbc.query(
            SQL_FIND_BY_IDS,
            mapOf("ids" to ids),
            UserRowMapper,
        )
    }

    /**
     * 활성 사용자 목록 조회 (FR-IS-03 Task 4).
     *
     * [query] null 이면 [SQL_FIND_ALL] 전체 조회, 있으면 [SQL_FIND_ALL_FILTERED] ILIKE 필터.
     * ILIKE 패턴은 named parameter 바인딩으로 처리한다 — 문자열 결합 없음.
     *
     * @param query 부분일치 검색어 (null 이면 전체)
     * @param limit 반환 상한 건수
     */
    @Transactional(readOnly = true)
    override fun findAll(
        query: String?,
        limit: Int,
    ): List<User> =
        if (query == null) {
            jdbc.query(SQL_FIND_ALL, mapOf("limit" to limit), UserRowMapper)
        } else {
            val pattern = "%$query%"
            jdbc.query(SQL_FIND_ALL_FILTERED, mapOf("pattern" to pattern, "limit" to limit), UserRowMapper)
        }

    /**
     * users UPSERT 공통 로직.
     *
     * UUID.randomUUID() 로 신규 id 를 미리 생성하지만, ON CONFLICT 시 DB 는 기존 id 를 보존한다.
     * RETURNING 절로 DB 반영 상태 (id / 타임스탬프 포함) 를 그대로 반환한다.
     */
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

        /**
         * 마지막 로그인 시각 갱신 — updated_at 컬럼만 갱신한다.
         * 향후 last_login_at 전용 컬럼 추가 시 이 SQL 과 함께 마이그레이션 필요.
         */
        const val SQL_UPDATE_LAST_LOGIN = """
            UPDATE users
            SET updated_at = :now
            WHERE id = :id
        """

        /**
         * id 목록으로 사용자 다건 조회 — `IN (:ids)` named parameter 바인딩.
         * NamedParameterJdbcTemplate 이 List<UUID> 를 자동으로 IN 절 플레이스홀더로 확장한다.
         * SQL 문자열 결합 없음 (DEVELOPMENT.md §1.3).
         */
        const val SQL_FIND_BY_IDS = """
            SELECT id, username, email, display_name, created_at, updated_at
            FROM users
            WHERE id IN (:ids)
            ORDER BY username
        """

        /**
         * 전체 사용자 조회 — username 오름차순, 상한 :limit 건.
         * V001 스키마 기준: is_active / deleted_at 컬럼 없음 → 모든 행 조회.
         */
        const val SQL_FIND_ALL = """
            SELECT id, username, email, display_name, created_at, updated_at
            FROM users
            ORDER BY username
            LIMIT :limit
        """

        /**
         * 부분일치 필터 조회 — username 또는 display_name ILIKE :pattern, 상한 :limit 건.
         * :pattern 은 호출 측에서 '%query%' 형식으로 조합하여 named parameter 로 바인딩한다.
         * SQL 문자열 결합 없음 (DEVELOPMENT.md §1.3).
         */
        const val SQL_FIND_ALL_FILTERED = """
            SELECT id, username, email, display_name, created_at, updated_at
            FROM users
            WHERE username ILIKE :pattern
               OR display_name ILIKE :pattern
            ORDER BY username
            LIMIT :limit
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
