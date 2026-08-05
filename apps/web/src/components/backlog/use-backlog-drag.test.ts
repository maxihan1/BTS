// useBacklogDrag 의 이동 0 드롭 차단 배선 검증 (FR-UX-13 F15 T-KB-3)
//
// ★왜 훅 단위로 따로 재나.
// `isZeroMoveDrop` 자체의 참·거짓은 `lib/backlog-keyboard-coordinates.test.ts` 가 이미 잰다.
// 여기서 재는 것은 **그 판정이 handleDragEnd 에 실제로 꽂혀 있는가**다. 순수 함수가 맞아도
// 호출부에 없으면 사용자에게는 아무 변화가 없다 — 「봉합이 절반」 양식을 닫는다.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook } from '@testing-library/react'
import type { DragEndEvent } from '@dnd-kit/core'
import { useBacklogDrag } from './use-backlog-drag'
import type { BacklogView } from '@/api/backlog'
import type { BacklogDragData } from './BacklogCard'

const mockRerankMutate = vi.fn()
const mockAssignMutate = vi.fn()
const mockUnassignMutate = vi.fn()

vi.mock('@/hooks/use-backlog', () => ({
  useRerankIssue: () => ({ mutate: mockRerankMutate, isPending: false }),
  useAssignToSprint: () => ({ mutate: mockAssignMutate, isPending: false }),
  useUnassignFromSprint: () => ({ mutate: mockUnassignMutate, isPending: false }),
}))

vi.mock('sonner', () => ({
  toast: { error: vi.fn(), warning: vi.fn(), success: vi.fn() },
}))

/** 백로그 3건짜리 최소 뷰. 카드 1건이면 `noop-move` 로 새어 가드가 공허해진다 */
const VIEW: BacklogView = {
  backlog: ['ATLAS-1', 'ATLAS-2', 'ATLAS-3'].map((key, index) => ({
    key,
    summary: key,
    currentStateKey: 'open',
    assigneeId: null,
    priority: 1,
    rank: `0|${String.fromCharCode(97 + index)}:`,
    version: 0,
    epicKey: null,
  })),
  sprints: [],
  truncated: false,
}

const ACTIVE_DATA: BacklogDragData = {
  issueKey: 'ATLAS-1',
  context: 'backlog',
  sprintId: null,
}

/**
 * 키보드로 시작된 드래그가 백로그 칸 droppable 위에서 끝난 이벤트를 만든다.
 *
 * 칸 droppable 이므로 `dropIndex = orderedKeys.length` — 즉 **맨 뒤로 보내기**이며,
 * 가드가 없으면 rerank mutation 이 반드시 한 번 발사된다. 그래야 「0회」 단언이 뭔가를 잰다.
 *
 * `activatorEvent` 는 실제 `KeyboardSensor` 가 싣는 것과 같은 `KeyboardEvent` 다
 * (dnd-kit 은 활성화 핸들러의 `nativeEvent` 를 그대로 넘긴다).
 */
function keyboardDragEndAt(delta: { x: number; y: number }): DragEndEvent {
  return {
    active: { id: 'backlog:ATLAS-1', data: { current: ACTIVE_DATA } },
    over: {
      id: 'column:backlog',
      data: {
        current: {
          context: 'backlog',
          sprintId: null,
          orderedKeys: ['ATLAS-1', 'ATLAS-2', 'ATLAS-3'],
        },
      },
    },
    delta,
    activatorEvent: new KeyboardEvent('keydown', { code: 'Space' }),
  } as unknown as DragEndEvent
}

describe('useBacklogDrag — 이동 0 드롭 (T-KB-3)', () => {
  beforeEach(() => {
    mockRerankMutate.mockClear()
    mockAssignMutate.mockClear()
    mockUnassignMutate.mockClear()
  })

  it('이동이 0이면 어떤 mutation 도 호출하지 않는다 (키보드로 집자마자 놓기)', () => {
    const { result } = renderHook(() => useBacklogDrag('ATLAS', VIEW, true))

    result.current.handleDragEnd(keyboardDragEndAt({ x: 0, y: 0 }))

    expect(mockRerankMutate).not.toHaveBeenCalled()
    expect(mockAssignMutate).not.toHaveBeenCalled()
    expect(mockUnassignMutate).not.toHaveBeenCalled()
  })

  it('짝 단언 — 같은 이벤트라도 이동이 있으면 mutation 이 발사된다 (가드가 전부를 막지 않는다)', () => {
    const { result } = renderHook(() => useBacklogDrag('ATLAS', VIEW, true))

    result.current.handleDragEnd(keyboardDragEndAt({ x: 0, y: 120 }))

    // 이 짝이 없으면 `handleDragEnd` 를 통째로 `return` 시켜도 위 테스트가 통과한다.
    // 가드를 지우면 위가 red, 가드를 무조건 참으로 넓히면 이쪽이 red 다.
    expect(mockRerankMutate).toHaveBeenCalledTimes(1)
  })
})
