// SpEL 평가용 sealed interface root (Issue) — getter only, action 메서드 0개로 SimpleEvaluationContext 안전성 확보

package com.bts.workflow.domain.expression

/**
 * SpEL(Spring Expression Language) 평가 컨텍스트에서 이슈 데이터를 노출하는 루트 인터페이스.
 *
 * ### 설계 원칙
 * - `sealed interface`: 이 모듈 내에서만 구현체를 허용해 외부 위조 방지.
 * - action 메서드 0개: `SimpleEvaluationContext.forReadOnlyDataBinding()` 와 함께 사용할 때
 *   메서드 호출이 차단되고 property getter 만 평가되도록 surface 를 getter-only 로 유지.
 *
 * @property key 이슈 식별 키. 예: "PROJ-42".
 * @property priority 이슈 우선순위. 예: "HIGH", "MEDIUM", "LOW".
 * @property fields 커스텀 필드 맵. 키는 필드 이름, 값은 임의 타입.
 */
sealed interface IssueView {
    val key: String
    val priority: String
    val fields: Map<String, Any?>
}

/**
 * [IssueView] 의 기본 구현체.
 *
 * data class 로 선언해 equals/hashCode/toString/copy 를 컴파일러가 자동 생성한다.
 * 사용자 정의 메서드는 추가하지 않아 SpEL surface 를 getter-only 로 유지한다.
 */
data class DefaultIssueView(
    override val key: String,
    override val priority: String,
    override val fields: Map<String, Any?>,
) : IssueView
