// WipCountBadge 컴포넌트 단위 테스트 — 기본 동작 + isFilterActive 방어 (hotfix-p2)
import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { WipCountBadge } from './WipCountBadge'

// ─────────────────────────────────────────────────────────────────────────────
// S1. 기본 동작 — isFilterActive 없음(false)
// ─────────────────────────────────────────────────────────────────────────────

describe('WipCountBadge — S1 기본 동작 (isFilterActive=false)', () => {
  it('S1a: wipLimit=null이면 카드 수만 표시한다', () => {
    render(<WipCountBadge count={3} wipLimit={null} wipExceeded={false} />)
    expect(screen.getByText('3')).toBeInTheDocument()
    expect(screen.queryByText(/\//)).not.toBeInTheDocument()
  })

  it('S1b: wipLimit 있음·미초과이면 "2/5" 중립 배지를 표시한다', () => {
    render(<WipCountBadge count={2} wipLimit={5} wipExceeded={false} />)
    expect(screen.getByText('2/5')).toBeInTheDocument()
    expect(screen.queryByLabelText('WIP 초과')).not.toBeInTheDocument()
  })

  it('S1c: wipLimit 있음·초과이면 warning 경고 배지 + "WIP 초과" aria-label이 표시된다', () => {
    render(<WipCountBadge count={3} wipLimit={2} wipExceeded={true} />)
    expect(screen.getByText('3/2')).toBeInTheDocument()
    expect(screen.getByLabelText('WIP 초과')).toBeInTheDocument()
    // warning 클래스가 있어야 한다
    const badge = screen.getByLabelText('WIP 초과')
    expect(badge.className).toMatch(/warning/)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S2. isFilterActive=true — WIP 초과 경고 약화 + "(필터됨)" 표시
// ─────────────────────────────────────────────────────────────────────────────

describe('WipCountBadge — S2 isFilterActive=true 방어', () => {
  it('S2a: wipExceeded=true·isFilterActive=true → 경고색(warning) 없음', () => {
    render(
      <WipCountBadge count={2} wipLimit={3} wipExceeded={true} isFilterActive={true} />,
    )
    // "WIP 초과" aria-label이 없어야 한다 — 경고 의미 약화
    expect(screen.queryByLabelText('WIP 초과')).not.toBeInTheDocument()
    // warning 클래스가 없어야 한다
    const badge = screen.getByText(/2\/3/)
    expect(badge.className).not.toMatch(/warning/)
  })

  it('S2b: wipExceeded=true·isFilterActive=true → "(필터됨)" 텍스트가 표시된다', () => {
    render(
      <WipCountBadge count={2} wipLimit={3} wipExceeded={true} isFilterActive={true} />,
    )
    expect(screen.getByText('(필터됨)')).toBeInTheDocument()
  })

  it('S2c: wipExceeded=true·isFilterActive=true → 필터 경고 aria-label이 있다', () => {
    render(
      <WipCountBadge count={2} wipLimit={3} wipExceeded={true} isFilterActive={true} />,
    )
    // 필터 적용 중임을 알리는 aria-label
    const badge = screen.getByText(/2\/3/)
    expect(badge.getAttribute('aria-label')).toMatch(/필터/)
  })

  it('S2d: wipExceeded=false·isFilterActive=true → 기존 중립 배지 그대로, "(필터됨)" 없음', () => {
    render(
      <WipCountBadge count={2} wipLimit={5} wipExceeded={false} isFilterActive={true} />,
    )
    expect(screen.getByText('2/5')).toBeInTheDocument()
    expect(screen.queryByText('(필터됨)')).not.toBeInTheDocument()
  })

  it('S2e: wipLimit=null·isFilterActive=true → 단순 카드 수만 표시, "(필터됨)" 없음', () => {
    render(
      <WipCountBadge count={4} wipLimit={null} wipExceeded={false} isFilterActive={true} />,
    )
    expect(screen.getByText('4')).toBeInTheDocument()
    expect(screen.queryByText('(필터됨)')).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S3. 회귀 — isFilterActive=false(기본값)이면 기존 경고 동작 그대로
// ─────────────────────────────────────────────────────────────────────────────

describe('WipCountBadge — S3 회귀 (isFilterActive=false 명시)', () => {
  it('S3a: wipExceeded=true·isFilterActive=false → warning 경고 그대로', () => {
    render(
      <WipCountBadge count={5} wipLimit={3} wipExceeded={true} isFilterActive={false} />,
    )
    expect(screen.getByLabelText('WIP 초과')).toBeInTheDocument()
    const badge = screen.getByLabelText('WIP 초과')
    expect(badge.className).toMatch(/warning/)
    expect(screen.queryByText('(필터됨)')).not.toBeInTheDocument()
  })
})
