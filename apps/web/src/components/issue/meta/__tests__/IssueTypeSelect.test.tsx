// IssueTypeSelect 단위 테스트 — IssueMetaPanel 분해 A (FR-UX-06 PR19 Task 2)
import { describe, it, expect, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { IssueTypeSelect } from '@/components/issue/meta/IssueTypeSelect'
import { issueDetailStrings } from '@/i18n/ko'
import type { IssueTypeResponse } from '@/api/issue-types'

/** 테스트용 이슈 타입 목록 픽스처 */
const availableTypes: IssueTypeResponse[] = [
  { id: 1, key: 'bug', name: '버그', description: '버그', iconName: 'bug' },
  { id: 2, key: 'story', name: '스토리', description: '스토리', iconName: 'story' },
  { id: 3, key: 'task', name: '작업', description: '작업', iconName: 'task' },
]

describe('IssueTypeSelect', () => {
  it('availableTypes 옵션이 모두 렌더된다', () => {
    render(
      <IssueTypeSelect
        value={1}
        availableTypes={availableTypes}
        currentTypeId={1}
        onTypeChange={vi.fn()}
      />,
    )
    const select = screen.getByRole('combobox', { name: issueDetailStrings.typeSelectLabel })
    expect(within(select as HTMLElement).getByRole('option', { name: '버그' })).toBeInTheDocument()
    expect(within(select as HTMLElement).getByRole('option', { name: '스토리' })).toBeInTheDocument()
    expect(within(select as HTMLElement).getByRole('option', { name: '작업' })).toBeInTheDocument()
  })

  it('현재값이 value prop과 일치한다', () => {
    render(
      <IssueTypeSelect
        value={2}
        availableTypes={availableTypes}
        currentTypeId={2}
        onTypeChange={vi.fn()}
      />,
    )
    const select = screen.getByRole('combobox', { name: issueDetailStrings.typeSelectLabel }) as HTMLSelectElement
    expect(select.value).toBe('2')
  })

  it('다른 타입 선택 시 onTypeChange가 typeId(number)로 호출된다', async () => {
    const onTypeChange = vi.fn()
    render(
      <IssueTypeSelect
        value={1}
        availableTypes={availableTypes}
        currentTypeId={1}
        onTypeChange={onTypeChange}
      />,
    )
    const user = userEvent.setup()
    const select = screen.getByRole('combobox', { name: issueDetailStrings.typeSelectLabel })
    await user.selectOptions(select, '2')
    expect(onTypeChange).toHaveBeenCalledOnce()
    expect(onTypeChange).toHaveBeenCalledWith(2)
  })

  it('현재와 같은 타입 선택 시 onTypeChange가 호출되지 않는다', async () => {
    const onTypeChange = vi.fn()
    render(
      <IssueTypeSelect
        value={1}
        availableTypes={availableTypes}
        currentTypeId={1}
        onTypeChange={onTypeChange}
      />,
    )
    const user = userEvent.setup()
    const select = screen.getByRole('combobox', { name: issueDetailStrings.typeSelectLabel })
    await user.selectOptions(select, '1')
    expect(onTypeChange).not.toHaveBeenCalled()
  })

  it('WCAG AA — aria-label과 min-h-[44px] 클래스가 있다', () => {
    render(
      <IssueTypeSelect
        value={1}
        availableTypes={availableTypes}
        currentTypeId={1}
        onTypeChange={vi.fn()}
      />,
    )
    const select = screen.getByRole('combobox', { name: issueDetailStrings.typeSelectLabel })
    expect(select).toHaveAttribute('aria-label')
    expect(select.className).toMatch(/min-h-\[44px\]/)
  })
})
