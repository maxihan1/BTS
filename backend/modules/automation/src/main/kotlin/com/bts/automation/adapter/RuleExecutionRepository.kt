// RuleExecution 영속 어댑터 — 실행 이력 저장/단건·룰별 조회, rule_id 하드 FK 없이 감사 독립성 보장 (FR-AT-05 Task 2)

package com.bts.automation.adapter

import com.bts.automation.application.ActionExecutionStatus
import com.bts.automation.application.ActionOutcome
import com.bts.automation.application.RuleExecution
import com.bts.automation.domain.TriggerType
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * [RuleExecution] 영속 어댑터 (FR-AT-05 Task 2).
 *
 * `rule_executions` 테이블(V305)에 자동화 룰 실행 이력을 저장/조회한다. automation 모듈은 jOOQ 미도입
 * (단일 테이블 단순 CRUD, [AutomationRuleRepository] 선례 동형)이므로 [NamedParameterJdbcTemplate]
 * `:param` 바인딩만 사용하고 SQL 문자열 결합은 하지 않는다(DEVELOPMENT.md §1 절대 규칙).
 *
 * ## 룰 테이블 조인 없음 — 감사 독립성(NFR-4)
 * `rule_id` 는 `automation_rules.id` 를 논리적으로만 가리키며 하드 FK 가 없다(V305 마이그레이션 KDoc
 * 참조). [findByRule] 을 포함한 모든 조회는 `automation_rules` 를 조인하지 않는다 — 룰이 나중에
 * 소프트/하드 삭제돼도 실행 이력은 그대로 조회 가능해야 audit trail 로서 의미가 있다.
 *
 * ## findByRule 의 선택적 필터 — NULL-safe 단일 SQL
 * [findByRule] 의 `issueKey`/`before` 는 선택적 필터다. 조건 유무에 따라 SQL 문자열을 분기 조립하는
 * 대신, `(:issueKey::text IS NULL OR issue_key = :issueKey)` 형태의 NULL-safe 조건으로 단일 SQL 상수
 * 안에서 처리한다(파라미터 바인딩만 쓰고 문자열 결합은 하지 않는다는 원칙을 지키면서 분기를 없앤다,
 * identity-access `PersonalAccessTokenRepository` 의 `(expires_at IS NULL OR ...)` 관례와 동형).
 *
 * ## 직렬화
 * [RuleExecution.triggerEvent]([com.fasterxml.jackson.databind.JsonNode])와 [RuleExecution.outcomes]
 * ([List] of [ActionOutcome])는 [objectMapper] 로 JSON 문자열 직렬화 후 `CAST(... AS jsonb)` 로
 * 저장한다(DATA.md §5). BC 격리상 `triggerEvent` 는 issue-tracking 등 다른 BC 타입을 절대 담지 않고
 * `JsonNode` 그대로 왕복한다.
 *
 * @param jdbc named parameter 바인딩 [NamedParameterJdbcTemplate].
 * @param objectMapper trigger_event/outcomes JSONB 직렬화·역직렬화용 Jackson [ObjectMapper](Spring Boot
 *   기본 자동 구성 빈).
 */
@Repository
class RuleExecutionRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val objectMapper: ObjectMapper,
) {
    /**
     * 신규 [execution] 을 삽입한다. id 는 호출자가 미리 확정한 값을 그대로 저장한다
     * ([AutomationRuleRepository.save] 관례 동형 — 애그리거트가 이미 확정한 id 를 그대로 씀).
     *
     * @param execution 저장할 실행 이력.
     */
    @Transactional
    fun save(execution: RuleExecution) {
        jdbc.update(SQL_INSERT, ruleExecutionInsertParams(execution, objectMapper))
    }

    /**
     * [id] 실행 이력을 단건 조회한다.
     *
     * @param id 실행 이력 id.
     * @return 실행 이력 또는 `null`.
     */
    @Transactional(readOnly = true)
    fun findById(id: UUID): RuleExecution? =
        jdbc.query(SQL_FIND_BY_ID, MapSqlParameterSource(PARAM_ID, id), RuleExecutionRowMapper(objectMapper))
            .firstOrNull()

    /**
     * [ruleId] 룰의 실행 이력을 최신순(`started_at DESC, id DESC`)으로 조회한다. `automation_rules` 를
     * 조인하지 않으므로 소프트/하드 삭제된 룰의 이력도 반환된다(클래스 KDoc "룰 테이블 조인 없음" 참조).
     *
     * @param projectKey 룰 소속 프로젝트 키(스코프 좁히기 — 다른 프로젝트 키로는 결과가 보이지 않는다).
     * @param ruleId 대상 룰 id.
     * @param issueKey 지정하면 해당 이슈에 대한 실행만 반환. `null` 이면 전체.
     * @param limit 최대 반환 건수.
     * @param before 지정하면 이 시각 이전(`started_at <`) 실행만 반환(keyset 페이지네이션 커서).
     * @return 최신순 실행 이력 목록(없으면 빈 리스트).
     */
    @Transactional(readOnly = true)
    fun findByRule(
        projectKey: String,
        ruleId: UUID,
        issueKey: String?,
        limit: Int,
        before: Instant?,
    ): List<RuleExecution> =
        jdbc.query(
            SQL_FIND_BY_RULE,
            ruleExecutionFindByRuleParams(projectKey, ruleId, issueKey, limit, before),
            RuleExecutionRowMapper(objectMapper),
        )

    private companion object {
        const val PARAM_ID = "id"

        const val SQL_INSERT = """
            INSERT INTO rule_executions
                (id, rule_id, project_key, trigger_type, trigger_event, issue_key, status, outcomes,
                 replayed_from, started_at, finished_at)
            VALUES
                (:id, :ruleId, :projectKey, :triggerType, CAST(:triggerEvent AS jsonb), :issueKey, :status,
                 CAST(:outcomes AS jsonb), :replayedFrom, :startedAt, :finishedAt)
        """

        const val SQL_SELECT_COLUMNS = """
            SELECT id, rule_id, project_key, trigger_type, trigger_event, issue_key, status, outcomes,
                   replayed_from, started_at, finished_at
            FROM rule_executions
        """

        const val SQL_FIND_BY_ID = "$SQL_SELECT_COLUMNS WHERE id = :id"

        const val SQL_FIND_BY_RULE = """
            $SQL_SELECT_COLUMNS
            WHERE rule_id = :ruleId AND project_key = :projectKey
              AND (:issueKey::text IS NULL OR issue_key = :issueKey)
              AND (:before::timestamptz IS NULL OR started_at < :before)
            ORDER BY started_at DESC, id DESC
            LIMIT :limit
        """
    }
}

/** 신규 [RuleExecution] 삽입 파라미터 — timestamptz 는 UTC [OffsetDateTime] 으로, JSONB 필드는 JSON 문자열로 바인딩한다. */
private fun ruleExecutionInsertParams(
    execution: RuleExecution,
    objectMapper: ObjectMapper,
): MapSqlParameterSource =
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

/** [RuleExecutionRepository.findByRule] 파라미터 — 선택적 필터(`issueKey`/`before`)는 클래스 KDoc 참조. */
private fun ruleExecutionFindByRuleParams(
    projectKey: String,
    ruleId: UUID,
    issueKey: String?,
    limit: Int,
    before: Instant?,
): MapSqlParameterSource =
    MapSqlParameterSource()
        .addValue("projectKey", projectKey)
        .addValue("ruleId", ruleId)
        .addValue("issueKey", issueKey)
        .addValue("before", before?.atOffset(ZoneOffset.UTC))
        .addValue("limit", limit)

/**
 * `rule_executions` 한 행 → [RuleExecution] 매핑.
 *
 * `trigger_event`/`outcomes`(jsonb) 는 텍스트로 읽어 [objectMapper] 로 역직렬화한다 — `trigger_event`
 * 는 [com.fasterxml.jackson.databind.JsonNode] 로, `outcomes` 는 `List<`[ActionOutcome]`>` 로.
 */
private class RuleExecutionRowMapper(
    private val objectMapper: ObjectMapper,
) : RowMapper<RuleExecution> {
    override fun mapRow(
        rs: ResultSet,
        rowNum: Int,
    ): RuleExecution =
        RuleExecution(
            id = rs.getObject("id", UUID::class.java),
            ruleId = rs.getObject("rule_id", UUID::class.java),
            projectKey = rs.getString("project_key"),
            triggerType = TriggerType.valueOf(rs.getString("trigger_type")),
            triggerEvent = objectMapper.readTree(rs.getString("trigger_event")),
            issueKey = rs.getString("issue_key"),
            status = ActionExecutionStatus.valueOf(rs.getString("status")),
            outcomes =
                objectMapper.readValue(rs.getString("outcomes"), object : TypeReference<List<ActionOutcome>>() {}),
            replayedFrom = rs.getObject("replayed_from", UUID::class.java),
            startedAt = rs.getObject("started_at", OffsetDateTime::class.java).toInstant(),
            finishedAt = rs.getObject("finished_at", OffsetDateTime::class.java).toInstant(),
        )
}
