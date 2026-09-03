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
 * ★**규칙 정본은 `@/lib/transition-key` 하나다.** 종전에는 이 파일이 NORMAL 규칙을,
 * `mocks/workflow-fixtures.ts` 가 GLOBAL·INITIAL 규칙을, 그리고 그 픽스처의 테스트가 둘을
 * **인라인으로 다시** 갖고 있었다 — 셋 중 어느 둘을 대조해도 프론트 사본끼리의 대조라
 * 규칙이 통째로 틀려도 초록이었다. 여기서는 정본을 다시 내보내기만 한다.
 */
export { transitionKey } from '@/lib/transition-key'
