// user_preferences 테이블 접근 인터페이스 구현체 (FR-PF-01)

package com.atlas.bts.identity.preferences

import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.util.UUID

/**
 * [UserPreferencesRepository] JDBC 구현체 (FR-PF-01).
 *
 * **트랜잭션 경계 (DATA.md §6)**:
 * 클래스 레벨 @Transactional(REQUIRED) — 호출 측 트랜잭션에 참여하거나 새로 시작.
 * 읽기 전용 메서드는 @Transactional(readOnly = true) 로 오버라이드.
 *
 * **UPSERT 설계 (DATA.md §5)**:
 * INSERT ... ON CONFLICT (user_id) DO UPDATE 로 멱등성 + lazy 생성을 함께 보장한다.
 * theme/locale/date_format/start_page 네 컬럼을 항상 함께 덮어쓴다 — 부분 SET(profile 의 avatar 분리 SET)
 * 은 필요 없다. 3-state 가 아니라 [UserPreferencesService] 가 이미 계산한 effective 값을 그대로 받기 때문.
 *
 * **UUID RowMapper**:
 * ResultSet.getObject + UUID::class.java — Postgres JDBC 권장 방식.
 *
 * SQL 인젝션 방어: 모든 파라미터를 NamedParameterJdbcTemplate 에 바인딩 (DEVELOPMENT.md §1.3).
 */
@Repository
@Transactional(propagation = Propagation.REQUIRED, isolation = Isolation.READ_COMMITTED)
class JdbcUserPreferencesRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) : UserPreferencesRepository {
    @Transactional(readOnly = true)
    override fun findByUserId(userId: UUID): UserPreferences? =
        jdbc.query(SQL_FIND_BY_USER_ID, mapOf("userId" to userId), UserPreferencesRowMapper).firstOrNull()

    override fun upsert(preferences: UserPreferences) {
        jdbc.update(
            SQL_UPSERT,
            mapOf(
                "userId" to preferences.userId,
                "theme" to preferences.theme,
                "locale" to preferences.locale,
                "dateFormat" to preferences.dateFormat,
                "startPage" to preferences.startPage,
            ),
        )
    }

    // ── SQL 상수 ─────────────────────────────────────────────────────────────

    private companion object {
        const val SQL_FIND_BY_USER_ID = """
            SELECT user_id, theme, locale, date_format, start_page
            FROM user_preferences
            WHERE user_id = :userId
        """

        /**
         * theme/locale/date_format/start_page UPSERT — user_id 가 처음 등장하면 INSERT(lazy 생성),
         * 이미 있으면 ON CONFLICT UPDATE.
         */
        const val SQL_UPSERT = """
            INSERT INTO user_preferences (user_id, theme, locale, date_format, start_page)
            VALUES (:userId, :theme, :locale, :dateFormat, :startPage)
            ON CONFLICT (user_id) DO UPDATE
                SET theme       = EXCLUDED.theme,
                    locale      = EXCLUDED.locale,
                    date_format = EXCLUDED.date_format,
                    start_page  = EXCLUDED.start_page,
                    updated_at  = NOW()
        """
    }
}

/** user_preferences RowMapper — ResultSet → UserPreferences 변환 */
private object UserPreferencesRowMapper : RowMapper<UserPreferences> {
    override fun mapRow(
        rs: ResultSet,
        rowNum: Int,
    ): UserPreferences =
        UserPreferences(
            // getObject + UUID::class.java — Postgres JDBC 권장 방식 (UUID.fromString 캐스팅 회피)
            userId = rs.getObject("user_id", UUID::class.java),
            theme = rs.getString("theme"),
            locale = rs.getString("locale"),
            dateFormat = rs.getString("date_format"),
            startPage = rs.getString("start_page"),
        )
}
