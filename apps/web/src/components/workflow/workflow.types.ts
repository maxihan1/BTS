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
