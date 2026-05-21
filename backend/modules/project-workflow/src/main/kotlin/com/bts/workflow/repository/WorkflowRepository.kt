// 워크플로우 조회 Repository — jOOQ generated 3 테이블 join → Workflow aggregate 복원

package com.bts.workflow.repository

import com.bts.workflow.domain.StateCategory
import com.bts.workflow.domain.Workflow
import com.bts.workflow.domain.WorkflowState
import com.bts.workflow.domain.WorkflowTransition
import com.bts.workflow.jooq.tables.WorkflowStates.Companion.WORKFLOW_STATES
import com.bts.workflow.jooq.tables.WorkflowTransitions.Companion.WORKFLOW_TRANSITIONS
import com.bts.workflow.jooq.tables.Workflows.Companion.WORKFLOWS
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.impl.DSL
import org.springframework.stereotype.Repository
import java.util.UUID

/**
 * 워크플로우 조회 Repository.
 *
 * jOOQ generated 3 테이블 (workflows / workflow_states / workflow_transitions) join 으로
 * [Workflow] aggregate 를 복원한다.
 *
 * validator / post_action 컬렉션은 [Workflow] aggregate 책임 외 (별도 SPI 동작) 이므로
 * 본 Repository 범위에서 제외한다 (DONE_WITH_CONCERNS).
 *
 * workflow_transitions 는 from_state_id / to_state_id (UUID FK) 를 가지므로
 * states 조회로 UUID → key 매핑을 먼저 구성한 뒤 [WorkflowTransition] 을 복원한다.
 */
@Repository
class WorkflowRepository(private val dsl: DSLContext) {
    /**
     * key 로 [Workflow] aggregate 를 조회한다.
     *
     * @param key 워크플로우 식별 키 (예: software-default)
     * @return 조회된 [Workflow], 부재 시 null
     */
    fun findByKey(key: String): Workflow? {
        val rows = fetchJoinedRows(WORKFLOWS.KEY.eq(key))
        return rows.toWorkflows().firstOrNull()
    }

    /**
     * 저장된 모든 [Workflow] aggregate 를 반환한다.
     *
     * @return [Workflow] 목록 (비어 있을 수 있음)
     */
    fun findAll(): List<Workflow> {
        val rows = fetchJoinedRows(DSL_TRUE)
        return rows.toWorkflows()
    }

    // ── 내부 구현 ───────────────────────────────────────────────────────────────

    /**
     * workflows LEFT JOIN workflow_states LEFT JOIN workflow_transitions 를 실행해
     * 결과 Record 목록을 반환한다.
     *
     * LEFT JOIN 을 사용하므로 state / transition 이 없는 workflow 도 포함된다.
     * (현재 스키마는 states 필수이나 향후 확장성을 위해 LEFT JOIN 유지)
     */
    private fun fetchJoinedRows(condition: Condition): List<Record> =
        dsl
            .select(
                // workflows 컬럼
                WORKFLOWS.ID,
                WORKFLOWS.KEY,
                WORKFLOWS.NAME,
                WORKFLOWS.DESCRIPTION,
                // workflow_states 컬럼
                WORKFLOW_STATES.ID,
                WORKFLOW_STATES.WORKFLOW_ID,
                WORKFLOW_STATES.KEY,
                WORKFLOW_STATES.NAME,
                WORKFLOW_STATES.CATEGORY,
                WORKFLOW_STATES.DISPLAY_ORDER,
                // workflow_transitions 컬럼
                WORKFLOW_TRANSITIONS.ID,
                WORKFLOW_TRANSITIONS.WORKFLOW_ID,
                WORKFLOW_TRANSITIONS.FROM_STATE_ID,
                WORKFLOW_TRANSITIONS.TO_STATE_ID,
                WORKFLOW_TRANSITIONS.NAME,
            )
            .from(WORKFLOWS)
            .leftJoin(WORKFLOW_STATES).on(WORKFLOW_STATES.WORKFLOW_ID.eq(WORKFLOWS.ID))
            .leftJoin(WORKFLOW_TRANSITIONS).on(WORKFLOW_TRANSITIONS.WORKFLOW_ID.eq(WORKFLOWS.ID))
            .where(condition)
            .fetch()

    /**
     * 조회 결과 Record 목록을 workflow_id 기준으로 그룹핑해 [Workflow] 목록으로 변환한다.
     *
     * LEFT JOIN 결과는 (workflow × states × transitions) 의 카르테시안 곱이므로
     * workflow_id → (고유 states, 고유 transitions) 으로 dedup 후 aggregate 를 복원한다.
     */
    private fun List<Record>.toWorkflows(): List<Workflow> {
        if (isEmpty()) return emptyList()

        // workflow_id 기준 그룹핑
        val grouped = this.groupBy { it[WORKFLOWS.ID] as UUID }

        return grouped.map { (_, rows) ->
            val firstRow = rows.first()
            val workflowKey = firstRow[WORKFLOWS.KEY]!!
            val workflowName = firstRow[WORKFLOWS.NAME]!!
            val workflowDescription = firstRow[WORKFLOWS.DESCRIPTION]

            // states — (workflow_states.id, key, name, category, display_order) 기준 dedup
            val states =
                rows
                    .filter { it[WORKFLOW_STATES.ID] != null }
                    .distinctBy { it[WORKFLOW_STATES.ID] as UUID }
                    .map { row -> row.toWorkflowState() }
                    .sortedBy { it.displayOrder }

            // UUID → state key 매핑 (transitions 복원에 사용)
            val stateIdToKey: Map<UUID, String> =
                rows
                    .filter { it[WORKFLOW_STATES.ID] != null }
                    .distinctBy { it[WORKFLOW_STATES.ID] as UUID }
                    .associate { row ->
                        (row[WORKFLOW_STATES.ID] as UUID) to row[WORKFLOW_STATES.KEY]!!
                    }

            // transitions — (workflow_transitions.id) 기준 dedup
            val transitions =
                rows
                    .filter { it[WORKFLOW_TRANSITIONS.ID] != null }
                    .distinctBy { it[WORKFLOW_TRANSITIONS.ID] as UUID }
                    .map { row -> row.toWorkflowTransition(stateIdToKey) }

            Workflow.of(
                key = workflowKey,
                name = workflowName,
                description = workflowDescription,
                states = states,
                transitions = transitions,
            )
        }
    }

    /** Record → [WorkflowState] 변환. */
    private fun Record.toWorkflowState(): WorkflowState =
        WorkflowState(
            key = this[WORKFLOW_STATES.KEY]!!,
            name = this[WORKFLOW_STATES.NAME]!!,
            category = StateCategory.valueOf(this[WORKFLOW_STATES.CATEGORY]!!),
            displayOrder = this[WORKFLOW_STATES.DISPLAY_ORDER]!!,
        )

    /**
     * Record → [WorkflowTransition] 변환.
     *
     * [stateIdToKey] 를 사용해 from_state_id / to_state_id (UUID) 를 상태 key (String) 로 변환한다.
     * 매핑 실패 시 IllegalStateException — 스키마 FK 제약상 발생 불가이나 방어적 처리.
     */
    private fun Record.toWorkflowTransition(stateIdToKey: Map<UUID, String>): WorkflowTransition {
        val fromStateId = this[WORKFLOW_TRANSITIONS.FROM_STATE_ID] as UUID
        val toStateId = this[WORKFLOW_TRANSITIONS.TO_STATE_ID] as UUID
        return WorkflowTransition(
            fromStateKey =
                stateIdToKey[fromStateId]
                    ?: error("from_state_id $fromStateId 에 해당하는 state key 가 없습니다"),
            toStateKey =
                stateIdToKey[toStateId]
                    ?: error("to_state_id $toStateId 에 해당하는 state key 가 없습니다"),
            name = this[WORKFLOW_TRANSITIONS.NAME]!!,
        )
    }

    companion object {
        // DSL_TRUE — 조건 없이 전체 조회할 때 사용하는 항등 조건
        private val DSL_TRUE: Condition = DSL.trueCondition()
    }
}
