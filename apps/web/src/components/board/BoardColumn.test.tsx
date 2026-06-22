// BoardColumn 컴포넌트 단위 테스트 — 헤더·카드 목록·빈 컬럼 placeholder·드롭 영역·스윔레인 그룹
import { describe, it, expect } from 'vitest'
import type { ReactNode } from 'react'
import { render, screen, within } from '@testing-library/react'
import { DndContext } from '@dnd-kit/core'
import type { BoardColumn as BoardColumnType, SwimlaneField } from '@/api/boards'

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
  wipLimit: null,
  wipExceeded: false,
  cards: [
    { issueKey: 'ATLAS-1', summary: '첫 번째 이슈', assigneeId: 'u1', version: 1, priority: 1 },
    { issueKey: 'ATLAS-2', summary: '두 번째 이슈', assigneeId: null, version: 2, priority: 1 },
    { issueKey: 'ATLAS-3', summary: '세 번째 이슈', assigneeId: 'u3-unknown', version: 3, priority: 1 },
  ],
}

const emptyColumn: BoardColumnType = {
  columnId: 'col-uuid-0002',
  stateKey: 'todo',
  name: '할 일',
  category: 'TODO',
  displayOrder: 1,
  wipLimit: null,
  wipExceeded: false,
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
  swimlaneField: SwimlaneField = 'NONE',
) {
  return render(
    <DndContext>
      <BoardColumn column={column} assigneeNames={names} isOver={isOver} swimlaneField={swimlaneField} />
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
      cards: [{ issueKey: 'ATLAS-99', summary: '알 수 없음', assigneeId: null, version: 1, priority: 1 }],
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

// ─────────────────────────────────────────────────────────────────────────────
// S5. WIP 제한 표시
// ─────────────────────────────────────────────────────────────────────────────

describe('BoardColumn — S5 WIP 제한 표시', () => {
  it('S5a: wipLimit=5·카드 2개·wipExceeded=false → 헤더에 "2/5" 표기, 경고 없음', () => {
    const col: BoardColumnType = {
      columnId: 'col-wip-ok',
      stateKey: 'in-progress',
      name: 'WIP 미초과',
      category: 'IN_PROGRESS',
      displayOrder: 1,
      wipLimit: 5,
      wipExceeded: false,
      cards: [
        { issueKey: 'ATLAS-10', summary: '이슈 10', assigneeId: null, version: 1, priority: 1 },
        { issueKey: 'ATLAS-11', summary: '이슈 11', assigneeId: null, version: 2, priority: 1 },
      ],
    }
    renderColumn(col, new Map())
    // 카드 수/한도 표기
    expect(screen.getByText('2/5')).toBeInTheDocument()
    // 경고 aria-label 없음
    expect(screen.queryByLabelText('WIP 초과')).not.toBeInTheDocument()
  })

  it('S5b: wipLimit=2·카드 3개·wipExceeded=true → "3/2" 표기 + "WIP 초과" 경고 표시', () => {
    const col: BoardColumnType = {
      columnId: 'col-wip-exceed',
      stateKey: 'in-progress',
      name: 'WIP 초과',
      category: 'IN_PROGRESS',
      displayOrder: 1,
      wipLimit: 2,
      wipExceeded: true,
      cards: [
        { issueKey: 'ATLAS-20', summary: '이슈 20', assigneeId: null, version: 1, priority: 1 },
        { issueKey: 'ATLAS-21', summary: '이슈 21', assigneeId: null, version: 2, priority: 1 },
        { issueKey: 'ATLAS-22', summary: '이슈 22', assigneeId: null, version: 3, priority: 1 },
      ],
    }
    renderColumn(col, new Map())
    // 초과 표기
    expect(screen.getByText('3/2')).toBeInTheDocument()
    // 경고 요소 — aria-label으로 의미 전달
    expect(screen.getByLabelText('WIP 초과')).toBeInTheDocument()
  })

  it('S5c: wipLimit=null → 기존처럼 카드 수만 표시, 슬래시 없음', () => {
    // columnWithCards: wipLimit=null, 카드 3개
    renderColumn()
    // 단순 숫자만 (슬래시 표기 없음)
    expect(screen.getByText('3')).toBeInTheDocument()
    expect(screen.queryByText(/\d+\/\d+/)).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S6. 스윔레인 그룹 렌더 — ASSIGNEE
// ─────────────────────────────────────────────────────────────────────────────

describe('BoardColumn — S6 스윔레인 ASSIGNEE 그룹', () => {
  const columnMultiAssignee: BoardColumnType = {
    columnId: COL_UUID,
    stateKey: 'in-progress',
    name: '진행 중',
    category: 'IN_PROGRESS',
    displayOrder: 2,
    wipLimit: null,
    wipExceeded: false,
    cards: [
      { issueKey: 'ATLAS-1', summary: '첫 번째 이슈', assigneeId: 'u1', version: 1, priority: 1 },
      { issueKey: 'ATLAS-2', summary: '두 번째 이슈', assigneeId: null, version: 2, priority: 1 },
    ],
  }

  it('S6a: swimlaneField=ASSIGNEE이면 담당자 서브헤더가 렌더된다', () => {
    renderColumn(columnMultiAssignee, assigneeNames, false, 'ASSIGNEE')
    // 담당자 이름 "박지현"이 서브헤더로 표시되어야 함
    expect(screen.getByText('박지현')).toBeInTheDocument()
  })

  it('S6b: swimlaneField=ASSIGNEE이면 미배정 그룹이 role=group으로 렌더된다', () => {
    renderColumn(columnMultiAssignee, assigneeNames, false, 'ASSIGNEE')
    // 서브헤더 그룹의 aria-label로 존재 확인 (카드 내 "미배정" 텍스트와 충돌 없이)
    expect(screen.getByRole('group', { name: '미배정' })).toBeInTheDocument()
  })

  it('S6c: swimlaneField=NONE이면 서브헤더 없이 단일 목록으로 렌더된다', () => {
    renderColumn(columnWithCards, assigneeNames, false, 'NONE')
    // NONE이면 담당자 서브헤더 역할의 그룹 요소가 없어야 한다
    // (group role은 컬럼 자체에만 있어야 함)
    const groups = screen.getAllByRole('group')
    // 컬럼 하나 + 스윔레인 그룹 없음 = 1개만
    expect(groups).toHaveLength(1)
  })

  it('S6d: droppable id(data-col-id)가 컬럼 UUID를 유지한다 — 회귀 없음', () => {
    renderColumn(columnMultiAssignee, assigneeNames, false, 'ASSIGNEE')
    // 드롭 영역의 data-col-id 속성이 columnId(COL_UUID)와 동일해야 함
    const dropZone = document.querySelector(`[data-col-id="${COL_UUID}"]`)
    expect(dropZone).toBeInTheDocument()
  })

  it('S6e: 스윔레인 그룹은 role=group + aria-label로 그룹 이름을 전달한다', () => {
    renderColumn(columnMultiAssignee, assigneeNames, false, 'ASSIGNEE')
    // 서브헤더 그룹에 aria-label이 있어야 함
    const groups = screen.getAllByRole('group')
    // 컬럼 그룹(1) + 스윔레인 그룹(최소 1개) = 2개 이상
    expect(groups.length).toBeGreaterThanOrEqual(2)
  })

  it('S6f: 각 카드가 올바른 그룹 아래에 렌더된다', () => {
    renderColumn(columnMultiAssignee, assigneeNames, false, 'ASSIGNEE')
    // 박지현 그룹에 ATLAS-1이 있어야 함
    const parkGroup = screen.getByRole('group', { name: /박지현/ })
    expect(within(parkGroup).getByText('ATLAS-1')).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S7. 스윔레인 그룹 렌더 — PRIORITY
// ─────────────────────────────────────────────────────────────────────────────

describe('BoardColumn — S7 스윔레인 PRIORITY 그룹', () => {
  const columnPriority: BoardColumnType = {
    columnId: 'col-priority-test',
    stateKey: 'in-progress',
    name: '진행 중',
    category: 'IN_PROGRESS',
    displayOrder: 2,
    wipLimit: null,
    wipExceeded: false,
    cards: [
      { issueKey: 'ATLAS-10', summary: 'P1 이슈', assigneeId: null, version: 1, priority: 1 },
      { issueKey: 'ATLAS-11', summary: 'P2 이슈', assigneeId: null, version: 2, priority: 2 },
    ],
  }

  it('S7a: swimlaneField=PRIORITY이면 "우선순위 N" 서브헤더가 렌더된다', () => {
    renderColumn(columnPriority, new Map(), false, 'PRIORITY')
    expect(screen.getByText('우선순위 1')).toBeInTheDocument()
    expect(screen.getByText('우선순위 2')).toBeInTheDocument()
  })

  it('S7b: droppable id(data-col-id)가 컬럼 UUID를 유지한다 — 회귀 없음', () => {
    renderColumn(columnPriority, new Map(), false, 'PRIORITY')
    const dropZone = document.querySelector('[data-col-id="col-priority-test"]')
    expect(dropZone).toBeInTheDocument()
  })

  it('S7c: 우선순위 그룹은 role=group으로 접근성을 제공한다', () => {
    renderColumn(columnPriority, new Map(), false, 'PRIORITY')
    // 컬럼(1) + 우선순위 그룹(2) = 3개
    const groups = screen.getAllByRole('group')
    expect(groups.length).toBeGreaterThanOrEqual(3)
  })
})
