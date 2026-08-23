// 워크플로우 다이어그램 view 모델 type — 정본은 `@/api/workflows` Zod 응답 스키마 하나뿐이다

import type { z } from 'zod'
import type {
  stateCategorySchema,
  transitionKindSchema,
  workflowStateViewSchema,
  workflowTransitionViewSchema,
} from '@/api/workflows'

// ─────────────────────────────────────────────────────────────────────────────
// 왜 손으로 쓴 interface 를 지웠나.
//
// 종전에는 같은 모양이 두 벌 있었다 — 여기의 `interface` 와 `@/api/workflows` 의 Zod 스키마.
// 둘이 서로를 검사하지 않으므로 한쪽만 바뀌면 다른 쪽이 조용히 썩는다. 실제로 응답 스키마가
// `fromStateKey` 를 nullable 로 완화하고 `id`·`kind` 를 더했을 때 이 파일이 따라오지 않아
// 렌더 계층이 null 에서 TypeError 를 냈다 (`two-lists-never-check-each-other` 양식).
//
// 처방은 「맞춰 쓰기」가 아니라 「한 벌로 줄이기」다. 응답을 실제로 검증하는 Zod 스키마가 정본이고
// 이 파일은 그 추론 타입을 다시 내보내기만 한다. 스키마에 필드가 늘면 여기는 자동으로 따라온다.
// ─────────────────────────────────────────────────────────────────────────────

/** 워크플로우 전체 view 모델 — states + transitions 로 다이어그램 구성 */
export type { WorkflowView } from '@/api/workflows'

/** 워크플로우 상태의 카테고리 — backend StateCategory enum 3종과 1:1 대응 */
export type StateCategory = z.infer<typeof stateCategorySchema>

/** 전환 종류 — backend TransitionKind enum 3종과 1:1 대응 */
export type TransitionKind = z.infer<typeof transitionKindSchema>

/** 워크플로우 단일 상태의 view 모델 */
export type WorkflowStateView = z.infer<typeof workflowStateViewSchema>

/**
 * 워크플로우 상태 전환(화살표)의 view 모델.
 *
 * `fromStateKey` 는 nullable 이다 — GLOBAL(어느 상태에서나)·INITIAL(이슈 생성 진입) 은 출발 상태가 없다.
 * 두 종류는 의미가 정반대이므로 null 하나로 구별할 수 없고 [TransitionKind] 로 갈라야 한다.
 */
export type WorkflowTransitionView = z.infer<typeof workflowTransitionViewSchema>

/**
 * 두 state key 를 backend `WorkflowTransition.key` 의 **NORMAL 분기**와 같은 형식으로 합성한다.
 *
 * 적용 범위가 NORMAL 뿐인 이유. backend 게터는 종류마다 다른 규칙을 쓴다 —
 * NORMAL 은 `from__to`, GLOBAL·INITIAL 은 `KIND__to` 다. 출발 상태가 없는 전환의 키를
 * 프론트에서 합성하려 들면 규칙을 두 번째로 구현하게 되고, 그 순간 backend 게터와 서로를
 * 검사하지 않는 두 벌이 다시 생긴다. **응답에 실린 `key` 를 그대로 쓰는 것이 정답이고**
 * 이 함수는 MSW fixture 처럼 NORMAL 전환만 만들어 내는 자리에서만 쓴다.
 *
 * @param fromStateKey 출발 상태 키 (NORMAL 전환이므로 항상 존재)
 * @param toStateKey 도착 상태 키
 * @returns `from__to` 형식 문자열
 */
export function transitionKey(fromStateKey: string, toStateKey: string): string {
  return `${fromStateKey}__${toStateKey}`
}
