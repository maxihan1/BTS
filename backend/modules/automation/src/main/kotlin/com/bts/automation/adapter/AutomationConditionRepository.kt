// automation_conditions 영속 어댑터 — 룰 조건 트리 upsert/delete/조회 (룰당 0..1 행, FR-AT-03 Task 4)

package com.bts.automation.adapter

import com.bts.automation.domain.Condition
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * [Condition] 영속 어댑터 (FR-AT-03 Task 4).
 *
 * `automation_conditions` 테이블(V304)에 룰의 조건 트리를 저장한다. 조건은 하나의 트리이므로
 * **룰당 최대 한 행**이며(rule_id 가 PK), automation 모듈은 jOOQ 미도입([AutomationActionRepository]
 * 선례 동형)이라 [NamedParameterJdbcTemplate] `:param` 바인딩만 사용하고 SQL 문자열 결합은 하지
 * 않는다(DEVELOPMENT.md §1 절대 규칙, DATA.md §5).
 *
 * ## replace 설계 — upsert / delete
 * [replace] 는 조건이 있으면 `INSERT ... ON CONFLICT (rule_id) DO UPDATE` 로 단일 행을 upsert 하고,
 * `null` 이면 DELETE 한다(룰당 0..1 시맨틱). automation_actions 의 replace-all(N행 DELETE→INSERT)과
 * 달리 단일 행이므로 부분 교체 문제가 없어 upsert 가 더 단순하고 안전하다.
 *
 * ## 직렬화 책임 분리
 * [Condition] ↔ `expression` JSON 직렬화/역직렬화는 도메인 [Condition.toJson]/[Condition.fromJson] 을
 * 그대로 재사용한다(파싱·검증 로직 중복 방지). 이 어댑터는 저장/조회 배관만 담당한다.
 *
 * @param jdbc named parameter 바인딩 [NamedParameterJdbcTemplate].
 */
@Repository
class AutomationConditionRepository(
    private val jdbc: NamedParameterJdbcTemplate,
) {
    /**
     * [ruleId] 룰의 조건 트리를 조회한다.
     *
     * @param ruleId 대상 룰 id.
     * @return 저장된 [Condition] 트리. 조건이 없으면 `null`.
     */
    @Transactional(readOnly = true)
    fun findByRuleId(ruleId: UUID): Condition? {
        val expression =
            jdbc
                .query(SQL_FIND_BY_RULE_ID, MapSqlParameterSource(PARAM_RULE_ID, ruleId)) { rs, _ ->
                    rs.getString(COLUMN_EXPRESSION)
                }.firstOrNull() ?: return null
        return Condition.fromJson(expression)
    }

    /**
     * [ruleId] 룰의 조건을 [condition] 으로 교체한다(룰당 0..1 행).
     *
     * [condition] 이 있으면 upsert(존재 시 UPDATE, 없으면 INSERT)하고, `null` 이면 기존 조건을 삭제한다.
     *
     * @param ruleId 대상 룰 id.
     * @param condition 저장할 조건 트리. `null` 이면 기존 조건 삭제(조건 없음으로 전환).
     */
    @Transactional
    fun replace(
        ruleId: UUID,
        condition: Condition?,
    ) {
        if (condition == null) {
            jdbc.update(SQL_DELETE_BY_RULE_ID, MapSqlParameterSource(PARAM_RULE_ID, ruleId))
            return
        }
        val params =
            MapSqlParameterSource()
                .addValue(PARAM_RULE_ID, ruleId)
                .addValue(PARAM_EXPRESSION, condition.toJson())
        jdbc.update(SQL_UPSERT, params)
    }

    private companion object {
        const val PARAM_RULE_ID = "ruleId"
        const val PARAM_EXPRESSION = "expression"
        const val COLUMN_EXPRESSION = "expression"

        const val SQL_FIND_BY_RULE_ID =
            "SELECT expression FROM automation_conditions WHERE rule_id = :ruleId"

        const val SQL_DELETE_BY_RULE_ID =
            "DELETE FROM automation_conditions WHERE rule_id = :ruleId"

        const val SQL_UPSERT = """
            INSERT INTO automation_conditions (rule_id, expression)
            VALUES (:ruleId, CAST(:expression AS jsonb))
            ON CONFLICT (rule_id) DO UPDATE
            SET expression = EXCLUDED.expression, updated_at = now()
        """
    }
}
