// 워크플로우 전이 정의 (from → to)

package com.bts.workflow.domain

/**
 * 두 상태 사이의 전이(Transition) 정의.
 *
 * 전이 identity = (fromStateKey, toStateKey) — 한 워크플로우 내에서 같은 (from, to) 쌍은 중복 정의 불가.
 * [name] 은 사람 친화 표시 라벨 (예. "Start Work", "Resolve") 로 매칭에 사용 안 함.
 * [key] 가 라우팅/API 호출용 1급 식별자.
 *
 * 결정 근거. ADR `docs/adr/2026-05-28-workflow-transition-identity-policy.md`.
 *
 * @property fromStateKey 전이 출발 상태의 키. [Workflow.states] 집합에 포함돼야 한다.
 * @property toStateKey 전이 도착 상태의 키. [Workflow.states] 집합에 포함돼야 한다.
 * @property name 사람 친화 표시 라벨 (예. "Start Work", "Resolve"). 매칭에 사용 안 함.
 * @property key 전이 고유 식별 키. [fromStateKey]__[toStateKey] 합성 — DB 저장 없이 계산된 값. 라우팅/API 호출용 1급.
 */
data class WorkflowTransition(
    val fromStateKey: String,
    val toStateKey: String,
    val name: String,
) {
    val key: String get() = "${fromStateKey}__$toStateKey"
}
