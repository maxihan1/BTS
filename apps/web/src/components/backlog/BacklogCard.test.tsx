// BacklogCard 컴포넌트 단위 테스트 — 이슈 정보 표시·드래그 affordance·이슈 상세 링크
import { describe, it, expect } from 'vitest'
import type { ReactNode } from 'react'
import { render, screen } from '@testing-library/react'
import { DndContext } from '@dnd-kit/core'
import type { BacklogIssue } from '@/api/backlog'

// TanStack Router Link — 라우터 컨텍스트 없이 단위 테스트 가능하도록 mock
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

import { BacklogCard } from './BacklogCard'

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const baseIssue: BacklogIssue = {
  key: 'ATLAS-10',
  summary: '백로그 드래그앤드롭 구현',
  currentStateKey: 'todo',
  assigneeId: 'user-uuid-0001',
  priority: 2,
  rank: 'aaa',
  version: 1,
  epicKey: null,
}

function renderCard(
  issue: BacklogIssue = baseIssue,
  context: 'backlog' | 'sprint' = 'backlog',
  sprintId?: string,
  assigneeName?: string,
) {
  return render(
    <DndContext>
      <BacklogCard
        issue={issue}
        context={context}
        sprintId={sprintId}
        assigneeName={assigneeName}
      />
    </DndContext>,
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// S1. 기본 렌더
// ─────────────────────────────────────────────────────────────────────────────

describe('BacklogCard — S1 기본 렌더', () => {
  it('S1a: issueKey를 표시한다', () => {
    renderCard()
    expect(screen.getByText('ATLAS-10')).toBeInTheDocument()
  })

  it('S1b: summary를 표시한다', () => {
    renderCard()
    expect(screen.getByText('백로그 드래그앤드롭 구현')).toBeInTheDocument()
  })

  it('S1c: 이슈 상세 링크가 /issues/ATLAS-10 href를 갖는다', () => {
    renderCard()
    const link = screen.getByTestId('issue-link')
    expect(link).toHaveAttribute('href', '/issues/ATLAS-10')
  })

  it('S1d: priority 값을 표시한다', () => {
    renderCard()
    // 우선순위 숫자나 이름 중 하나를 표시해야 함
    const card = document.querySelector('[aria-roledescription="draggable card"]')
    expect(card).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S2. 담당자 표시
// ─────────────────────────────────────────────────────────────────────────────

describe('BacklogCard — S2 담당자 표시', () => {
  it('S2a: assigneeName이 있으면 이름 이니셜 아바타를 표시한다', () => {
    renderCard(baseIssue, 'backlog', undefined, '김철수')
    expect(screen.getByText('김')).toBeInTheDocument()
  })

  it('S2b: assigneeName이 없고 assigneeId가 null이면 "미배정" 텍스트를 표시한다', () => {
    const unassignedIssue: BacklogIssue = { ...baseIssue, assigneeId: null }
    renderCard(unassignedIssue, 'backlog', undefined, undefined)
    expect(screen.getByText('미배정')).toBeInTheDocument()
  })

  it('S2c: assigneeName이 없지만 assigneeId가 있으면 "?" 아바타를 표시한다', () => {
    renderCard(baseIssue, 'backlog', undefined, undefined)
    expect(screen.getByText('?')).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S3. 드래그 affordance
// ─────────────────────────────────────────────────────────────────────────────

describe('BacklogCard — S3 드래그 affordance', () => {
  it('S3a: aria-roledescription="draggable card" 속성이 있다', () => {
    renderCard()
    expect(
      document.querySelector('[aria-roledescription="draggable card"]'),
    ).toBeInTheDocument()
  })

  it('S3b: backlog context일 때 드래그 data에 context="backlog"가 포함된다 (data-context attr)', () => {
    renderCard(baseIssue, 'backlog')
    const card = document.querySelector('[data-drag-context="backlog"]')
    expect(card).toBeInTheDocument()
  })

  it('S3c: sprint context일 때 드래그 data에 context="sprint"가 포함된다 (data-context attr)', () => {
    renderCard(baseIssue, 'sprint', 'sprint-uuid-001')
    const card = document.querySelector('[data-drag-context="sprint"]')
    expect(card).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S4. concern-1 RED — 카드 droppable 등록
// BacklogCard는 드래그 대상이자 드롭 수신 대상이어야 한다.
// 현재 useDraggable만 있고 useDroppable이 없으므로 data-card-droppable 속성이 없다.
// ─────────────────────────────────────────────────────────────────────────────

describe('BacklogCard — S4 concern-1 카드 droppable 등록', () => {
  it('S4a red: backlog 카드에 data-card-droppable 속성이 있다', () => {
    renderCard(baseIssue, 'backlog')
    // useDroppable로 등록된 카드 droppable id = 'card:backlog:ATLAS-10'
    // DOM에 data-card-droppable="card:backlog:ATLAS-10" 속성으로 노출
    expect(document.querySelector('[data-card-droppable="card:backlog:ATLAS-10"]')).toBeInTheDocument()
  })

  it('S4b red: sprint 카드에 data-card-droppable 속성이 있다', () => {
    renderCard(baseIssue, 'sprint', 'sprint-uuid-001')
    expect(document.querySelector('[data-card-droppable="card:sprint:ATLAS-10"]')).toBeInTheDocument()
  })
})
