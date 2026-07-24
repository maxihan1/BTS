// KanbanBoard 단위 테스트 — resolveDropAction 순수 헬퍼 + 컬럼 displayOrder 렌더 + onDragEnd 통합(Task 7)
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { DndContext } from '@dnd-kit/core'
import type { Active, Announcements, DragEndEvent, Over } from '@dnd-kit/core'
import { createElement } from 'react'
import type { ReactNode } from 'react'
import type { BoardDetail } from '@/api/boards'
import type { CardAssigneeDisplay } from './BoardCard'

// useMoveCard mock — mutate spy(shared) 노출 + boardId/filter 인자 캡처 (Task6 통합 검증)
const capturedMoveCardArgs: Array<[string, unknown]> = []
const moveCardMutateSpy = vi.fn()
vi.mock('@/hooks/use-move-card', () => ({
  useMoveCard: (boardId: string, filter?: unknown) => {
    capturedMoveCardArgs.push([boardId, filter])
    return { mutate: moveCardMutateSpy, isPending: false }
  },
}))

// useReorderCard mock — mutate spy(shared) 노출 + boardId/filter 인자 캡처 (Task 7 reorder 분기 검증)
const capturedReorderCardArgs: Array<[string, unknown]> = []
const reorderCardMutateSpy = vi.fn()
vi.mock('@/hooks/use-reorder-card', () => ({
  useReorderCard: (boardId: string, filter?: unknown) => {
    capturedReorderCardArgs.push([boardId, filter])
    return { mutate: reorderCardMutateSpy, isPending: false }
  },
}))

// useResolutions mock
vi.mock('@/hooks/use-resolutions', () => ({
  useResolutions: () => ({ data: [] }),
}))

// TanStack Router Link mock
vi.mock('@tanstack/react-router', () => ({
  Link: ({
    children,
    className,
    onClick,
  }: {
    children: ReactNode
    className?: string
    onClick?: React.MouseEventHandler
  }) => (
    <a className={className} onClick={onClick} data-testid="issue-link">
      {children}
    </a>
  ),
}))

// @dnd-kit/core — DndContext 이벤트를 테스트에서 직접 트리거하기 위해 부분 mock
// (BacklogBoard.test.tsx 선례와 동일 패턴 — PointerSensor 실제 입력 이벤트 없이 onDragEnd 시뮬레이션)
let capturedOnDragEnd: ((event: DragEndEvent) => void) | undefined
let capturedAnnouncements: Announcements | undefined

vi.mock('@dnd-kit/core', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@dnd-kit/core')>()
  return {
    ...actual,
    DndContext: ({
      children,
      onDragEnd,
      accessibility,
    }: {
      children: ReactNode
      onDragEnd?: (event: DragEndEvent) => void
      accessibility?: { announcements?: Announcements }
    }) => {
      capturedOnDragEnd = onDragEnd
      capturedAnnouncements = accessibility?.announcements
      return createElement('div', { 'data-testid': 'dnd-context' }, children)
    },
  }
})

// KanbanBoard + resolveDropAction
import { KanbanBoard, resolveDropAction } from './KanbanBoard'
import type { BoardCardFilterParams } from '@/api/boards'

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const COL_TODO = '00000000-0000-4000-8000-000000000010'
const COL_INPROGRESS = '00000000-0000-4000-8000-000000000020'
const COL_DONE = '00000000-0000-4000-8000-000000000030'

const boardFixture: BoardDetail = {
  boardId: '00000000-0000-4000-8000-000000000001',
  projectKey: 'ATLAS',
  name: 'ATLAS 보드',
  truncated: false,
  unplacedCount: 0,
  swimlaneField: 'NONE',
  quickFilters: [],
  columns: [
    {
      columnId: COL_TODO,
      stateKey: 'todo',
      name: '할 일',
      category: 'TODO',
      displayOrder: 1,
      wipLimit: null,
      wipExceeded: false,
      cards: [
        { issueKey: 'ATLAS-1', summary: '첫 번째 이슈', assigneeId: null, version: 1, priority: 1, epicKey: null, rank: null },
      ],
    },
    {
      columnId: COL_DONE,
      stateKey: 'done',
      name: '완료',
      category: 'DONE',
      displayOrder: 3,
      wipLimit: null,
      wipExceeded: false,
      cards: [
        { issueKey: 'ATLAS-3', summary: '완료된 이슈', assigneeId: null, version: 5, priority: 1, epicKey: null, rank: null },
      ],
    },
    {
      columnId: COL_INPROGRESS,
      stateKey: 'in-progress',
      name: '진행 중',
      category: 'IN_PROGRESS',
      displayOrder: 2,
      wipLimit: null,
      wipExceeded: false,
      cards: [
        { issueKey: 'ATLAS-2', summary: '두 번째 이슈', assigneeId: null, version: 3, priority: 1, epicKey: null, rank: null },
      ],
    },
  ],
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return function Wrapper({ children }: { children: ReactNode }) {
    return createElement(QueryClientProvider, { client: queryClient }, children)
  }
}

function renderBoard(
  board: BoardDetail = boardFixture,
  assigneeNames?: Map<string, CardAssigneeDisplay>,
  filter?: BoardCardFilterParams,
) {
  const names = assigneeNames ?? new Map<string, CardAssigneeDisplay>()
  const wrapper = createWrapper()
  return render(
    createElement(DndContext, {}, createElement(KanbanBoard, {
      boardId: boardFixture.boardId,
      board,
      assigneeNames: names,
      ...(filter !== undefined ? { filter } : {}),
    })),
    { wrapper },
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// S1. resolveDropAction 순수 헬퍼 단위 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('resolveDropAction — S1 순수 헬퍼', () => {
  it('S1a: over가 null이면 no-op을 반환한다', () => {
    const active = { id: 'ATLAS-1', data: { current: { fromColumnId: COL_TODO } } }
    const result = resolveDropAction(boardFixture, active, null)
    expect(result).toEqual({ type: 'noop' })
  })

  it('S1b: 같은 컬럼으로 드롭하면 no-op을 반환한다 (EC1)', () => {
    const active = { id: 'ATLAS-1', data: { current: { fromColumnId: COL_TODO } } }
    const over = { id: COL_TODO }
    const result = resolveDropAction(boardFixture, active, over)
    expect(result).toEqual({ type: 'noop' })
  })

  it('S1c: 다른 컬럼(non-DONE)으로 드롭하면 move를 반환하고 expectedVersion을 포함한다', () => {
    const active = { id: 'ATLAS-1', data: { current: { fromColumnId: COL_TODO } } }
    const over = { id: COL_INPROGRESS }
    const result = resolveDropAction(boardFixture, active, over)
    expect(result).toEqual({
      type: 'move',
      issueKey: 'ATLAS-1',
      fromColumnId: COL_TODO,
      toColumnId: COL_INPROGRESS,
      expectedVersion: 1,
    })
  })

  it('S1d: DONE 컬럼으로 드롭하면 needs-resolution을 반환한다', () => {
    const active = { id: 'ATLAS-1', data: { current: { fromColumnId: COL_TODO } } }
    const over = { id: COL_DONE }
    const result = resolveDropAction(boardFixture, active, over)
    expect(result).toEqual({
      type: 'needs-resolution',
      issueKey: 'ATLAS-1',
      fromColumnId: COL_TODO,
      toColumnId: COL_DONE,
      expectedVersion: 1,
    })
  })

  it('S1e: active 카드가 board에 없으면 no-op을 반환한다', () => {
    const active = { id: 'ATLAS-999', data: { current: { fromColumnId: COL_TODO } } }
    const over = { id: COL_INPROGRESS }
    const result = resolveDropAction(boardFixture, active, over)
    expect(result).toEqual({ type: 'noop' })
  })

  it('S1f: over.id가 존재하지 않는 컬럼이면 no-op을 반환한다', () => {
    const active = { id: 'ATLAS-1', data: { current: { fromColumnId: COL_TODO } } }
    const over = { id: 'nonexistent-col-id' }
    const result = resolveDropAction(boardFixture, active, over)
    expect(result).toEqual({ type: 'noop' })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S2. KanbanBoard 컴포넌트 렌더
// ─────────────────────────────────────────────────────────────────────────────

describe('KanbanBoard — S2 컬럼 렌더', () => {
  beforeEach(() => {
    capturedMoveCardArgs.length = 0
    capturedReorderCardArgs.length = 0
    moveCardMutateSpy.mockClear()
    reorderCardMutateSpy.mockClear()
    capturedOnDragEnd = undefined
    capturedAnnouncements = undefined
  })

  it('S2a: 컬럼들을 displayOrder 오름차순으로 렌더한다', () => {
    renderBoard()
    // 컬럼 헤더 이름이 displayOrder 순서(1→2→3)로 DOM에 나타나야 함
    const columnHeaders = screen.getAllByRole('group')
    const columnNames = columnHeaders.map((el) => el.getAttribute('aria-label') ?? '')
    // '할 일'(1) → '진행 중'(2) → '완료'(3) 순서
    const todoIdx = columnNames.findIndex((n) => n.includes('할 일'))
    const inprogressIdx = columnNames.findIndex((n) => n.includes('진행 중'))
    const doneIdx = columnNames.findIndex((n) => n.includes('완료'))
    expect(todoIdx).toBeLessThan(inprogressIdx)
    expect(inprogressIdx).toBeLessThan(doneIdx)
  })

  it('S2b: 세 컬럼이 모두 렌더된다', () => {
    renderBoard()
    expect(screen.getByText('할 일')).toBeInTheDocument()
    expect(screen.getByText('진행 중')).toBeInTheDocument()
    expect(screen.getByText('완료')).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S3. KanbanBoard filter prop → useMoveCard 전달 검증 (FR-BD-02 Task 6)
// ─────────────────────────────────────────────────────────────────────────────

describe('KanbanBoard — S3 filter prop 전달', () => {
  beforeEach(() => {
    capturedMoveCardArgs.length = 0
    capturedReorderCardArgs.length = 0
    moveCardMutateSpy.mockClear()
    reorderCardMutateSpy.mockClear()
    capturedOnDragEnd = undefined
    capturedAnnouncements = undefined
  })

  /**
   * S3a: filter prop을 전달하면 useMoveCard가 (boardId, filter)로 호출된다.
   */
  it('S3a: filter prop이 있으면 useMoveCard(boardId, filter)로 호출된다', () => {
    const filter: BoardCardFilterParams = {
      assigneeIds: ['c3d4e5f6-a7b8-4890-abcd-ef1234567893'],
      includeUnassigned: false,
      labels: ['버그'],
      componentIds: [],
    }
    renderBoard(boardFixture, undefined, filter)

    // useMoveCard가 boardId와 filter 모두 받아야 한다
    expect(capturedMoveCardArgs.length).toBeGreaterThan(0)
    const lastCall = capturedMoveCardArgs[capturedMoveCardArgs.length - 1]
    expect(lastCall?.[0]).toBe(boardFixture.boardId)
    expect(lastCall?.[1]).toEqual(filter)
  })

  /**
   * S3b: filter prop 없이 호출하면 useMoveCard가 (boardId, undefined)로 호출된다
   * (기존 호출 호환).
   */
  it('S3b: filter prop 없으면 useMoveCard(boardId, undefined)로 호출된다 (기존 호환)', () => {
    renderBoard()

    expect(capturedMoveCardArgs.length).toBeGreaterThan(0)
    const lastCall = capturedMoveCardArgs[capturedMoveCardArgs.length - 1]
    expect(lastCall?.[0]).toBe(boardFixture.boardId)
    expect(lastCall?.[1]).toBeUndefined()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S4/S5 공용 헬퍼 — 최소 필드로 dnd-kit Active/Over/DragEndEvent를 구성한다
// (BacklogBoard.test.tsx triggerDragEnd 선례와 동일 패턴 — 실제 pointer 이벤트 없이 시뮬레이션)
// ─────────────────────────────────────────────────────────────────────────────

/** 최소 필드만 채운 가짜 Active — id + data.current(fromColumnId 등)만 실제로 쓰인다 */
function fakeActive(id: string, dataCurrent: Record<string, unknown>): Active {
  return {
    id,
    data: { current: dataCurrent },
    rect: { current: { initial: null, translated: null } },
  } as unknown as Active
}

/** 최소 필드만 채운 가짜 Over — id만 실제로 쓰인다 */
function fakeOver(id: string): Over {
  return {
    id,
    data: { current: {} },
    rect: { width: 0, height: 0, top: 0, left: 0, bottom: 0, right: 0 },
    disabled: false,
  } as unknown as Over
}

/**
 * DndContext mock을 통해 onDragEnd를 트리거한다.
 *
 * @param active 드래그 중이던 카드(id + fromColumnId)
 * @param overId 드롭 대상 id(컬럼 UUID 또는 카드 issueKey). null이면 드롭 영역 밖
 */
function triggerDragEnd(active: { id: string; fromColumnId: string }, overId: string | null): void {
  act(() => {
    capturedOnDragEnd?.({
      active: fakeActive(active.id, { fromColumnId: active.fromColumnId }),
      over: overId !== null ? fakeOver(overId) : null,
      collisions: null,
      delta: { x: 0, y: 0 },
      activatorEvent: new PointerEvent('pointerdown'),
    } as unknown as DragEndEvent)
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// S4. onDragEnd 통합 — reorder/move/needs-resolution/noop 분기 (FR-UX-06 PR21 Task 7)
// ─────────────────────────────────────────────────────────────────────────────

// TODO 컬럼에 카드 2개(ATLAS-1, ATLAS-4)를 둔 보드 — 셀 내 순서변경(reorder)을 유발하기 위한 픽스처.
// boardFixture는 컬럼당 카드 1개뿐이라 reorder 분기(이웃 카드 필요)를 트리거할 수 없다.
const boardWithTwoCardsInTodo: BoardDetail = {
  ...boardFixture,
  columns: boardFixture.columns.map((col) =>
    col.columnId === COL_TODO
      ? {
          ...col,
          cards: [
            ...col.cards,
            { issueKey: 'ATLAS-4', summary: '네 번째 이슈', assigneeId: null, version: 2, priority: 1, epicKey: null, rank: null },
          ],
        }
      : col,
  ),
}

describe('KanbanBoard — S4 onDragEnd 분기 (Task 7)', () => {
  beforeEach(() => {
    capturedMoveCardArgs.length = 0
    capturedReorderCardArgs.length = 0
    moveCardMutateSpy.mockClear()
    reorderCardMutateSpy.mockClear()
    capturedOnDragEnd = undefined
    capturedAnnouncements = undefined
  })

  it('S4a: 같은 셀 내 순서변경(reorder)이면 useReorderCard.mutate가 이웃 issueKey와 함께 호출된다', () => {
    renderBoard(boardWithTwoCardsInTodo)

    triggerDragEnd({ id: 'ATLAS-1', fromColumnId: COL_TODO }, 'ATLAS-4')

    expect(reorderCardMutateSpy).toHaveBeenCalledWith({
      issueKey: 'ATLAS-1',
      columnId: COL_TODO,
      previousIssueKey: 'ATLAS-4',
      nextIssueKey: undefined,
    })
    expect(moveCardMutateSpy).not.toHaveBeenCalled()
  })

  it('S4b: 다른 컬럼(non-DONE)으로 이동(move)이면 기존대로 useMoveCard.mutate가 호출된다 (무회귀)', () => {
    renderBoard()

    triggerDragEnd({ id: 'ATLAS-1', fromColumnId: COL_TODO }, COL_INPROGRESS)

    expect(moveCardMutateSpy).toHaveBeenCalledWith(
      {
        issueKey: 'ATLAS-1',
        fromColumnId: COL_TODO,
        toColumnId: COL_INPROGRESS,
        expectedVersion: 1,
      },
      expect.objectContaining({ onError: expect.any(Function) }),
    )
    expect(reorderCardMutateSpy).not.toHaveBeenCalled()
  })

  it('S4c: DONE 컬럼으로 이동이면 resolution 모달이 열리고 mutate는 아직 호출되지 않는다 (무회귀)', () => {
    renderBoard()

    triggerDragEnd({ id: 'ATLAS-1', fromColumnId: COL_TODO }, COL_DONE)

    expect(moveCardMutateSpy).not.toHaveBeenCalled()
    expect(reorderCardMutateSpy).not.toHaveBeenCalled()
    expect(screen.getByText('해결 방안 선택')).toBeInTheDocument()
  })

  it('S4d: noop(제자리 드롭)이면 어떤 mutate도 호출되지 않는다', () => {
    renderBoard()

    triggerDragEnd({ id: 'ATLAS-1', fromColumnId: COL_TODO }, COL_TODO)

    expect(moveCardMutateSpy).not.toHaveBeenCalled()
    expect(reorderCardMutateSpy).not.toHaveBeenCalled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S5. 접근성 announcements (DR2, FR-UX-06 PR21 Task 7)
// ─────────────────────────────────────────────────────────────────────────────

describe('KanbanBoard — S5 접근성 announcements (DR2)', () => {
  beforeEach(() => {
    capturedOnDragEnd = undefined
    capturedAnnouncements = undefined
  })

  it('S5a: DndContext accessibility.announcements가 한국어 문구로 주입된다 (더 이상 undefined 아님)', () => {
    renderBoard(boardWithTwoCardsInTodo)

    expect(capturedAnnouncements).toBeDefined()
    const announcements = capturedAnnouncements
    if (announcements === undefined) return

    // 집기 — 카드 정보를 포함한 한국어 공지
    expect(
      announcements.onDragStart({
        active: fakeActive('ATLAS-1', { fromColumnId: COL_TODO }),
      }),
    ).toContain('카드를 집었습니다')

    // 컬럼 이동 완료 — "○○ 컬럼으로 이동했습니다"
    expect(
      announcements.onDragEnd({
        active: fakeActive('ATLAS-1', { fromColumnId: COL_TODO }),
        over: fakeOver(COL_INPROGRESS),
      }),
    ).toContain('이동했습니다')

    // 순서변경 완료 — "순서를 변경했습니다"
    expect(
      announcements.onDragEnd({
        active: fakeActive('ATLAS-1', { fromColumnId: COL_TODO }),
        over: fakeOver('ATLAS-4'),
      }),
    ).toContain('순서를 변경했습니다')

    // 취소
    expect(
      announcements.onDragCancel({
        active: fakeActive('ATLAS-1', { fromColumnId: COL_TODO }),
        over: null,
      }),
    ).toContain('취소')

    // 드래그 오버 중 — 문자열을 반환한다(구체 문구는 onDragOver 자유, 공지 자체 존재만 보장)
    expect(
      typeof announcements.onDragOver({
        active: fakeActive('ATLAS-1', { fromColumnId: COL_TODO }),
        over: fakeOver(COL_INPROGRESS),
      }),
    ).toBe('string')
  })
})
