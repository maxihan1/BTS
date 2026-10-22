// 필수 필드 Validator — 이슈 필드가 null / whitespace / empty 면 전환 차단

package com.bts.workflow.validator

import com.bts.workflow.domain.dto.TransitionContext
import com.bts.workflow.domain.spi.ValidatorPhase
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
class RequiredFieldValidator(val field: String) : WorkflowValidator {
    override val type: String = "RequiredField"

    companion object {
        /**
         * 엔진이 실제로 값을 받는 필드 — issue-tracking `IssueApplicationService` 가
         * `issueFields` 에 넣는 키 전수다(WF-03-2). 여기 없는 필드명은 값이 늘 null 이라 그 전환이
         * 영영 막히므로 편집 관문([com.bts.workflow.application.TransitionRuleGuard])이 거절한다.
         * 엔진 입력을 넓히면 여기 한 줄을 더한다.
         */
        val ENGINE_FIELDS: Set<String> = setOf("summary", "resolution")

        /** 거부 사유에 넣을 화면 이름 — 프론트 `i18n/ko.ts` 의 필드 라벨과 같은 말을 쓴다. */
        private val FIELD_LABELS: Map<String, String> = mapOf("summary" to "제목", "resolution" to "해결 결과")
    }

    /**
     * 이 Validator 는 실제 전환 실행 시에만 평가되는 EXECUTION 게이트다.
     * 필드 입력은 전환 목록 조회가 아니라 전환 실행 화면에서 강제한다.
     */
    override val phase: ValidatorPhase get() = ValidatorPhase.EXECUTION

    /**
     * [field] 에 해당하는 이슈 필드 값을 확인하고 전환 허용 여부를 반환한다.
     *
     * @param ctx 전환 요청 컨텍스트.
     * @return 필드가 채워져 있으면 [ValidatorResult.Pass], 그렇지 않으면 [ValidatorResult.Fail].
     */
    override fun validate(ctx: TransitionContext): ValidatorResult {
        val value = ctx.request.issueFields[field]
        val blank = value == null || (value is String && value.trim().isEmpty())
        if (!blank) return ValidatorResult.Pass
        val label = FIELD_LABELS[field] ?: field
        return ValidatorResult.Fail(field = field, reason = "'$label' 항목을 채워야 이 전환을 할 수 있습니다.")
    }
}
