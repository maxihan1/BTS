// IssueImpactSelect 단위 테스트 — IssueMetaPanel 분해 A (FR-UX-06 PR19 Task 2)
import { describe, it, expect, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { IssueImpactSelect } from '@/components/issue/meta/IssueImpactSelect'
import { issueDetailStrings } from '@/i18n/ko'

describe('IssueImpactSelect', () => {
  it('1~3 영향도 옵션이 모두 렌더된다', () => {
    render(<IssueImpactSelect value={null} onImpactChange={vi.fn()} />)
    const select = screen.getByRole('combobox', { name: issueDetailStrings.impactSelectLabel })
    ;([1, 2, 3] as const).forEach((i) => {
      expect(
        within(select as HTMLElement).getByRole('option', { name: issueDetailStrings.impactNames[i] }),
      ).toBeInTheDocument()
    })
  })

  it('value=null이면 미지정 옵션이 enabled 상태이고 현재값이 빈 문자열이다', () => {
    render(<IssueImpactSelect value={null} onImpactChange={vi.fn()} />)
    const select = screen.getByRole('combobox', { name: issueDetailStrings.impactSelectLabel }) as HTMLSelectElement
    const unsetOption = within(select).getByRole('option', { name: issueDetailStrings.impactUnset })
    expect(unsetOption).not.toBeDisabled()
    expect(select.value).toBe('')
  })

  it('value가 설정되면 미지정 옵션이 disabled이고 현재값이 해당 숫자다', () => {
    render(<IssueImpactSelect value={2} onImpactChange={vi.fn()} />)
    const select = screen.getByRole('combobox', { name: issueDetailStrings.impactSelectLabel }) as HTMLSelectElement
    const unsetOption = within(select).getByRole('option', { name: issueDetailStrings.impactUnset })
    expect(unsetOption).toBeDisabled()
    expect(select.value).toBe('2')
  })

  it('선택 변경 시 onImpactChange가 number로 호출된다', async () => {
    const onImpactChange = vi.fn()
    render(<IssueImpactSelect value={null} onImpactChange={onImpactChange} />)
    const user = userEvent.setup()
    const select = screen.getByRole('combobox', { name: issueDetailStrings.impactSelectLabel })
    await user.selectOptions(select, '1')
    expect(onImpactChange).toHaveBeenCalledOnce()
    expect(onImpactChange).toHaveBeenCalledWith(1)
  })

  it('WCAG AA — aria-label과 min-h-[44px] 클래스가 있다', () => {
    render(<IssueImpactSelect value={null} onImpactChange={vi.fn()} />)
    const select = screen.getByRole('combobox', { name: issueDetailStrings.impactSelectLabel })
    expect(select).toHaveAttribute('aria-label')
    expect(select.className).toMatch(/min-h-\[44px\]/)
  })
})
