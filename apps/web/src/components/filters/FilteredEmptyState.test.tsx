// 공용 FilteredEmptyState — 화면별 문구/여백 주입 계약 검증 (FR-UX-06 PR22 Task 5)
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { FilteredEmptyState } from './FilteredEmptyState'

describe('FilteredEmptyState', () => {
  it('제목과 CTA 라벨을 prop으로 받아 렌더한다', () => {
    render(<FilteredEmptyState title="조건에 맞는 카드가 없습니다" resetLabel="필터 초기화" onReset={vi.fn()} />)
    expect(screen.getByText('조건에 맞는 카드가 없습니다')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '필터 초기화', exact: true })).toBeInTheDocument()
  })

  it('description을 주면 2행으로 렌더한다 (이슈 목록 계약)', () => {
    render(
      <FilteredEmptyState
        title="필터 조건에 맞는 이슈가 없습니다."
        description="다른 조건을 시도하거나 필터를 초기화하세요."
        resetLabel="필터 초기화"
        onReset={vi.fn()}
      />,
    )
    expect(screen.getByText('다른 조건을 시도하거나 필터를 초기화하세요.')).toBeInTheDocument()
  })

  it('description이 없으면 2행을 렌더하지 않는다 (보드 계약 — 원본 1행 유지)', () => {
    const { container } = render(
      <FilteredEmptyState title="조건에 맞는 카드가 없습니다" resetLabel="필터 초기화" onReset={vi.fn()} />,
    )
    expect(container.querySelector('[data-slot="empty-state-description"]')).toBeNull()
  })

  it('CTA 클릭 시 onReset을 호출한다', async () => {
    const onReset = vi.fn()
    render(<FilteredEmptyState title="t" resetLabel="필터 초기화" onReset={onReset} />)
    await userEvent.click(screen.getByRole('button', { name: '필터 초기화' }))
    expect(onReset).toHaveBeenCalledTimes(1)
  })

  it('className을 컨테이너에 병합한다 (화면별 여백/최소높이 보존)', () => {
    const { container } = render(
      <FilteredEmptyState title="t" resetLabel="r" onReset={vi.fn()} className="min-h-48" />,
    )
    expect(container.querySelector('[data-slot="empty-state"]')).toHaveClass('min-h-48')
  })

  it('EmptyState 프리미티브를 쓴다 (인라인 재정의 금지)', () => {
    const { container } = render(<FilteredEmptyState title="t" resetLabel="r" onReset={vi.fn()} />)
    expect(container.querySelector('[data-slot="empty-state"]')).not.toBeNull()
  })
})
