// BacklogColumn 컴포넌트 단위 테스트 — 헤더·카드 목록·드롭 영역
import { describe, it, expect } from 'vitest'
import type { ReactNode } from 'react'
import { render, screen } from '@testing-library/react'
import { DndContext } from '@dnd-kit/core'
import type { BacklogIssue } from '@/api/backlog'

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

import { BacklogColumn } from './BacklogColumn'

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const issue1: BacklogIssue = {
  key: 'ATLAS-1',
  summary: '첫 번째 백로그 이슈',
  currentStateKey: 'todo',
  assigneeId: 'user-uuid-001',
  priority: 1,
  rank: 'aaa',
  version: 1,
  epicKey: null,
}

const issue2: BacklogIssue = {
  key: 'ATLAS-2',
  summary: '두 번째 백로그 이슈',
  currentStateKey: 'todo',
  assigneeId: null,
  priority: 3,
  rank: 'bbb',
  version: 1,
  epicKey: null,
}

const assigneeNames = new Map<string, string>([['ATLAS-1', '박지현']])

function renderColumn(
  issues: BacklogIssue[] = [issue1, issue2],
  names: Map<string, string> = assigneeNames,
  isOver = false,
) {
  return render(
    <DndContext>
      <BacklogColumn issues={issues} assigneeNames={names} isOver={isOver} />
    </DndContext>,
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// S1. 헤더 렌더
// ─────────────────────────────────────────────────────────────────────────────

describe('BacklogColumn — S1 헤더 렌더', () => {
  it('S1a: "백로그" 헤더 텍스트를 표시한다', () => {
    renderColumn()
    expect(screen.getByText('백로그')).toBeInTheDocument()
  })

  it('S1b: 카드 수를 헤더에 표시한다', () => {
    renderColumn()
    expect(screen.getByText('2')).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S2. 카드 목록 렌더
// ─────────────────────────────────────────────────────────────────────────────

describe('BacklogColumn — S2 카드 목록 렌더', () => {
  it('S2a: 각 카드의 issueKey를 렌더한다', () => {
    renderColumn()
    expect(screen.getByText('ATLAS-1')).toBeInTheDocument()
    expect(screen.getByText('ATLAS-2')).toBeInTheDocument()
  })

  it('S2b: 각 카드의 summary를 렌더한다', () => {
    renderColumn()
    expect(screen.getByText('첫 번째 백로그 이슈')).toBeInTheDocument()
    expect(screen.getByText('두 번째 백로그 이슈')).toBeInTheDocument()
  })

  it('S2c: assigneeNames에 이름이 있는 카드는 이니셜을 표시한다', () => {
    renderColumn()
    expect(screen.getByText('박')).toBeInTheDocument()
  })

  it('S2d: assigneeId=null 카드는 "미배정"을 표시한다', () => {
    renderColumn()
    expect(screen.getByText('미배정')).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S3. 드롭 영역
// ─────────────────────────────────────────────────────────────────────────────

describe('BacklogColumn — S3 드롭 영역', () => {
  it('S3a: 드롭 영역에 data-droppable="backlog" 속성이 있다', () => {
    renderColumn()
    expect(document.querySelector('[data-droppable="backlog"]')).toBeInTheDocument()
  })

  it('S3b: 빈 목록에도 드롭 영역이 있다', () => {
    renderColumn([], new Map())
    expect(document.querySelector('[data-droppable="backlog"]')).toBeInTheDocument()
  })

  it('S3c: isOver=true일 때 드롭 하이라이트 클래스가 적용된다', () => {
    renderColumn([issue1], assigneeNames, true)
    const dropZone = document.querySelector('[data-droppable="backlog"]')
    expect(dropZone?.className).toMatch(/ring|bg-accent/)
  })

  it('S3d: isOver=false일 때 하이라이트 클래스가 없다', () => {
    renderColumn([issue1], assigneeNames, false)
    const dropZone = document.querySelector('[data-droppable="backlog"]')
    expect(dropZone?.className).not.toMatch(/ring-2/)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S4. 빈 목록 placeholder
// ─────────────────────────────────────────────────────────────────────────────

describe('BacklogColumn — S4 빈 목록 placeholder', () => {
  it('S4a: 이슈가 없으면 빈 상태 메시지를 표시한다', () => {
    renderColumn([], new Map())
    expect(screen.getByText('이슈 없음')).toBeInTheDocument()
  })
})
