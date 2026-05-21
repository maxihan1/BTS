// 워크플로우 전이 정의 (from → to)

package com.bts.workflow.domain

/**
 * 두 상태 사이의 전이(Transition) 정의.
 *
 * @property fromStateKey 전이 출발 상태의 키. [Workflow.states] 집합에 포함돼야 한다.
 * @property toStateKey 전이 도착 상태의 키. [Workflow.states] 집합에 포함돼야 한다.
 * @property name 전이 이름 (예. "시작", "완료", "재열기"). (fromStateKey, toStateKey, name) 조합이 [Workflow] 내에서 고유해야 한다.
 * @property key 전이 고유 식별 키. [fromStateKey]__[toStateKey] 합성 — DB 저장 없이 계산된 값. 라우팅/API 호출용.
 */
data class WorkflowTransition(
    val fromStateKey: String,
    val toStateKey: String,
    val name: String,
) {
    val key: String get() = "${fromStateKey}__${toStateKey}"
}
