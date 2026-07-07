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
// 단일 테이블(users) 접근층이라 조회/UPSERT/갱신 메서드가 응집해 자연히 11개를 넘는다.
// FR-PR-04 에서 source 조회/재동기화 2개가 추가돼 임계 초과 — baseline 동결 대신 클래스 단위 명시 억제(ExternalAccountRepository 선례).
@Suppress("TooManyFunctions")
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
     * 신규 로컬 계정 전용 INSERT (FR-AU-05).
     *
     * [save] / [provisionFromExternal] 의 UPSERT 와 달리 **ON CONFLICT 없는 순수 INSERT** 다.
     * 관리자 회원가입은 기존 사용자 덮어쓰기가 절대 일어나면 안 되므로, username 이 이미 존재하면
     * unique 제약 위반을 [org.springframework.dao.DuplicateKeyException] 로 전파한다.
     *
     * @param username    로그인 식별자 (UNIQUE)
     * @param email       이메일 (null 허용)
     * @param displayName 화면 표시 이름
     * @return INSERT 된 신규 [User] (DB 발급 id / 타임스탬프 포함)
     * @throws org.springframework.dao.DuplicateKeyException username 이 이미 존재할 때
     */
    fun create(
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
     * **신규 판정 (FR-AU-10).**
     * 반환 [ProvisionResult.isNew] 는 이번 호출이 신규 INSERT 였는지(true) ON CONFLICT UPDATE 였는지(false)를
     * PostgreSQL 시스템 컬럼 `xmax = 0` 으로 구분한다. 호출자(AutoProvisionService)는 신규일 때만
     * `USER_PROVISIONED` 감사 이벤트를 emit 한다 (기존 사용자 재로그인은 emit 안 함, EC-3).
     *
     * @return DB 에 반영된 최신 상태의 [User] + 신규 INSERT 여부 [ProvisionResult]
     */
    fun provisionFromExternal(
        username: String,
        email: String?,
        displayName: String,
    ): ProvisionResult

    /**
     * 마지막 로그인 시각 갱신 — users.updated_at 을 NOW() 로 갱신한다.
     *
     * 존재하지 않는 id 는 조용히 무시한다 (0 행 영향).
     */
    fun updateLastLogin(id: UUID)

    /**
     * display_name 만 갱신한다 (FR-PR-01 프로필 편집 — Task 4).
     *
     * users.updated_at 도 함께 NOW() 로 갱신한다. 존재하지 않는 id 는 조용히 무시한다 (0 행 영향).
     * 값 검증(공백/길이)은 호출 측 [com.atlas.bts.identity.profile.UserProfileService] 책임이다.
     *
     * @param userId 갱신 대상 사용자 id
     * @param displayName 새 표시 이름
     */
    fun updateDisplayName(
        userId: UUID,
        displayName: String,
    )

    /**
     * display_name 값 출처를 LDAP 로 되돌린다 (FR-PR-04 관리자 재동기화).
     *
     * source=USER(사용자 편집)로 잠긴 행을 LDAP 동기화 대상으로 되돌려, 다음 [provisionFromExternal]
     * 재로그인 시 디렉터리 값으로 다시 갱신되게 한다. users.updated_at 도 함께 NOW() 로 갱신한다.
     * 존재하지 않는 id 는 조용히 무시한다 (0 행 영향).
     *
     * @param userId 대상 사용자 id
     */
    fun resyncDisplayNameSource(userId: UUID)

    /**
     * display_name 값 출처를 조회한다 (FR-PR-04).
     *
     * 공유 [User] 도메인 / [UserRowMapper] 에 컬럼을 추가하지 않고 이 targeted 쿼리로만 읽는다
     * (다수 SELECT fanout 회피 — plan 제약).
     *
     * @param userId 대상 사용자 id
     * @return 'LDAP' 또는 'USER', 존재하지 않으면 null
     */
    fun findDisplayNameSource(userId: UUID): String?

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
 * 외부 IdP Auto-provisioning UPSERT 결과 (FR-AU-10).
 *
 * @property user DB 에 반영된 최신 상태의 사용자.
 * @property isNew 이번 호출이 신규 INSERT 였으면 true, ON CONFLICT UPDATE(기존 사용자)였으면 false.
 *   PostgreSQL `xmax = 0` 으로 판정한다 — `USER_PROVISIONED` 감사 emit 조건(신규 only).
 */
data class ProvisionResult(
    val user: User,
    val isNew: Boolean,
)

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
// 단일 테이블(users) 접근 구현층 — 인터페이스와 동일 사유로 메서드가 응집한다(TooManyFunctions 명시 억제).
@Suppress("TooManyFunctions")
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

    /**
     * 신규 로컬 계정 INSERT (FR-AU-05) — ON CONFLICT 없는 순수 INSERT.
     *
     * username 중복 시 Postgres unique 위반을 Spring 이 [org.springframework.dao.DuplicateKeyException]
     * 으로 변환해 던진다. 호출 측(서비스)이 이를 도메인 예외로 변환한다.
     */
    override fun create(
        username: String,
        email: String?,
        displayName: String,
    ): User =
        jdbc.queryForObject(
            SQL_INSERT,
            mapOf(
                "id" to UUID.randomUUID(),
                "username" to username,
                "email" to email,
                "displayName" to displayName,
            ),
            UserRowMapper,
        ) ?: error("INSERT RETURNING 결과 없음 — username=$username")

    /**
     * 외부 IdP Auto-provisioning UPSERT (FR-AU-09 §29 / FR-AU-10).
     *
     * [save] 와 동일한 ON CONFLICT UPSERT 이지만, RETURNING 절에 `(xmax = 0) AS is_new` 를 추가해
     * 이번 호출이 신규 INSERT 였는지(true) 충돌 UPDATE 였는지(false)를 [ProvisionResult.isNew] 로 노출한다.
     * 순수 INSERT 면 행의 시스템 컬럼 xmax 가 0, ON CONFLICT UPDATE 면 0 이 아니다 — 추가 SELECT 없이 1 왕복으로 판정.
     */
    override fun provisionFromExternal(
        username: String,
        email: String?,
        displayName: String,
    ): ProvisionResult {
        val id = UUID.randomUUID()
        return jdbc.queryForObject(
            SQL_PROVISION_UPSERT,
            mapOf(
                "id" to id,
                "username" to username,
                "email" to email,
                "displayName" to displayName,
            ),
            ProvisionResultRowMapper,
        ) ?: error("provisionFromExternal UPSERT RETURNING 결과 없음 — username=$username")
    }

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
     * display_name 만 갱신한다 (FR-PR-01 Task 4).
     *
     * 존재하지 않는 id 는 조용히 무시한다 (0 행 영향 — 예외 없음).
     */
    override fun updateDisplayName(
        userId: UUID,
        displayName: String,
    ) {
        jdbc.update(
            SQL_UPDATE_DISPLAY_NAME,
            mapOf("userId" to userId, "displayName" to displayName),
        )
    }

    /**
     * display_name 출처를 LDAP 로 되돌린다 (FR-PR-04 재동기화).
     *
     * 존재하지 않는 id 는 조용히 무시한다 (0 행 영향 — 예외 없음).
     */
    override fun resyncDisplayNameSource(userId: UUID) {
        jdbc.update(
            SQL_RESYNC_DISPLAY_NAME_SOURCE,
            mapOf("userId" to userId),
        )
    }

    /**
     * display_name 출처를 조회한다 (FR-PR-04).
     *
     * 없는 id 는 null 을 반환한다 ([queryForObject] 의 EmptyResultDataAccessException 회피 위해 query().firstOrNull()).
     */
    @Transactional(readOnly = true)
    override fun findDisplayNameSource(userId: UUID): String? =
        jdbc.query(SQL_FIND_DISPLAY_NAME_SOURCE, mapOf("userId" to userId)) { rs, _ ->
            rs.getString("display_name_source")
        }.firstOrNull()

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
         * 신규 로컬 계정 INSERT (FR-AU-05) — ON CONFLICT 없음.
         * username 중복 시 unique 제약 위반으로 실패한다 (덮어쓰기 금지 — [SQL_UPSERT] 와 구분).
         * RETURNING 으로 DB 발급 id / now() 타임스탬프를 그대로 반환한다.
         */
        const val SQL_INSERT = """
            INSERT INTO users (id, username, email, display_name)
            VALUES (:id, :username, :email, :displayName)
            RETURNING id, username, email, display_name, created_at, updated_at
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
         * 외부 IdP Auto-provisioning UPSERT — [SQL_UPSERT] 와 동일하나 두 가지가 다르다 (FR-PR-04 / FR-AU-10).
         *
         * 1. display_name 은 CASE 게이트로 보호한다 — source=USER(사용자 편집)면 기존 값을 보존하고,
         *    아니면(source=LDAP) EXCLUDED 로 동기화한다. LDAP 재로그인이 사용자가 편집한 이름을 덮어쓰지
         *    않게 한다 (ADR 2026-07-07 D2, S2 보존 / S3 동기화). display_name_source 컬럼 자체는 갱신하지 않는다.
         * 2. RETURNING 에 `(xmax = 0) AS is_new` 추가 — xmax = 0 이면 신규 INSERT(true), 아니면 ON CONFLICT
         *    UPDATE(기존 사용자, false). USER_PROVISIONED 감사 emit(신규 only, EC-3) 판정에 쓴다 (FR-AU-10).
         *
         * email·updated_at 은 계속 동기화한다.
         */
        const val SQL_PROVISION_UPSERT = """
            INSERT INTO users (id, username, email, display_name)
            VALUES (:id, :username, :email, :displayName)
            ON CONFLICT (username) DO UPDATE
                SET display_name = CASE
                        WHEN users.display_name_source = 'USER' THEN users.display_name
                        ELSE EXCLUDED.display_name
                    END,
                    email        = EXCLUDED.email,
                    updated_at   = NOW()
            RETURNING id, username, email, display_name, created_at, updated_at, (xmax = 0) AS is_new
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
         * display_name 갱신 (FR-PR-01 Task 4) — updated_at 도 함께 NOW() 로 갱신한다.
         *
         * display_name_source 를 'USER' 로 전환한다 (FR-PR-04) — 편집은 override 의도이므로, 같은 값이어도
         * USER 로 잠가 이후 LDAP 재로그인([SQL_PROVISION_UPSERT] CASE 게이트)이 덮어쓰지 못하게 한다.
         */
        const val SQL_UPDATE_DISPLAY_NAME = """
            UPDATE users
            SET display_name        = :displayName,
                display_name_source = 'USER',
                updated_at          = NOW()
            WHERE id = :userId
        """

        /**
         * display_name 출처 재동기화 (FR-PR-04) — source 를 'LDAP' 로 되돌려 다음 재로그인 시 동기화되게 한다.
         * updated_at 도 함께 NOW() 로 갱신한다.
         */
        const val SQL_RESYNC_DISPLAY_NAME_SOURCE = """
            UPDATE users
            SET display_name_source = 'LDAP',
                updated_at          = NOW()
            WHERE id = :userId
        """

        /**
         * display_name 출처 조회 (FR-PR-04) — 공유 [User] SELECT 목록에 컬럼을 늘리지 않는 targeted 쿼리.
         */
        const val SQL_FIND_DISPLAY_NAME_SOURCE = """
            SELECT display_name_source
            FROM users
            WHERE id = :userId
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

/**
 * provisionFromExternal RowMapper — ResultSet → [ProvisionResult] 변환 (FR-AU-10).
 *
 * users 컬럼은 [UserRowMapper] 로 매핑하고, 추가된 `is_new` boolean 컬럼(`xmax = 0`)으로 신규 INSERT 여부를 채운다.
 */
private object ProvisionResultRowMapper : RowMapper<ProvisionResult> {
    override fun mapRow(
        rs: ResultSet,
        rowNum: Int,
    ): ProvisionResult =
        ProvisionResult(
            user = UserRowMapper.mapRow(rs, rowNum),
            isNew = rs.getBoolean("is_new"),
        )
}
