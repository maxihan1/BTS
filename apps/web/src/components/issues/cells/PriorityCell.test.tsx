// 이슈 목록 우선순위 셀 편집부 테스트 — 선택 · 권한 fail-closed · 저장 중 잠금 (FR-UX-11 F9 FR6·FR10·NFR3)
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { issueDetailStrings } from '@/i18n/ko'
import { PriorityCellDisplay, PriorityCellEditor } from './PriorityCell'

describe('PriorityCellDisplay', () => {
  it('닫힌 셀도 한국어 라벨 정본을 쓴다 — 백엔드 영어 표기를 화면에 내지 않는다 (Maxi 확정 2026-08-04)', () => {
    render(<PriorityCellDisplay priority={3} />)

    expect(screen.getByText(issueDetailStrings.priorityNames[3])).toBeInTheDocument()
    // ★백엔드 priorityName('Medium')이 화면에 새어 나오면 닫힘/열림이 다른 언어로 보인다
    expect(screen.queryByText('Medium')).not.toBeInTheDocument()
  })

  it('1~5 전 범위가 라벨 정본과 1:1 대응한다', () => {
    for (const p of [1, 2, 3, 4, 5] as const) {
      const { unmount } = render(<PriorityCellDisplay priority={p} />)
      expect(screen.getByText(issueDetailStrings.priorityNames[p])).toBeInTheDocument()
      unmount()
    }
  })

  it('정본 범위를 벗어난 값은 숨기지 않고 숫자를 그대로 보인다', () => {
    // 값이 사라지면 사용자는 "우선순위가 없다"로 오독한다 — 모르는 값도 보여준다
    render(<PriorityCellDisplay priority={9} />)

    expect(screen.getByText('9')).toBeInTheDocument()
  })
})

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
