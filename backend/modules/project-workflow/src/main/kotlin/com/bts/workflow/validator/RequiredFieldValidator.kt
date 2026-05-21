// 필수 필드 Validator — 이슈 필드가 null / whitespace / empty 면 전이 차단

package com.bts.workflow.validator

import com.bts.workflow.domain.dto.TransitionContext
import com.bts.workflow.domain.spi.ValidatorResult
import com.bts.workflow.domain.spi.WorkflowValidator

/**
 * 지정된 이슈 필드가 채워져 있는지 검증하는 [WorkflowValidator] 구현체.
 *
 * YAML 워크플로우 정의에서 `validators[].type = "RequiredField"` 로 참조된다.
 * config 예시 (jsonb).
 * ```json
 * { "field": "resolution" }
 * ```
 *
 * 거부 조건.
 * - 필드 값이 `null` 인 경우.
 * - 필드 값이 `String` 이고 trim 후 empty 인 경우 (whitespace 전용 포함).
 *
 * @param field 검사할 이슈 필드 이름. [TransitionContext.request.issueFields] 맵의 키와 일치해야 한다.
 */
class RequiredFieldValidator(private val field: String) : WorkflowValidator {
    override val type: String = "RequiredField"

    /**
     * [field] 에 해당하는 이슈 필드 값을 확인하고 전이 허용 여부를 반환한다.
     *
     * @param ctx 전이 요청 컨텍스트.
     * @return 필드가 채워져 있으면 [ValidatorResult.Pass], 그렇지 않으면 [ValidatorResult.Fail].
     */
    override fun validate(ctx: TransitionContext): ValidatorResult {
        val value = ctx.request.issueFields[field]
        return when {
            value == null -> ValidatorResult.Fail(field = field, reason = "required field missing")
            value is String && value.trim().isEmpty() ->
                ValidatorResult.Fail(field = field, reason = "required field blank")
            else -> ValidatorResult.Pass
        }
    }
}
