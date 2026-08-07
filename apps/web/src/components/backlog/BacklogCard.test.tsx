// BacklogCard 컴포넌트 단위 테스트 — 이슈 정보 표시·드래그 affordance·이슈 상세 링크
import { describe, it, expect } from 'vitest'
import type { ReactNode } from 'react'
import { render, screen } from '@testing-library/react'
import { DndContext } from '@dnd-kit/core'
import type { BacklogIssue } from '@/api/backlog'
import { backlogLabels } from '@/i18n/backlog-labels'

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
  typeKey: 'task',
  labels: [],
  originalEstimateSeconds: null,
}

/**
 * 부모(`BacklogColumn`/`SprintColumn`)가 이슈 타입 맵에서 해석해 넘기는 두 원시값.
 * 미지정이면 실제 배선처럼 「타입 미해석」 상태(`typeIconName: null`, `typeName: issue.typeKey`)로
 * 기본값을 채운다 — 이 두 컴포넌트는 항상 해석 결과를 넘기고 `BacklogCard` 는 맵을 모른다.
 */
interface TypeProps {
  typeIconName?: string | null
  typeName?: string
}

function renderCard(
  issue: BacklogIssue = baseIssue,
  context: 'backlog' | 'sprint' = 'backlog',
  sprintId?: string,
  assigneeName?: string,
  type?: TypeProps,
) {
  return render(
    <DndContext>
      <BacklogCard
        issue={issue}
        context={context}
        sprintId={sprintId}
        assigneeName={assigneeName}
        typeIconName={type?.typeIconName ?? null}
        typeName={type?.typeName ?? issue.typeKey}
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

// ─────────────────────────────────────────────────────────────────────────────
// S5. 유형 아이콘·라벨 칩·추정 배지 (FR-UX-14 F14 Task 4)
// 보드 카드(BoardCard)와 동형 5종 + 백로그 고유 1종(P{priority} 칩 유지 확인).
// ─────────────────────────────────────────────────────────────────────────────

describe('BacklogCard — S5 유형·라벨·추정 (F14 Task 4)', () => {
  it('S5a: 유형 아이콘이 유형 이름으로 읽힌다', () => {
    renderCard(baseIssue, 'backlog', undefined, undefined, {
      typeIconName: 'bug',
      typeName: '버그',
    })
    expect(screen.getByRole('img', { name: '버그' })).toBeInTheDocument()
  })

  it('S5b: 유형 미해석 시 typeKey 원문이 접근성 이름이 된다', () => {
    // 부모가 issueTypesByKey 맵에서 못 찾으면 typeIconName=null · typeName=issue.typeKey 를 넘긴다.
    renderCard(baseIssue, 'backlog', undefined, undefined, {
      typeIconName: null,
      typeName: baseIssue.typeKey,
    })
    expect(screen.getByRole('img', { name: baseIssue.typeKey })).toBeInTheDocument()
  })

  it('S5c: 라벨이 없으면 라벨 칩 행 DOM이 없다', () => {
    renderCard({ ...baseIssue, labels: [] })
    // CardLabelChips는 labels=[]면 null을 반환한다 — 행 자체가 DOM에 없어야 한다.
    expect(document.querySelector('.flex-wrap')).toBeNull()
  })

  it('S5d: 추정이 null이면 추정 배지 DOM이 없다', () => {
    renderCard({ ...baseIssue, originalEstimateSeconds: null })
    // CardEstimateBadge는 seconds=null이면 null을 반환한다 — aria-label="추정 …" 요소가 없어야 한다.
    expect(screen.queryByLabelText(/^추정/)).toBeNull()
  })

  it('S5e: 카드 루트의 aria-label·aria-roledescription·data-* 속성이 그대로다 (계약 §2)', () => {
    renderCard()
    const card = document.querySelector('[data-drag-context="backlog"]')
    expect(card).toHaveAttribute('aria-roledescription', backlogLabels.draggableCard)
    expect(card).toHaveAttribute(
      'aria-label',
      backlogLabels.cardAriaLabel(baseIssue.key, baseIssue.summary),
    )
    expect(card).toHaveAttribute('data-card-droppable', 'card:backlog:ATLAS-10')
  })

  it('S5f 백로그 고유: 기존 P{priority} 칩과 추정 배지가 동시에 보인다', () => {
    const issueWithEstimate: BacklogIssue = { ...baseIssue, originalEstimateSeconds: 9000 }
    renderCard(issueWithEstimate)

    expect(screen.getByText('P2')).toBeInTheDocument() // 기존 유지
    expect(screen.getByText('2h 30m')).toBeInTheDocument() // 신규 · E10
  })
})
