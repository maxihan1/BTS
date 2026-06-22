// KanbanBoard 단위 테스트 — resolveDropAction 순수 헬퍼 + 컬럼 displayOrder 렌더
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { DndContext } from '@dnd-kit/core'
import { createElement } from 'react'
import type { ReactNode } from 'react'
import type { BoardDetail } from '@/api/boards'
import type { CardAssigneeDisplay } from './BoardCard'

// useMoveCard mock — mutate spy 노출 + boardId/filter 인자 캡처 (Task6 통합 검증)
const capturedMoveCardArgs: Array<[string, unknown]> = []
vi.mock('@/hooks/use-move-card', () => ({
  useMoveCard: (boardId: string, filter?: unknown) => {
    capturedMoveCardArgs.push([boardId, filter])
    return { mutate: vi.fn(), isPending: false }
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
        { issueKey: 'ATLAS-1', summary: '첫 번째 이슈', assigneeId: null, version: 1, priority: 1 },
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
        { issueKey: 'ATLAS-3', summary: '완료된 이슈', assigneeId: null, version: 5, priority: 1 },
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
        { issueKey: 'ATLAS-2', summary: '두 번째 이슈', assigneeId: null, version: 3, priority: 1 },
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
