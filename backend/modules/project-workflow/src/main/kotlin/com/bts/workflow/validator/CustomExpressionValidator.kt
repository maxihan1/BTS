// SpEL 기반 커스텀 표현식 Validator — true/false/timeout 결과를 ValidatorResult 로 매핑

package com.bts.workflow.validator

import com.bts.workflow.domain.dto.TransitionContext
import com.bts.workflow.domain.exception.WorkflowExpressionTimeoutException
import com.bts.workflow.domain.spi.ValidatorResult
import com.bts.workflow.domain.spi.WorkflowValidator
import com.bts.workflow.expression.DefaultSpelRoot
import com.bts.workflow.expression.SpelEvaluator

/**
 * SpEL(Spring Expression Language) 표현식을 평가해 전환 허용 여부를 판정하는 [WorkflowValidator] 구현체.
 *
 * [SpelEvaluator] 에 표현식과 컨텍스트 루트(이슈 뷰 + 액터 뷰)를 전달하고, 반환값에 따라
 * [ValidatorResult.Pass] 또는 [ValidatorResult.Fail] 로 매핑한다.
 *
 * ### timeout 처리
 * [SpelEvaluator] 가 [WorkflowExpressionTimeoutException] 을 던지면 전환을 거부하되
 * 예외를 전파하지 않는다. 거부 사유는 `"expression_timeout"` 으로 고정한다.
 * 원인 예외는 이 KDoc 에만 명시한다 — [ValidatorResult.Fail] 에 cause 필드가 없기 때문이다.
 *
 * @param evaluator SpEL 평가기. 50ms timeout + SimpleEvaluationContext sandbox 를 내장한다.
 * @param expression 평가할 SpEL 표현식 문자열. 예: `"issue.priority == 'HIGH' and actor.roles.contains('DEVELOPER')"`.
 */
class CustomExpressionValidator(
    private val evaluator: SpelEvaluator,
    private val expression: String,
) : WorkflowValidator {
    override val type: String = "CustomExpression"

    /**
     * SpEL 표현식을 평가해 전환 허용 여부를 반환한다.
     *
     * @param ctx 전환 요청 컨텍스트. [com.bts.workflow.domain.expression.IssueView] 와
     *   [com.bts.workflow.domain.expression.ActorView] 를 포함한다.
     * @return 표현식이 true 이면 [ValidatorResult.Pass],
     *   false 이면 [ValidatorResult.Fail] (reason = "expression evaluated to false"),
     *   timeout 이면 [ValidatorResult.Fail] (reason = "expression_timeout").
     *
     * ### SwallowedException 억제 근거
     * [ValidatorResult.Fail] 에 cause 필드가 없어 timeout 예외를 직접 전달할 수 없다.
     * 예외 타입을 명시적으로 포착해 TooGenericExceptionCaught 를 방지하며,
     * swallow 는 의도된 동작이다.
     */
    @Suppress("SwallowedException")
    override fun validate(ctx: TransitionContext): ValidatorResult {
        return try {
            val root = DefaultSpelRoot(issue = ctx.issueView, actor = ctx.actorView)
            if (evaluator.evaluate(expression, root)) {
                ValidatorResult.Pass
            } else {
                ValidatorResult.Fail(field = null, reason = "expression evaluated to false")
            }
        } catch (e: WorkflowExpressionTimeoutException) {
            ValidatorResult.Fail(field = null, reason = "expression_timeout")
        }
    }
}
