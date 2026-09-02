// KanbanBoard 단위 테스트 — resolveDropAction 순수 헬퍼 + 컬럼 displayOrder 렌더 + onDragEnd 통합(Task 7)
// + 필드변경 훅 배선 + 드래그 시각 힌트 통합(FR-UX-06 PR21b Task 5)
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, within, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { DndContext } from '@dnd-kit/core'
import type { Active, Announcements, DragEndEvent, DragOverEvent, DragStartEvent, Over } from '@dnd-kit/core'
import { createElement } from 'react'
import type { ReactNode } from 'react'
import type { BoardDetail } from '@/api/boards'
import type { IssueTypeResponse } from '@/api/issue-types'
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

// useChangeCardField mock — mutate spy(shared) 노출 + boardId/filter 인자 캡처 (Task 5 field-change 통합 검증)
const capturedChangeCardFieldArgs: Array<[string, unknown]> = []
const changeCardFieldMutateSpy = vi.fn()
vi.mock('@/hooks/use-change-card-field', () => ({
  useChangeCardField: (boardId: string, filter?: unknown) => {
    capturedChangeCardFieldArgs.push([boardId, filter])
    return { mutate: changeCardFieldMutateSpy, isPending: false }
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
// onDragStart/onDragOver도 캡처한다 — FR-8 드래그 시각 힌트(overColumnId) 통합 검증(Task 5)
let capturedOnDragStart: ((event: DragStartEvent) => void) | undefined
let capturedOnDragOver: ((event: DragOverEvent) => void) | undefined
let capturedOnDragEnd: ((event: DragEndEvent) => void) | undefined
let capturedAnnouncements: Announcements | undefined

vi.mock('@dnd-kit/core', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@dnd-kit/core')>()
  return {
    ...actual,
    DndContext: ({
      children,
      onDragStart,
      onDragOver,
      onDragEnd,
      accessibility,
    }: {
      children: ReactNode
      onDragStart?: (event: DragStartEvent) => void
      onDragOver?: (event: DragOverEvent) => void
      onDragEnd?: (event: DragEndEvent) => void
      accessibility?: { announcements?: Announcements }
    }) => {
      capturedOnDragStart = onDragStart
      capturedOnDragOver = onDragOver
      capturedOnDragEnd = onDragEnd
      capturedAnnouncements = accessibility?.announcements
      return createElement('div', { 'data-testid': 'dnd-context' }, children)
    },
    // 실제 DragOverlay는 dnd-kit 내부 InternalContext(active)에 의존하는데, DndContext를
    // 위처럼 대체하면 그 context가 없어 항상 null을 렌더한다(진짜 드래그 없이는 검증 불가).
    // BoardColumn.test.tsx의 SortableContext 대체와 동일한 패턴 — children을 그대로 통과시켜
    // 고스트 카드(BoardCard) DOM 검증을 가능하게 한다(E9).
    DragOverlay: ({ children }: { children: ReactNode }) =>
      createElement('div', { 'data-testid': 'drag-overlay' }, children),
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
  boardType: 'KANBAN',
  activeSprint: null,
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
        { issueKey: 'ATLAS-1', summary: '첫 번째 이슈', assigneeId: null, version: 1, priority: 1, epicKey: null, rank: null, typeKey: 'task', labels: [], originalEstimateSeconds: null },
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
        { issueKey: 'ATLAS-3', summary: '완료된 이슈', assigneeId: null, version: 5, priority: 1, epicKey: null, rank: null, typeKey: 'task', labels: [], originalEstimateSeconds: null },
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
        { issueKey: 'ATLAS-2', summary: '두 번째 이슈', assigneeId: null, version: 3, priority: 1, epicKey: null, rank: null, typeKey: 'task', labels: [], originalEstimateSeconds: null },
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
  issueTypesByKey?: Map<string, IssueTypeResponse>,
) {
  const names = assigneeNames ?? new Map<string, CardAssigneeDisplay>()
  const types = issueTypesByKey ?? new Map<string, IssueTypeResponse>()
  const wrapper = createWrapper()
  return render(
    createElement(DndContext, {}, createElement(KanbanBoard, {
      boardId: boardFixture.boardId,
      board,
      assigneeNames: names,
      issueTypesByKey: types,
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

/**
 * DndContext mock을 통해 onDragStart를 트리거한다 — FR-8 하이라이트 검증 시
 * activeFromColumnId state를 채우기 위해 onDragOver 전에 호출한다.
 */
function triggerDragStart(active: { id: string; fromColumnId: string }): void {
  act(() => {
    capturedOnDragStart?.({
      active: fakeActive(active.id, { fromColumnId: active.fromColumnId }),
      activatorEvent: new PointerEvent('pointerdown'),
    } as unknown as DragStartEvent)
  })
}

/**
 * DndContext mock을 통해 onDragOver를 트리거한다.
 *
 * @param active 드래그 중인 카드(id + fromColumnId)
 * @param overId 드롭 대상 id(컬럼 UUID 또는 카드 issueKey). null이면 드롭 영역 밖
 */
function triggerDragOver(active: { id: string; fromColumnId: string }, overId: string | null): void {
  act(() => {
    capturedOnDragOver?.({
      active: fakeActive(active.id, { fromColumnId: active.fromColumnId }),
      over: overId !== null ? fakeOver(overId) : null,
      collisions: null,
      delta: { x: 0, y: 0 },
      activatorEvent: new PointerEvent('pointerdown'),
    } as unknown as DragOverEvent)
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
            { issueKey: 'ATLAS-4', summary: '네 번째 이슈', assigneeId: null, version: 2, priority: 1, epicKey: null, rank: null, typeKey: 'task', labels: [], originalEstimateSeconds: null },
          ],
        }
      : col,
  ),
}

describe('KanbanBoard — S4 onDragEnd 분기 (Task 7)', () => {
  beforeEach(() => {
    capturedMoveCardArgs.length = 0
    capturedReorderCardArgs.length = 0
    capturedChangeCardFieldArgs.length = 0
    moveCardMutateSpy.mockClear()
    reorderCardMutateSpy.mockClear()
    changeCardFieldMutateSpy.mockClear()
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
    expect(changeCardFieldMutateSpy).not.toHaveBeenCalled()
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
    expect(changeCardFieldMutateSpy).not.toHaveBeenCalled()
  })

  it('S4c: DONE 컬럼으로 이동이면 resolution 모달이 열리고 mutate는 아직 호출되지 않는다 (무회귀)', () => {
    renderBoard()

    triggerDragEnd({ id: 'ATLAS-1', fromColumnId: COL_TODO }, COL_DONE)

    expect(moveCardMutateSpy).not.toHaveBeenCalled()
    expect(reorderCardMutateSpy).not.toHaveBeenCalled()
    expect(changeCardFieldMutateSpy).not.toHaveBeenCalled()
    expect(screen.getByText('해결 방안 선택')).toBeInTheDocument()
  })

  it('S4d: noop(제자리 드롭)이면 어떤 mutate도 호출되지 않는다', () => {
    renderBoard()

    triggerDragEnd({ id: 'ATLAS-1', fromColumnId: COL_TODO }, COL_TODO)

    expect(moveCardMutateSpy).not.toHaveBeenCalled()
    expect(reorderCardMutateSpy).not.toHaveBeenCalled()
    expect(changeCardFieldMutateSpy).not.toHaveBeenCalled()
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

// ─────────────────────────────────────────────────────────────────────────────
// S6. 필드변경 announcements (FR-7, FR-UX-06 PR21b Task 4)
// 스윔레인 그룹이 다른 카드 위로 드롭 완료/드래그 중일 때 필드변경 공지 문구를 검증한다.
// ─────────────────────────────────────────────────────────────────────────────

const ASSIGNEE_ID_ALICE = '10000000-0000-4000-8000-000000000001'
const ASSIGNEE_ID_BOB = '10000000-0000-4000-8000-000000000002'
const ASSIGNEE_ID_UNRESOLVED = '10000000-0000-4000-8000-000000000003'

/** ASSIGNEE 스윔레인 보드 — TODO 컬럼에 담당자가 다른 카드 4장(김앨리스·박밥·이름조회실패·미배정) */
const assigneeSwimlaneBoard: BoardDetail = {
  ...boardFixture,
  swimlaneField: 'ASSIGNEE',
  columns: boardFixture.columns.map((col) =>
    col.columnId === COL_TODO
      ? {
          ...col,
          cards: [
            { issueKey: 'ATLAS-1', summary: '담당자 변경 대상', assigneeId: ASSIGNEE_ID_ALICE, version: 1, priority: 1, epicKey: null, rank: null, typeKey: 'task', labels: [], originalEstimateSeconds: null },
            { issueKey: 'ATLAS-4', summary: '담당자 박밥', assigneeId: ASSIGNEE_ID_BOB, version: 2, priority: 1, epicKey: null, rank: null, typeKey: 'task', labels: [], originalEstimateSeconds: null },
            { issueKey: 'ATLAS-5', summary: '담당자 이름 조회 실패', assigneeId: ASSIGNEE_ID_UNRESOLVED, version: 3, priority: 1, epicKey: null, rank: null, typeKey: 'task', labels: [], originalEstimateSeconds: null },
            { issueKey: 'ATLAS-6', summary: '미배정', assigneeId: null, version: 4, priority: 1, epicKey: null, rank: null, typeKey: 'task', labels: [], originalEstimateSeconds: null },
          ],
        }
      : col,
  ),
}

// ATLAS-5는 의도적으로 map에 없음 — 이름 조회 실패("미배정" 폴백) 시나리오
const assigneeSwimlaneNames = new Map<string, CardAssigneeDisplay>([
  ['ATLAS-1', { state: 'named', name: '김앨리스' }],
  ['ATLAS-4', { state: 'named', name: '박밥' }],
])

/** PRIORITY 스윔레인 보드 — TODO 컬럼에 우선순위가 다른 카드 2장 */
const prioritySwimlaneBoard: BoardDetail = {
  ...boardFixture,
  swimlaneField: 'PRIORITY',
  columns: boardFixture.columns.map((col) =>
    col.columnId === COL_TODO
      ? {
          ...col,
          cards: [
            { issueKey: 'ATLAS-1', summary: '우선순위 3', assigneeId: null, version: 1, priority: 3, epicKey: null, rank: null, typeKey: 'task', labels: [], originalEstimateSeconds: null },
            { issueKey: 'ATLAS-4', summary: '우선순위 1', assigneeId: null, version: 2, priority: 1, epicKey: null, rank: null, typeKey: 'task', labels: [], originalEstimateSeconds: null },
          ],
        }
      : col,
  ),
}

/** EPIC 스윔레인 보드 — TODO 컬럼에 에픽이 다른 카드 3장(에픽A·에픽B·에픽없음) */
const epicSwimlaneBoard: BoardDetail = {
  ...boardFixture,
  swimlaneField: 'EPIC',
  columns: boardFixture.columns.map((col) =>
    col.columnId === COL_TODO
      ? {
          ...col,
          cards: [
            { issueKey: 'ATLAS-1', summary: '에픽 ATLAS-10', assigneeId: null, version: 1, priority: 1, epicKey: 'ATLAS-10', rank: null, typeKey: 'task', labels: [], originalEstimateSeconds: null },
            { issueKey: 'ATLAS-4', summary: '에픽 ATLAS-20', assigneeId: null, version: 2, priority: 1, epicKey: 'ATLAS-20', rank: null, typeKey: 'task', labels: [], originalEstimateSeconds: null },
            { issueKey: 'ATLAS-5', summary: '에픽 없음', assigneeId: null, version: 3, priority: 1, epicKey: null, rank: null, typeKey: 'task', labels: [], originalEstimateSeconds: null },
          ],
        }
      : col,
  ),
}

describe('KanbanBoard — S6 필드변경 announcements (FR-7, PR21b Task 4)', () => {
  beforeEach(() => {
    capturedOnDragEnd = undefined
    capturedAnnouncements = undefined
  })

  it('S6a: 담당자 스윔레인 — 다른 담당자 줄로 드롭 완료 시 대상 이름을 포함한 완료형 문구를 공지한다', () => {
    renderBoard(assigneeSwimlaneBoard, assigneeSwimlaneNames)
    const announcements = capturedAnnouncements
    expect(announcements).toBeDefined()
    if (announcements === undefined) return

    const message = announcements.onDragEnd({
      active: fakeActive('ATLAS-1', { fromColumnId: COL_TODO }),
      over: fakeOver('ATLAS-4'),
    })
    expect(message).toContain('ATLAS-1')
    expect(message).toContain('변경했습니다')
    // "박밥"은 받침(ㅂ)이 있어 "으로"가 붙는다(리뷰 S1 — josaEuro 일관성)
    expect(message).toContain('박밥으로')
  })

  it('S6h: 담당자 스윔레인 — 받침 없는 이름 뒤에는 "로"만 붙는다("으로" 아님, 리뷰 S1)', () => {
    renderBoard(assigneeSwimlaneBoard, assigneeSwimlaneNames)
    const announcements = capturedAnnouncements
    expect(announcements).toBeDefined()
    if (announcements === undefined) return

    const message = announcements.onDragEnd({
      active: fakeActive('ATLAS-4', { fromColumnId: COL_TODO }),
      over: fakeOver('ATLAS-1'),
    })
    // "김앨리스"는 받침이 없어 "로"만 붙는다 — "으로"가 섞이면 안 된다
    expect(message).toContain('김앨리스로')
    expect(message).not.toContain('김앨리스으로')
  })

  it('S6b: 담당자 스윔레인 — 미배정 줄로 드롭 완료 시 해제 문구를 공지한다', () => {
    renderBoard(assigneeSwimlaneBoard, assigneeSwimlaneNames)
    const announcements = capturedAnnouncements
    expect(announcements).toBeDefined()
    if (announcements === undefined) return

    const message = announcements.onDragEnd({
      active: fakeActive('ATLAS-4', { fromColumnId: COL_TODO }),
      over: fakeOver('ATLAS-6'),
    })
    expect(message).toContain('ATLAS-4')
    expect(message).toContain('담당자를 해제')
  })

  it('S6c: 담당자 스윔레인 — 이름 조회 실패 시 "미배정" 없이 중립 문구로 공지한다(리뷰 S2 — 실제 해제와 구분)', () => {
    renderBoard(assigneeSwimlaneBoard, assigneeSwimlaneNames)
    const announcements = capturedAnnouncements
    expect(announcements).toBeDefined()
    if (announcements === undefined) return

    const message = announcements.onDragEnd({
      active: fakeActive('ATLAS-1', { fromColumnId: COL_TODO }),
      over: fakeOver('ATLAS-5'),
    })
    expect(message).toContain('ATLAS-1')
    expect(message).toContain('담당자를')
    expect(message).toContain('변경했습니다')
    // "미배정"은 실제 담당자 해제(S6b)와 같은 단어라 스크린리더로 두 상황이 구분되지 않는다 — 쓰지 않는다
    expect(message).not.toContain('미배정')
    expect(message).not.toContain('해제')
  })

  it('S6d: 담당자 스윔레인 — onDragOver는 예고형("변경합니다") 문구를 공지한다', () => {
    renderBoard(assigneeSwimlaneBoard, assigneeSwimlaneNames)
    const announcements = capturedAnnouncements
    expect(announcements).toBeDefined()
    if (announcements === undefined) return

    const message = announcements.onDragOver({
      active: fakeActive('ATLAS-1', { fromColumnId: COL_TODO }),
      over: fakeOver('ATLAS-4'),
    })
    expect(message).toContain('변경합니다')
    expect(message).not.toContain('변경했습니다')
  })

  it('S6e: 우선순위 스윔레인 — 다른 우선순위 줄로 드롭 완료 시 대상 숫자를 포함한 문구를 공지한다', () => {
    renderBoard(prioritySwimlaneBoard)
    const announcements = capturedAnnouncements
    expect(announcements).toBeDefined()
    if (announcements === undefined) return

    const message = announcements.onDragEnd({
      active: fakeActive('ATLAS-1', { fromColumnId: COL_TODO }),
      over: fakeOver('ATLAS-4'),
    })
    expect(message).toContain('우선순위')
    expect(message).toContain('변경했습니다')
    // 우선순위 "1"은 "일"로 읽혀 받침(ㄹ)이 있다 — 담당자·에픽과 동일한 josaEuro로 "으로"가 붙는다(S1)
    expect(message).toContain('1으로')
  })

  it('S6f: 에픽 스윔레인 — 다른 에픽 줄로 드롭 완료 시 대상 에픽 키를 포함한 이동 문구를 공지한다', () => {
    renderBoard(epicSwimlaneBoard)
    const announcements = capturedAnnouncements
    expect(announcements).toBeDefined()
    if (announcements === undefined) return

    const message = announcements.onDragEnd({
      active: fakeActive('ATLAS-1', { fromColumnId: COL_TODO }),
      over: fakeOver('ATLAS-4'),
    })
    expect(message).toContain('이동했습니다')
    // "ATLAS-20"은 마지막 글자 "0"→"영"의 받침이 있어 "으로"가 붙는다(리뷰 S1 — josaEuro 일관성)
    expect(message).toContain('ATLAS-20으로')
  })

  it('S6g: 에픽 스윔레인 — "에픽 없음" 줄로 드롭 완료 시 해제 문구를 공지한다', () => {
    renderBoard(epicSwimlaneBoard)
    const announcements = capturedAnnouncements
    expect(announcements).toBeDefined()
    if (announcements === undefined) return

    const message = announcements.onDragEnd({
      active: fakeActive('ATLAS-1', { fromColumnId: COL_TODO }),
      over: fakeOver('ATLAS-5'),
    })
    expect(message).toContain('ATLAS-1')
    expect(message).toContain('에픽 연결을 해제')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S7. onDragEnd → useChangeCardField 배선 (FR-UX-06 PR21b Task 5)
// resolveDropAction이 field-change로 판정한 액션이 changeCardField.mutate로 올바른 vars와 함께
// 전달되는지 검증한다(담당자·우선순위·에픽 각).
// ─────────────────────────────────────────────────────────────────────────────

describe('KanbanBoard — S7 onDragEnd field-change 배선 (Task 5)', () => {
  beforeEach(() => {
    capturedChangeCardFieldArgs.length = 0
    changeCardFieldMutateSpy.mockClear()
    moveCardMutateSpy.mockClear()
    reorderCardMutateSpy.mockClear()
    capturedOnDragEnd = undefined
    capturedAnnouncements = undefined
  })

  it('S7a: 담당자 스윔레인 — 다른 담당자 줄로 드롭하면 changeCardField.mutate가 assignee vars로 호출된다', () => {
    renderBoard(assigneeSwimlaneBoard, assigneeSwimlaneNames)

    triggerDragEnd({ id: 'ATLAS-1', fromColumnId: COL_TODO }, 'ATLAS-4')

    expect(changeCardFieldMutateSpy).toHaveBeenCalledWith({
      issueKey: 'ATLAS-1',
      field: 'assignee',
      toAssigneeId: ASSIGNEE_ID_BOB,
      expectedVersion: 1,
    })
    expect(moveCardMutateSpy).not.toHaveBeenCalled()
    expect(reorderCardMutateSpy).not.toHaveBeenCalled()
  })

  it('S7b: 담당자 스윔레인 — 미배정 줄로 드롭하면 toAssigneeId null로 해제 vars가 전달된다', () => {
    renderBoard(assigneeSwimlaneBoard, assigneeSwimlaneNames)

    triggerDragEnd({ id: 'ATLAS-4', fromColumnId: COL_TODO }, 'ATLAS-6')

    expect(changeCardFieldMutateSpy).toHaveBeenCalledWith({
      issueKey: 'ATLAS-4',
      field: 'assignee',
      toAssigneeId: null,
      expectedVersion: 2,
    })
  })

  it('S7c: 우선순위 스윔레인 — 다른 우선순위 줄로 드롭하면 changeCardField.mutate가 priority vars로 호출된다', () => {
    renderBoard(prioritySwimlaneBoard)

    triggerDragEnd({ id: 'ATLAS-1', fromColumnId: COL_TODO }, 'ATLAS-4')

    expect(changeCardFieldMutateSpy).toHaveBeenCalledWith({
      issueKey: 'ATLAS-1',
      field: 'priority',
      toPriority: 1,
      expectedVersion: 1,
    })
    expect(moveCardMutateSpy).not.toHaveBeenCalled()
    expect(reorderCardMutateSpy).not.toHaveBeenCalled()
  })

  it('S7d: 에픽 스윔레인 — 다른 에픽 줄로 드롭하면 changeCardField.mutate가 toEpicKey/fromEpicKey와 함께 호출된다', () => {
    renderBoard(epicSwimlaneBoard)

    triggerDragEnd({ id: 'ATLAS-1', fromColumnId: COL_TODO }, 'ATLAS-4')

    expect(changeCardFieldMutateSpy).toHaveBeenCalledWith({
      issueKey: 'ATLAS-1',
      field: 'epic',
      toEpicKey: 'ATLAS-20',
      fromEpicKey: 'ATLAS-10',
      expectedVersion: 1,
    })
    expect(moveCardMutateSpy).not.toHaveBeenCalled()
    expect(reorderCardMutateSpy).not.toHaveBeenCalled()
  })

  it('S7e: 에픽 스윔레인 — "에픽 없음" 줄로 드롭하면 toEpicKey null로 해제 vars가 전달된다', () => {
    renderBoard(epicSwimlaneBoard)

    triggerDragEnd({ id: 'ATLAS-1', fromColumnId: COL_TODO }, 'ATLAS-5')

    expect(changeCardFieldMutateSpy).toHaveBeenCalledWith({
      issueKey: 'ATLAS-1',
      field: 'epic',
      toEpicKey: null,
      fromEpicKey: 'ATLAS-10',
      expectedVersion: 1,
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S8. 드래그 시각 힌트 (FR-8, FR-UX-06 PR21b Task 5)
// 스윔레인 그룹이 다른 카드 위 dragOver 시 field-change가 유효 드롭임을 컬럼 하이라이트로
// 알린다(PR21의 그룹 경계 "드롭 불가" 억제를 반전). noop이면 여전히 하이라이트가 없다.
// ─────────────────────────────────────────────────────────────────────────────

describe('KanbanBoard — S8 드래그 시각 힌트 (FR-8, Task 5)', () => {
  beforeEach(() => {
    capturedOnDragStart = undefined
    capturedOnDragOver = undefined
    capturedOnDragEnd = undefined
    capturedAnnouncements = undefined
  })

  it('S8a: 스윔레인 그룹이 다른 카드 위 dragOver 시(field-change) 대상 컬럼을 드롭 가능으로 하이라이트한다', () => {
    const { container } = renderBoard(assigneeSwimlaneBoard, assigneeSwimlaneNames)
    expect(capturedOnDragStart).toBeDefined()
    expect(capturedOnDragOver).toBeDefined()

    triggerDragStart({ id: 'ATLAS-1', fromColumnId: COL_TODO })
    triggerDragOver({ id: 'ATLAS-1', fromColumnId: COL_TODO }, 'ATLAS-4')

    const todoColumnEl = container.querySelector(`[data-col-id="${COL_TODO}"]`)
    expect(todoColumnEl).not.toBeNull()
    expect(todoColumnEl?.className).toContain('ring-2')
  })

  it('S8b: 제자리(자기 자신 위) dragOver는 noop이므로 하이라이트를 표시하지 않는다', () => {
    const { container } = renderBoard(assigneeSwimlaneBoard, assigneeSwimlaneNames)

    triggerDragStart({ id: 'ATLAS-1', fromColumnId: COL_TODO })
    triggerDragOver({ id: 'ATLAS-1', fromColumnId: COL_TODO }, 'ATLAS-1')

    const todoColumnEl = container.querySelector(`[data-col-id="${COL_TODO}"]`)
    expect(todoColumnEl?.className).not.toContain('ring-2')
  })

  it('S8c: 같은 셀 내 카드 위 dragOver(reorder)는 기존대로 대상 컬럼을 하이라이트한다 (무회귀)', () => {
    const { container } = renderBoard(boardWithTwoCardsInTodo)

    triggerDragStart({ id: 'ATLAS-1', fromColumnId: COL_TODO })
    triggerDragOver({ id: 'ATLAS-1', fromColumnId: COL_TODO }, 'ATLAS-4')

    const todoColumnEl = container.querySelector(`[data-col-id="${COL_TODO}"]`)
    expect(todoColumnEl?.className).toContain('ring-2')
  })

  it('S8d: 다른 컬럼(move) 위 dragOver는 기존대로 대상 컬럼을 하이라이트한다 (무회귀)', () => {
    const { container } = renderBoard()

    triggerDragStart({ id: 'ATLAS-1', fromColumnId: COL_TODO })
    triggerDragOver({ id: 'ATLAS-1', fromColumnId: COL_TODO }, COL_INPROGRESS)

    const inProgressColumnEl = container.querySelector(`[data-col-id="${COL_INPROGRESS}"]`)
    expect(inProgressColumnEl?.className).toContain('ring-2')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S9. 드래그 고스트 카드(DragOverlay) issueTypesByKey 배선 (E9, FR-UX-14 F14 Task 3)
// ─────────────────────────────────────────────────────────────────────────────

describe('KanbanBoard — S9 드래그 고스트 카드 issueTypesByKey (E9)', () => {
  beforeEach(() => {
    capturedOnDragStart = undefined
    capturedOnDragOver = undefined
    capturedOnDragEnd = undefined
    capturedAnnouncements = undefined
  })

  it('S9a: 드래그 시작 시 고스트 카드가 issueTypesByKey로 해석된 유형 아이콘을 갖는다', () => {
    const taskType: IssueTypeResponse = { id: 1, key: 'task', name: '작업', description: '', iconName: 'task' }
    renderBoard(boardFixture, undefined, undefined, new Map([[taskType.key, taskType]]))

    triggerDragStart({ id: 'ATLAS-1', fromColumnId: COL_TODO })

    const overlay = screen.getByTestId('drag-overlay')
    expect(within(overlay).getByRole('img', { name: '작업' })).toBeInTheDocument()
  })

  it('S9b: issueTypesByKey에 없는 typeKey는 원문이 고스트 카드 접근성 이름이 된다(FR6)', () => {
    renderBoard(boardFixture, undefined, undefined, new Map())

    triggerDragStart({ id: 'ATLAS-1', fromColumnId: COL_TODO })

    const overlay = screen.getByTestId('drag-overlay')
    expect(within(overlay).getByRole('img', { name: 'task' })).toBeInTheDocument()
  })
})
