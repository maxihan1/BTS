// LabelChipsEditor 단위 테스트 — 저장 버튼 없는 순수 라벨 편집기 (FR-UX-09 F2)
import { describe, it, expect, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { LabelChipsEditor } from '@/components/issue/meta/LabelChipsEditor'
import { issueDetailStrings } from '@/i18n/ko'

// LabelAutocompleteInput 내부 useLabels/useDebounce mock — QueryClient 없이 렌더 가능하게 한다
// (형제 IssueLabelsEdit.test.tsx:9-14 와 동일 관례)
vi.mock('@/hooks/use-labels', () => ({
  useLabels: vi.fn().mockReturnValue({ data: [], isLoading: false, isError: false }),
}))
vi.mock('@/hooks/use-debounce', () => ({
  useDebounce: (value: string) => value,
}))

/** 라벨 입력창을 찾는다. */
function labelInput(): HTMLElement {
  return screen.getByPlaceholderText(issueDetailStrings.labelAddPlaceholder)
}

describe('LabelChipsEditor', () => {
  it('value의 각 라벨이 칩으로 렌더된다', () => {
    render(<LabelChipsEditor value={['bug', 'urgent']} onChange={vi.fn()} />)

    expect(screen.getByText('bug')).toBeInTheDocument()
    expect(screen.getByText('urgent')).toBeInTheDocument()
  })

  it('★저장 버튼이 없다 — 생성 폼의 「만들기」와 이름이 겹치면 E2E strict mode 가 깨진다', () => {
    render(<LabelChipsEditor value={['bug']} onChange={vi.fn()} />)

    expect(screen.queryByRole('button', { name: issueDetailStrings.labelsSaveButton })).toBeNull()
  })

  it('라벨을 추가하면 저장 버튼 없이 onChange 가 즉시 발화한다', async () => {
    const onChange = vi.fn()
    const user = userEvent.setup()
    render(<LabelChipsEditor value={[]} onChange={onChange} />)

    await user.type(labelInput(), 'backend{Enter}')

    expect(onChange).toHaveBeenCalledWith(['backend'])
  })

  it('칩을 제거하면 onChange 가 제거된 배열로 발화한다', async () => {
    const onChange = vi.fn()
    const user = userEvent.setup()
    render(<LabelChipsEditor value={['bug', 'urgent']} onChange={onChange} />)

    const chip = screen.getByText('bug').closest('span') as HTMLElement
    await user.click(within(chip).getByRole('button', { name: issueDetailStrings.labelRemoveLabel }))

    expect(onChange).toHaveBeenCalledWith(['urgent'])
  })

  // ───────────────────────────────────────────────────────────────────────────
  // 검증 4종 — 추출 과정에서 유실되면 안 된다 (백엔드 @AssertTrue 와 짝)
  // ───────────────────────────────────────────────────────────────────────────

  it('공백만 있는 라벨은 추가되지 않는다', async () => {
    const onChange = vi.fn()
    const user = userEvent.setup()
    render(<LabelChipsEditor value={[]} onChange={onChange} />)

    await user.type(labelInput(), '   {Enter}')

    expect(onChange).not.toHaveBeenCalled()
  })

  it('50자를 넘는 라벨은 추가되지 않는다', async () => {
    const onChange = vi.fn()
    const user = userEvent.setup()
    render(<LabelChipsEditor value={[]} onChange={onChange} />)

    await user.type(labelInput(), `${'a'.repeat(51)}{Enter}`)

    expect(onChange).not.toHaveBeenCalled()
  })

  it('이미 있는 라벨은 중복 추가되지 않는다', async () => {
    const onChange = vi.fn()
    const user = userEvent.setup()
    render(<LabelChipsEditor value={['bug']} onChange={onChange} />)

    await user.type(labelInput(), 'bug{Enter}')

    expect(onChange).not.toHaveBeenCalled()
  })

  it('20개에 도달하면 입력창이 비활성된다', () => {
    const labels = Array.from({ length: 20 }, (_, i) => `label-${i}`)
    render(<LabelChipsEditor value={labels} onChange={vi.fn()} />)

    expect(labelInput()).toBeDisabled()
  })
})
