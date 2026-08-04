// 이슈 목록 담당자 셀 편집부 테스트 — 검색·선택·해제·로딩/빈 상태·권한·Enter (FR-UX-11 F9 FR5·FR10·E6)
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { AssigneeCellEditor } from './AssigneeCell'
import type { UserSummary } from '@/api/users'

/**
 * 후보 1명 픽스처.
 *
 * `as UserSummary` 캐스팅을 쓰지 않고 전 필드를 채운다 — 캐스팅은 스키마가 자라도 조용히
 * 통과해 mock drift 를 감춘다 (PR #46 교훈). 형제 `AssigneeUserList.test.tsx` 와 같은 관례다.
 */
const MAXI: UserSummary = {
  id: '11111111-1111-1111-1111-111111111111',
  username: 'maxi',
  displayName: '맥시',
  email: null,
}

const USERS: UserSummary[] = [MAXI]

describe('AssigneeCellEditor', () => {
  it('후보를 고르면 그 UUID 로 onChange 를 부른다 (FR5)', async () => {
    const onChange = vi.fn()
    render(
      <AssigneeCellEditor
        value={null} currentAssigneeName={null} users={USERS} isLoading={false} canEdit isSaving={false}
        onSearch={vi.fn()} onChange={onChange}
      />,
    )

    await userEvent.click(screen.getByRole('button', { name: '맥시' }))

    expect(onChange).toHaveBeenCalledWith('11111111-1111-1111-1111-111111111111')
  })

  it('담당자가 있으면 해제 버튼이 null 로 onChange 를 부른다 (FR5)', async () => {
    const onChange = vi.fn()
    render(
      <AssigneeCellEditor
        value={MAXI.id} currentAssigneeName={MAXI.displayName} users={[]} isLoading={false} canEdit isSaving={false}
        onSearch={vi.fn()} onChange={onChange}
      />,
    )

    await userEvent.click(screen.getByRole('button', { name: '담당자 해제' }))

    expect(onChange).toHaveBeenCalledWith(null)
  })

  it('검색 결과가 없으면 빈 상태를 안내한다 (E6)', () => {
    render(
      <AssigneeCellEditor
        value={null} currentAssigneeName={null} users={[]} isLoading={false} canEdit isSaving={false}
        onSearch={vi.fn()} onChange={vi.fn()}
      />,
    )

    expect(screen.getByText('검색 결과가 없습니다.')).toBeInTheDocument()
  })

  it('검색 중에는 "결과 없음" 대신 진행 상태를 보인다 (디자인 리뷰 Pass 2)', () => {
    render(
      <AssigneeCellEditor
        value={null} currentAssigneeName={null} users={[]} isLoading canEdit isSaving={false}
        onSearch={vi.fn()} onChange={vi.fn()}
      />,
    )

    expect(screen.getByText('검색 중…')).toBeInTheDocument()
    expect(screen.queryByText('검색 결과가 없습니다.')).not.toBeInTheDocument()
  })

  it('popover 맨 위에 현재 담당자를 보인다 (디자인 리뷰 Pass 1)', () => {
    render(
      <AssigneeCellEditor
        value={MAXI.id} currentAssigneeName={MAXI.displayName} users={[]} isLoading={false} canEdit isSaving={false}
        onSearch={vi.fn()} onChange={vi.fn()}
      />,
    )

    expect(screen.getByTestId('cell-assignee-current')).toHaveTextContent('맥시')
  })

  it('권한이 없으면 검색창이 비활성이다 (FR10 fail-closed)', () => {
    render(
      <AssigneeCellEditor
        value={null} currentAssigneeName={null} users={USERS} isLoading={false} canEdit={false} isSaving={false}
        onSearch={vi.fn()} onChange={vi.fn()}
      />,
    )

    expect(screen.getByRole('textbox', { name: '담당자 검색' })).toBeDisabled()
  })

  it('Enter 로 폼이 제출되지 않도록 기본동작을 막는다 (FR-UX-09 F2 회귀 방지)', () => {
    render(
      <AssigneeCellEditor
        value={null} currentAssigneeName={null} users={USERS} isLoading={false} canEdit isSaving={false}
        onSearch={vi.fn()} onChange={vi.fn()}
      />,
    )

    const input = screen.getByRole('textbox', { name: '담당자 검색' })
    input.focus()
    const event = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true })
    input.dispatchEvent(event)

    expect(event.defaultPrevented).toBe(true)
  })
})
