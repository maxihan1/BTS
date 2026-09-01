// 초안 타입 ↔ 기존 목록 패널 타입 어댑터 — 패널을 고치지 않고 초안을 그리기 위한 다리
import { transitionKey } from '@/components/workflow/workflow.types'
import type { WorkflowView } from '@/api/workflows'
import type { EditableDraft, EditableTransition } from '@/lib/workflow-draft'
import type { PanelStatus } from './StatusListPanel'

type PanelTransition = WorkflowView['transitions'][number]

/**
 * 초안 전환을 목록 패널이 이해하는 형태로 옮긴다.
 *
 * ### `id` 자리에 **로컬 id** 를 넣는다
 * 패널은 `id` 를 「이 행을 가리키는 값」으로만 쓴다(편집·삭제 콜백의 인자). 초안 전환에는
 * 서버 id 가 없으므로 그 자리에 로컬 id 를 넣으면 패널을 고치지 않고 그대로 쓸 수 있고,
 * 콜백이 돌려주는 값이 곧 리듀서가 필요로 하는 `localId` 가 된다.
 *
 * `key` 는 백엔드 계산 규칙과 같은 방식으로 만든다 — 패널이 React key 로 쓴다.
 */
export function toPanelTransition(transition: EditableTransition): PanelTransition {
  return {
    key:
      transition.from === null
        ? `${transition.kind}__${transition.to}`
        : transitionKey(transition.from, transition.to),
    fromStateKey: transition.from,
    toStateKey: transition.to,
    name: transition.name,
    id: transition.localId,
    kind: transition.kind,
  }
}

/**
 * 초안 상태를 목록 패널이 이해하는 형태로 옮긴다.
 *
 * ### `id` 자리에 **상태 키** 를 넣는다
 * 정규 테이블을 직접 고치던 시절에는 편성 API 가 카탈로그 UUID 를 받아서 조인이 필요했다.
 * 초안은 상태를 **키로** 다루므로 그 조인이 통째로 사라진다 — 카탈로그에 없는 상태 때문에
 * 순서 변경이 막히던 자리도 함께 없어진다.
 */
export function toPanelStatuses(draft: EditableDraft): PanelStatus[] {
  return [...draft.states]
    .sort((a, b) => a.displayOrder - b.displayOrder)
    .map((state) => ({ id: state.key, key: state.key, name: state.name, category: state.category }))
}

/** 상태 키 → 표시 이름. 전환 목록이 경로를 사람 말로 그릴 때 쓴다. */
export function toStateNames(draft: EditableDraft): Record<string, string> {
  return Object.fromEntries(draft.states.map((s) => [s.key, s.name]))
}
