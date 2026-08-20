// 워크플로우 전환 정의 (from → to) — 전환 ID 가 1급 식별자

package com.bts.workflow.domain

import java.util.UUID

/**
 * 두 상태 사이의 전환(Transition) 정의.
 *
 * 전환 identity = [id] (UUID). 같은 (from, to) 쌍에 [name] 이 다른 전환을 여럿 둘 수 있고,
 * 출발 상태가 없는 전환(GLOBAL·INITIAL)도 표현된다. [name] 은 사람 친화 표시 라벨로 매칭에 쓰지 않는다.
 * [key] 는 하위호환용 계산 프로퍼티일 뿐 매칭의 기준이 아니다.
 *
 * 결정 근거. ADR `docs/adr/2026-08-18-workflow-transition-id-identity.md` §D1 · §D2.
 *
 * @property id 전환 1급 식별자. `workflow_transitions.id` 와 같은 값이다.
 * @property fromStateKey 전환 출발 상태의 키. [TransitionKind.NORMAL] 은 필수이고
 *   [TransitionKind.GLOBAL]·[TransitionKind.INITIAL] 은 null 이어야 한다 ([Workflow.of] 가 검증).
 * @property toStateKey 전환 도착 상태의 키. [Workflow.states] 집합에 포함돼야 한다.
 * @property name 사람 친화 표시 라벨 (예. "Start Work", "Resolve"). 매칭에 사용 안 함.
 * @property kind 전환 종류. 기본값 [TransitionKind.NORMAL] — DB 컬럼 기본값과 같다.
 * @property key 하위호환용 계산 키. [key] 는 URL 경로 세그먼트로 소비되므로
 *   (`PostActionController` 의 `.../transitions/{transitionKey}/post-actions`) 경로 문자로 안전한 토큰만 쓴다.
 *   NORMAL 은 종전대로 `from__to`, GLOBAL·INITIAL 은 `KIND__to`.
 */
data class WorkflowTransition(
    val id: UUID,
    val fromStateKey: String?,
    val toStateKey: String,
    val name: String,
    val kind: TransitionKind = TransitionKind.NORMAL,
) {
    val key: String
        get() =
            when (kind) {
                TransitionKind.NORMAL -> "${fromStateKey ?: kind.name}__$toStateKey"
                TransitionKind.GLOBAL, TransitionKind.INITIAL -> "${kind.name}__$toStateKey"
            }
}
