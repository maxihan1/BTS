// 워크플로우 전이 정의 (from → to)

package com.bts.workflow.domain

/**
 * 두 상태 사이의 전이(Transition) 정의.
 *
 * @property fromStateKey 전이 출발 상태의 키. [Workflow.states] 집합에 포함돼야 한다.
 * @property toStateKey 전이 도착 상태의 키. [Workflow.states] 집합에 포함돼야 한다.
 * @property name 전이 이름 (예. "시작", "완료", "재열기"). (fromStateKey, toStateKey, name) 조합이 [Workflow] 내에서 고유해야 한다.
 */
data class WorkflowTransition(
    val fromStateKey: String,
    val toStateKey: String,
    val name: String,
)
