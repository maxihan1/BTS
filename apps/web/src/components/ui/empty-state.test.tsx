// EmptyState 프리미티브 단위 테스트 — title/description/icon/action 슬롯 렌더 검증
import { render, screen } from '@testing-library/react'
import { describe, it, expect } from 'vitest'
import { EmptyState } from './empty-state'

describe('EmptyState', () => {
  it('title과 description을 렌더한다', () => {
    render(<EmptyState title="이슈가 없습니다" description="새 이슈를 생성해 보세요" />)

    expect(screen.getByText('이슈가 없습니다')).toBeInTheDocument()
    expect(screen.getByText('새 이슈를 생성해 보세요')).toBeInTheDocument()
  })

  it('description 없이도 title만으로 렌더된다', () => {
    render(<EmptyState title="검색 결과 없음" />)

    expect(screen.getByText('검색 결과 없음')).toBeInTheDocument()
  })

  it('icon과 action 슬롯을 렌더한다', () => {
    render(
      <EmptyState
        title="이슈가 없습니다"
        icon={<svg data-testid="empty-icon" />}
        action={<button type="button">이슈 생성</button>}
      />,
    )

    expect(screen.getByTestId('empty-icon')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '이슈 생성' })).toBeInTheDocument()
  })

  // 소비처(FilteredEmptyState 흡수 예정)가 레이아웃 클래스를 주입할 수 있도록
  // 나머지 14종 프리미티브와 동일하게 className/...props를 forward해야 한다 (C5).
  it('className과 임의 div props를 루트에 forward한다', () => {
    render(<EmptyState title="빈 상태" className="my-empty" data-testid="empty-root" />)

    const root = screen.getByTestId('empty-root')
    expect(root).toHaveClass('my-empty')
    // 기존 기본 레이아웃 클래스도 유지된다 (cn 병합)
    expect(root).toHaveClass('flex')
  })
})
