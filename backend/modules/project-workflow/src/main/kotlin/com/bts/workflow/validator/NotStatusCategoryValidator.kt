// 카테고리 차단 Validator — 현재 상태 카테고리가 forbidden 이면 전환 차단

package com.bts.workflow.validator

import com.bts.workflow.domain.StateCategory
import com.bts.workflow.domain.dto.TransitionContext
import com.bts.workflow.domain.spi.ValidatorResult
import com.bts.workflow.domain.spi.WorkflowValidator

/**
 * [TransitionContext.fromState] 의 카테고리가 [forbidden] 과 같으면 전환을 차단하는 Validator.
 *
 * 예를 들어 `forbidden = StateCategory.DONE` 으로 설정하면 완료(DONE) 상태에서는
 * 더 이상 전환할 수 없다.
 *
 * ### config 키는 `category` 다 (생성자 파라미터 이름과 다르다)
 * YAML 워크플로우 정의와 전환 규칙 CRUD API 는 `validators[].type: not-status-category` 로
 * 등록하고 **`config.category`** 에 [StateCategory] 이름을 지정한다. 그 값을
 * `engine/DefaultWorkflowValidatorFactory.createNotStatusCategory` 가 읽어
 * 이 클래스의 생성자 파라미터 [forbidden] 으로 넘긴다 — 두 이름이 다르므로 생성자 쪽 이름을
 * config 키로 그대로 옮겨 적으면 「필수 키 'category' 가 없습니다」로 400 이다.
 *
 * @param forbidden 이 카테고리에서 출발하는 전환을 차단한다.
 */
class NotStatusCategoryValidator(
    private val forbidden: StateCategory,
) : WorkflowValidator {
    override val type: String = "not-status-category"

    /**
     * [ctx] 의 [TransitionContext.fromState] 카테고리가 [forbidden] 이면 [ValidatorResult.Fail] 을 반환한다.
     * 그 외에는 [ValidatorResult.Pass] 를 반환한다.
     *
     * @param ctx 전환 요청 컨텍스트.
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
