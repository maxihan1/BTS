// SlackUserMappingRepository JdbcTemplate 구현체 — user_slack_mapping upsert/삭제/조회 (FR-SL-02 Task 5)

package com.bts.slack.persistence

import com.bts.slack.application.SlackUserMappingRepository
import com.bts.slack.domain.SlackUserMapping
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.util.UUID

/**
 * [SlackUserMappingRepository] JdbcTemplate 구현체 (FR-SL-02 Task 5).
 *
 * **SQL 인젝션 방어:** 모든 파라미터를 [NamedParameterJdbcTemplate] `:param` 바인딩으로 처리하며,
 * SQL 문자열 결합은 하지 않는다 (DATA.md §5).
 *
 * **멱등 업서트:** `ON CONFLICT (user_id) DO UPDATE` — 같은 사용자 재연결 시 최신 값으로 갱신하고
 * `linked_at` 을 `now()` 로 새로 찍는다(last-write-wins, V701 마이그레이션 주석 동형).
 */
@Repository
class JdbcSlackUserMappingRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) : SlackUserMappingRepository {
    @Transactional
    override fun upsert(
        userId: UUID,
        slackUserId: String,
        teamId: String,
    ) {
        jdbc.update(
            SQL_UPSERT,
            mapOf(
                "userId" to userId,
                "slackUserId" to slackUserId,
                "teamId" to teamId,
            ),
        )
    }

    @Transactional
    override fun deleteByUserId(userId: UUID) {
        jdbc.update(SQL_DELETE, mapOf("userId" to userId))
    }

    /** 조회 전용 트랜잭션 — 쓰기 잠금을 잡지 않는다 (DATA.md §6 읽기 전용 규칙). */
    @Transactional(readOnly = true)
    override fun findByUserId(userId: UUID): SlackUserMapping? =
        jdbc.query(SQL_FIND_BY_USER_ID, mapOf("userId" to userId), SlackUserMappingRowMapper)
            .firstOrNull()

    /**
     * 조회 전용 트랜잭션 — 쓰기 잠금을 잡지 않는다 (DATA.md §6 읽기 전용 규칙).
     *
     * **다중행 fail-closed:** `slackUserId`/`teamId` 매칭 행이 정확히 1개일 때만 값을 반환한다.
     * [kotlin.collections.singleOrNull] 은 0개 또는 2개 이상이면 null 을 반환하므로(임의 선택 없음),
     * V702 UNIQUE 인덱스(1차 방어)가 무력화된 상태에서도 잘못된(더 높은 권한의) viewer 를 고르지 않는다.
     */
    @Transactional(readOnly = true)
    override fun findUserIdBySlackUserId(
        slackUserId: String,
        teamId: String,
    ): UUID? =
        jdbc.query(
            "SELECT user_id FROM user_slack_mapping WHERE slack_user_id = :slackUserId AND team_id = :teamId",
            mapOf("slackUserId" to slackUserId, "teamId" to teamId),
        ) { rs, _ -> rs.getObject("user_id", UUID::class.java) }
            .singleOrNull()

    private companion object {
        /** 사용자↔Slack 매핑 멱등 업서트 — PK(user_id) 위반 시 최신 값으로 갱신(last-write-wins). */
        const val SQL_UPSERT = """
            INSERT INTO user_slack_mapping (user_id, slack_user_id, team_id)
            VALUES (:userId, :slackUserId, :teamId)
            ON CONFLICT (user_id) DO UPDATE SET
                slack_user_id = EXCLUDED.slack_user_id,
                team_id = EXCLUDED.team_id,
                linked_at = now()
        """

        /** 매핑 해제 — 하드 삭제(소프트 삭제 대상 아님, V701 주석 정합). */
        const val SQL_DELETE = "DELETE FROM user_slack_mapping WHERE user_id = :userId"

        /** userId 단건 조회. */
        const val SQL_FIND_BY_USER_ID = """
            SELECT user_id, slack_user_id, team_id, linked_at
            FROM user_slack_mapping
            WHERE user_id = :userId
        """
    }
}

/** `user_slack_mapping` 한 행 → [SlackUserMapping] VO 매핑. */
private object SlackUserMappingRowMapper : RowMapper<SlackUserMapping> {
    override fun mapRow(
        rs: ResultSet,
        rowNum: Int,
    ): SlackUserMapping =
        SlackUserMapping(
            userId = rs.getObject("user_id", UUID::class.java),
            slackUserId = rs.getString("slack_user_id"),
            teamId = rs.getString("team_id"),
            linkedAt = rs.getTimestamp("linked_at").toInstant(),
        )
}
