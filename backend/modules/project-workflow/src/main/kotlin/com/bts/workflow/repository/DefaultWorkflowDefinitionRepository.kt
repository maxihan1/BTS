// DefaultWorkflowDefinitionRepository — jOOQ 기반 workflow_validators / workflow_post_actions 조회 구현체

package com.bts.workflow.repository

import com.bts.workflow.domain.WorkflowTransition
import com.bts.workflow.engine.PostActionConfig
import com.bts.workflow.engine.ValidatorConfig
import com.bts.workflow.engine.WorkflowDefinitionRepository
import com.bts.workflow.jooq.tables.WorkflowPostActions.Companion.WORKFLOW_POST_ACTIONS
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
 * 2. `workflow_transitions` 에서 (id = transition.id, workflow_id) 로 그 워크플로우 소속임을 확인.
 * 3. workflow_validators / workflow_post_actions 에서 transition_id = ? 행을 display_order ASC 정렬 조회.
 *
 * workflowKey 를 필수 1급 인자로 받아 cross-workflow 오매칭(B2)을 방지한다.
 * workflow / transition 중 하나라도 미존재 시 빈 리스트를 반환한다 (예외 아님 — 검증할 게 없으면 통과).
 *
 * ### 왜 (from, to) 가 아니라 [WorkflowTransition.id] 로 찾는가 (FR-WF-05 · F1)
 * V207 이후 같은 상태쌍에 이름이 다른 전환을 여럿 둘 수 있다. (from, to) 로 찾으면 **어느 전환의
 * validator 인지 정할 수 없고**, 출발지가 없는 GLOBAL·INITIAL 전환은 애초에 그 쌍으로 표현되지도
 * 않는다. 전환의 1급 식별자는 `workflow_transitions.id` 이고 [WorkflowRepository] 가 읽어 온
 * [WorkflowTransition.id] 가 바로 그 값이다.
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
     * workflowKey 가 가리키는 workflow 범위 안에서만 transition 을 해석하므로 다른 워크플로우의
     * validator 행이 결과에 포함되지 않는다 (B2 cross-workflow 격리 보장).
     *
     * workflow / transition 미존재 시 빈 리스트 반환 (예외 없음).
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
     * workflowKey 가 가리키는 workflow 범위 안에서만 transition 을 해석하므로 다른 워크플로우의
     * post_action 행이 결과에 포함되지 않는다 (B2 cross-workflow 격리 보장).
     *
     * workflow / transition 미존재 시 빈 리스트 반환 (예외 없음).
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
     * workflowKey + transition 으로부터 workflow_transitions.id 를 해석한다.
     *
     * 단계.
     * 1. workflowKey to workflows.id
     * 2. (transition.id, workflow_id) 로 그 전환이 이 워크플로우 소속인지 확인
     *
     * 2단계가 `workflow_id` 를 함께 거는 것이 B2(cross-workflow 오매칭) 차단 지점이다 —
     * 다른 워크플로우의 전환 id 를 실어 보내도 여기서 걸러진다.
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
            return null
        }
        val resolved =
            dsl
                .select(WORKFLOW_TRANSITIONS.ID)
                .from(WORKFLOW_TRANSITIONS)
                .where(
                    WORKFLOW_TRANSITIONS.ID.eq(transition.id)
                        .and(WORKFLOW_TRANSITIONS.WORKFLOW_ID.eq(workflowId)),
                )
                .fetchOne(WORKFLOW_TRANSITIONS.ID)
        if (resolved == null) {
            log.debug(
                "DefaultWorkflowDefinitionRepository: transition 미존재 workflowKey={} transitionId={}",
                workflowKey,
                transition.id,
            )
        }
        return resolved
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
