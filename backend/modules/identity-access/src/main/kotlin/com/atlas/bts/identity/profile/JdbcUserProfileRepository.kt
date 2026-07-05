// user_profiles 테이블 접근 인터페이스 + JdbcTemplate 구현체 (FR-PR-01)

package com.atlas.bts.identity.profile

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.util.UUID

/**
 * [UserProfileRepository] JDBC 구현체 (FR-PR-01).
 *
 * **트랜잭션 경계 (DATA.md §6)**:
 * 클래스 레벨 @Transactional(REQUIRED) — 호출 측 트랜잭션에 참여하거나 새로 시작.
 * 읽기 전용 메서드는 @Transactional(readOnly = true) 로 오버라이드.
 *
 * **UPSERT 설계 (DATA.md §5)**:
 * INSERT ... ON CONFLICT (user_id) DO UPDATE 로 멱등성 + lazy 생성을 함께 보장한다.
 * [upsertProfile] 은 SET 절에서 avatar_object_key 를 제외해 보존하고,
 * [setAvatarObjectKey] 는 반대로 SET 절에서 timezone/department 를 제외해 보존한다.
 *
 * **UUID RowMapper**:
 * ResultSet.getObject + UUID::class.java — Postgres JDBC 권장 방식.
 *
 * SQL 인젝션 방어: 모든 파라미터를 NamedParameterJdbcTemplate 에 바인딩 (DEVELOPMENT.md §1.3).
 */
@Repository
@Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED)
class JdbcUserProfileRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) : UserProfileRepository {
    @Transactional(readOnly = true)
    override fun findByUserId(userId: UUID): UserProfile? =
        jdbc.query(SQL_FIND_BY_USER_ID, mapOf("userId" to userId), UserProfileRowMapper).firstOrNull()

    /**
     * timezone / department UPSERT — avatar_object_key 는 SET 절에서 제외해 보존한다.
     * 신규 INSERT 시 avatar_object_key 는 컬럼 기본값(NULL) 이 적용된다.
     */
    override fun upsertProfile(
        userId: UUID,
        timezone: String,
        department: String?,
    ) {
        jdbc.update(
            SQL_UPSERT_PROFILE,
            mapOf("userId" to userId, "timezone" to timezone, "department" to department),
        )
    }

    /**
     * avatar_object_key UPSERT — timezone / department 는 SET 절에서 제외해 보존한다.
     * 신규 INSERT 시 timezone 컬럼 기본값('UTC') 이 그대로 적용된다.
     */
    override fun setAvatarObjectKey(
        userId: UUID,
        objectKey: String,
    ) {
        jdbc.update(
            SQL_UPSERT_AVATAR,
            mapOf("userId" to userId, "objectKey" to objectKey),
        )
    }

    /** avatar_object_key 를 NULL 로 초기화한다. 프로필 행이 없으면 0 행 영향으로 조용히 무시된다. */
    override fun clearAvatar(userId: UUID) {
        jdbc.update(SQL_CLEAR_AVATAR, mapOf("userId" to userId))
    }

    // ── SQL 상수 ─────────────────────────────────────────────────────────────

    private companion object {
        const val SQL_FIND_BY_USER_ID = """
            SELECT user_id, avatar_object_key, timezone, department
            FROM user_profiles
            WHERE user_id = :userId
        """

        /**
         * timezone/department UPSERT — avatar_object_key 는 SET 절에서 제외해 보존한다.
         * user_id 가 처음 등장하면 INSERT(lazy 생성), 이미 있으면 ON CONFLICT UPDATE.
         */
        const val SQL_UPSERT_PROFILE = """
            INSERT INTO user_profiles (user_id, timezone, department)
            VALUES (:userId, :timezone, :department)
            ON CONFLICT (user_id) DO UPDATE
                SET timezone   = EXCLUDED.timezone,
                    department = EXCLUDED.department,
                    updated_at = NOW()
        """

        /**
         * avatar_object_key UPSERT — timezone/department 는 SET 절에서 제외해 보존한다.
         * 신규 INSERT 시 timezone 컬럼 기본값('UTC') 이 그대로 적용된다.
         */
        const val SQL_UPSERT_AVATAR = """
            INSERT INTO user_profiles (user_id, avatar_object_key)
            VALUES (:userId, :objectKey)
            ON CONFLICT (user_id) DO UPDATE
                SET avatar_object_key = EXCLUDED.avatar_object_key,
                    updated_at        = NOW()
        """

        /** avatar_object_key 만 NULL 로 초기화. 프로필 행이 없으면 0 행 영향. */
        const val SQL_CLEAR_AVATAR = """
            UPDATE user_profiles
            SET avatar_object_key = NULL,
                updated_at        = NOW()
            WHERE user_id = :userId
        """
    }
}

/** user_profiles RowMapper — ResultSet → UserProfile 변환 */
private object UserProfileRowMapper : RowMapper<UserProfile> {
    override fun mapRow(
        rs: ResultSet,
        rowNum: Int,
    ): UserProfile =
        UserProfile(
            // getObject + UUID::class.java — Postgres JDBC 권장 방식 (UUID.fromString 캐스팅 회피)
            userId = rs.getObject("user_id", UUID::class.java),
            avatarObjectKey = rs.getString("avatar_object_key"),
            timezone = rs.getString("timezone"),
            department = rs.getString("department"),
        )
}
