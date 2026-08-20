// 워크플로우 조회 Repository — 전역 상태 카탈로그 2단 join + 전환 join → Workflow aggregate 복원

package com.bts.workflow.repository

import com.bts.workflow.domain.StateCategory
import com.bts.workflow.domain.TransitionKind
import com.bts.workflow.domain.Workflow
import com.bts.workflow.domain.WorkflowState
import com.bts.workflow.domain.WorkflowTransition
import com.bts.workflow.jooq.tables.Statuses.Companion.STATUSES
import com.bts.workflow.jooq.tables.WorkflowStatuses.Companion.WORKFLOW_STATUSES
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
 * jOOQ generated 테이블 (workflows / statuses / workflow_statuses / workflow_transitions) join 으로
 * [Workflow] aggregate 를 복원한다.
 *
 * validator / post_action 컬렉션은 [Workflow] aggregate 책임 외 (별도 SPI 동작) 이므로
 * 본 Repository 범위에서 제외한다 (DONE_WITH_CONCERNS).
 *
 * ### 전환의 출발·도착은 `workflow_statuses` 를 가리킨다 (V207 · FR-WF-05)
 * 종전에는 구형 `workflow_states.id` 를 읽었다. 그러면 V207 이 백필한 `kind='INITIAL'` 행에서
 * **읽기가 죽는다** — 그 행은 구 컬럼이 NULL 이기 때문이다. 읽기를 전역 카탈로그 편성
 * (`workflow_statuses`)으로 옮기면 상태 목록과 전환이 **같은 한 벌의 id** 를 쓰게 되어
 * 별도 join 도 필요 없다.
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
     * UUID(PK) 로 [Workflow] aggregate 를 조회한다.
     *
     * WorkflowResolverImpl 에서 mapping.workflowId(UUID) → Workflow 변환에 사용한다.
     *
     * @param id workflows.id (UUID PK)
     * @return 조회된 [Workflow], 부재 시 null
     */
    fun findById(id: UUID): Workflow? {
        val rows = fetchJoinedRows(WORKFLOWS.ID.eq(id))
        return rows.toWorkflows().firstOrNull()
    }

    /**
     * key 로 workflows.id(UUID) 를 조회한다.
     *
     * Mapping 추가 시 workflowKey → workflowId(UUID) 변환에 사용한다.
     * aggregate 전체를 조회하지 않아 효율적이다.
     *
     * @param key 워크플로우 식별 키.
     * @return workflows.id(UUID), 부재 시 null.
     */
    fun findIdByKey(key: String): UUID? =
        dsl
            .select(WORKFLOWS.ID)
            .from(WORKFLOWS)
            .where(WORKFLOWS.KEY.eq(key))
            .and(WORKFLOWS.DELETED_AT.isNull)
            .fetchOne()
            ?.get(WORKFLOWS.ID) as UUID?

    /**
     * UUID 목록으로 [Workflow] aggregate 를 일괄 조회한다.
     *
     * 입력이 빈 리스트이면 DB 호출 없이 빈 map 을 반환한다.
     * 매핑 응답(MappingResponseDetail) 에서 workflowId → Workflow 변환에 사용한다.
     *
     * @param ids 조회할 workflows.id(UUID) 목록.
     * @return UUID → [Workflow] 매핑. 미존재 ID 는 포함되지 않는다.
     */
    fun findByIds(ids: List<UUID>): Map<UUID, Workflow> {
        if (ids.isEmpty()) return emptyMap()
        val rows = fetchJoinedRows(WORKFLOWS.ID.`in`(ids))
        return rows.toWorkflows().associateBy { workflow ->
            // Workflow.key 기준으로는 UUID FK 를 복원할 수 없으므로
            // JOIN 결과에서 WORKFLOWS.ID 를 직접 읽어 매핑한다.
            rows.firstOrNull { row -> row[WORKFLOWS.KEY] == workflow.key }
                ?.let { row -> row[WORKFLOWS.ID] as UUID }
                ?: error("WORKFLOWS.ID not found for workflow key=${workflow.key}")
        }
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
     * workflows LEFT JOIN (workflow_statuses ⋈ statuses) LEFT JOIN workflow_transitions 를 실행해
     * 결과 Record 목록을 반환한다.
     *
     * LEFT JOIN 을 사용하므로 state / transition 이 없는 workflow 도 포함된다.
     * (현재 스키마는 states 필수이나 향후 확장성을 위해 LEFT JOIN 유지)
     *
     * 구형 `workflow_states` 는 더 이상 join 하지 않는다 — 전환이 `workflow_statuses` 를 가리키므로
     * 그 join 은 결과 행만 배로 늘리고 아무 값도 주지 않는다.
     */
    private fun fetchJoinedRows(condition: Condition): List<Record> =
        dsl
            .select(
                // workflows 컬럼
                WORKFLOWS.ID,
                WORKFLOWS.KEY,
                WORKFLOWS.NAME,
                WORKFLOWS.DESCRIPTION,
                // 상태 — 전역 카탈로그 2단 (workflow_statuses ⋈ statuses)
                WORKFLOW_STATUSES.ID,
                WORKFLOW_STATUSES.DISPLAY_ORDER,
                STATUSES.KEY,
                STATUSES.NAME,
                STATUSES.CATEGORY,
                // workflow_transitions 컬럼
                WORKFLOW_TRANSITIONS.ID,
                WORKFLOW_TRANSITIONS.WORKFLOW_ID,
                WORKFLOW_TRANSITIONS.KIND,
                WORKFLOW_TRANSITIONS.FROM_STATUS_ID,
                WORKFLOW_TRANSITIONS.TO_STATUS_ID,
                WORKFLOW_TRANSITIONS.DISPLAY_ORDER,
                WORKFLOW_TRANSITIONS.NAME,
            )
            .from(WORKFLOWS)
            // 상태 목록의 정본. 소프트 삭제된 카탈로그 항목은 없는 것으로 취급한다.
            .leftJoin(WORKFLOW_STATUSES).on(WORKFLOW_STATUSES.WORKFLOW_ID.eq(WORKFLOWS.ID))
            .leftJoin(STATUSES).on(STATUSES.ID.eq(WORKFLOW_STATUSES.STATUS_ID).and(STATUSES.DELETED_AT.isNull))
            .leftJoin(WORKFLOW_TRANSITIONS).on(WORKFLOW_TRANSITIONS.WORKFLOW_ID.eq(WORKFLOWS.ID))
            .where(condition)
            // ★ 소프트 삭제 필터를 **여기 한 곳**에 둔다. 이 저장소에는 공통 필터 래퍼가 없어
            //   (DATA.md §3) 호출부마다 붙이면 언젠가 빠뜨린다 — 그러면 지운 워크플로우가
            //   목록과 전환 계산에 되살아난다. V205 가 deleted_at 을 만들었으나 읽기는
            //   그것을 보지 않고 있었다(PR 3 에서 실측).
            .and(WORKFLOWS.DELETED_AT.isNull)
            // 전환 표시 순서의 정본은 display_order 다 (V207 이 row_number() 로 백필해 뒀다).
            // dedup 이 첫 등장을 남기므로 정렬을 여기서 걸어야 그 순서가 aggregate 까지 간다.
            .orderBy(WORKFLOW_TRANSITIONS.DISPLAY_ORDER.asc(), WORKFLOW_TRANSITIONS.ID.asc())
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

            // states — 전역 카탈로그 편성(workflow_statuses.id) 기준 dedup
            val states =
                rows
                    .filter { it[WORKFLOW_STATUSES.ID] != null }
                    .distinctBy { it[WORKFLOW_STATUSES.ID] as UUID }
                    .map { row -> row.toWorkflowState() }
                    .sortedBy { it.displayOrder }

            // workflow_statuses.id → 상태 key 매핑. 전환의 from/to 가 가리키는 것이 바로 이 id 다.
            val statusIdToKey: Map<UUID, String> =
                rows
                    .filter { it[WORKFLOW_STATUSES.ID] != null && it[STATUSES.KEY] != null }
                    .associate { row -> row.required(WORKFLOW_STATUSES.ID) to row.required(STATUSES.KEY) }

            // transitions — (workflow_transitions.id) 기준 dedup. 정렬은 SQL 의 display_order 가 정한다.
            val transitions =
                rows
                    .filter { it[WORKFLOW_TRANSITIONS.ID] != null }
                    .distinctBy { it.required(WORKFLOW_TRANSITIONS.ID) }
                    .map { row -> row.toWorkflowTransition(statusIdToKey) }

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
            key = required(STATUSES.KEY),
            name = required(STATUSES.NAME),
            category = StateCategory.valueOf(required(STATUSES.CATEGORY)),
            displayOrder = required(WORKFLOW_STATUSES.DISPLAY_ORDER),
        )

    /**
     * Record → [WorkflowTransition] 변환.
     *
     * [statusIdToKey] 로 from_status_id / to_status_id (workflow_statuses.id) 를 상태 key 로 되돌린다.
     *
     * **`from_status_id` 의 null 은 정상이다** — GLOBAL·INITIAL 전환은 출발 상태가 없다는 것이
     * 그 종류의 정의다. 종전 구현은 구 컬럼을 `as UUID` 로 캐스팅해 V207 이 백필한 INITIAL 행에서
     * `NullPointerException` 을 냈다.
     *
     * @param statusIdToKey 이 워크플로우의 `workflow_statuses.id` → 상태 key 매핑.
     */
    private fun Record.toWorkflowTransition(statusIdToKey: Map<UUID, String>): WorkflowTransition =
        WorkflowTransition(
            id = required(WORKFLOW_TRANSITIONS.ID),
            fromStateKey = this[WORKFLOW_TRANSITIONS.FROM_STATUS_ID]?.let { statusIdToKey.statusKeyOf(it) },
            toStateKey = statusIdToKey.statusKeyOf(required(WORKFLOW_TRANSITIONS.TO_STATUS_ID)),
            name = required(WORKFLOW_TRANSITIONS.NAME),
            kind = transitionKind(),
        )

    companion object {
        // DSL_TRUE — 조건 없이 전체 조회할 때 사용하는 항등 조건
        private val DSL_TRUE: Condition = DSL.trueCondition()
    }
}

/**
 * `workflow_statuses.id` 를 상태 key 로 되돌린다. FK 제약상 실패할 수 없으나, 대상 상태가
 * 소프트 삭제됐으면 join 이 끊겨 여기까지 온다 — 그때 조용히 빠뜨리지 않고 원인을 말한다.
 */
private fun Map<UUID, String>.statusKeyOf(statusCompositionId: UUID): String =
    this[statusCompositionId]
        ?: error(
            "workflow_statuses.id=$statusCompositionId 에 해당하는 상태 key 가 없다. " +
                "그 상태가 소프트 삭제됐는지 확인할 것",
        )

/**
 * `workflow_transitions.kind` (TEXT) → [TransitionKind].
 *
 * 알 수 없는 값을 [TransitionKind.NORMAL] 로 떨어뜨리지 않는다 — 그러면 전역 전환이 보통 전환으로
 * 둔갑해 후보 계산이 조용히 틀린다. DB CHECK(`ck_workflow_transitions_kind`)가 이미 3종으로
 * 좁히므로 여기 오는 값은 코드와 스키마가 갈라졌다는 뜻이고, 그때는 즉시 멈추는 편이 싸다.
 */
private fun Record.transitionKind(): TransitionKind {
    val raw = required(WORKFLOW_TRANSITIONS.KIND)
    return TransitionKind.entries.firstOrNull { it.name == raw }
        ?: error(
            "workflow_transitions.kind='$raw' 는 알 수 없는 전환 종류다. " +
                "TransitionKind enum 과 마이그레이션의 CHECK 제약이 갈라졌는지 확인할 것",
        )
}
