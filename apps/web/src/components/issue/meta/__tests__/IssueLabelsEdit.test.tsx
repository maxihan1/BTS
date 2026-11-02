// IssueLabelsEdit 단위 테스트 — IssueMetaPanel 분해 A (FR-UX-06 PR19 Task 2) · 「누르면 편집」(JV-17)
import { describe, it, expect, vi } from 'vitest'
import { createRef } from 'react'
import { render, screen, act } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { IssueLabelsEdit } from '@/components/issue/meta/IssueLabelsEdit'
import type { FocusHandle } from '@/components/issue/meta/use-inline-edit'
import { issueDetailStrings } from '@/i18n/ko'

// LabelAutocompleteInput 내부 useLabels/useDebounce mock — QueryClient 없이 렌더 가능하게 한다
vi.mock('@/hooks/use-labels', () => ({
  useLabels: vi.fn().mockReturnValue({ data: [], isLoading: false, isError: false }),
}))
vi.mock('@/hooks/use-debounce', () => ({
  useDebounce: (value: string) => value,
}))

/** 라벨 입력창 */
function labelInput(): HTMLElement {
  return screen.getByPlaceholderText(issueDetailStrings.labelAddPlaceholder)
}

/** 여는 단추 */
function trigger(): HTMLElement {
  return screen.getByTestId('labels-edit-trigger')
}

describe('IssueLabelsEdit — 평소(접힘)', () => {
  it('value의 각 라벨이 칩으로만 보이고 편집기는 없다', () => {
    render(<IssueLabelsEdit value={['bug', 'urgent']} onSave={vi.fn()} canEdit={true} />)
    expect(trigger()).toHaveTextContent('bug')
    expect(trigger()).toHaveTextContent('urgent')
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
    expect(screen.queryByTestId('labels-save')).not.toBeInTheDocument()
  })

  it('라벨이 없으면 「라벨 추가」 글자가 보인다', () => {
    render(<IssueLabelsEdit value={[]} onSave={vi.fn()} canEdit={true} />)
    expect(trigger()).toHaveTextContent(issueDetailStrings.labelAddPlaceholder)
  })

  it('canEdit=false이면 여는 단추가 disabled이다', () => {
    render(<IssueLabelsEdit value={[]} onSave={vi.fn()} canEdit={false} />)
    expect(trigger()).toBeDisabled()
  })

  it('value props가 바뀌면(refetch) 표시 라벨도 따라 바뀐다 (stale 회귀 가드)', () => {
    const { rerender } = render(<IssueLabelsEdit value={[]} onSave={vi.fn()} canEdit={true} />)
    rerender(<IssueLabelsEdit value={['refactored']} onSave={vi.fn()} canEdit={true} />)
    expect(trigger()).toHaveTextContent('refactored')
  })
})

describe('IssueLabelsEdit — 누르면 편집', () => {
  it('누르면 편집기가 열리고 라벨 입력에 포커스가 간다', async () => {
    const user = userEvent.setup()
    render(<IssueLabelsEdit value={[]} onSave={vi.fn()} canEdit={true} />)
    await user.click(trigger())
    expect(labelInput()).toHaveFocus()
  })

  it('라벨 추가 후 저장 시 onSave가 새 배열로 호출되고 닫힌다', async () => {
    const onSave = vi.fn()
    const user = userEvent.setup()
    render(<IssueLabelsEdit value={[]} onSave={onSave} canEdit={true} />)
    await user.click(trigger())
    await user.type(labelInput(), 'new-label')
    await user.keyboard('{Enter}')
    await user.click(screen.getByRole('button', { name: issueDetailStrings.labelsSaveButton }))
    expect(onSave).toHaveBeenCalledOnce()
    expect(onSave).toHaveBeenCalledWith(['new-label'])
    expect(trigger()).toHaveFocus()
  })

  it('라벨 제거 버튼 클릭 후 저장 시 해당 라벨이 제거된 배열로 onSave가 호출된다', async () => {
    const onSave = vi.fn()
    const user = userEvent.setup()
    render(<IssueLabelsEdit value={['bug', 'urgent']} onSave={onSave} canEdit={true} />)
    await user.click(trigger())
    const removeBtns = screen.getAllByRole('button', { name: issueDetailStrings.labelRemoveLabel })
    await user.click(removeBtns[0] as HTMLElement)
    await user.click(screen.getByRole('button', { name: issueDetailStrings.labelsSaveButton }))
    expect(onSave).toHaveBeenCalledWith(['urgent'])
  })

  it('취소하면 저장하지 않고 고치던 칩을 버린다', async () => {
    const onSave = vi.fn()
    const user = userEvent.setup()
    render(<IssueLabelsEdit value={['bug']} onSave={onSave} canEdit={true} />)
    await user.click(trigger())
    await user.click(screen.getByRole('button', { name: issueDetailStrings.labelRemoveLabel }))
    await user.click(screen.getByRole('button', { name: issueDetailStrings.labelsCancelButton }))
    expect(onSave).not.toHaveBeenCalled()
    expect(trigger()).toHaveTextContent('bug')
    // 다시 열어도 버린 편집이 살아나지 않는다
    await user.click(trigger())
    expect(screen.getByText('bug')).toBeInTheDocument()
  })

  it('Esc 는 저장하지 않고 닫으며 기본 동작을 막는다(상세 창이 같이 닫히지 않게)', async () => {
    const onSave = vi.fn()
    const user = userEvent.setup()
    const seen: boolean[] = []
    const record = (e: KeyboardEvent): void => { seen.push(e.defaultPrevented) }
    document.addEventListener('keydown', record)
    render(<IssueLabelsEdit value={[]} onSave={onSave} canEdit={true} />)
    await user.click(trigger())
    await user.keyboard('{Escape}')
    document.removeEventListener('keydown', record)
    expect(onSave).not.toHaveBeenCalled()
    expect(screen.queryByTestId('labels-save')).not.toBeInTheDocument()
    expect(seen).toEqual([true])
    expect(trigger()).toHaveFocus()
  })

  it('바깥을 눌러도 닫히지 않는다 — 고치던 칩을 말없이 잃지 않게', async () => {
    const user = userEvent.setup()
    render(
      <div>
        <p>바깥</p>
        <IssueLabelsEdit value={[]} onSave={vi.fn()} canEdit={true} />
      </div>,
    )
    await user.click(trigger())
    await user.click(screen.getByText('바깥'))
    expect(screen.getByTestId('labels-save')).toBeInTheDocument()
  })

  it('20개 라벨이 있으면 추가 입력 필드가 disabled이다', async () => {
    const labels = Array.from({ length: 20 }, (_, i) => `label-${i}`)
    const user = userEvent.setup()
    render(<IssueLabelsEdit value={labels} onSave={vi.fn()} canEdit={true} />)
    await user.click(trigger())
    expect(labelInput()).toBeDisabled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// FR-UX-10 F11 — 단축키 `l` 손잡이 (Task 5 가 소비하는 계약 지점)
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueLabelsEdit — FR-UX-10 F11 단축키 `l` 손잡이', () => {
  it('접힌 상태에서 focusRef.focus() 가 오면 편집을 열고 라벨 입력에 포커스한다', () => {
    const focusRef = createRef<FocusHandle>()
    render(<IssueLabelsEdit value={[]} onSave={vi.fn()} canEdit={true} focusRef={focusRef} />)

    // ref 가 손잡이를 잡았는지 먼저 본다 — `?.` 가 null 을 삼켜 공허 통과하는 것을 막는다
    expect(focusRef.current).not.toBeNull()
    act(() => {
      focusRef.current?.focus()
    })
    expect(labelInput()).toHaveFocus()
  })

  it('canEdit=false 면 focusRef.focus() 가 칸을 열지 않는다', () => {
    const focusRef = createRef<FocusHandle>()
    render(<IssueLabelsEdit value={[]} onSave={vi.fn()} canEdit={false} focusRef={focusRef} />)
    act(() => {
      focusRef.current?.focus()
    })
    expect(screen.queryByRole('textbox')).not.toBeInTheDocument()
  })

  it('focusRef 가 연결되면 여는 단추와 라벨 입력이 aria-keyshortcuts="l" 을 알린다', async () => {
    const focusRef = createRef<FocusHandle>()
    const user = userEvent.setup()
    render(<IssueLabelsEdit value={[]} onSave={vi.fn()} canEdit={true} focusRef={focusRef} />)

    expect(trigger()).toHaveAttribute('aria-keyshortcuts', 'l')
    await user.click(trigger())
    expect(labelInput()).toHaveAttribute('aria-keyshortcuts', 'l')
  })

  it('focusRef 가 없으면 aria-keyshortcuts 를 붙이지 않는다', async () => {
    const user = userEvent.setup()
    render(<IssueLabelsEdit value={[]} onSave={vi.fn()} canEdit={true} />)

    expect(trigger()).not.toHaveAttribute('aria-keyshortcuts')
    await user.click(trigger())
    expect(labelInput()).not.toHaveAttribute('aria-keyshortcuts')
  })
})
