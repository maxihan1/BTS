// 지금 발행돼 있는 정의를 초안 형태로 읽는다 — 「편집 시작」의 출발점

package com.bts.workflow.application

import com.bts.workflow.cache.WorkflowCache
import com.bts.workflow.domain.DraftRuleDto
import com.bts.workflow.domain.DraftStateDto
import com.bts.workflow.domain.DraftTransitionDto
import com.bts.workflow.domain.Workflow
import com.bts.workflow.domain.WorkflowDraftDefinition
import com.bts.workflow.postaction.PostActionRepository
import com.bts.workflow.repository.StatusLayout
import com.bts.workflow.repository.WorkflowRepository
import com.bts.workflow.repository.WorkflowStatusCompositionRepository
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
 * 수준이라 N+1 이 문제 되는 규모가 아니고, 규칙까지 aggregate 에 얹으면 런타임 전환 계산 경로가
 * 매번 쓰지도 않는 config JSONB 를 함께 읽게 된다.
 *
 * ### 다이어그램 좌표도 같은 이유로 따로 읽는다 (FR-WF-07 D8)
 * `Workflow` 와 그 아래 `WorkflowState` · shared-kernel `WorkflowStateView` 는 좌표를 담지
 * **않는다.** 담게 하면 `WorkflowRepository` 의 SELECT 가 넓어져 **모든 전환 계산**이 편집기
 * 전용 컬럼 두 개를 함께 읽는다 — 캐시에 얹혀 상시 메모리에도 남는다.
 *
 * 그래서 좌표는 편성 리포지토리에서 상태 키 기준으로 한 번 더 읽어 여기서 합친다. 이 합침이
 * 빠지면 발행으로 `workflow_statuses` 에 저장된 좌표를 **아무도 되읽지 않아**, 초안을 폐기하거나
 * 발행 뒤 편집기를 새로 열 때마다 사용자가 배치해 둔 다이어그램이 조용히 흐트러진다.
 * 「발행하면 좌표가 실린다」만 재는 판정으로는 그 결함이 초록으로 통과한다.
 */
@Component
class CurrentDefinitionReader(
    private val cache: WorkflowCache,
    private val validatorRepository: ValidatorRepository,
    private val postActionRepository: PostActionRepository,
    private val workflowRepository: WorkflowRepository,
    private val compositionRepository: WorkflowStatusCompositionRepository,
) {
    /**
     * 지금 정의를 초안 형태로 돌려준다. 워크플로우가 없으면 null.
     *
     * @param key 워크플로우 식별 키.
     */
    fun read(key: String): WorkflowDraftDefinition? {
        val workflow = cache.findByKey(key) ?: return null
        // 좌표는 aggregate 에 없어 편성 테이블에서 따로 읽는다. id 를 못 찾는 경우는 캐시를 읽은
        // 직후 그 워크플로우가 지워진 경합뿐이고, 그때는 좌표 없이 그리는 편이 낫다 —
        // 편집기가 자동 배치로 떨어질 뿐 편집 자체는 이어진다.
        val layouts =
            workflowRepository.findIdByKey(key)
                ?.let { compositionRepository.findLayouts(it) }
                ?: emptyMap()
        return toDefinition(workflow, layouts)
    }

    private fun toDefinition(
        workflow: Workflow,
        layouts: Map<String, StatusLayout>,
    ): WorkflowDraftDefinition =
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
                        layoutX = layouts[it.key]?.x,
                        layoutY = layouts[it.key]?.y,
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
