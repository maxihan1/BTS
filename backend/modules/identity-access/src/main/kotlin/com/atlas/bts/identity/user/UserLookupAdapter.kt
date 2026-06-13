// UserLookupPort shared-kernel 구현체 — users 테이블 행 존재 여부 확인 및 username 일괄 해석 (FR-IS-03 Task 3 / FR-MN-01 Task 3)

package com.atlas.bts.identity.user

import com.bts.shared.user.UserLookupPort
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * [UserLookupPort] JDBC 구현체 (FR-IS-03 Task 3 / FR-MN-01 Task 3 / ADR 2026-06-01-issue-assignee-user-lookup-port).
 *
 * users 테이블에서 `SELECT EXISTS` 를 실행해 사용자 UUID 가 실재하는지 확인하고,
 * username 집합을 `username -> UUID` 맵으로 일괄 해석한다.
 * jOOQ 를 사용하지 않는다 — identity-access 는 [NamedParameterJdbcTemplate] + SQL 상수 패턴을
 * [JdbcUserRepository] 선례와 동일하게 따른다.
 *
 * ### SQL 인젝션 방어
 * 모든 파라미터를 named parameter (`:id`, `:names`) 로 바인딩한다.
 * SQL 문자열 결합 금지 (DEVELOPMENT.md §1.3).
 *
 * ### "실재" 정의
 * users 테이블 행 존재 = 실재. is_active / deleted_at 같은 소프트 삭제 컬럼은
 * V001 스키마에 없으므로 행 존재 여부만 확인한다.
 *
 * ### 트랜잭션
 * 읽기 전용 — `@Transactional(readOnly = true)`. 호출자 트랜잭션에 참여하거나 새로 시작한다.
 */
@Component
class UserLookupAdapter(
    private val jdbc: NamedParameterJdbcTemplate,
) : UserLookupPort {
    /**
     * users 테이블에 주어진 UUID 행이 존재하는지 확인한다.
     *
     * `SELECT EXISTS(SELECT 1 FROM users WHERE id = :id)` — 행 스캔 없이 존재 여부만 반환한다.
     *
     * @param userId 확인할 사용자의 UUID
     * @return 행이 존재하면 true, 없으면 false
     */
    @Transactional(readOnly = true)
    override fun exists(userId: UUID): Boolean =
        jdbc.queryForObject(
            SQL_EXISTS,
            mapOf("id" to userId),
            Boolean::class.java,
        ) ?: false

    /**
     * 주어진 username 집합을 실재 사용자 id 로 일괄 해석한다 (FR-MN-01 Task 3).
     *
     * 빈 입력 시 DB 쿼리 없이 emptyMap 을 즉시 반환한다.
     * `WHERE LOWER(username) IN (:names)` 단일 쿼리로 N+1 없이 대소문자 무시 매칭한다.
     * 바인딩 직전 입력을 모두 lowercase 로 변환해 LOWER(username) 비교와 정합한다.
     * 미존재 username 은 결과에서 자동으로 제외된다 — 호출자가 명시적으로 드롭 처리한다.
     *
     * SQL 인젝션 방어: named parameter `:names` 바인딩 (문자열 결합 금지, DEVELOPMENT.md §1.3).
     *
     * @param usernames 해석할 username 집합 (대소문자 무시 매칭 — LOWER 비교)
     * @return 실재하는 username 만 포함한 `username -> UUID` 맵 (순서 미보장, 키는 DB 원문 케이스)
     */
    @Transactional(readOnly = true)
    override fun findIdsByUsernames(usernames: Set<String>): Map<String, UUID> {
        if (usernames.isEmpty()) return emptyMap()
        val lowercaseNames = usernames.map { it.lowercase() }
        return jdbc.query(
            SQL_FIND_IDS_BY_USERNAMES,
            mapOf("names" to lowercaseNames),
        ) { rs, _ ->
            val username = rs.getString("username")
            val id = rs.getObject("id", UUID::class.java)
            username to id
        }.toMap()
    }

    /**
     * 주어진 사용자 UUID 의 이메일 주소를 users 테이블에서 조회한다 (FR-NT-02 Task 2).
     *
     * `SELECT email FROM users WHERE id = :id` — 행이 없으면 null 을 반환한다.
     * [UserLookupPort.findEmailById] 계약에 따라 미존재 시 null 반환이 정상 동작이다.
     * [org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate.query] 를 사용해
     * 0행에 null 을 안전하게 반환한다 (queryForObject 는 0행에 EmptyResultDataAccessException 을 던짐).
     *
     * SQL 인젝션 방어: named parameter :id 바인딩 (문자열 결합 금지, DEVELOPMENT.md §1.3).
     *
     * @param userId 이메일을 조회할 사용자 UUID
     * @return 해당 사용자의 이메일 주소, 미존재 시 null
     */
    @Transactional(readOnly = true)
    override fun findEmailById(userId: UUID): String? =
        jdbc.query(
            SQL_FIND_EMAIL_BY_ID,
            mapOf("id" to userId),
        ) { rs, _ -> rs.getString("email") }.firstOrNull()

    /**
     * 주어진 사용자 UUID 집합을 표시명으로 역방향 일괄 해석한다 (FR-HS-01 Task 2).
     *
     * 빈 입력 시 DB 쿼리 없이 emptyMap 을 즉시 반환한다.
     * `WHERE id IN (:ids)` 단일 쿼리로 N+1 없이 조회하며, 미존재 id 는 결과에서 자동으로 제외된다.
     * 표시명은 `COALESCE(display_name, username)` 로 결정한다 — display_name 이 null 이면
     * username 으로 폴백한다 (Jira 식 표시명 규약). users.display_name 은 V001 스키마에서 nullable 이다.
     *
     * SQL 인젝션 방어: named parameter `:ids` 바인딩 (문자열 결합 금지, DEVELOPMENT.md §1.3).
     *
     * @param ids 표시명을 조회할 사용자 UUID 집합
     * @return 실재하는 id 만 포함한 `UUID -> 표시명` 맵 (순서 미보장)
     */
    @Transactional(readOnly = true)
    override fun findDisplayNamesByIds(ids: Set<UUID>): Map<UUID, String> {
        if (ids.isEmpty()) return emptyMap()
        return jdbc.query(
            SQL_FIND_DISPLAY_NAMES_BY_IDS,
            mapOf("ids" to ids),
        ) { rs, _ ->
            val id = rs.getObject("id", UUID::class.java)
            val displayName = rs.getString("dn")
            id to displayName
        }.toMap()
    }

    private companion object {
        /**
         * users 행 존재 여부 확인 — EXISTS 를 사용해 불필요한 행 스캔을 방지한다.
         * named parameter :id 로 SQL 인젝션을 방어한다.
         */
        const val SQL_EXISTS = """
            SELECT EXISTS(
                SELECT 1
                FROM users
                WHERE id = :id
            )
        """

        /**
         * username 집합을 id 로 일괄 해석 — 대소문자 무시(LOWER 비교) (FR-MN-01 Task 3).
         *
         * `WHERE LOWER(username) IN (:names)` — 호출 전 입력을 lowercase 로 변환해 바인딩하므로
         * DB 에 "Bob" 이 저장되어 있어도 "bob" 입력으로 매칭된다.
         * NamedParameterJdbcTemplate 이 Collection 을 IN 절 플레이스홀더로 자동 확장한다.
         * 미존재 username 은 결과에 포함되지 않는다.
         *
         * SQL 인젝션 방어: named parameter :names 바인딩 (문자열 결합 없음, DEVELOPMENT.md §1.3).
         *
         * 주의: `= ANY(:names)` 는 PostgreSQL 배열 바인딩이 필요해 드라이버 호환성에 의존하므로,
         * NamedParameterJdbcTemplate 의 표준 컬렉션 바인딩(`IN (:names)`)을 채택한다.
         *
         * 성능: LOWER(username) 는 username 컬럼 B-Tree 인덱스를 우회할 수 있으나,
         * 멘션은 cap 50 으로 N 이 매우 작아 함수 인덱스 추가 없이 허용한다.
         */
        const val SQL_FIND_IDS_BY_USERNAMES = """
            SELECT id, username
            FROM users
            WHERE LOWER(username) IN (:names)
        """

        /**
         * 단일 사용자 UUID 로 이메일 주소 조회 (FR-NT-02 Task 2).
         *
         * `WHERE id = :id` — 0행이면 결과 리스트가 비어 있으므로 호출자가 firstOrNull() 로 null 처리한다.
         * named parameter :id 바인딩으로 SQL 인젝션을 방어한다.
         */
        const val SQL_FIND_EMAIL_BY_ID = """
            SELECT email
            FROM users
            WHERE id = :id
        """

        /**
         * 사용자 UUID 집합을 표시명으로 일괄 해석 (FR-HS-01 Task 2).
         *
         * `COALESCE(display_name, username) AS dn` — display_name 이 null 이면 username 으로 폴백한다.
         * display_name 은 V001 스키마에서 nullable 이므로 null 폴백이 필요하다 (Jira 식 표시명).
         * NamedParameterJdbcTemplate 이 Collection 을 IN 절 플레이스홀더로 자동 확장한다.
         * 미존재 id 는 결과에 포함되지 않는다.
         *
         * SQL 인젝션 방어: named parameter :ids 바인딩 (문자열 결합 없음, DEVELOPMENT.md §1.3).
         */
        const val SQL_FIND_DISPLAY_NAMES_BY_IDS = """
            SELECT id, COALESCE(display_name, username) AS dn
            FROM users
            WHERE id IN (:ids)
        """
    }
}
