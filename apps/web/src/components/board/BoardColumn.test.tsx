// BoardColumn 컴포넌트 단위 테스트 — 헤더·카드 목록·빈 컬럼 placeholder·드롭 영역
import { describe, it, expect } from 'vitest'
import type { ReactNode } from 'react'
import { render, screen } from '@testing-library/react'
import { DndContext } from '@dnd-kit/core'
import type { BoardColumn as BoardColumnType } from '@/api/boards'

// TanStack Router Link mock
vi.mock('@tanstack/react-router', () => ({
  Link: ({
    to,
    params,
    children,
    className,
    onClick,
  }: {
    to: string
    params?: Record<string, string>
    children: ReactNode
    className?: string
    onClick?: React.MouseEventHandler
  }) => (
    <a
      href={params ? to.replace('$key', params['key'] ?? '') : to}
      className={className}
      onClick={onClick}
      data-testid="issue-link"
    >
      {children}
    </a>
  ),
}))

import { BoardColumn } from './BoardColumn'
import type { CardAssigneeDisplay } from './BoardCard'

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const COL_UUID = 'col-uuid-0001'

const columnWithCards: BoardColumnType = {
  columnId: COL_UUID,
  stateKey: 'in-progress',
  name: '진행 중',
  category: 'IN_PROGRESS',
  displayOrder: 2,
  cards: [
    { issueKey: 'ATLAS-1', summary: '첫 번째 이슈', assigneeId: 'u1', version: 1 },
    { issueKey: 'ATLAS-2', summary: '두 번째 이슈', assigneeId: null, version: 2 },
    { issueKey: 'ATLAS-3', summary: '세 번째 이슈', assigneeId: 'u3-unknown', version: 3 },
  ],
}

const emptyColumn: BoardColumnType = {
  columnId: 'col-uuid-0002',
  stateKey: 'todo',
  name: '할 일',
  category: 'TODO',
  displayOrder: 1,
  cards: [],
}

const assigneeNames: Map<string, CardAssigneeDisplay> = new Map([
  ['ATLAS-1', { state: 'named', name: '박지현' }],
  ['ATLAS-2', { state: 'unassigned' }],
  ['ATLAS-3', { state: 'unknown' }],
])

function renderColumn(
  column: BoardColumnType = columnWithCards,
  names: Map<string, CardAssigneeDisplay> = assigneeNames,
  isOver = false,
) {
  return render(
    <DndContext>
      <BoardColumn column={column} assigneeNames={names} isOver={isOver} />
    </DndContext>,
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// S1. 헤더 렌더
// ─────────────────────────────────────────────────────────────────────────────

describe('BoardColumn — S1 헤더 렌더', () => {
  it('S1a: column.name을 헤더에 표시한다', () => {
    renderColumn()
    expect(screen.getByText('진행 중')).toBeInTheDocument()
  })

  it('S1b: 카드 수(column.cards.length)를 헤더에 표시한다', () => {
    renderColumn()
    // 카드 3개 → "3" 표시
    expect(screen.getByText('3')).toBeInTheDocument()
  })

  it('S1c: category 배지를 헤더에 표시한다', () => {
    renderColumn()
    expect(screen.getByText('IN_PROGRESS')).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S2. 카드 목록 렌더
// ─────────────────────────────────────────────────────────────────────────────

describe('BoardColumn — S2 카드 목록 렌더', () => {
  it('S2a: 각 카드의 issueKey를 렌더한다', () => {
    renderColumn()
    expect(screen.getByText('ATLAS-1')).toBeInTheDocument()
    expect(screen.getByText('ATLAS-2')).toBeInTheDocument()
    expect(screen.getByText('ATLAS-3')).toBeInTheDocument()
  })

  it('S2b: 각 카드의 summary를 렌더한다', () => {
    renderColumn()
    expect(screen.getByText('첫 번째 이슈')).toBeInTheDocument()
    expect(screen.getByText('두 번째 이슈')).toBeInTheDocument()
    expect(screen.getByText('세 번째 이슈')).toBeInTheDocument()
  })

  it('S2c: {state:"named"} 카드는 이니셜 아바타를 표시한다', () => {
    renderColumn()
    // '박지현'의 첫 글자 '박'
    expect(screen.getByText('박')).toBeInTheDocument()
  })

  it('S2d: {state:"unassigned"} 카드는 "미배정" 텍스트를 표시한다', () => {
    renderColumn()
    expect(screen.getByText('미배정')).toBeInTheDocument()
  })

  it('S2e: {state:"unknown"} 카드는 "?" 아바타를 표시한다 — "미배정" 아님', () => {
    renderColumn()
    expect(screen.getByText('?')).toBeInTheDocument()
  })

  it('S2f: assigneeNames에 없는 카드는 fallback unassigned로 표시한다', () => {
    const col: BoardColumnType = {
      ...columnWithCards,
      cards: [{ issueKey: 'ATLAS-99', summary: '알 수 없음', assigneeId: null, version: 1 }],
    }
    renderColumn(col, new Map())
    // Map에 없으므로 unassigned fallback
    expect(screen.getByText('미배정')).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S3. 빈 컬럼 placeholder
// ─────────────────────────────────────────────────────────────────────────────

describe('BoardColumn — S3 빈 컬럼', () => {
  it('S3a: 카드가 없으면 "카드 없음" placeholder를 표시한다', () => {
    renderColumn(emptyColumn, new Map())
    expect(screen.getByText('카드 없음')).toBeInTheDocument()
  })

  it('S3b: 빈 컬럼에도 드롭 영역(ref=setNodeRef)이 있다 — data-col-id 속성으로 확인', () => {
    renderColumn(emptyColumn, new Map())
    // 드롭 가능 영역은 data-col-id 속성을 가져야 함
    expect(
      document.querySelector(`[data-col-id="${emptyColumn.columnId}"]`),
    ).toBeInTheDocument()
  })

  it('S3c: 카드가 있는 컬럼에도 드롭 영역이 있다', () => {
    renderColumn()
    expect(
      document.querySelector(`[data-col-id="${COL_UUID}"]`),
    ).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S4. 드롭 하이라이트
// ─────────────────────────────────────────────────────────────────────────────

describe('BoardColumn — S4 드롭 하이라이트', () => {
  it('S4a: isOver=true일 때 드롭 하이라이트 클래스가 적용된다', () => {
    renderColumn(columnWithCards, assigneeNames, true)
    const dropZone = document.querySelector(`[data-col-id="${COL_UUID}"]`)
    expect(dropZone).toBeInTheDocument()
    // ring 또는 bg 하이라이트가 적용되어야 함 (className에 ring 포함)
    expect(dropZone?.className).toMatch(/ring|bg-accent/)
  })

  it('S4b: isOver=false일 때 하이라이트 클래스가 없다', () => {
    renderColumn(columnWithCards, assigneeNames, false)
    const dropZone = document.querySelector(`[data-col-id="${COL_UUID}"]`)
    expect(dropZone?.className).not.toMatch(/ring-2/)
  })
})
