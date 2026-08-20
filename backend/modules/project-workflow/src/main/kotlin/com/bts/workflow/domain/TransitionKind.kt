// 전환의 종류 — 일반(NORMAL) · 전역(GLOBAL) · 최초(INITIAL)

package com.bts.workflow.domain

/**
 * 전환의 종류.
 *
 * `fromStateKey = null` 하나로는 전역 전환과 최초 전환을 구별할 수 없다 — 둘 다 출발 상태가 없지만
 * 의미가 정반대다. GLOBAL 은 아무 상태에서나 쓸 수 있고, INITIAL 은 상태가 아직 없을 때 딱 한 번 쓴다.
 * `workflow_transitions.kind` 컬럼과 1:1 대응한다.
 *
 * 결정 근거. ADR `docs/adr/2026-08-18-workflow-transition-id-identity.md` §D2.
 */
enum class TransitionKind {
    /** 일반 전환. 출발 상태([WorkflowTransition.fromStateKey])가 반드시 있다. */
    NORMAL,

    /** 전역 전환. 어떤 상태에서든 쓸 수 있어 출발 상태가 없다. */
    GLOBAL,

    /** 최초 전환. 이슈 생성 시 진입할 상태를 정하며 워크플로우당 최대 1개다. */
    INITIAL,
}
