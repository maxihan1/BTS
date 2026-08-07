// BoardCard 컴포넌트 단위 테스트 — 카드 정보 표시·담당자 3-상태(unassigned/named/unknown)·링크·useSortable 배선
import { describe, it, expect, vi } from 'vitest'
import type { ReactNode } from 'react'
import { render, screen } from '@testing-library/react'
import { DndContext } from '@dnd-kit/core'
import * as sortableModule from '@dnd-kit/sortable'
import type { BoardCard as BoardCardType } from '@/api/boards'

// @dnd-kit/sortable — useSortable 호출 인자(id/data) 검증을 위해 실제 구현을 감싼 spy로 mock
vi.mock('@dnd-kit/sortable', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@dnd-kit/sortable')>()
  return { ...actual, useSortable: vi.fn(actual.useSortable) }
})

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

import { BoardCard } from './BoardCard'
import type { CardAssigneeDisplay } from './BoardCard'

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const baseCard: BoardCardType = {
  issueKey: 'ATLAS-42',
  summary: '칸반 보드 드래그앤드롭 구현',
  assigneeId: 'user-uuid-0001',
  version: 3,
  priority: 1,
  epicKey: null,
  rank: null,
  typeKey: 'task',
  labels: [],
  originalEstimateSeconds: null,
}

const COLUMN_ID = 'col-uuid-0001'

function renderCard(
  card: BoardCardType = baseCard,
  assignee: CardAssigneeDisplay = { state: 'named', name: '김철수' },
  typeIconName: string | null = 'task',
  typeName = '작업',
) {
  return render(
    <DndContext>
      <BoardCard
        card={card}
        columnId={COLUMN_ID}
        assignee={assignee}
        typeIconName={typeIconName}
        typeName={typeName}
      />
    </DndContext>,
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// S1. 기본 렌더
// ─────────────────────────────────────────────────────────────────────────────

describe('BoardCard — S1 기본 렌더', () => {
  it('S1a: issueKey를 보조 텍스트로 표시한다', () => {
    renderCard()
    expect(screen.getByText('ATLAS-42')).toBeInTheDocument()
  })

  it('S1b: summary를 주 텍스트로 표시한다', () => {
    renderCard()
    expect(screen.getByText('칸반 보드 드래그앤드롭 구현')).toBeInTheDocument()
  })

  it('S1c: 이슈 상세 링크가 /issues/ATLAS-42 href를 갖는다', () => {
    renderCard()
    const link = screen.getByTestId('issue-link')
    expect(link).toHaveAttribute('href', '/issues/ATLAS-42')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S2. 담당자 표시 3-상태
// ─────────────────────────────────────────────────────────────────────────────

describe('BoardCard — S2 담당자 표시 3-상태', () => {
  it('S2a: assignee={state:"named"} 이면 이름 첫 글자 이니셜을 아바타로 표시한다', () => {
    renderCard(baseCard, { state: 'named', name: '김철수' })
    expect(screen.getByText('김')).toBeInTheDocument()
  })

  it('S2b: assignee={state:"named"} 이면 title 속성에 전체 이름을 표시한다', () => {
    renderCard(baseCard, { state: 'named', name: '김철수' })
    const avatar = screen.getByTitle('김철수')
    expect(avatar).toBeInTheDocument()
  })

  it('S2c: assignee={state:"named"} 이면 aria-label에 "담당자: 김철수"를 표시한다', () => {
    renderCard(baseCard, { state: 'named', name: '김철수' })
    expect(screen.getByLabelText('담당자: 김철수')).toBeInTheDocument()
  })

  it('S2d: assignee={state:"unassigned"} 이면 "미배정" 텍스트를 표시한다', () => {
    const unassigned: BoardCardType = { ...baseCard, assigneeId: null }
    renderCard(unassigned, { state: 'unassigned' })
    expect(screen.getByText('미배정')).toBeInTheDocument()
  })

  it('S2e: assignee={state:"unassigned"} 이면 아바타가 없다', () => {
    const unassigned: BoardCardType = { ...baseCard, assigneeId: null }
    renderCard(unassigned, { state: 'unassigned' })
    expect(screen.queryByTitle('담당자 (이름 미확인)')).not.toBeInTheDocument()
    // named 아바타도 없어야 함 — 어떤 title도 없음
    expect(document.querySelector('[title]')).not.toBeInTheDocument()
  })

  it('S2f: assignee={state:"unknown"} 이면 "?" 아바타를 표시한다 — 미배정 아님', () => {
    renderCard(baseCard, { state: 'unknown' })
    // "?" 텍스트가 표시되어야 함
    expect(screen.getByText('?')).toBeInTheDocument()
    // "미배정" 텍스트는 없어야 함 — 배정됐으나 이름 미확인
    expect(screen.queryByText('미배정')).not.toBeInTheDocument()
  })

  it('S2g: assignee={state:"unknown"} 이면 title="담당자 (이름 미확인)"을 표시한다', () => {
    renderCard(baseCard, { state: 'unknown' })
    expect(screen.getByTitle('담당자 (이름 미확인)')).toBeInTheDocument()
  })

  it('S2h: assignee={state:"unknown"} 이면 aria-label="담당자 이름 미확인"을 표시한다', () => {
    renderCard(baseCard, { state: 'unknown' })
    expect(screen.getByLabelText('담당자 이름 미확인')).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S3. 드래그 affordance
// ─────────────────────────────────────────────────────────────────────────────

describe('BoardCard — S3 드래그 affordance', () => {
  it('S3a: aria-roledescription="draggable card" 속성이 있다', () => {
    renderCard()
    // @dnd-kit이 role="button" + aria-roledescription="draggable card"를 자동 부여
    expect(
      document.querySelector('[aria-roledescription="draggable card"]'),
    ).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S4. useSortable 배선 — id/data(fromColumnId)
// ─────────────────────────────────────────────────────────────────────────────

describe('BoardCard — S4 useSortable 배선', () => {
  it('S4a: useSortable을 id=issueKey, data={fromColumnId}로 호출한다', () => {
    renderCard()
    expect(sortableModule.useSortable).toHaveBeenCalledWith(
      expect.objectContaining({
        id: 'ATLAS-42',
        data: { fromColumnId: COLUMN_ID },
      }),
    )
  })

  it('S4b: 드래그 중(isDragging)이면 원위치 카드에 opacity 저하 클래스가 적용된다', () => {
    renderCard()
    const card = document.querySelector('[aria-roledescription="draggable card"]')
    // 렌더 직후(isDragging=false)에는 opacity-50이 없어야 함 — 회귀 방지용 음성 가드
    expect(card?.className).not.toMatch(/opacity-50/)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S5. 유형 아이콘 · 라벨 칩 · 추정 배지 (FR-UX-14 F14 Task 3)
// ─────────────────────────────────────────────────────────────────────────────

describe('BoardCard — S5 유형 아이콘·라벨 칩·추정 배지 (FR-UX-14 F14 Task 3)', () => {
  it('S5a: 유형 아이콘이 이슈 키 왼쪽에 유형 이름으로 읽힌다', () => {
    renderCard(baseCard, undefined, 'bug', '버그') // S1 · NFR4
    expect(screen.getByRole('img', { name: '버그' })).toBeInTheDocument()
  })

  it('S5b: 유형을 해석 못하면 typeKey 원문이 접근성 이름이 된다', () => {
    renderCard(baseCard, undefined, null, 'custom-type') // FR6 · E6
    expect(screen.getByRole('img', { name: 'custom-type' })).toBeInTheDocument()
  })

  it('S5c: 라벨이 없으면 칩 행이 DOM 에 없다', () => {
    renderCard({ ...baseCard, labels: [] }) // S3 · E1
    expect(document.querySelector('[data-slot="badge"]')).not.toBeInTheDocument()
  })

  it('S5d: 라벨이 있으면 칩 행이 라벨 텍스트로 표시된다', () => {
    renderCard({ ...baseCard, labels: ['백엔드'] })
    expect(screen.getByText('백엔드')).toBeInTheDocument()
  })

  it('S5e: 추정이 null 이면 배지가 DOM 에 없다', () => {
    renderCard({ ...baseCard, originalEstimateSeconds: null }) // S6 · E2
    expect(screen.queryByLabelText(/^추정 /)).not.toBeInTheDocument()
  })

  it('S5f: 추정이 있으면 배지가 "시간h 분m" 형식으로 표시된다', () => {
    renderCard({ ...baseCard, originalEstimateSeconds: 9000 })
    expect(screen.getByLabelText('추정 2h 30m')).toBeInTheDocument()
  })

  it('S5g: 카드 루트의 aria-label 과 aria-roledescription 이 변하지 않는다', () => {
    // FR13 · jira-parity-contract.md §2 — 새 요소는 카드 내부에만, 루트 속성은 문자열 그대로 보존
    renderCard()
    const cardEl = document.querySelector('[aria-roledescription="draggable card"]')
    expect(cardEl).toHaveAttribute('aria-label', `${baseCard.issueKey} — ${baseCard.summary}`)
  })
})
