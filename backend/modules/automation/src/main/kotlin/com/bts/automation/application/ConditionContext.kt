// 조건 평가 시 var 참조(issue.status 등)를 값으로 해석하는 읽기 전용 컨텍스트 (FR-AT-03 Task 2)

package com.bts.automation.application

/**
 * [com.bts.automation.domain.Condition.Comparison.field] (`issue.status` 등
 * [com.bts.automation.domain.Condition.FIELD_WHITELIST] 소속 `var` 참조)를 실제 값으로 해석하는
 * 읽기 전용 뷰.
 *
 * ## 값 타입 계약
 * 필드 값은 `String`/`Int`/`Boolean`/`List<String>`/`null` 중 하나다. 이 계약은 [ConditionEvaluator]
 * 의 타입 분기(스칼라 deep equal·서수 비교·멤버십 판정)가 전제하는 값 형태이며, 계약을 벗어난 값이
 * 들어와도 [ConditionEvaluator] 는 예외를 던지지 않고 `false` 로 흡수한다(fail-safe).
 *
 * ## 생성 경로
 * 이 Task(FR-AT-03 Task 2)의 테스트는 [of] 로 합성 fixture 맵을 직접 넘겨 컨텍스트를 만든다.
 * 실제 이슈 스냅샷에서 이 맵을 구성하는 `IssueSnapshot → ConditionContext` 어댑팅은 별도 Task 책임이다.
 */
class ConditionContext private constructor(private val fields: Map<String, Any?>) {
    /**
     * [field] 에 대응하는 값을 반환한다.
     *
     * 컨텍스트에 없는 필드(또는 화이트리스트 밖 필드 — 파싱 단계에서 이미 걸러지므로 정상 경로에서는
     * 발생하지 않는다)는 `null` 을 반환한다. 이는 조건 평가 계약의 "누락 필드는 `null` 취급"
     * 규칙([ConditionEvaluator] 클래스 KDoc 참고)과 일치시키기 위함이다.
     *
     * @param field `var` 참조 필드 경로(예: `issue.status`).
     * @return 필드 값. 없으면 `null`.
     */
    fun valueOf(field: String): Any? = fields[field]

    companion object {
        /**
         * [fields] 맵으로 컨텍스트를 만든다.
         *
         * @param fields 필드 경로 → 값(`String`/`Int`/`Boolean`/`List<String>`/`null`) 매핑.
         * @return 읽기 전용 [ConditionContext].
         */
        fun of(fields: Map<String, Any?>): ConditionContext = ConditionContext(fields)
    }
}
