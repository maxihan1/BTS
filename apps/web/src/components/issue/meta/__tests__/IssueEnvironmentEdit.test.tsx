// IssueEnvironmentEdit 단위 테스트 — IssueMetaPanel 분해 A (FR-UX-06 PR19 Task 2)
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { IssueEnvironmentEdit } from '@/components/issue/meta/IssueEnvironmentEdit'
import { issueDetailStrings } from '@/i18n/ko'

describe('IssueEnvironmentEdit', () => {
  it('value=null이면 textarea가 비어 있다', () => {
    render(<IssueEnvironmentEdit value={null} onSave={vi.fn()} canEdit={true} />)
    const textarea = screen.getByPlaceholderText(issueDetailStrings.environmentPlaceholder) as HTMLTextAreaElement
    expect(textarea.value).toBe('')
  })

  it('value가 있으면 textarea에 표시된다', () => {
    render(<IssueEnvironmentEdit value="Chrome 125 / macOS 14" onSave={vi.fn()} canEdit={true} />)
    const textarea = screen.getByPlaceholderText(issueDetailStrings.environmentPlaceholder) as HTMLTextAreaElement
    expect(textarea.value).toBe('Chrome 125 / macOS 14')
  })

  it('저장 버튼 클릭 시 onSave가 편집값으로 호출된다', async () => {
    const onSave = vi.fn()
    render(<IssueEnvironmentEdit value={null} onSave={onSave} canEdit={true} />)
    const user = userEvent.setup()
    const textarea = screen.getByPlaceholderText(issueDetailStrings.environmentPlaceholder)
    await user.type(textarea, 'Firefox 126')
    const saveBtn = screen.getByTestId('environment-save')
    await user.click(saveBtn)
    expect(onSave).toHaveBeenCalledOnce()
    expect(onSave).toHaveBeenCalledWith('Firefox 126')
  })

  it('canEdit=false이면 저장 버튼이 disabled이다', () => {
    render(<IssueEnvironmentEdit value={null} onSave={vi.fn()} canEdit={false} />)
    expect(screen.getByTestId('environment-save')).toBeDisabled()
  })

  it('value props가 바뀌면(refetch) textarea 값도 따라 바뀐다 (stale 회귀 가드)', () => {
    const { rerender } = render(<IssueEnvironmentEdit value={null} onSave={vi.fn()} canEdit={true} />)
    rerender(<IssueEnvironmentEdit value="Safari 17" onSave={vi.fn()} canEdit={true} />)
    const textarea = screen.getByPlaceholderText(issueDetailStrings.environmentPlaceholder) as HTMLTextAreaElement
    expect(textarea.value).toBe('Safari 17')
  })

  it('저장 버튼에 aria-label이 있다 (WCAG AA)', () => {
    render(<IssueEnvironmentEdit value={null} onSave={vi.fn()} canEdit={true} />)
    const saveBtn = screen.getByRole('button', { name: issueDetailStrings.environmentSaveButton })
    expect(saveBtn).toHaveAttribute('aria-label')
  })
})
