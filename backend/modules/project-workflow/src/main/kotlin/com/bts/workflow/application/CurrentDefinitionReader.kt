// 지금 발행돼 있는 정의를 초안 형태로 읽는다 — 「편집 시작」의 출발점

package com.bts.workflow.application

import com.bts.workflow.cache.WorkflowCache
import com.bts.workflow.domain.DraftRuleDto
import com.bts.workflow.domain.DraftStateDto
import com.bts.workflow.domain.DraftTransitionDto
import com.bts.workflow.domain.Workflow
import com.bts.workflow.domain.WorkflowDraftDefinition
import com.bts.workflow.postaction.PostActionRepository
import com.bts.workflow.transition.TransitionRuleRow
import com.bts.workflow.validator.ValidatorRepository
import org.springframework.stereotype.Component

/**
 * 현재 발행된 정의를 [WorkflowDraftDefinition] 으로 옮긴다.
 *
 * ### 왜 필요한가
 * 초안이 없는 워크플로우를 편집하려면 출발점이 있어야 한다. Jira 도 편집기를 열면 지금 정의가
 * 채워진 채로 시작한다 — 빈 화면에서 상태부터 다시 만들게 하지 않는다.
 *
 * ### 규칙을 전환마다 따로 읽는다
 * `Workflow` aggregate 는 상태와 전환만 담고 규칙은 담지 않는다. 전환 수가 워크플로우당 수십 개
 * 수준이라 N+1 이 문제 되는 규모가 아니고, 규칙까지 aggregate 에 얹으면 런타임 전이 계산 경로가
 * 매번 쓰지도 않는 config JSONB 를 함께 읽게 된다.
 */
@Component
class CurrentDefinitionReader(
    private val cache: WorkflowCache,
    private val validatorRepository: ValidatorRepository,
    private val postActionRepository: PostActionRepository,
) {
    /**
     * 지금 정의를 초안 형태로 돌려준다. 워크플로우가 없으면 null.
     *
     * @param key 워크플로우 식별 키.
     */
    fun read(key: String): WorkflowDraftDefinition? {
        val workflow = cache.findByKey(key) ?: return null
        return toDefinition(workflow)
    }

    private fun toDefinition(workflow: Workflow): WorkflowDraftDefinition =
        WorkflowDraftDefinition(
            key = workflow.key,
            name = workflow.name,
            description = workflow.description,
            states =
                workflow.states.map {
                    DraftStateDto(
                        key = it.key,
                        name = it.name,
                        category = it.category.name,
                        displayOrder = it.displayOrder,
                    )
                },
            transitions =
                workflow.transitions.map { transition ->
                    DraftTransitionDto(
                        from = transition.fromStateKey,
                        to = transition.toStateKey,
                        name = transition.name,
                        kind = transition.kind.name,
                        validators = validatorRepository.findByTransitionId(transition.id).map { it.toDraftRule() },
                        postActions = postActionRepository.findByTransitionId(transition.id).map { it.toDraftRule() },
                    )
                },
        )
}

/** 규칙 행을 초안 규칙으로. id 는 옮기지 않는다 — 초안의 규칙은 아직 DB 행이 아니다. */
private fun TransitionRuleRow.toDraftRule(): DraftRuleDto = DraftRuleDto(type = type, config = config)
