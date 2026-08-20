// 워크플로우 전환 키 → transition_id UUID 해석 컴포넌트

package com.bts.workflow.postaction

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
 * [WorkflowTransition.key] 를 `workflow_transitions.id` 로 되돌리는 컴포넌트.
 *
 * post-action 관리 경로(`.../transitions/{transitionKey}/post-actions`)가 URL 세그먼트로 받는
 * 합성 키를 전환 1급 식별자로 옮긴다. 어느 단계에서든 미존재면 null 을 돌려주고 호출자가 404 로 바꾼다.
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
class PostActionTransitionResolver(
    private val dsl: DSLContext,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * (workflowKey, fromStateKey, toStateKey) → `workflow_transitions.id` 를 반환한다.
     *
     * 어느 단계에서든 미존재 시 null 반환 (예외 아님 — 호출자가 404 처리).
     *
     * @param workflowKey 워크플로우 식별 키.
     * @param fromStateKey 출발 상태 키. GLOBAL·INITIAL 전환은 상태 키 대신 종류 이름이 온다.
     * @param toStateKey 도착 상태 키.
     * @return 전환 UUID, 미존재 시 null.
     */
    @Suppress("ReturnCount")
    @Transactional(readOnly = true)
    fun resolveTransitionId(
        workflowKey: String,
        fromStateKey: String,
        toStateKey: String,
    ): UUID? {
        val workflowId = resolveWorkflowId(workflowKey) ?: return miss("workflow", workflowKey, workflowKey)
        val from = fromCondition(workflowId, fromStateKey) ?: return miss("fromState", workflowKey, fromStateKey)
        val to = toCondition(workflowId, toStateKey) ?: return miss("toState", workflowKey, toStateKey)
        return dsl
            .select(WORKFLOW_TRANSITIONS.ID)
            .from(WORKFLOW_TRANSITIONS)
            .where(WORKFLOW_TRANSITIONS.WORKFLOW_ID.eq(workflowId).and(from).and(to))
            .fetchOne(WORKFLOW_TRANSITIONS.ID)
    }

    /** 해석 실패를 남기고 null 을 돌려준다. 어느 단계에서 끊겼는지가 404 의 유일한 단서다. */
    private fun miss(
        stage: String,
        workflowKey: String,
        detail: String,
    ): UUID? {
        log.debug(
            "PostActionTransitionResolver: {} 미존재 workflowKey={} value={}",
            stage,
            workflowKey,
            detail,
        )
        return null
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

    private fun resolveWorkflowId(workflowKey: String): UUID? =
        dsl.select(WORKFLOWS.ID)
            .from(WORKFLOWS)
            .where(WORKFLOWS.KEY.eq(workflowKey))
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
