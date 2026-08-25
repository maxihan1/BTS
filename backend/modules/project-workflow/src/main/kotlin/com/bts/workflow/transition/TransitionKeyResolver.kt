// 워크플로우 전환 키 → transition_id UUID 해석 컴포넌트

package com.bts.workflow.transition

import com.bts.workflow.domain.TransitionKind
import com.bts.workflow.domain.WorkflowTransition
import com.bts.workflow.jooq.tables.Statuses.Companion.STATUSES
import com.bts.workflow.jooq.tables.WorkflowStates.Companion.WORKFLOW_STATES
import com.bts.workflow.jooq.tables.WorkflowStatuses.Companion.WORKFLOW_STATUSES
import com.bts.workflow.jooq.tables.WorkflowTransitions.Companion.WORKFLOW_TRANSITIONS
import com.bts.workflow.jooq.tables.Workflows.Companion.WORKFLOWS
import org.jooq.Condition
import org.jooq.DSLContext
import org.jooq.Field
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * 규칙 경로가 받은 전환 지목값을 `workflow_transitions.id` 로 되돌리는 컴포넌트.
 *
 * ### 1급 식별자는 id 다 (ADR 2026-08-18 §D1)
 * 경로 세그먼트가 UUID 로 파싱되면 [resolveById] 로 간다. 그쪽이 정본이다 —
 * `workflow_transitions.id` 는 PK 라 언제나 단건이다.
 *
 * [WorkflowTransition.key] 로 지목하는 [resolveTransitionIds] 는 **하위호환 경로**다.
 * V207 ① 이 `UNIQUE(workflow_id, from_state_id, to_state_id)` 를 풀어 같은 (from,to) 구간에
 * 전환을 여럿 둘 수 있게 됐으므로 `key` 는 더 이상 유일하지 않다. 그래서 이 함수는 **목록**을
 * 돌려주고 몇 건인지는 호출자가 보고 판단한다 — `fetchOne` 으로 단건을 가정하면 2행을 만나는
 * 순간 `TooManyRowsException` 이고, 저장소에 그 예외를 잡는 곳이 없어 곧바로 500 이 된다.
 *
 * ### 신 컬럼이 정본, 구 컬럼은 폴백이다 (3단 분할의 2단계)
 * `workflow_transitions` 는 출발·도착을 가리키는 컬럼을 두 벌 갖고 있다 — 구형
 * `from_state_id`/`to_state_id`(→ `workflow_states`)와 신형 `from_status_id`/`to_status_id`
 * (→ 전역 상태 카탈로그 편성 `workflow_statuses`). 전환 정의 CRUD 가 만드는 행은 **신 컬럼만**
 * 채우므로 구 컬럼으로만 찾으면 새로 만든 전환이 한 건도 안 잡혀 규칙을 붙일 수 없었다.
 *
 * 우선순위는 읽기 정본인 `WorkflowRepository.toWorkflowTransition` 과 **같다** — 신 컬럼이
 * 채워진 행은 신 컬럼으로, 신 컬럼이 NULL 인 행만 구 컬럼으로 고른다. 실제 조건 조립은
 * [endpointCondition] 이고 그 KDoc 에 대응 관계를 적어 뒀다.
 *
 * ### 출발 상태가 없는 전환 (GLOBAL·INITIAL)
 * 그 두 종류의 [WorkflowTransition.key] 는 `KIND__to` 라서 출발 자리에 상태 키가 아니라 **종류
 * 이름**이 온다. `from_state_id = ?` 같은 등치 비교는 NULL 을 못 맞추므로(SQL 3값 논리) 그 자리를
 * 상태로 해석하면 영원히 0건이다. [KIND_TOKENS] 로 걸러 `kind` 컬럼 비교로 바꾼다.
 *
 * @param dsl jOOQ DSLContext.
 */
@Component
class TransitionKeyResolver(
    private val dsl: DSLContext,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 전환 id 가 그 워크플로우의 것인지 확인하고 그대로 돌려준다.
     *
     * 경로 세그먼트가 UUID 로 파싱되면 이 길로 온다. PK 조회라 다건이 될 수 없고, 워크플로우
     * 소속을 같은 질의에서 확인하므로 남의 워크플로우 전환을 지목할 수 없다.
     *
     * @param workflowKey 워크플로우 식별 키.
     * @param transitionId 경로가 지목한 전환 UUID.
     * @return 전환 UUID, 그 워크플로우에 없으면 null (예외 아님 — 호출자가 404 처리).
     */
    @Transactional(readOnly = true)
    fun resolveById(
        workflowKey: String,
        transitionId: UUID,
    ): UUID? {
        val found =
            dsl
                .select(WORKFLOW_TRANSITIONS.ID)
                .from(WORKFLOW_TRANSITIONS)
                .join(WORKFLOWS)
                .on(WORKFLOWS.ID.eq(WORKFLOW_TRANSITIONS.WORKFLOW_ID).and(WORKFLOWS.DELETED_AT.isNull))
                .where(WORKFLOW_TRANSITIONS.ID.eq(transitionId).and(WORKFLOWS.KEY.eq(workflowKey)))
                .fetchOne(WORKFLOW_TRANSITIONS.ID)
        if (found == null) {
            log.debug(
                "TransitionKeyResolver: 전환 id 미존재 workflowKey={} transitionId={}",
                workflowKey,
                transitionId,
            )
        }
        return found
    }

    /**
     * (workflowKey, fromStateKey, toStateKey) 에 걸리는 전환 id 를 **전부** 반환한다.
     *
     * ★ 단건을 가정하지 않는다. 같은 (from,to) 구간에 전환이 여럿일 수 있고, GLOBAL 은 조건이
     * `kind = 'GLOBAL'` + 도착 상태뿐이라 같은 도착지를 향한 2건이면 그것만으로 다건이 된다.
     * 몇 건인지를 보고 404 로 바꿀지 결정하는 것은 호출자의 몫이다 — 규칙 종류마다 호출자가 다르다.
     *
     * @param workflowKey 워크플로우 식별 키.
     * @param fromStateKey 출발 상태 키. GLOBAL·INITIAL 전환은 상태 키 대신 종류 이름이 온다.
     * @param toStateKey 도착 상태 키.
     * @return 걸린 전환 UUID 목록. 어느 단계에서든 미존재면 빈 목록.
     */
    @Suppress("ReturnCount")
    @Transactional(readOnly = true)
    fun resolveTransitionIds(
        workflowKey: String,
        fromStateKey: String,
        toStateKey: String,
    ): List<UUID> {
        val workflowId = resolveWorkflowId(workflowKey) ?: return miss("workflow", workflowKey, workflowKey)
        val from = fromCondition(workflowId, fromStateKey) ?: return miss("fromState", workflowKey, fromStateKey)
        val to = toCondition(workflowId, toStateKey) ?: return miss("toState", workflowKey, toStateKey)
        return dsl
            .select(WORKFLOW_TRANSITIONS.ID)
            .from(WORKFLOW_TRANSITIONS)
            .where(WORKFLOW_TRANSITIONS.WORKFLOW_ID.eq(workflowId).and(from).and(to))
            .fetch(WORKFLOW_TRANSITIONS.ID)
            .filterNotNull()
    }

    /** 해석 실패를 남기고 빈 목록을 돌려준다. 어느 단계에서 끊겼는지가 404 의 유일한 단서다. */
    private fun miss(
        stage: String,
        workflowKey: String,
        detail: String,
    ): List<UUID> {
        log.debug(
            "TransitionKeyResolver: {} 미존재 workflowKey={} value={}",
            stage,
            workflowKey,
            detail,
        )
        return emptyList()
    }

    /**
     * 출발 자리 조건. [KIND_TOKENS] 에 걸리면 상태가 아니라 전환 종류를 가리키는 것으로 읽는다.
     *
     * 상태로 읽는 경우 두 세대 어디에도 그 키가 없으면 null 이다.
     */
    private fun fromCondition(
        workflowId: UUID,
        fromStateKey: String,
    ): Condition? =
        KIND_TOKENS[fromStateKey]?.let { WORKFLOW_TRANSITIONS.KIND.eq(it.name) }
            ?: endpointCondition(
                WORKFLOW_TRANSITIONS.FROM_STATUS_ID,
                WORKFLOW_TRANSITIONS.FROM_STATE_ID,
                resolveStatusCompositionId(workflowId, fromStateKey),
                resolveLegacyStateId(workflowId, fromStateKey),
            )

    /** 도착 자리 조건. 도착지 없는 전환은 어느 종류에도 없으므로 종류 토큰을 보지 않는다. */
    private fun toCondition(
        workflowId: UUID,
        toStateKey: String,
    ): Condition? =
        endpointCondition(
            WORKFLOW_TRANSITIONS.TO_STATUS_ID,
            WORKFLOW_TRANSITIONS.TO_STATE_ID,
            resolveStatusCompositionId(workflowId, toStateKey),
            resolveLegacyStateId(workflowId, toStateKey),
        )

    /**
     * 워크플로우 키 → id.
     *
     * ★ `DELETED_AT.isNull` 이 빠지면 `fetchOne` 이 다건을 만난다. V206 이 key 유니크를
     * 「살아 있는 행끼리만」으로 완화해 **삭제된 동명 워크플로우가 함께 잡히기 때문**이다.
     * 읽기 정본(`WorkflowRepository`)도 같은 조건을 걸어 두 경로가 같은 집합을 본다.
     */
    private fun resolveWorkflowId(workflowKey: String): UUID? =
        dsl.select(WORKFLOWS.ID)
            .from(WORKFLOWS)
            .where(WORKFLOWS.KEY.eq(workflowKey).and(WORKFLOWS.DELETED_AT.isNull))
            .fetchOne(WORKFLOWS.ID)

    /**
     * 상태 키 → 그 워크플로우의 `workflow_statuses.id`. 전환의 **신 컬럼이 가리키는 값**이다.
     *
     * 소프트 삭제된 카탈로그 항목은 없는 것으로 친다 — 읽기 정본(`WorkflowRepository` 의
     * `STATUSES.DELETED_AT.isNull`)과 같은 집합을 봐야 한 쪽에만 보이는 상태가 생기지 않는다.
     */
    private fun resolveStatusCompositionId(
        workflowId: UUID,
        stateKey: String,
    ): UUID? =
        dsl.select(WORKFLOW_STATUSES.ID)
            .from(WORKFLOW_STATUSES)
            .join(STATUSES)
            .on(STATUSES.ID.eq(WORKFLOW_STATUSES.STATUS_ID).and(STATUSES.DELETED_AT.isNull))
            .where(WORKFLOW_STATUSES.WORKFLOW_ID.eq(workflowId).and(STATUSES.KEY.eq(stateKey)))
            .fetchOne(WORKFLOW_STATUSES.ID)

    /** 상태 키 → 구형 `workflow_states.id`. **3단계(`workflow_states` DROP)에서 이 함수를 지운다.** */
    private fun resolveLegacyStateId(
        workflowId: UUID,
        stateKey: String,
    ): UUID? =
        dsl.select(WORKFLOW_STATES.ID)
            .from(WORKFLOW_STATES)
            .where(WORKFLOW_STATES.WORKFLOW_ID.eq(workflowId).and(WORKFLOW_STATES.KEY.eq(stateKey)))
            .fetchOne(WORKFLOW_STATES.ID)
}

/**
 * 출발 상태가 없는 전환 종류의 예약 토큰. `GLOBAL`·`INITIAL` 은 상태 키가 아니라 종류 이름이다.
 *
 * [TransitionKind] 에서 파생시킨다 — 손으로 적으면 종류가 늘어날 때 이 목록만 남아 썩는다.
 * `NORMAL` 은 출발 상태가 있으므로 토큰이 아니다.
 */
private val KIND_TOKENS: Map<String, TransitionKind> =
    TransitionKind.entries.filterNot { it == TransitionKind.NORMAL }.associateBy { it.name }

/**
 * 전환의 한쪽 끝(출발 또는 도착)을 상태 키로 고르는 조건.
 *
 * **신 컬럼이 정본이고 구 컬럼은 폴백이다.** `WorkflowRepository.toWorkflowTransition` 의
 * `this[FROM_STATUS_ID]?.let { … } ?: this[FROM_STATE_ID]?.let { … }` 를 SQL 로 옮긴 것이라
 * 읽기와 해석이 같은 행을 고른다. 신 컬럼이 채워진 행은 신 컬럼으로만, 신 컬럼이 NULL 인 행만
 * 구 컬럼으로 고른다 — 두 갈래가 `IS NULL` 로 배타적이라 한 행이 양쪽에 걸리지 않는다.
 *
 * ★ 구 컬럼 쪽에 `statusColumn.isNull` 을 붙이는 것이 폴백의 핵심이다. 이것을 빼고 단순 OR 로
 * 두면 신·구가 모두 채워진 행이 **구 컬럼 값으로도** 잡혀 우선순위가 사라진다.
 *
 * @param statusColumn 신 컬럼 (`from_status_id` 또는 `to_status_id`).
 * @param legacyColumn 구 컬럼 (`from_state_id` 또는 `to_state_id`). 3단계에서 사라진다.
 * @param statusCompositionId 상태 키가 가리키는 `workflow_statuses.id`. 편성이 없으면 null.
 * @param legacyStateId 상태 키가 가리키는 구형 `workflow_states.id`. 행이 없으면 null.
 * @return 조건. 두 세대 어디에도 그 상태가 없으면 null.
 */
private fun endpointCondition(
    statusColumn: Field<UUID?>,
    legacyColumn: Field<UUID?>,
    statusCompositionId: UUID?,
    legacyStateId: UUID?,
): Condition? {
    val byStatus = statusCompositionId?.let { statusColumn.eq(it) }
    val byLegacy = legacyStateId?.let { statusColumn.isNull.and(legacyColumn.eq(it)) }
    if (byStatus == null) return byLegacy
    return if (byLegacy == null) byStatus else byStatus.or(byLegacy)
}
