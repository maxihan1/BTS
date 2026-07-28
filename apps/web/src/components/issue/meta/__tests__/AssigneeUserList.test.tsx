// AssigneeUserList 단위 테스트 — IssueMetaPanel 분해 B (FR-UX-06 PR19 Task 3)
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { UserSummary } from '@/api/users'
import { AssigneeUserList } from '@/components/issue/meta/AssigneeUserList'

const usersFixture: UserSummary[] = [
  { id: '00000000-0000-4000-8000-000000000001', username: 'alice', displayName: '김앨리스', email: null },
  { id: '00000000-0000-4000-8000-000000000002', username: 'bob', displayName: null, email: null },
]

describe('AssigneeUserList', () => {
  it('사용자 목록이 버튼으로 렌더된다 — displayName 우선, 없으면 username', () => {
    render(<AssigneeUserList users={usersFixture} onSelect={vi.fn()} />)
    expect(screen.getByRole('button', { name: '김앨리스' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'bob' })).toBeInTheDocument()
  })

  it('사용자 클릭 시 onSelect(userId)가 호출된다', async () => {
    const onSelect = vi.fn()
    render(<AssigneeUserList users={usersFixture} onSelect={onSelect} />)
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: '김앨리스' }))
    expect(onSelect).toHaveBeenCalledOnce()
    expect(onSelect).toHaveBeenCalledWith('00000000-0000-4000-8000-000000000001')
  })

  it('WCAG AA — 각 버튼이 min-h-[44px] 클래스를 가진다', () => {
    render(<AssigneeUserList users={usersFixture} onSelect={vi.fn()} />)
    const button = screen.getByRole('button', { name: '김앨리스' })
    expect(button.className).toMatch(/min-h-\[44px\]/)
  })
})
