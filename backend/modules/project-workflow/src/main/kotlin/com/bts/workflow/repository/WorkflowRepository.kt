// 워크플로우 조회 Repository — 전역 상태 카탈로그 2단 join + 전환 join → Workflow aggregate 복원

package com.bts.workflow.repository

import com.bts.workflow.domain.StateCategory
import com.bts.workflow.domain.TransitionKind
import com.bts.workflow.domain.Workflow
import com.bts.workflow.domain.WorkflowState
import com.bts.workflow.domain.WorkflowTransition
import com.bts.workflow.jooq.tables.Statuses.Companion.STATUSES
import com.bts.workflow.jooq.tables.WorkflowStates.Companion.WORKFLOW_STATES
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
// TooManyFunctions — 한 aggregate 의 조회 경로다. 나누면 JOIN 재조립 헬퍼가 둘로 갈린다.
@Suppress("TooManyFunctions")
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
     * key 로 워크플로우의 소유 프로젝트(`workflows.project_id`)를 조회한다.
     *
     * null = 전역 공유 워크플로우. 권한 스코프 결정에 쓴다(FR-WF-08).
     * 워크플로우가 없어도 null 이므로, 호출부는 「없음」과 「전역」을 구분하지 않고 전역으로 판정한다
     * — 전역 판정은 SYSTEM_ADMIN 만 통과하므로 없는 키에 대해 fail-closed 다.
     *
     * @param key 워크플로우 식별 키.
     * @return 소유 프로젝트 UUID, 전역이거나 부재 시 null.
     *
     * ★★2026-09-14 운영 실측. 종전 판본은 `fetchOne` 이었고, V209 가 정상으로 만들어 둔
     *   「전역 1 + 프로젝트 1」에서 `TooManyRowsException` 으로 죽었다. 권한 판정이
     *   대상 조회보다 **먼저** 도므로, 그 key 를 건드리는 모든 요청이 500 이 됐다.
     *
     * ★중복일 때 **전역을 고른다**. 근거는 fail-closed 다 —
     *   `ofProjectId` 가 되짚기에 실패했을 때 `WorkflowScope.Global` 로 떨어지는 것과
     *   같은 방향이고, 전역 스코프가 프로젝트 스코프보다 **엄격한** 권한을 요구한다
     *   (「전역 워크플로우는 시스템 관리자만」). 모호할 때 느슨한 쪽을 고르면
     *   프로젝트 관리자가 전역 템플릿을 고칠 수 있게 된다.
     *
     * ★그래서 이것은 **죽지 않게 만든 것**이지 모호함을 푼 것이 아니다. 어느 소유를
     *   뜻했는지는 호출자만 안다 — 스코프 해석에 맥락을 태우는 것은 FR-WF-08 의 남은 몫이다.
     */
    fun findProjectIdByKey(key: String): UUID? =
        dsl
            .select(WORKFLOWS.PROJECT_ID)
            .from(WORKFLOWS)
            .where(WORKFLOWS.KEY.eq(key))
            .and(WORKFLOWS.DELETED_AT.isNull)
            // NULL(전역)을 앞으로 — 모호하면 더 엄격한 스코프를 고른다.
            .orderBy(WORKFLOWS.PROJECT_ID.asc().nullsFirst())
            .limit(1)
            .fetchOne()
            ?.get(WORKFLOWS.PROJECT_ID)

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

    /**
     * [projectId] 프로젝트가 쓸 수 있는 워크플로우를 조회한다 — 전역 공유 + 그 프로젝트 전용.
     *
     * ★ [findAll] 은 전량을 준다. 프로젝트 설정 화면이 그걸 그대로 쓰면 **남의 프로젝트 전용
     * 워크플로우가 보인다** — 이름만으로도 그 팀이 무슨 흐름을 쓰는지 새는 셈이다.
     * PR ② 가 스킴 목록에 같은 필터를 넣었고(`WorkflowSchemeRepository.findAllForProject`),
     * 워크플로우 쪽만 전량으로 남으면 두 목록이 서로 다른 규칙을 갖게 된다.
     *
     * @param projectId 대상 프로젝트 `projects.id`.
     * @return 전역 + 해당 프로젝트 소유 워크플로우.
     */
    fun findAllForProject(projectId: UUID): List<Workflow> {
        val rows = fetchJoinedRows(WORKFLOWS.PROJECT_ID.isNull.or(WORKFLOWS.PROJECT_ID.eq(projectId)))
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
                listOf(
                    // workflows 컬럼
                    WORKFLOWS.ID,
                    WORKFLOWS.KEY,
                    WORKFLOWS.NAME,
                    WORKFLOWS.DESCRIPTION,
                    // 화면이 「전역 템플릿」과 「이 프로젝트 것」을 갈라 그리는 근거다(FR-WF-08).
                    WORKFLOWS.PROJECT_ID,
                    // 상태 — 전역 카탈로그 2단 (workflow_statuses ⋈ statuses)
                    WORKFLOW_STATUSES.ID,
                    WORKFLOW_STATUSES.DISPLAY_ORDER,
                    STATUSES.KEY,
                    STATUSES.NAME,
                    STATUSES.CATEGORY,
                ) + TRANSITION_COLUMNS,
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
     * LEFT JOIN 결과는 (workflow × 상태 편성 × transitions) 의 카르테시안 곱이므로
     * workflow_id → (고유 states, 고유 transitions) 으로 dedup 후 aggregate 를 복원한다.
     */
    private fun List<Record>.toWorkflows(): List<Workflow> {
        if (isEmpty()) return emptyList()

        // 구 컬럼 폴백용 매핑. 신 컬럼이 채워진 행만 있으면 빈 맵이라 조회도 일어나지 않는다.
        val legacyStateKeys = fetchLegacyStateKeys(this)

        // workflow_id 기준 그룹핑
        val grouped = this.groupBy { it[WORKFLOWS.ID] as UUID }

        return grouped.map { (_, rows) ->
            val firstRow = rows.first()
            val workflowKey = firstRow[WORKFLOWS.KEY]!!
            val workflowName = firstRow[WORKFLOWS.NAME]!!
            val workflowDescription = firstRow[WORKFLOWS.DESCRIPTION]
            val workflowProjectId = firstRow[WORKFLOWS.PROJECT_ID]

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
                    .map { row -> row.toWorkflowTransition(statusIdToKey, legacyStateKeys) }

            Workflow.of(
                key = workflowKey,
                name = workflowName,
                description = workflowDescription,
                states = states,
                transitions = transitions,
                projectId = workflowProjectId,
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
     * 구 컬럼(`workflow_states.id`) → 상태 key 매핑을 만든다. **폴백 전용**이다.
     *
     * ### ★ 3단계에서 이 함수와 호출부를 통째로 지운다
     * 지금은 add → backfill → drop 3단 분할의 2단계라 구·신 컬럼이 공존한다. `workflow_transitions`
     * 에 구 컬럼만 채워 넣는 코드가 아직 살아 있고(전환을 직접 INSERT 하는 테스트 23파일 중 21파일),
     * 그 행들은 신 컬럼이 NULL 이다. 읽기가 신 컬럼만 보면 그 행에서 죽는다.
     * `workflow_states` 를 DROP 하는 3단계 PR 이 이 폴백을 함께 지운다.
     *
     * ### 왜 join 이 아니라 별도 조회인가
     * `workflow_states` 를 메인 쿼리에 LEFT JOIN 하면 결과 행이 (상태 편성 × 전환 × 구 상태)로
     * 한 겹 더 곱해진다. 필요한 id 만 모아 한 번 조회하면 곱이 늘지 않고, 폴백이 필요 없는
     * 배포(신 컬럼이 다 찬 상태)에서는 조회 자체가 일어나지 않는다.
     */
    private fun fetchLegacyStateKeys(rows: List<Record>): Map<UUID, String> {
        val legacyIds =
            rows.flatMapTo(mutableSetOf()) { row ->
                listOfNotNull(
                    row[WORKFLOW_TRANSITIONS.FROM_STATE_ID]
                        .takeIf { row[WORKFLOW_TRANSITIONS.FROM_STATUS_ID] == null },
                    row[WORKFLOW_TRANSITIONS.TO_STATE_ID]
                        .takeIf { row[WORKFLOW_TRANSITIONS.TO_STATUS_ID] == null },
                )
            }
        if (legacyIds.isEmpty()) return emptyMap()

        return dsl
            .select(WORKFLOW_STATES.ID, WORKFLOW_STATES.KEY)
            .from(WORKFLOW_STATES)
            .where(WORKFLOW_STATES.ID.`in`(legacyIds))
            .fetch()
            .associate { row -> row.required(WORKFLOW_STATES.ID) to row.required(WORKFLOW_STATES.KEY) }
    }

    /**
     * Record → [WorkflowTransition] 변환.
     *
     * [statusIdToKey] 로 from_status_id / to_status_id (workflow_statuses.id) 를 상태 key 로 되돌린다.
     * 신 컬럼이 NULL 이면 [legacyStateKeys] 로 구 컬럼(`workflow_states.id`)을 거쳐 같은 key 에 닿는다 —
     * 두 세대는 상태 key 를 다리로 이어져 있다(V204 가 key 기준으로 카탈로그를 승격했다).
     *
     * **`from_status_id` 의 null 은 두 가지 뜻이다.** ① GLOBAL·INITIAL 이라 출발 상태가 없다
     * ② 구 컬럼만 채운 행이다. ②는 구 컬럼이 값을 갖고 있어 갈린다. 종전 구현은 구 컬럼을
     * `as UUID` 로 캐스팅해 V207 이 백필한 INITIAL 행에서 `NullPointerException` 을 냈다.
     *
     * 읽는 컬럼의 정본은 [TRANSITION_COLUMNS] 다 — 새 읽기 경로는 그 목록과 이 함수를 함께 쓴다.
     *
     * @param statusIdToKey 이 워크플로우의 `workflow_statuses.id` → 상태 key 매핑.
     * @param legacyStateKeys 구 컬럼 폴백용 `workflow_states.id` → 상태 key 매핑. 3단계에서 사라진다.
     */
    private fun Record.toWorkflowTransition(
        statusIdToKey: Map<UUID, String>,
        legacyStateKeys: Map<UUID, String>,
    ): WorkflowTransition =
        WorkflowTransition(
            id = required(WORKFLOW_TRANSITIONS.ID),
            fromStateKey =
                this[WORKFLOW_TRANSITIONS.FROM_STATUS_ID]?.let { statusIdToKey.statusKeyOf(it) }
                    ?: this[WORKFLOW_TRANSITIONS.FROM_STATE_ID]?.let { legacyStateKeys.legacyStateKeyOf(it) },
            toStateKey =
                this[WORKFLOW_TRANSITIONS.TO_STATUS_ID]?.let { statusIdToKey.statusKeyOf(it) }
                    ?: this[WORKFLOW_TRANSITIONS.TO_STATE_ID]?.let { legacyStateKeys.legacyStateKeyOf(it) }
                    ?: error(
                        "전환 id=${required(WORKFLOW_TRANSITIONS.ID)} 의 도착 상태가 신·구 컬럼 모두 " +
                            "NULL 이다. 도착지 없는 전환은 어느 종류에도 없다",
                    ),
            name = required(WORKFLOW_TRANSITIONS.NAME),
            kind = transitionKind(),
        )

    companion object {
        // DSL_TRUE — 조건 없이 전체 조회할 때 사용하는 항등 조건
        private val DSL_TRUE: Condition = DSL.trueCondition()
    }
}

/**
 * 전환 1행을 복원하는 데 필요한 컬럼 묶음.
 *
 * [WorkflowRepository] 안의 `Record.toWorkflowTransition` 과 **짝**이다. 전환을 읽는 경로가
 * 늘어날 때(로드맵 PR 4 의 전환 CRUD 가 곧 하나 더 만든다) 컬럼 목록을 손으로 다시 적으면
 * 하나를 빠뜨려도 컴파일이 통과하고 `required(...)` 가 런타임에야 죽는다. 목록과 매핑을
 * 한 파일에 나란히 두어 한쪽만 바뀌는 것을 막는다.
 */
private val TRANSITION_COLUMNS =
    listOf(
        WORKFLOW_TRANSITIONS.ID,
        WORKFLOW_TRANSITIONS.WORKFLOW_ID,
        WORKFLOW_TRANSITIONS.KIND,
        WORKFLOW_TRANSITIONS.FROM_STATUS_ID,
        WORKFLOW_TRANSITIONS.TO_STATUS_ID,
        WORKFLOW_TRANSITIONS.DISPLAY_ORDER,
        WORKFLOW_TRANSITIONS.NAME,
        // ★ 3단계(workflow_states DROP)에서 아래 2개와 폴백 경로를 함께 지운다.
        WORKFLOW_TRANSITIONS.FROM_STATE_ID,
        WORKFLOW_TRANSITIONS.TO_STATE_ID,
    )

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
 * 구 컬럼 `workflow_states.id` 를 상태 key 로 되돌린다. **3단계에서 이 함수를 지운다.**
 *
 * 여기까지 왔다는 것은 신 컬럼이 NULL 이라 폴백을 탄다는 뜻이다. 그런데 구 컬럼이 가리키는 상태 행이
 * 없다면 두 세대 어느 쪽으로도 상태를 정할 수 없다 — 조용히 빠뜨리면 그 전환이 화면에서 사라진다.
 */
private fun Map<UUID, String>.legacyStateKeyOf(legacyStateId: UUID): String =
    this[legacyStateId]
        ?: error(
            "workflow_states.id=$legacyStateId 에 해당하는 상태가 없다. " +
                "전환이 신 컬럼(workflow_statuses)도 구 컬럼도 못 가리키는 상태다",
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
