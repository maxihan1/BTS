// SpEL 평가용 sealed interface root (Actor) — getter only

package com.bts.workflow.domain.expression

/**
 * SpEL(Spring Expression Language) 평가 컨텍스트에서 액터(요청자) 데이터를 노출하는 루트 인터페이스.
 *
 * ### 설계 원칙
 * - `sealed interface`: 이 모듈 내에서만 구현체를 허용해 외부 위조 방지.
 * - action 메서드 0개: [IssueView] 와 동일하게 getter-only surface 를 유지해
 *   `SimpleEvaluationContext` 안전성을 확보한다.
 *
 * @property userId 액터의 고유 식별자.
 * @property roles 액터에게 부여된 역할 집합. 예: `setOf("MEMBER", "PROJECT_LEAD")`.
 */
sealed interface ActorView {
    val userId: String
    val roles: Set<String>
}

/**
 * [ActorView] 의 기본 구현체.
 *
 * data class 로 선언해 equals/hashCode/toString/copy 를 컴파일러가 자동 생성한다.
 * 사용자 정의 메서드는 추가하지 않아 SpEL surface 를 getter-only 로 유지한다.
 */
data class DefaultActorView(
    override val userId: String,
    override val roles: Set<String>,
) : ActorView
