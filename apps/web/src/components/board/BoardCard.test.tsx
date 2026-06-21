// BoardCard 컴포넌트 단위 테스트 — 카드 정보 표시·담당자 이니셜·미배정·링크
import { describe, it, expect } from 'vitest'
import type { ReactNode } from 'react'
import { render, screen } from '@testing-library/react'
import { DndContext } from '@dnd-kit/core'
import type { BoardCard as BoardCardType } from '@/api/boards'

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

// BoardCard는 아직 존재하지 않음 — RED 단계
import { BoardCard } from './BoardCard'

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const baseCard: BoardCardType = {
  issueKey: 'ATLAS-42',
  summary: '칸반 보드 드래그앤드롭 구현',
  assigneeId: 'user-uuid-0001',
  version: 3,
}

const COLUMN_ID = 'col-uuid-0001'

function renderCard(
  card: BoardCardType = baseCard,
  assigneeName: string | null = '김철수',
) {
  return render(
    <DndContext>
      <BoardCard card={card} columnId={COLUMN_ID} assigneeName={assigneeName} />
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
// S2. 담당자 아바타
// ─────────────────────────────────────────────────────────────────────────────

describe('BoardCard — S2 담당자 아바타', () => {
  it('S2a: assigneeName이 있으면 이름 첫 글자 이니셜을 아바타로 표시한다', () => {
    renderCard(baseCard, '김철수')
    // 이니셜 '김' 이 아바타로 존재해야 함
    expect(screen.getByText('김')).toBeInTheDocument()
  })

  it('S2b: assigneeName이 있으면 title 속성에 전체 이름을 표시한다', () => {
    renderCard(baseCard, '김철수')
    const avatar = screen.getByTitle('김철수')
    expect(avatar).toBeInTheDocument()
  })

  it('S2c: assigneeName이 null이면 "미배정" 텍스트를 표시한다', () => {
    renderCard(baseCard, null)
    expect(screen.getByText('미배정')).toBeInTheDocument()
  })

  it('S2d: assigneeId가 null인 카드에서 assigneeName도 null이면 "미배정"을 표시한다', () => {
    const unassigned: BoardCardType = { ...baseCard, assigneeId: null }
    renderCard(unassigned, null)
    expect(screen.getByText('미배정')).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S3. 드래그 affordance
// ─────────────────────────────────────────────────────────────────────────────

describe('BoardCard — S3 드래그 affordance', () => {
  it('S3a: aria-roledescription="draggable card" 속성이 있다', () => {
    renderCard()
    expect(
      screen.getByRole('article', { name: /ATLAS-42/ }) ??
        document.querySelector('[aria-roledescription="draggable card"]'),
    ).toBeTruthy()
    // aria-roledescription 가진 요소가 DOM에 있어야 함
    expect(
      document.querySelector('[aria-roledescription="draggable card"]'),
    ).toBeInTheDocument()
  })
})
