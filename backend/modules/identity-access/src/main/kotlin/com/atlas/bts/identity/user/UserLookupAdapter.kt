// UserLookupPort shared-kernel 구현체 — users 테이블 행 존재 여부를 JDBC 로 확인 (FR-IS-03 Task 3)

package com.atlas.bts.identity.user

import com.bts.shared.user.UserLookupPort
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * [UserLookupPort] JDBC 구현체 (FR-IS-03 Task 3 / ADR 2026-06-01-issue-assignee-user-lookup-port).
 *
 * users 테이블에서 `SELECT EXISTS` 를 실행해 사용자 UUID 가 실재하는지 확인한다.
 * jOOQ 를 사용하지 않는다 — identity-access 는 [NamedParameterJdbcTemplate] + SQL 상수 패턴을
 * [JdbcUserRepository] 선례와 동일하게 따른다.
 *
 * ### SQL 인젝션 방어
 * 모든 파라미터를 named parameter `:id` 로 바인딩한다.
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
    }
}
