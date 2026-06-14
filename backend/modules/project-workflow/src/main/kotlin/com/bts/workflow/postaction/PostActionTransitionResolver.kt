// 워크플로우 전이 키 → transition_id UUID 해석 컴포넌트

package com.bts.workflow.postaction

import com.bts.workflow.jooq.tables.WorkflowStates.Companion.WORKFLOW_STATES
import com.bts.workflow.jooq.tables.WorkflowTransitions.Companion.WORKFLOW_TRANSITIONS
import com.bts.workflow.jooq.tables.Workflows.Companion.WORKFLOWS
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * (workflowKey, fromStateKey, toStateKey) 로 workflow_transitions.id 를 해석하는 컴포넌트.
 *
 * DefaultWorkflowDefinitionRepository 의 private resolveTransitionId 와 동일한
 * 3-단계 조회 로직(workflow → state → transition) 을 post-action 관리 용도로 추출한다.
 *
 * @param dsl jOOQ DSLContext.
 */
@Component
class PostActionTransitionResolver(
    private val dsl: DSLContext,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * (workflowKey, fromStateKey, toStateKey) → workflow_transitions.id 를 반환한다.
     *
     * 어느 단계에서든 미존재 시 null 반환 (예외 아님 — 호출자가 404 처리).
     *
     * @param workflowKey 워크플로우 식별 키.
     * @param fromStateKey 출발 상태 키.
     * @param toStateKey 도착 상태 키.
     * @return 전이 UUID, 미존재 시 null.
     */
    @Suppress("ReturnCount")
    @Transactional(readOnly = true)
    fun resolveTransitionId(
        workflowKey: String,
        fromStateKey: String,
        toStateKey: String,
    ): UUID? {
        val workflowId =
            resolveWorkflowId(workflowKey) ?: run {
                log.debug("PostActionTransitionResolver: workflow 미존재 key={}", workflowKey)
                return null
            }
        val fromStateId =
            resolveStateId(workflowId, fromStateKey) ?: run {
                log.debug(
                    "PostActionTransitionResolver: fromState 미존재 workflowKey={} fromStateKey={}",
                    workflowKey,
                    fromStateKey,
                )
                return null
            }
        val toStateId =
            resolveStateId(workflowId, toStateKey) ?: run {
                log.debug(
                    "PostActionTransitionResolver: toState 미존재 workflowKey={} toStateKey={}",
                    workflowKey,
                    toStateKey,
                )
                return null
            }
        return dsl
            .select(WORKFLOW_TRANSITIONS.ID)
            .from(WORKFLOW_TRANSITIONS)
            .where(
                WORKFLOW_TRANSITIONS.WORKFLOW_ID.eq(workflowId)
                    .and(WORKFLOW_TRANSITIONS.FROM_STATE_ID.eq(fromStateId))
                    .and(WORKFLOW_TRANSITIONS.TO_STATE_ID.eq(toStateId)),
            )
            .fetchOne()
            ?.get(WORKFLOW_TRANSITIONS.ID) as UUID?
    }

    private fun resolveWorkflowId(workflowKey: String): UUID? =
        dsl.select(WORKFLOWS.ID)
            .from(WORKFLOWS)
            .where(WORKFLOWS.KEY.eq(workflowKey))
            .fetchOne()
            ?.get(WORKFLOWS.ID) as UUID?

    private fun resolveStateId(
        workflowId: UUID,
        stateKey: String,
    ): UUID? =
        dsl.select(WORKFLOW_STATES.ID)
            .from(WORKFLOW_STATES)
            .where(
                WORKFLOW_STATES.WORKFLOW_ID.eq(workflowId)
                    .and(WORKFLOW_STATES.KEY.eq(stateKey)),
            )
            .fetchOne()
            ?.get(WORKFLOW_STATES.ID) as UUID?
}
