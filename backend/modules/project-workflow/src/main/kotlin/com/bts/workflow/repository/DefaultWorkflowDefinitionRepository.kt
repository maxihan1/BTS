// DefaultWorkflowDefinitionRepository — jOOQ 기반 workflow_validators / workflow_post_actions 조회 구현체

package com.bts.workflow.repository

import com.bts.workflow.domain.WorkflowTransition
import com.bts.workflow.engine.PostActionConfig
import com.bts.workflow.engine.ValidatorConfig
import com.bts.workflow.engine.WorkflowDefinitionRepository
import com.bts.workflow.jooq.tables.WorkflowPostActions.Companion.WORKFLOW_POST_ACTIONS
import com.bts.workflow.jooq.tables.WorkflowStates.Companion.WORKFLOW_STATES
import com.bts.workflow.jooq.tables.WorkflowTransitions.Companion.WORKFLOW_TRANSITIONS
import com.bts.workflow.jooq.tables.WorkflowValidators.Companion.WORKFLOW_VALIDATORS
import com.bts.workflow.jooq.tables.Workflows.Companion.WORKFLOWS
import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import org.jooq.DSLContext
import org.jooq.JSONB
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * [WorkflowDefinitionRepository] jOOQ 구현체.
 *
 * transition_id 해석 단계.
 * 1. workflowKey to workflows.key to workflows.id 조회.
 * 2. transition.fromStateKey, transition.toStateKey to workflow_states.key (해당 workflow_id 범위) to
 *    from_state_id, to_state_id 조회.
 * 3. workflow_transitions 에서 (workflow_id, from_state_id, to_state_id) 로 transition_id 조회.
 * 4. workflow_validators / workflow_post_actions 에서 transition_id = ? 행을 display_order ASC 정렬 조회.
 *
 * workflowKey 를 필수 1급 인자로 받아 cross-workflow 오매칭(B2)을 방지한다.
 * workflow / state / transition 중 하나라도 미존재 시 빈 리스트를 반환한다 (예외 아님 — 검증할 게 없으면 통과).
 */
@Repository
class DefaultWorkflowDefinitionRepository(
    private val dsl: DSLContext,
) : WorkflowDefinitionRepository {
    private val log = LoggerFactory.getLogger(javaClass)
    private val objectMapper = ObjectMapper()
    private val mapTypeRef = object : TypeReference<Map<String, Any?>>() {}

    /**
     * 전환에 설정된 Validator 설정 목록을 display_order ASC 순서로 반환한다.
     *
     * workflowKey 가 가리키는 workflow 범위 안에서만 state / transition 을 해석하므로
     * 같은 (fromStateKey, toStateKey) 쌍을 사용하는 다른 워크플로우의 validator 행이
     * 결과에 포함되지 않는다 (B2 cross-workflow 격리 보장).
     *
     * workflow / state / transition 미존재 시 빈 리스트 반환 (예외 없음).
     *
     * @param workflowKey 워크플로우 식별자 (workflows.key)
     * @param transition 조회 대상 전환 정의
     */
    @Transactional(readOnly = true)
    override fun findValidators(
        workflowKey: String,
        transition: WorkflowTransition,
    ): List<ValidatorConfig> {
        val transitionId = resolveTransitionId(workflowKey, transition) ?: return emptyList()

        return dsl
            .select(WORKFLOW_VALIDATORS.TYPE, WORKFLOW_VALIDATORS.CONFIG)
            .from(WORKFLOW_VALIDATORS)
            .where(WORKFLOW_VALIDATORS.TRANSITION_ID.eq(transitionId))
            .orderBy(WORKFLOW_VALIDATORS.DISPLAY_ORDER.asc())
            .fetch()
            .map { record ->
                ValidatorConfig(
                    type =
                        record.get(WORKFLOW_VALIDATORS.TYPE)
                            ?: error("workflow_validators.type 이 null — transition_id=$transitionId"),
                    config = parseJsonbToMap(record.get(WORKFLOW_VALIDATORS.CONFIG), transitionId),
                )
            }
    }

    /**
     * 전환에 설정된 PostAction 설정 목록을 display_order ASC 순서로 반환한다.
     *
     * workflowKey 가 가리키는 workflow 범위 안에서만 state / transition 을 해석하므로
     * 같은 (fromStateKey, toStateKey) 쌍을 사용하는 다른 워크플로우의 post_action 행이
     * 결과에 포함되지 않는다 (B2 cross-workflow 격리 보장).
     *
     * workflow / state / transition 미존재 시 빈 리스트 반환 (예외 없음).
     *
     * @param workflowKey 워크플로우 식별자 (workflows.key)
     * @param transition 조회 대상 전환 정의
     */
    @Transactional(readOnly = true)
    override fun findPostActions(
        workflowKey: String,
        transition: WorkflowTransition,
    ): List<PostActionConfig> {
        val transitionId = resolveTransitionId(workflowKey, transition) ?: return emptyList()

        return dsl
            .select(WORKFLOW_POST_ACTIONS.TYPE, WORKFLOW_POST_ACTIONS.CONFIG)
            .from(WORKFLOW_POST_ACTIONS)
            .where(WORKFLOW_POST_ACTIONS.TRANSITION_ID.eq(transitionId))
            .orderBy(WORKFLOW_POST_ACTIONS.DISPLAY_ORDER.asc())
            .fetch()
            .map { record ->
                PostActionConfig(
                    type =
                        record.get(WORKFLOW_POST_ACTIONS.TYPE)
                            ?: error("workflow_post_actions.type 이 null — transition_id=$transitionId"),
                    config = parseJsonbToMap(record.get(WORKFLOW_POST_ACTIONS.CONFIG), transitionId),
                )
            }
    }

    // ── private helpers ──────────────────────────────────────────────────────

    /**
     * workflowKey + transition 정의로부터 workflow_transitions.id 를 단계적으로 해석한다.
     *
     * 단계.
     * 1. workflowKey to workflows.id
     * 2. fromStateKey, toStateKey to workflow_states.id (workflow_id 범위 한정)
     * 3. (workflow_id, from_state_id, to_state_id) to workflow_transitions.id
     *
     * 어느 단계에서든 미존재 시 null 반환 (예외 아님).
     */
    private fun resolveTransitionId(
        workflowKey: String,
        transition: WorkflowTransition,
    ): UUID? {
        val workflowId = resolveWorkflowId(workflowKey)
        if (workflowId == null) {
            log.debug("DefaultWorkflowDefinitionRepository: workflow 미존재 key={}", workflowKey)
        }
        val fromStateId = workflowId?.let { resolveStateId(it, transition.fromStateKey) }
        if (workflowId != null && fromStateId == null) {
            log.debug(
                "DefaultWorkflowDefinitionRepository: fromState 미존재 workflowKey={} fromStateKey={}",
                workflowKey,
                transition.fromStateKey,
            )
        }
        val toStateId = workflowId?.let { wfId -> fromStateId?.let { resolveStateId(wfId, transition.toStateKey) } }
        if (workflowId != null && fromStateId != null && toStateId == null) {
            log.debug(
                "DefaultWorkflowDefinitionRepository: toState 미존재 workflowKey={} toStateKey={}",
                workflowKey,
                transition.toStateKey,
            )
        }
        return if (workflowId != null && fromStateId != null && toStateId != null) {
            dsl
                .select(WORKFLOW_TRANSITIONS.ID)
                .from(WORKFLOW_TRANSITIONS)
                .where(
                    WORKFLOW_TRANSITIONS.WORKFLOW_ID.eq(workflowId)
                        .and(WORKFLOW_TRANSITIONS.FROM_STATE_ID.eq(fromStateId))
                        .and(WORKFLOW_TRANSITIONS.TO_STATE_ID.eq(toStateId)),
                )
                .fetchOne()
                ?.get(WORKFLOW_TRANSITIONS.ID) as UUID?
        } else {
            null
        }
    }

    /**
     * workflows.key to workflows.id 조회.
     *
     * @param workflowKey 워크플로우 식별 키
     * @return workflows.id, 미존재 시 null
     */
    private fun resolveWorkflowId(workflowKey: String): UUID? =
        dsl
            .select(WORKFLOWS.ID)
            .from(WORKFLOWS)
            .where(WORKFLOWS.KEY.eq(workflowKey))
            .fetchOne()
            ?.get(WORKFLOWS.ID) as UUID?

    /**
     * (workflow_id, stateKey) to workflow_states.id 조회.
     *
     * workflow_id 범위를 한정해 cross-workflow 동명 state 오매칭을 방지한다.
     *
     * @param workflowId 워크플로우 PK
     * @param stateKey 상태 식별 키 (workflow_states.key)
     * @return workflow_states.id, 미존재 시 null
     */
    private fun resolveStateId(
        workflowId: UUID,
        stateKey: String,
    ): UUID? =
        dsl
            .select(WORKFLOW_STATES.ID)
            .from(WORKFLOW_STATES)
            .where(
                WORKFLOW_STATES.WORKFLOW_ID.eq(workflowId)
                    .and(WORKFLOW_STATES.KEY.eq(stateKey)),
            )
            .fetchOne()
            ?.get(WORKFLOW_STATES.ID) as UUID?

    /**
     * jOOQ JSONB 값을 Map 으로 역직렬화한다.
     *
     * JSONB null 이거나 data() 가 빈 문자열이면 빈 Map 을 반환한다.
     * Jackson ObjectMapper 로 역직렬화하며, [JsonProcessingException] 발생 시
     * 빈 Map 을 반환하고 경고 로그를 남긴다.
     *
     * @param jsonb jOOQ JSONB 컬럼 값
     * @param transitionId 로그 컨텍스트용 transition_id
     */
    private fun parseJsonbToMap(
        jsonb: JSONB?,
        transitionId: UUID,
    ): Map<String, Any?> {
        val raw = jsonb?.data()?.takeIf { it.isNotBlank() && it != "{}" } ?: return emptyMap()
        return try {
            objectMapper.readValue(raw, mapTypeRef)
        } catch (ex: JsonProcessingException) {
            log.warn(
                "DefaultWorkflowDefinitionRepository: JSONB 파싱 실패 transition_id={} raw='{}' error={}",
                transitionId,
                raw,
                ex.message,
            )
            emptyMap()
        }
    }
}
