// 초안의 전환 규칙(validator · post-action)을 발행 시 정규 테이블에 다시 심는다

package com.bts.workflow.application

import com.bts.workflow.domain.WorkflowDraftDefinition
import com.bts.workflow.postaction.PostActionRepository
import com.bts.workflow.validator.ValidatorRepository
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * 발행 시 규칙을 다시 심는다.
 *
 * ### 왜 별도 컴포넌트인가
 * 발행은 전환을 **전량 교체**한다. 전환이 지워지면 매달린 규칙도 FK `ON DELETE CASCADE` 로 함께
 * 사라지므로, 초안 기준으로 다시 심지 않으면 화면에서 걸어 둔 검증기가 발행과 함께 조용히 없어진다.
 *
 * 이 일을 [WorkflowPublishService] 안에 두면 그 서비스가 규칙 리포지토리 2개를 더 들고 있게 되어
 * 협력자가 8개가 된다(detekt `LongParameterList` 임계 7 **이상**에서 발동). 임계를 억제로 넘기는
 * 대신 「규칙을 다시 심는다」는 하나의 책임을 여기로 떼어 냈다.
 */
@Component
class DraftRuleWriter(
    private val validatorRepository: ValidatorRepository,
    private val postActionRepository: PostActionRepository,
) {
    /**
     * 초안의 모든 전환 규칙을 심는다.
     *
     * @param definition 발행 대상 초안 정의.
     * @param transitionIds 초안의 전환 순번 → 새로 생긴 `workflow_transitions.id`.
     *   순번이 맵에 없으면 그 전환은 건너뛴다 — 재작성이 만들지 않은 전환에는 매달 곳이 없다.
     */
    fun writeAll(
        definition: WorkflowDraftDefinition,
        transitionIds: Map<Int, UUID>,
    ) {
        definition.transitions.forEachIndexed { index, transition ->
            val transitionId = transitionIds[index] ?: return@forEachIndexed
            transition.validators.forEachIndexed { order, rule ->
                validatorRepository.insert(transitionId, rule.type, rule.config, order)
            }
            transition.postActions.forEachIndexed { order, rule ->
                postActionRepository.insert(transitionId, rule.type, rule.config, order)
            }
        }
    }
}
