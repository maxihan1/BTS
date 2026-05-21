// 카테고리 차단 Validator — 현재 상태 카테고리가 forbidden 이면 전이 차단

package com.bts.workflow.validator

import com.bts.workflow.domain.StateCategory
import com.bts.workflow.domain.dto.TransitionContext
import com.bts.workflow.domain.spi.ValidatorResult
import com.bts.workflow.domain.spi.WorkflowValidator

/**
 * [TransitionContext.fromState] 의 카테고리가 [forbidden] 과 같으면 전이를 차단하는 Validator.
 *
 * 예를 들어 `forbidden = StateCategory.DONE` 으로 설정하면 완료(DONE) 상태에서는
 * 더 이상 전이할 수 없다. YAML 워크플로우 정의에서 `validators[].type: not-status-category`
 * 로 등록하고 `config.forbidden` 에 카테고리 이름을 지정한다.
 *
 * @param forbidden 이 카테고리에서 출발하는 전이를 차단한다.
 */
class NotStatusCategoryValidator(
    private val forbidden: StateCategory,
) : WorkflowValidator {

    override val type: String = "not-status-category"

    /**
     * [ctx] 의 [TransitionContext.fromState] 카테고리가 [forbidden] 이면 [ValidatorResult.Fail] 을 반환한다.
     * 그 외에는 [ValidatorResult.Pass] 를 반환한다.
     *
     * @param ctx 전이 요청 컨텍스트.
     * @return [ValidatorResult.Pass] 또는 [ValidatorResult.Fail].
     */
    override fun validate(ctx: TransitionContext): ValidatorResult {
        if (ctx.fromState.category == forbidden) {
            return ValidatorResult.Fail(
                field = null,
                reason = "from state category '$forbidden' is forbidden",
            )
        }
        return ValidatorResult.Pass
    }
}
