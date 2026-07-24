// IssueLabelsEdit 단위 테스트 — IssueMetaPanel 분해 A (FR-UX-06 PR19 Task 2)
import { describe, it, expect, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { IssueLabelsEdit } from '@/components/issue/meta/IssueLabelsEdit'
import { issueDetailStrings } from '@/i18n/ko'

// LabelAutocompleteInput 내부 useLabels/useDebounce mock — QueryClient 없이 렌더 가능하게 한다
vi.mock('@/hooks/use-labels', () => ({
  useLabels: vi.fn().mockReturnValue({ data: [], isLoading: false, isError: false }),
}))
vi.mock('@/hooks/use-debounce', () => ({
  useDebounce: (value: string) => value,
}))

describe('IssueLabelsEdit', () => {
  it('value의 각 라벨이 칩으로 렌더된다', () => {
    render(<IssueLabelsEdit value={['bug', 'urgent']} onSave={vi.fn()} canEdit={true} />)
    expect(screen.getByText('bug')).toBeInTheDocument()
    expect(screen.getByText('urgent')).toBeInTheDocument()
  })

  it('라벨 추가 후 저장 시 onSave가 새 배열로 호출된다', async () => {
    const onSave = vi.fn()
    render(<IssueLabelsEdit value={[]} onSave={onSave} canEdit={true} />)
    const user = userEvent.setup()
    const input = screen.getByPlaceholderText(issueDetailStrings.labelAddPlaceholder)
    await user.type(input, 'new-label')
    await user.keyboard('{Enter}')
    const saveBtn = screen.getByRole('button', { name: issueDetailStrings.labelsSaveButton })
    await user.click(saveBtn)
    expect(onSave).toHaveBeenCalledOnce()
    expect(onSave).toHaveBeenCalledWith(['new-label'])
  })

  it('라벨 제거 버튼 클릭 후 저장 시 해당 라벨이 제거된 배열로 onSave가 호출된다', async () => {
    const onSave = vi.fn()
    render(<IssueLabelsEdit value={['bug', 'urgent']} onSave={onSave} canEdit={true} />)
    const user = userEvent.setup()
    const removeBtns = screen.getAllByRole('button', { name: issueDetailStrings.labelRemoveLabel })
    await user.click(removeBtns[0] as HTMLElement)
    const saveBtn = screen.getByRole('button', { name: issueDetailStrings.labelsSaveButton })
    await user.click(saveBtn)
    expect(onSave).toHaveBeenCalledOnce()
    expect(onSave).toHaveBeenCalledWith(['urgent'])
  })

  it('canEdit=false이면 저장 버튼이 disabled이다', () => {
    render(<IssueLabelsEdit value={[]} onSave={vi.fn()} canEdit={false} />)
    expect(screen.getByRole('button', { name: issueDetailStrings.labelsSaveButton })).toBeDisabled()
  })

  it('20개 라벨이 있으면 추가 입력 필드가 disabled이다', () => {
    const labels = Array.from({ length: 20 }, (_, i) => `label-${i}`)
    render(<IssueLabelsEdit value={labels} onSave={vi.fn()} canEdit={true} />)
    expect(screen.getByPlaceholderText(issueDetailStrings.labelAddPlaceholder)).toBeDisabled()
  })

  it('value props가 바뀌면(refetch) 표시 라벨도 따라 바뀐다 (stale 회귀 가드)', () => {
    const { rerender } = render(<IssueLabelsEdit value={[]} onSave={vi.fn()} canEdit={true} />)
    rerender(<IssueLabelsEdit value={['refactored']} onSave={vi.fn()} canEdit={true} />)
    expect(within(screen.getByText('refactored').parentElement as HTMLElement).getByText('refactored')).toBeInTheDocument()
  })
})
