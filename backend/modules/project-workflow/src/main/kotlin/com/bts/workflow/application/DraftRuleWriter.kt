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
    private val guard: TransitionRuleGuard,
) {
    /**
     * 초안의 모든 전환 규칙을 심는다. **심기 직전에 관문을 지난다.**
     *
     * 관문을 호출부에만 두면 이 메서드가 관문 없는 두 번째 쓰기 경로로 남는다 — PR #411 리뷰가
     * BLOCKER 로 잡은 것이 정확히 그 형태다. 여기서 거절하면 발행 트랜잭션 전체가 되돌아가므로
     * 부분 반영이 남지 않는다.
     *
     * @param definition 발행 대상 초안 정의.
     * @param transitionIds 초안의 전환 순번 → 새로 생긴 `workflow_transitions.id`.
     *   순번이 맵에 없으면 그 전환은 건너뛴다 — 재작성이 만들지 않은 전환에는 매달 곳이 없다.
     * @throws TransitionRuleRejected 규칙 하나라도 관문을 못 지날 때
     */
    fun writeAll(
        definition: WorkflowDraftDefinition,
        transitionIds: Map<Int, UUID>,
    ) {
        definition.transitions.forEachIndexed { index, transition ->
            val transitionId = transitionIds[index] ?: return@forEachIndexed
            transition.validators.forEachIndexed { order, rule ->
                guard.checkValidator(rule.type, rule.config)
                validatorRepository.insert(transitionId, rule.type, rule.config, order)
            }
            transition.postActions.forEachIndexed { order, rule ->
                guard.checkPostAction(rule.type, rule.config)
                postActionRepository.insert(transitionId, rule.type, rule.config, order)
            }
        }
    }

    /**
     * 심지 않고 관문만 태운다. 초안 저장 시점에 같은 판정을 주기 위한 것이다.
     *
     * 저장에서 통과한 것이 발행에서 터지면 관리자는 고칠 방법을 모르는 막다른 길에 놓인다 —
     * 이 PR 이 상태·전환 invariant 에 대해 `Workflow.of()` 를 두 경로에 함께 태운 이유와 같다.
     *
     * @throws TransitionRuleRejected 규칙 하나라도 관문을 못 지날 때
     */
    fun checkAll(definition: WorkflowDraftDefinition) {
        definition.transitions.forEach { transition ->
            transition.validators.forEach { guard.checkValidator(it.type, it.config) }
            transition.postActions.forEach { guard.checkPostAction(it.type, it.config) }
        }
    }
}
