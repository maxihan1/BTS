// 워크플로우 다이어그램 view 모델 type 정의

/** 워크플로우 상태의 카테고리 — backend StateCategory enum 3종과 1:1 대응 */
export type StateCategory = 'TODO' | 'IN_PROGRESS' | 'DONE';

/** 워크플로우 단일 상태의 view 모델 */
export interface WorkflowStateView {
  key: string;
  name: string;
  category: StateCategory;
  displayOrder: number;
}

/** 워크플로우 상태 전이(화살표)의 view 모델 — fromStateKey/toStateKey 는 WorkflowStateView.key 참조 */
export interface WorkflowTransitionView {
  key: string;
  name: string;
  fromStateKey: string;
  toStateKey: string;
}

/** 워크플로우 전체 view 모델 — states + transitions 로 다이어그램 구성 */
export interface WorkflowView {
  key: string;
  name: string;
  description: string;
  states: WorkflowStateView[];
  transitions: WorkflowTransitionView[];
}

/**
 * 두 state key 를 backend WorkflowTransition.key 와 같은 형식으로 합성한다.
 * backend `WorkflowTransition.kt:18` 의 `"${fromStateKey}__$toStateKey"` 와 정확 일치.
 * MSW fixture / production 검증 / 향후 라우팅 시 frontend-backend key 형식 일관성 보장.
 */
export function transitionKey(fromStateKey: string, toStateKey: string): string {
  return `${fromStateKey}__${toStateKey}`
}
