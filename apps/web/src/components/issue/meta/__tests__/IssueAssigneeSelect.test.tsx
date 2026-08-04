// IssueAssigneeSelect 단위 테스트 — IssueMetaPanel 분해 B (FR-UX-06 PR19 Task 3)
import { describe, it, expect, vi } from 'vitest'
import { createRef } from 'react'
import { render, screen, act } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import type { UserSummary } from '@/api/users'
import { IssueAssigneeSelect } from '@/components/issue/meta/IssueAssigneeSelect'
import { issueDetailStrings } from '@/i18n/ko'

const aliceFixture: UserSummary = {
  id: '00000000-0000-4000-8000-000000000001',
  username: 'alice',
  displayName: '김앨리스',
  email: null,
}
const bobFixture: UserSummary = {
  id: '00000000-0000-4000-8000-000000000002',
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

// ─────────────────────────────────────────────────────────────────────────────
// FR-UX-10 F11 — 단축키 `a` 손잡이 (focusRef + aria-keyshortcuts)
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueAssigneeSelect — FR-UX-10 F11 단축키 `a` 손잡이', () => {
  /** 검색 input locator — 세 테스트가 같은 좌표를 쓴다 */
  function searchInput(): HTMLElement {
    return screen.getByRole('textbox', { name: issueDetailStrings.assigneeSearchPlaceholder })
  }

  it('focusRef 로 검색 입력에 포커스를 줄 수 있다', () => {
    const focusRef = createRef<HTMLInputElement>()
    render(
      <IssueAssigneeSelect
        value={null}
        currentAssignee={null}
        users={[]}
        onSearch={vi.fn()}
        onAssigneeChange={vi.fn()}
        canEdit
        focusRef={focusRef}
      />,
    )
    // ref 가 실제 DOM 노드를 잡았는지 먼저 본다 — `?.` 가 null 을 삼켜 공허 통과하는 것을 막는다
    expect(focusRef.current).not.toBeNull()
    act(() => {
      focusRef.current?.focus()
    })
    expect(searchInput()).toHaveFocus()
  })

  it('focusRef 가 연결되면 검색 입력이 aria-keyshortcuts="a" 를 알린다', () => {
    const focusRef = createRef<HTMLInputElement>()
    render(
      <IssueAssigneeSelect
        value={null}
        currentAssignee={null}
        users={[]}
        onSearch={vi.fn()}
        onAssigneeChange={vi.fn()}
        canEdit
        focusRef={focusRef}
      />,
    )
    expect(searchInput()).toHaveAttribute('aria-keyshortcuts', 'a')
  })

  it('focusRef 가 없으면 aria-keyshortcuts 를 붙이지 않는다 — 이슈 생성 폼에 `a` 는 없다', () => {
    // 이 셀렉터는 이슈 상세와 이슈 생성 폼(IssueCreateAssignmentFields)이 공유한다.
    // 무조건 붙이면 단축키가 없는 생성 폼에서 스크린리더가 없는 기능을 안내한다.
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
    expect(searchInput()).not.toHaveAttribute('aria-keyshortcuts')
  })
})
