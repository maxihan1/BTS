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

/**
 * 상태를 갖는 래퍼 컴포넌트 — controlled LabelAutocompleteInput 을 감싸 실제 사용 패턴 재현.
 * value/onChange 를 wrapper 내부 state로 관리하고, onCommit 호출을 기록한다.
 */
function ControlledWrapper({
  onCommit,
  disabled = false,
}: {
  onCommit: (label: string) => void
  disabled?: boolean
}) {
  const [value, setValue] = React.useState('')
  return (
    <LabelAutocompleteInput
      value={value}
      onChange={setValue}
      onCommit={onCommit}
      disabled={disabled}
    />
  )
}

import * as React from 'react'

function renderControlled(props: { disabled?: boolean } = {}) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const onCommit = vi.fn()

  const result = render(
    <QueryClientProvider client={qc}>
      <ControlledWrapper onCommit={onCommit} disabled={props.disabled} />
    </QueryClientProvider>,
  )

  return { ...result, onCommit }
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('LabelAutocompleteInput', () => {
  beforeEach(() => {
    mockUseLabels([])
  })

  it('입력 "b" 후 후보 드롭다운이 빈도순으로 렌더된다', async () => {
    // debounce 때문에 타이핑 직후 바로 B_LABELS를 반환하도록 mock
    mockUseLabels(B_LABELS)
    const user = userEvent.setup()
    renderControlled()

    const input = screen.getByRole('combobox', { name: /라벨 자동완성/i })
    await user.click(input)
    await user.type(input, 'b')

    // 드롭다운에 후보가 빈도순으로 렌더됨
    await waitFor(() => {
      expect(screen.getByRole('listbox', { name: /라벨 자동완성/i })).toBeInTheDocument()
    })
    const items = screen.getAllByRole('option')
    expect(items[0]).toHaveTextContent('bug')
    expect(items[1]).toHaveTextContent('backend')
  })

  it('후보 클릭 시 onCommit이 해당 라벨로 호출된다', async () => {
    mockUseLabels(B_LABELS)
    const user = userEvent.setup()
    const { onCommit } = renderControlled()

    const input = screen.getByRole('combobox', { name: /라벨 자동완성/i })
    await user.click(input)
    await user.type(input, 'b')

    await waitFor(() => {
      expect(screen.getByRole('option', { name: 'bug' })).toBeInTheDocument()
    })

    await user.click(screen.getByRole('option', { name: 'bug' }))
    expect(onCommit).toHaveBeenCalledWith('bug')
  })

  it('신규 라벨 입력 후 Enter 키 → onCommit이 현재 입력값으로 호출된다', async () => {
    mockUseLabels([]) // 후보 없음 — free-form 신규 라벨
    const user = userEvent.setup()
    const { onCommit } = renderControlled()

    const input = screen.getByRole('combobox', { name: /라벨 자동완성/i })
    await user.click(input)
    await user.type(input, 'my-new-label')
    await user.keyboard('{Enter}')

    expect(onCommit).toHaveBeenCalledWith('my-new-label')
  })

  it('disabled=true 시 입력이 비활성화된다', () => {
    renderControlled({ disabled: true })

    const input = screen.getByRole('combobox', { name: /라벨 자동완성/i })
    expect(input).toBeDisabled()
  })

  it('ArrowDown으로 후보 하이라이트 후 Enter → 하이라이트된 후보로 onCommit 호출', async () => {
    mockUseLabels(B_LABELS)
    const user = userEvent.setup()
    const { onCommit } = renderControlled()

    const input = screen.getByRole('combobox', { name: /라벨 자동완성/i })
    await user.click(input)
    await user.type(input, 'b')

    // 후보 목록이 렌더될 때까지 대기
    await waitFor(() => {
      expect(screen.getByRole('option', { name: 'bug' })).toBeInTheDocument()
    })

    // ArrowDown 2회 → index 0(bug) → index 1(backend) 하이라이트
    await user.keyboard('{ArrowDown}')
    await user.keyboard('{ArrowDown}')

    // index 1 후보(backend)가 aria-selected=true
    await waitFor(() => {
      expect(screen.getByRole('option', { name: 'backend' })).toHaveAttribute('aria-selected', 'true')
    })

    // Enter → 입력값(b)이 아닌 하이라이트된 후보(backend)로 확정
    await user.keyboard('{Enter}')
    expect(onCommit).toHaveBeenCalledWith('backend')
    expect(onCommit).not.toHaveBeenCalledWith('b')
  })

  it('빈 입력 포커스 시 인기 라벨이 표시된다', async () => {
    mockUseLabels(POPULAR_LABELS)
    const user = userEvent.setup()
    renderControlled()

    const input = screen.getByRole('combobox', { name: /라벨 자동완성/i })
    await user.click(input)

    await waitFor(() => {
      expect(screen.getByRole('option', { name: 'bug' })).toBeInTheDocument()
      expect(screen.getByRole('option', { name: 'feature' })).toBeInTheDocument()
    })
  })
})
