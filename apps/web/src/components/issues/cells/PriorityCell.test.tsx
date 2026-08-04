// 이슈 목록 우선순위 셀 편집부 테스트 — 선택 · 권한 fail-closed · 저장 중 잠금 (FR-UX-11 F9 FR6·FR10·NFR3)
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { PriorityCellEditor } from './PriorityCell'

describe('PriorityCellEditor', () => {
  it('우선순위를 고르면 그 값으로 onChange 를 부른다 (FR6)', async () => {
    const onChange = vi.fn()
    render(<PriorityCellEditor value={3} canEdit onChange={onChange} isSaving={false} />)

    await userEvent.click(screen.getByRole('button', { name: '높음' }))

    expect(onChange).toHaveBeenCalledWith(2)
  })

  it('권한이 없으면 모든 선택지가 비활성이다 (FR10 fail-closed)', () => {
    render(<PriorityCellEditor value={3} canEdit={false} onChange={vi.fn()} isSaving={false} />)

    for (const button of screen.getAllByRole('button')) {
      expect(button).toBeDisabled()
    }
    expect(screen.getByText('편집 권한이 없습니다.')).toBeInTheDocument()
  })

  it('저장 중이면 비활성이다 (NFR3 중복 제출 차단)', () => {
    render(<PriorityCellEditor value={3} canEdit onChange={vi.fn()} isSaving />)

    expect(screen.getByRole('button', { name: '높음' })).toBeDisabled()
  })
})
