// SlackInteractionLogRepository JdbcTemplate 구현체 — slack_interaction_log append-only 기록 (FR-SL-05 Task 4)

package com.bts.slack.persistence

import com.bts.slack.application.SlackInteractionLogRepository
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.Types
import java.util.UUID

/**
 * [SlackInteractionLogRepository] JdbcTemplate 구현체 (FR-SL-05 Task 4).
 *
 * **SQL 인젝션 방어:** 모든 파라미터를 [NamedParameterJdbcTemplate] `:param` 바인딩으로 처리하며,
 * SQL 문자열 결합은 하지 않는다 (DATA.md §5).
 *
 * **append-only:** 매 호출이 새 행을 INSERT 한다(UPDATE/DELETE 없음). PK([id])는 애플리케이션이
 * [UUID.randomUUID] 로 생성한다(V703 DDL 은 `DEFAULT` 없이 명시 삽입을 요구).
 *
 * **nullable UUID 바인딩:** `bts_user_id` 는 미연결 시 null 이며, [MapSqlParameterSource] 에 명시
 * [Types.OTHER] 타입을 지정해 null 을 uuid 컬럼에 안전하게 바인딩한다(타입 미지정 null 로 인한
 * "could not determine data type" 회피).
 */
@Repository
class JdbcSlackInteractionLogRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) : SlackInteractionLogRepository {
    @Transactional
    override fun record(
        teamId: String,
        slackUserId: String,
        btsUserId: UUID?,
        actionType: String,
        outcome: String,
        issueKey: String?,
    ) {
        val params =
            MapSqlParameterSource()
                .addValue("id", UUID.randomUUID(), Types.OTHER)
                .addValue("teamId", teamId)
                .addValue("slackUserId", slackUserId)
                .addValue("btsUserId", btsUserId, Types.OTHER)
                .addValue("actionType", actionType)
                .addValue("outcome", outcome)
                .addValue("issueKey", issueKey)
        jdbc.update(SQL_INSERT, params)
    }

    private companion object {
        /** 인터랙티브 상호작용 결과 append-only 기록. */
        const val SQL_INSERT = """
            INSERT INTO slack_interaction_log
                (id, team_id, slack_user_id, bts_user_id, action_type, outcome, issue_key)
            VALUES
                (:id, :teamId, :slackUserId, :btsUserId, :actionType, :outcome, :issueKey)
        """
    }
}
