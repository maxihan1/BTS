// user_profiles 테이블 접근 인터페이스 + JdbcTemplate 구현체 (FR-PR-01)

package com.atlas.bts.identity.profile

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** [UserProfileRepository] JDBC 구현체 (FR-PR-01). */
@Repository
@Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED)
class JdbcUserProfileRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) : UserProfileRepository {
    @Transactional(readOnly = true)
    override fun findByUserId(userId: UUID): UserProfile? =
        jdbc
            .query(
                """
                SELECT user_id, avatar_object_key, timezone, department
                FROM user_profiles
                WHERE user_id = :userId
                """,
                mapOf("userId" to userId),
            ) { rs, _ ->
                UserProfile(
                    userId = rs.getObject("user_id", UUID::class.java),
                    avatarObjectKey = rs.getString("avatar_object_key"),
                    timezone = rs.getString("timezone"),
                    department = rs.getString("department"),
                )
            }.firstOrNull()

    override fun upsertProfile(
        userId: UUID,
        timezone: String,
        department: String?,
    ) {
        jdbc.update(
            """
            INSERT INTO user_profiles (user_id, timezone, department)
            VALUES (:userId, :timezone, :department)
            ON CONFLICT (user_id) DO UPDATE
                SET timezone   = EXCLUDED.timezone,
                    department = EXCLUDED.department,
                    updated_at = NOW()
            """,
            mapOf("userId" to userId, "timezone" to timezone, "department" to department),
        )
    }

    override fun setAvatarObjectKey(
        userId: UUID,
        objectKey: String,
    ) {
        jdbc.update(
            """
            INSERT INTO user_profiles (user_id, avatar_object_key)
            VALUES (:userId, :objectKey)
            ON CONFLICT (user_id) DO UPDATE
                SET avatar_object_key = EXCLUDED.avatar_object_key,
                    updated_at        = NOW()
            """,
            mapOf("userId" to userId, "objectKey" to objectKey),
        )
    }

    override fun clearAvatar(userId: UUID) {
        jdbc.update(
            """
            UPDATE user_profiles
            SET avatar_object_key = NULL,
                updated_at        = NOW()
            WHERE user_id = :userId
            """,
            mapOf("userId" to userId),
        )
    }
}
