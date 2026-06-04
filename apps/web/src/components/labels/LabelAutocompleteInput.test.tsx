// LabelAutocompleteInput 자동완성 입력 컴포넌트 단위 테스트 — FR-IS-09 Task-5
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { LabelAutocompleteInput } from './LabelAutocompleteInput'

// ─────────────────────────────────────────────────────────────────────────────
// useLabels mock — MSW labelHandlers와 동일한 시드 데이터 사용
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('@/hooks/use-labels', () => ({
  useLabels: vi.fn(),
}))

import { useLabels } from '@/hooks/use-labels'

/** prefix 'b'에 매칭되는 라벨 목록 (빈도순) */
const B_LABELS = ['bug', 'backend', 'billing', 'breaking-change']
/** 빈 쿼리 시 인기 라벨 목록 */
const POPULAR_LABELS = ['bug', 'feature', 'frontend', 'backend', 'billing']

/** useLabels 기본 mock 반환값 헬퍼 */
function mockUseLabels(labels: string[]) {
  vi.mocked(useLabels).mockReturnValue({
    data: labels,
    isLoading: false,
    isError: false,
    isPending: false,
    isSuccess: true,
    error: null,
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
  } as any)
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function renderComponent(props: Partial<React.ComponentProps<typeof LabelAutocompleteInput>> = {}) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const onChange = vi.fn()
  const onCommit = vi.fn()

  const result = render(
    <QueryClientProvider client={qc}>
      <LabelAutocompleteInput
        value={props.value ?? ''}
        onChange={props.onChange ?? onChange}
        onCommit={props.onCommit ?? onCommit}
        disabled={props.disabled ?? false}
      />
    </QueryClientProvider>,
  )

  return { ...result, onChange, onCommit }
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('LabelAutocompleteInput', () => {
  beforeEach(() => {
    mockUseLabels([])
  })

  it('입력 "b" 후 후보 드롭다운이 빈도순으로 렌더된다', async () => {
    mockUseLabels(B_LABELS)
    renderComponent({ value: 'b' })

    const listbox = screen.getByRole('listbox', { name: /라벨 자동완성/i })
    const items = screen.getAllByRole('option')

    expect(listbox).toBeInTheDocument()
    // 빈도순 — bug가 첫번째
    expect(items[0]).toHaveTextContent('bug')
    expect(items[1]).toHaveTextContent('backend')
  })

  it('후보 클릭 시 onCommit이 해당 라벨로 호출된다', async () => {
    mockUseLabels(B_LABELS)
    const user = userEvent.setup()
    const { onCommit } = renderComponent({ value: 'b' })
    // onCommit 검증에만 집중 — onChange는 외부 controlled 컴포넌트가 처리

    const bugOption = screen.getByRole('option', { name: 'bug' })
    await user.click(bugOption)

    expect(onCommit).toHaveBeenCalledWith('bug')
  })

  it('신규 라벨 입력 후 Enter 키 → onCommit이 현재 입력값으로 호출된다', async () => {
    mockUseLabels([]) // 후보 없음 — free-form 신규 라벨
    const user = userEvent.setup()
    const { onCommit } = renderComponent({ value: 'my-new-label' })

    const input = screen.getByRole('combobox', { name: /라벨 검색/i })
    await user.click(input)
    await user.keyboard('{Enter}')

    expect(onCommit).toHaveBeenCalledWith('my-new-label')
  })

  it('disabled=true 시 입력이 비활성화된다', () => {
    renderComponent({ disabled: true })

    const input = screen.getByRole('combobox', { name: /라벨 검색/i })
    expect(input).toBeDisabled()
  })

  it('빈 입력 포커스 시 인기 라벨이 표시된다', async () => {
    mockUseLabels(POPULAR_LABELS)
    const user = userEvent.setup()
    renderComponent({ value: '' })

    const input = screen.getByRole('combobox', { name: /라벨 검색/i })
    await user.click(input)

    await waitFor(() => {
      expect(screen.getByRole('option', { name: 'bug' })).toBeInTheDocument()
      expect(screen.getByRole('option', { name: 'feature' })).toBeInTheDocument()
    })
  })
})
