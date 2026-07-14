// RuleExecution 영속 어댑터 — 실행 이력 저장/단건·룰별 조회 (FR-AT-05 Task 2)

package com.bts.automation.adapter

import com.bts.automation.application.ActionExecutionStatus
import com.bts.automation.application.ActionOutcome
import com.bts.automation.application.RuleExecution
import com.bts.automation.domain.TriggerType
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

@Repository
class RuleExecutionRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val objectMapper: ObjectMapper,
) {
    @Transactional
    fun save(execution: RuleExecution) {
        val params =
            MapSqlParameterSource()
                .addValue("id", execution.id)
                .addValue("ruleId", execution.ruleId)
                .addValue("projectKey", execution.projectKey)
                .addValue("triggerType", execution.triggerType.name)
                .addValue("triggerEvent", objectMapper.writeValueAsString(execution.triggerEvent))
                .addValue("issueKey", execution.issueKey)
                .addValue("status", execution.status.name)
                .addValue("outcomes", objectMapper.writeValueAsString(execution.outcomes))
                .addValue("replayedFrom", execution.replayedFrom)
                .addValue("startedAt", execution.startedAt.atOffset(ZoneOffset.UTC))
                .addValue("finishedAt", execution.finishedAt.atOffset(ZoneOffset.UTC))
        jdbc.update(
            """
            INSERT INTO rule_executions
                (id, rule_id, project_key, trigger_type, trigger_event, issue_key, status, outcomes,
                 replayed_from, started_at, finished_at)
            VALUES
                (:id, :ruleId, :projectKey, :triggerType, CAST(:triggerEvent AS jsonb), :issueKey, :status,
                 CAST(:outcomes AS jsonb), :replayedFrom, :startedAt, :finishedAt)
            """,
            params,
        )
    }

    @Transactional(readOnly = true)
    fun findById(id: UUID): RuleExecution? =
        jdbc
            .query(
                "SELECT id, rule_id, project_key, trigger_type, trigger_event, issue_key, status, outcomes, " +
                    "replayed_from, started_at, finished_at FROM rule_executions WHERE id = :id",
                MapSqlParameterSource("id", id),
            ) { rs, _ -> mapRow(rs) }
            .firstOrNull()

    @Transactional(readOnly = true)
    fun findByRule(
        projectKey: String,
        ruleId: UUID,
        issueKey: String?,
        limit: Int,
        before: Instant?,
    ): List<RuleExecution> {
        val params =
            MapSqlParameterSource()
                .addValue("projectKey", projectKey)
                .addValue("ruleId", ruleId)
                .addValue("issueKey", issueKey)
                .addValue("before", before?.atOffset(ZoneOffset.UTC))
                .addValue("limit", limit)
        return jdbc.query(
            """
            SELECT id, rule_id, project_key, trigger_type, trigger_event, issue_key, status, outcomes,
                   replayed_from, started_at, finished_at
            FROM rule_executions
            WHERE rule_id = :ruleId AND project_key = :projectKey
              AND (:issueKey::text IS NULL OR issue_key = :issueKey)
              AND (:before::timestamptz IS NULL OR started_at < :before)
            ORDER BY started_at DESC, id DESC
            LIMIT :limit
            """,
            params,
        ) { rs, _ -> mapRow(rs) }
    }

    private fun mapRow(rs: ResultSet): RuleExecution =
        RuleExecution(
            id = rs.getObject("id", UUID::class.java),
            ruleId = rs.getObject("rule_id", UUID::class.java),
            projectKey = rs.getString("project_key"),
            triggerType = TriggerType.valueOf(rs.getString("trigger_type")),
            triggerEvent = objectMapper.readTree(rs.getString("trigger_event")),
            issueKey = rs.getString("issue_key"),
            status = ActionExecutionStatus.valueOf(rs.getString("status")),
            outcomes = objectMapper.readValue(rs.getString("outcomes"), object : TypeReference<List<ActionOutcome>>() {}),
            replayedFrom = rs.getObject("replayed_from", UUID::class.java),
            startedAt = rs.getObject("started_at", OffsetDateTime::class.java).toInstant(),
            finishedAt = rs.getObject("finished_at", OffsetDateTime::class.java).toInstant(),
        )
}
