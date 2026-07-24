// IssueAssigneeSelect 단위 테스트 — IssueMetaPanel 분해 B (FR-UX-06 PR19 Task 3)
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { UserSummary } from '@/api/users'
import { IssueAssigneeSelect } from '@/components/issue/meta/IssueAssigneeSelect'
import { issueDetailStrings } from '@/i18n/ko'

const aliceFixture: UserSummary = {
  id: 'c3d4e5f6-a7b8-4c9d-ae1f-2a3b4c5d6e7f',
  username: 'alice',
  displayName: '김앨리스',
  email: null,
}
const bobFixture: UserSummary = {
  id: 'd4e5f6a7-b8c9-4d0e-af1f-3b4c5d6e7f8a',
  username: 'bob',
  displayName: null,
  email: null,
}

describe('IssueAssigneeSelect', () => {
  it('currentAssignee=null이면 미지정 텍스트가 표시된다', () => {
    render(
      <IssueAssigneeSelect
        value={null}
        currentAssignee={null}
        users={[]}
        onSearch={vi.fn()}
        onAssigneeChange={vi.fn()}
        canEdit
      />,
    )
    expect(screen.getByTestId('assignee-current-name')).toHaveTextContent(issueDetailStrings.assigneeUnassigned)
  })

  it('currentAssignee가 있으면 displayName이 표시된다', () => {
    render(
      <IssueAssigneeSelect
        value={aliceFixture.id}
        currentAssignee={aliceFixture}
        users={[]}
        onSearch={vi.fn()}
        onAssigneeChange={vi.fn()}
        canEdit
      />,
    )
    expect(screen.getByTestId('assignee-current-name')).toHaveTextContent('김앨리스')
  })

  it('검색 input에 입력 시 onSearch(query)가 호출된다', async () => {
    const onSearch = vi.fn()
    render(
      <IssueAssigneeSelect
        value={null}
        currentAssignee={null}
        users={[]}
        onSearch={onSearch}
        onAssigneeChange={vi.fn()}
        canEdit
      />,
    )
    const user = userEvent.setup()
    const input = screen.getByRole('textbox', { name: issueDetailStrings.assigneeSearchPlaceholder })
    await user.type(input, 'ali')
    expect(onSearch).toHaveBeenCalled()
    expect(onSearch).toHaveBeenLastCalledWith('ali')
  })

  it('검색 결과 목록에서 사용자 선택 시 onAssigneeChange(userId)가 호출된다', async () => {
    const onAssigneeChange = vi.fn()
    render(
      <IssueAssigneeSelect
        value={null}
        currentAssignee={null}
        users={[bobFixture]}
        onSearch={vi.fn()}
        onAssigneeChange={onAssigneeChange}
        canEdit
      />,
    )
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: 'bob' }))
    expect(onAssigneeChange).toHaveBeenCalledOnce()
    expect(onAssigneeChange).toHaveBeenCalledWith(bobFixture.id)
  })

  it('할당된 상태에서 해제 버튼 클릭 시 onAssigneeChange(null)이 호출된다', async () => {
    const onAssigneeChange = vi.fn()
    render(
      <IssueAssigneeSelect
        value={aliceFixture.id}
        currentAssignee={aliceFixture}
        users={[]}
        onSearch={vi.fn()}
        onAssigneeChange={onAssigneeChange}
        canEdit
      />,
    )
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: issueDetailStrings.assigneeUnassignButton }))
    expect(onAssigneeChange).toHaveBeenCalledOnce()
    expect(onAssigneeChange).toHaveBeenCalledWith(null)
  })

  it('value=null이면 해제 버튼이 렌더되지 않는다', () => {
    render(
      <IssueAssigneeSelect
        value={null}
        currentAssignee={null}
        users={[]}
        onSearch={vi.fn()}
        onAssigneeChange={vi.fn()}
        canEdit
      />,
    )
    expect(
      screen.queryByRole('button', { name: issueDetailStrings.assigneeUnassignButton }),
    ).not.toBeInTheDocument()
  })

  it('canEdit=false이면 검색 input과 해제 버튼이 disabled된다', () => {
    render(
      <IssueAssigneeSelect
        value={aliceFixture.id}
        currentAssignee={aliceFixture}
        users={[]}
        onSearch={vi.fn()}
        onAssigneeChange={vi.fn()}
        canEdit={false}
      />,
    )
    expect(screen.getByRole('textbox', { name: issueDetailStrings.assigneeSearchPlaceholder })).toBeDisabled()
    expect(screen.getByRole('button', { name: issueDetailStrings.assigneeUnassignButton })).toBeDisabled()
  })
})
