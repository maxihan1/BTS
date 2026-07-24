// IssuePrioritySelect 단위 테스트 — IssueMetaPanel 분해 A (FR-UX-06 PR19 Task 2)
import { describe, it, expect, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { IssuePrioritySelect } from '@/components/issue/meta/IssuePrioritySelect'
import { issueDetailStrings } from '@/i18n/ko'

describe('IssuePrioritySelect', () => {
  it('1~5 우선순위 옵션이 모두 렌더된다', () => {
    render(<IssuePrioritySelect value={3} onPriorityChange={vi.fn()} />)
    const select = screen.getByRole('combobox', { name: issueDetailStrings.prioritySelectLabel })
    ;([1, 2, 3, 4, 5] as const).forEach((p) => {
      expect(
        within(select as HTMLElement).getByRole('option', { name: issueDetailStrings.priorityNames[p] }),
      ).toBeInTheDocument()
    })
  })

  it('현재값이 value prop에서 파생된다', () => {
    render(<IssuePrioritySelect value={2} onPriorityChange={vi.fn()} />)
    const select = screen.getByRole('combobox', { name: issueDetailStrings.prioritySelectLabel }) as HTMLSelectElement
    expect(select.value).toBe('2')
  })

  it('선택 변경 시 onPriorityChange가 number로 호출된다', async () => {
    const onPriorityChange = vi.fn()
    render(<IssuePrioritySelect value={3} onPriorityChange={onPriorityChange} />)
    const user = userEvent.setup()
    const select = screen.getByRole('combobox', { name: issueDetailStrings.prioritySelectLabel })
    await user.selectOptions(select, '1')
    expect(onPriorityChange).toHaveBeenCalledOnce()
    expect(onPriorityChange).toHaveBeenCalledWith(1)
  })

  it('WCAG AA — aria-label과 min-h-[44px] 클래스가 있다', () => {
    render(<IssuePrioritySelect value={3} onPriorityChange={vi.fn()} />)
    const select = screen.getByRole('combobox', { name: issueDetailStrings.prioritySelectLabel })
    expect(select).toHaveAttribute('aria-label')
    expect(select.className).toMatch(/min-h-\[44px\]/)
  })
})
