// automation_actions 영속 어댑터 — 룰 액션 리스트 원자적 교체(replace)/position 순 조회 (FR-AT-02 Task 6)

package com.bts.automation.adapter

import com.bts.automation.domain.Action
import com.bts.automation.domain.ActionType
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.jdbc.core.RowMapper
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.core.namedparam.SqlParameterSource
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.sql.ResultSet
import java.util.UUID

/**
 * [Action] 영속 어댑터 (FR-AT-02 Task 6).
 *
 * `automation_actions` 테이블(V302)에 룰별 액션 리스트를 저장한다. automation 모듈은 jOOQ 미도입
 * (단일 테이블 단순 CRUD, [AutomationRuleRepository] 선례 동형)이므로 [NamedParameterJdbcTemplate]
 * `:param` 바인딩만 사용하고 SQL 문자열 결합은 하지 않는다(DEVELOPMENT.md §1 절대 규칙).
 *
 * ## replace-all 설계
 * [replaceForRule] 은 DELETE 후 position(리스트 순서, 0-base) 순 INSERT 를 같은 트랜잭션에서 수행해
 * 룰의 액션 리스트를 원자적으로 교체한다(부분 UPSERT 아님 — identity-access
 * `JdbcUserKeymapRepository.replaceOverrides` 선례 동형). automation_actions 는 룰 소유 하위 엔티티
 * (V302 FK `ON DELETE CASCADE`)라 전체 교체가 UPSERT 보다 시맨틱에 더 맞는다.
 *
 * ## 직렬화 책임 분리
 * [Action] → `(action_type, action_config)` 직렬화는 이 어댑터 책임이다(도메인 [Action] 은 역직렬화
 * ([Action.fromJson])만 공개 API 로 노출한다). 역직렬화는 [Action.fromJson] 을 그대로 재사용해 파싱/
 * 검증 로직이 두 곳에 중복되지 않도록 한다 — 이 어댑터가 만드는 JSON 은 반드시 [Action.fromJson] 이
 * 기대하는 필드 형식과 정확히 대응해야 round-trip 이 보장된다.
 *
 * @param jdbc named parameter 바인딩 [NamedParameterJdbcTemplate].
 * @param objectMapper action_config JSON 직렬화용 Jackson [ObjectMapper](Spring Boot 기본 자동 구성 빈).
 */
@Repository
class AutomationActionRepository(
    private val jdbc: NamedParameterJdbcTemplate,
    private val objectMapper: ObjectMapper,
) {
    /**
     * [ruleId] 룰의 기존 액션을 모두 삭제하고 [actions] 를 리스트 순서(position, 0-base)대로 재삽입한다.
     *
     * @param ruleId 대상 룰 id.
     * @param actions 저장할 액션 목록(빈 리스트면 전량 삭제만 수행하고 재삽입은 생략한다).
     */
    @Transactional
    fun replaceForRule(
        ruleId: UUID,
        actions: List<Action>,
    ) {
        jdbc.update(SQL_DELETE_BY_RULE_ID, MapSqlParameterSource(PARAM_RULE_ID, ruleId))
        if (actions.isEmpty()) return
        jdbc.batchUpdate(SQL_INSERT, actionInsertParams(ruleId, actions, objectMapper))
    }

    /**
     * [ruleId] 룰의 액션을 실행 순서(position ASC)대로 조회한다.
     *
     * @param ruleId 대상 룰 id.
     * @return position 순 [Action] 목록(없으면 빈 리스트).
     */
    @Transactional(readOnly = true)
    fun findByRuleId(ruleId: UUID): List<Action> =
        jdbc.query(SQL_FIND_BY_RULE_ID, MapSqlParameterSource(PARAM_RULE_ID, ruleId), ActionRowMapper)

    /**
     * [ruleIds] 룰들의 액션을 **한 번의 쿼리로** 조회해 룰별로 묶어 돌려준다(N+1 제거).
     *
     * ## 왜 배치인가 — 2코어 VM 실측이 강제했다 (2026-09-09)
     *
     * `AutomationRuleService.analyzeProjectConflicts` 는 규칙마다 [findByRuleId] 를 불렀다.
     * 스펙(`docs/specs/2026-07-13-fr-at-04-conflict-analysis.md`)이 「N+1은 허용하되 100규칙 1s
     * 임계 내」라고 **조건부 허용**했는데, 그 조건이 깨진 것이 젠킨스 이전에서 드러났다.
     *
     * | n | find | hydrate(N+1) | analyze(CPU) |
     * |---|---|---|---|
     * | 100 | 19ms | **2,331ms** | 7ms |
     * | 200 | 17ms | **4,152ms** | 16ms |
     *
     * 왕복 1회당 약 11.6ms · 전체의 **98.9%**. 쿼리가 느린 게 아니라 **왕복이 많다** —
     * 같은 100건을 `find` 는 한 번에 19ms 에 가져온다. CPU 분석은 O(n²)인데도 7ms 라
     * 손댈 이유가 없다(추측으로 그쪽을 고쳤으면 헛수고였다).
     *
     * ★액션이 0건인 룰은 **키 자체가 없다**(빈 리스트가 아니다). 호출자는 `?: emptyList()` 로
     *   받는다 — 없는 키와 빈 리스트를 둘 다 만들면 「없음」의 표현이 두 가지가 된다.
     *
     * @param ruleIds 대상 룰 id 목록. 비면 DB 를 치지 않고 빈 맵을 돌려준다.
     * @return `ruleId -> position ASC 정렬된 액션 목록`. 액션이 없는 룰은 키가 없다.
     */
    @Transactional(readOnly = true)
    fun findByRuleIds(ruleIds: Collection<UUID>): Map<UUID, List<Action>> {
        if (ruleIds.isEmpty()) return emptyMap()
        val rows =
            jdbc.query(SQL_FIND_BY_RULE_IDS, MapSqlParameterSource(PARAM_RULE_IDS, ruleIds)) { rs, rowNum ->
                rs.getObject(COLUMN_RULE_ID, UUID::class.java) to ActionRowMapper.mapRow(rs, rowNum)
            }
        return rows.groupBy({ it.first }, { it.second })
    }

    private companion object {
        const val PARAM_RULE_ID = "ruleId"
        const val PARAM_RULE_IDS = "ruleIds"
        const val COLUMN_RULE_ID = "rule_id"

        const val SQL_DELETE_BY_RULE_ID = "DELETE FROM automation_actions WHERE rule_id = :ruleId"

        const val SQL_INSERT = """
            INSERT INTO automation_actions (rule_id, position, action_type, action_config)
            VALUES (:ruleId, :position, :actionType, CAST(:actionConfig AS jsonb))
        """

        const val SQL_FIND_BY_RULE_ID = """
            SELECT action_type, action_config
            FROM automation_actions
            WHERE rule_id = :ruleId
            ORDER BY position ASC
        """

        // ★`rule_id` 를 함께 뽑는다 — 단건 조회와 달리 어느 룰 것인지 결과에서 알아야 한다.
        //   정렬은 (rule_id, position) 이어야 룰별 묶음 안에서 position 순서가 보장된다.
        const val SQL_FIND_BY_RULE_IDS = """
            SELECT rule_id, action_type, action_config
            FROM automation_actions
            WHERE rule_id IN (:ruleIds)
            ORDER BY rule_id, position ASC
        """
    }
}

/**
 * [AutomationActionRepository.replaceForRule] 배치 삽입 파라미터.
 *
 * position 은 리스트 순서([List.mapIndexed] 의 index, 0-base)이며, action_config 는 [actionConfigJson]
 * 으로 액션별 형식에 맞게 직렬화한다.
 */
private fun actionInsertParams(
    ruleId: UUID,
    actions: List<Action>,
    objectMapper: ObjectMapper,
): Array<SqlParameterSource> =
    actions
        .mapIndexed { index, action ->
            MapSqlParameterSource()
                .addValue("ruleId", ruleId)
                .addValue("position", index)
                .addValue("actionType", actionTypeOf(action).name)
                .addValue("actionConfig", actionConfigJson(action, objectMapper))
        }.toTypedArray()

/** [Action] 서브타입 → [ActionType] 매핑(저장 시 action_type 컬럼 값 결정). */
private fun actionTypeOf(action: Action): ActionType =
    when (action) {
        is Action.SetFieldAction -> ActionType.SET_FIELD
        is Action.AssignAction -> ActionType.ASSIGN
        is Action.AddCommentAction -> ActionType.ADD_COMMENT
        is Action.CallWebhookAction -> ActionType.CALL_WEBHOOK
        is Action.SetFixVersionsAction -> ActionType.SET_FIX_VERSIONS
    }

/**
 * [Action] → `action_config` JSON 문자열 직렬화.
 *
 * [Action.fromJson] 의 파서(`parseSetField`/`parseAssign`/`parseAddComment`/`parseCallWebhook`/
 * `parseSetFixVersions`)가 기대하는 필드명과 정확히 일치해야 한다 — 여기서 어긋나면 [findByRuleId]
 * 역직렬화가 실패한다.
 */
private fun actionConfigJson(
    action: Action,
    objectMapper: ObjectMapper,
): String {
    val node = objectMapper.createObjectNode()
    when (action) {
        is Action.SetFieldAction -> {
            node.put("field", action.field)
            node.set<JsonNode>("value", action.value)
        }
        is Action.AssignAction -> {
            if (action.assigneeId != null) {
                node.put("assigneeId", action.assigneeId.toString())
            } else {
                node.putNull("assigneeId")
            }
        }
        is Action.AddCommentAction -> node.put("body", action.body)
        is Action.CallWebhookAction -> {
            node.put("url", action.url)
            node.put("method", action.method)
            node.put("body", action.body)
            val headersNode = objectMapper.createObjectNode()
            action.headers.forEach { (key, value) -> headersNode.put(key, value) }
            node.set<JsonNode>("headers", headersNode)
        }
        is Action.SetFixVersionsAction -> {
            val versionIdsNode = node.putArray("versionIds")
            action.versionIds.forEach { versionIdsNode.add(it.toString()) }
        }
    }
    return objectMapper.writeValueAsString(node)
}

/**
 * `automation_actions` 한 행 → [Action] 역직렬화.
 *
 * 파싱/검증은 [Action.fromJson] 을 재사용한다(중복 방지 — [actionConfigJson] KDoc 참고).
 */
private object ActionRowMapper : RowMapper<Action> {
    override fun mapRow(
        rs: ResultSet,
        rowNum: Int,
    ): Action {
        val actionType = ActionType.valueOf(rs.getString("action_type"))
        val configJson = rs.getString("action_config")
        return Action.fromJson(actionType, configJson)
    }
}
