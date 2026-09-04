// 드래그 두 축 분기 판정 — kind 로 상태 매핑과 컬럼 순서를 가르는가 (부채 177 · 리뷰 B1)
//
// ★리뷰가 「use-column-settings-drag 를 import 하는 테스트가 0건」이라 지적한 자리다.
//   분기를 통째로 지워도(예: kind 검사 제거) 아무 판정이 안 울렸다.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { DragEndEvent } from '@dnd-kit/core'
import type { JSX, ReactNode } from 'react'
import type { BoardDetail, BoardColumn, ColumnState } from '@/api/boards'
import { useColumnSettingsDrag } from './use-column-settings-drag'
import { UNMAPPED_DROP_ID } from './state-mapping-drop'

const mockReplace = vi.fn()
const mockReorder = vi.fn()

vi.mock('@/hooks/use-replace-column-states', () => ({
  useReplaceColumnStates: () => ({ mutate: mockReplace, isPending: false }),
  isStateConflict: () => false,
}))
vi.mock('@/hooks/use-update-column', () => ({
  useReorderColumns: () => ({ mutate: mockReorder, isPending: false }),
  useUpdateColumn: () => ({ mutate: vi.fn(), isPending: false }),
}))

const COL_A = 'b2c3d4e5-f6a7-4901-8bcd-ef1234567891'
const COL_B = 'c3d4e5f6-a7b8-4012-9cde-f01234567892'
const STATE_OPEN: ColumnState = { key: 'open', name: '열림', category: 'TODO' }

function column(columnId: string, keys: string[]): BoardColumn {
  return {
    columnId,
    states: keys.map((key) => ({ ...STATE_OPEN, key, name: key })),
    name: columnId,
    category: 'TODO',
    displayOrder: 0,
    cards: [],
    wipLimit: null,
    wipExceeded: false,
  }
}

const board: BoardDetail = {
  boardId: 'a1b2c3d4-e5f6-4890-abcd-ef1234567890',
  projectKey: 'ATLAS',
  name: 'ATLAS 보드',
  columns: [column(COL_A, ['open']), column(COL_B, ['closed'])],
  truncated: false,
  unplacedCount: 0,
  unmappedStates: [],
  swimlaneField: 'NONE',
  quickFilters: [],
  boardType: 'KANBAN',
  activeSprint: null,
}

function wrapper({ children }: { children: ReactNode }): JSX.Element {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return <QueryClientProvider client={qc}>{children}</QueryClientProvider>
}

/** dnd-kit `DragEndEvent` 의 이 훅이 읽는 부분만 만든다. */
function dragEnd(data: Record<string, unknown>, overId: string | null): DragEndEvent {
  return {
    active: { id: 'x', data: { current: data } },
    over: overId === null ? null : { id: overId },
  } as unknown as DragEndEvent
}

beforeEach(() => {
  vi.clearAllMocks()
})

describe('드래그 두 축 분기 (R5 · R10)', () => {
  it('T-DR-1: kind=column 이면 순서 교체만 부른다', () => {
    const { result } = renderHook(() => useColumnSettingsDrag(board), { wrapper })

    result.current(dragEnd({ kind: 'column', columnId: COL_A }, COL_B))

    expect(mockReorder).toHaveBeenCalledWith([COL_B, COL_A], expect.anything())
    // ★두 축이 섞이면 컬럼을 끌었는데 상태 매핑이 바뀐다. 부정 단언이 그 자리를 지킨다.
    expect(mockReplace).not.toHaveBeenCalled()
  })

  it('T-DR-2: stateKey 가 있으면 상태 매핑만 부른다', () => {
    const { result } = renderHook(() => useColumnSettingsDrag(board), { wrapper })

    result.current(dragEnd({ stateKey: 'open', fromColumnId: COL_A }, UNMAPPED_DROP_ID))

    expect(mockReplace).toHaveBeenCalled()
    expect(mockReorder).not.toHaveBeenCalled()
  })

  it('T-DR-3: 허공에 놓으면 아무것도 부르지 않는다', () => {
    const { result } = renderHook(() => useColumnSettingsDrag(board), { wrapper })

    result.current(dragEnd({ stateKey: 'open', fromColumnId: COL_A }, null))
    result.current(dragEnd({ kind: 'column', columnId: COL_A }, null))

    expect(mockReplace).not.toHaveBeenCalled()
    expect(mockReorder).not.toHaveBeenCalled()
  })

  it('T-DR-4: 알 수 없는 데이터는 던지지 않고 무시한다', () => {
    const { result } = renderHook(() => useColumnSettingsDrag(board), { wrapper })

    // 던지면 드래그 한 번이 화면을 통째로 죽인다.
    expect(() => {
      result.current(dragEnd({}, COL_B))
    }).not.toThrow()
    expect(mockReplace).not.toHaveBeenCalled()
    expect(mockReorder).not.toHaveBeenCalled()
  })
})
