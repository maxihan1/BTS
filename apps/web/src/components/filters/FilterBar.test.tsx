// FilterBar 공유 코어 컴포넌트 단위 테스트 — 담당자·라벨·컴포넌트·칩·초기화(공통) FR-UX-06 PR17 Task 2
import { describe, it, expect, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import type { BoardCardFilterParams } from '@/api/boards'
import type { UserSummary } from '@/api/users'
import type { Component } from '@/api/components'
import { filterBarLabels } from '@/i18n/filter-bar-labels'
import * as useUsersModule from '@/hooks/use-users'

// ─────────────────────────────────────────────────────────────────────────────
// 훅 mock — 데이터 페칭 없이 순수 UI 단위 테스트 (BoardFilterBar.test.tsx / IssueFilterBar.test.tsx 관례 동일)
// ─────────────────────────────────────────────────────────────────────────────

const mockUsers: UserSummary[] = [
  { id: 'user-uuid-0001', username: 'alice', displayName: '김앨리스', email: null },
  { id: 'user-uuid-0002', username: 'bob', displayName: '박밥', email: null },
]

const mockComponents: Component[] = [
  {
    id: 'comp-uuid-0001',
    projectId: '00000000-0000-4000-8000-000000000099',
    name: '프론트엔드',
    description: null,
    leadUserId: null,
  },
  {
    id: 'comp-uuid-0002',
    projectId: '00000000-0000-4000-8000-000000000099',
    name: '백엔드',
    description: null,
    leadUserId: null,
  },
]

vi.mock('@/hooks/use-users', () => ({
  useUsers: vi.fn(() => ({ data: mockUsers, isLoading: false })),
  useUsersByIds: vi.fn(() => ({ data: mockUsers, isLoading: false })),
}))

vi.mock('@/hooks/use-labels', () => ({
  useLabels: vi.fn(() => ({ data: ['bug', 'feature', 'docs'], isLoading: false })),
}))

vi.mock('@/hooks/use-components', () => ({
  useComponents: vi.fn(() => ({ data: mockComponents, isLoading: false })),
}))

// cmdk는 ResizeObserver 의존, jsdom에서 mock 필요
vi.mock('cmdk', () => {
  const Input = ({
    value,
    onValueChange,
    onFocus,
    onBlur,
    onKeyDown,
    disabled,
    placeholder,
    className,
    ...rest
  }: {
    value?: string
    onValueChange?: (v: string) => void
    onFocus?: () => void
    onBlur?: () => void
    onKeyDown?: (e: React.KeyboardEvent<HTMLInputElement>) => void
    disabled?: boolean
    placeholder?: string
    className?: string
    [key: string]: unknown
  }) => {
    const restRecord = rest as Record<string, unknown>
    return (
      <input
        value={value}
        onChange={(e) => onValueChange?.(e.target.value)}
        onFocus={onFocus}
        onBlur={onBlur}
        onKeyDown={onKeyDown}
        disabled={disabled}
        placeholder={placeholder}
        className={className}
        aria-label={restRecord['aria-label'] as string | undefined}
        data-testid={restRecord['data-testid'] as string | undefined}
      />
    )
  }

  const Root = ({ children }: { children: ReactNode }) => <div>{children}</div>
  Root.Input = Input

  return { Command: Root }
})

// ─────────────────────────────────────────────────────────────────────────────
// QueryClient wrapper
// ─────────────────────────────────────────────────────────────────────────────

function makeWrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={qc}>{children}</QueryClientProvider>
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 기본 필터 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const emptyFilter: BoardCardFilterParams = {
  assigneeIds: [],
  includeUnassigned: false,
  labels: [],
  componentIds: [],
}

// ─────────────────────────────────────────────────────────────────────────────
// import — RED 단계: 아직 존재하지 않음
// ─────────────────────────────────────────────────────────────────────────────

import { FilterBar } from './FilterBar'
import type { FilterChipData } from './FilterBar'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

interface RenderBarExtra {
  readonly leadingSection?: ReactNode
  readonly leadingChips?: readonly FilterChipData[]
  readonly extraActiveCount?: number
}

function renderBar(
  value: BoardCardFilterParams = emptyFilter,
  onChange: (next: BoardCardFilterParams) => void = vi.fn(),
  extra: RenderBarExtra = {},
) {
  return render(
    <FilterBar
      projectKey="ATLAS"
      value={value}
      onChange={onChange}
      idPrefix="test-filter"
      leadingSection={extra.leadingSection}
      leadingChips={extra.leadingChips}
      extraActiveCount={extra.extraActiveCount}
    />,
    { wrapper: makeWrapper() },
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// S1. 담당자 typeahead
// ─────────────────────────────────────────────────────────────────────────────

describe('FilterBar — S1 담당자 typeahead', () => {
  it('S1a: 담당자 input이 idPrefix 기반 id를 갖는다', () => {
    renderBar()
    const input = screen.getByRole('textbox', { name: /담당자/ })
    expect(input).toHaveAttribute('id', 'test-filter-assignee-input')
  })

  it('S1b: 입력 후 검색 결과가 표시되면 클릭 시 onChange에 assigneeIds가 추가된다', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    renderBar(emptyFilter, onChange)

    const input = screen.getByRole('textbox', { name: /담당자/ })
    await user.click(input)
    await user.type(input, '앨리스')

    const option = await screen.findByRole('button', { name: '김앨리스' })
    await user.click(option)

    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({ assigneeIds: ['user-uuid-0001'] }),
    )
  })

  it('S1c: 이미 선택된 담당자는 검색 결과 후보에서 제외된다 (중복 무시)', async () => {
    const user = userEvent.setup()
    renderBar({ ...emptyFilter, assigneeIds: ['user-uuid-0001'] })

    const input = screen.getByRole('textbox', { name: /담당자/ })
    await user.click(input)
    await user.type(input, '앨리스')

    // 이미 선택된 '김앨리스'는 후보 버튼으로 다시 나타나지 않는다
    expect(screen.queryByRole('button', { name: '김앨리스' })).not.toBeInTheDocument()
  })

  it('S1d: "미배정" 토글 → onChange({ includeUnassigned: true })가 호출된다', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    renderBar(emptyFilter, onChange)

    const unassignedCheckbox = screen.getByRole('checkbox', { name: /미배정/ })
    await user.click(unassignedCheckbox)

    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({ includeUnassigned: true }),
    )
  })

  it('S1e: 선택된 담당자 이름이 useUsersByIds로 안정적으로 표시된다 (검색어가 비어도 유지)', () => {
    vi.mocked(useUsersModule.useUsers).mockReturnValueOnce(
      { data: [], isLoading: false } as unknown as ReturnType<typeof useUsersModule.useUsers>,
    )
    vi.mocked(useUsersModule.useUsersByIds).mockReturnValueOnce(
      {
        data: [{ id: 'user-uuid-0001', username: 'alice', displayName: '김앨리스', email: null }],
        isLoading: false,
      } as unknown as ReturnType<typeof useUsersModule.useUsersByIds>,
    )

    renderBar({ ...emptyFilter, assigneeIds: ['user-uuid-0001'] })

    expect(screen.getByText('김앨리스')).toBeInTheDocument()
    expect(screen.queryByText('user-uuid-0001')).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S2. 라벨 자동완성
// ─────────────────────────────────────────────────────────────────────────────

describe('FilterBar — S2 라벨 자동완성', () => {
  it('S2a: 라벨 label의 htmlFor가 idPrefix 기반이다', () => {
    renderBar()
    const label = screen.getByText(filterBarLabels.filter.labelLabel)
    expect(label.tagName).toBe('LABEL')
    expect(label).toHaveAttribute('for', 'test-filter-label-input')
  })

  it('S2b: 라벨 input에 값 입력 후 드롭다운에서 라벨 선택 → onChange(labels 포함)가 호출된다', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    renderBar(emptyFilter, onChange)

    const input = screen.getByTestId('label-autocomplete-input')
    await user.click(input)
    await user.type(input, 'b')

    const option = await screen.findByText('bug')
    await user.click(option)

    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({ labels: ['bug'] }),
    )
  })

  it('S2c: 라벨 선택 후 칩이 표시된다', () => {
    renderBar({ ...emptyFilter, labels: ['bug'] })
    const chipList = screen.getByRole('list', { name: '적용된 필터' })
    expect(within(chipList).getByText('bug')).toBeInTheDocument()
  })

  it('S2d: 이미 추가된 라벨은 드롭다운 후보에서 제외된다 (중복 무시)', async () => {
    const user = userEvent.setup()
    renderBar({ ...emptyFilter, labels: ['bug'] })

    const input = screen.getByTestId('label-autocomplete-input')
    await user.click(input)
    await user.type(input, 'b')

    // 드롭다운(listbox) 안에는 'bug' 옵션이 없어야 한다 — 이미 선택된 값을 제외해 후보 목록을 구성
    const dropdown = screen.queryByRole('listbox')
    if (dropdown !== null) {
      expect(within(dropdown).queryByText('bug')).not.toBeInTheDocument()
    }
  })

  it('S2e: 이미 추가된 라벨 텍스트로 Enter 커밋해도 onChange가 호출되지 않는다 (중복 무시 가드)', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    renderBar({ ...emptyFilter, labels: ['bug'] }, onChange)

    const input = screen.getByTestId('label-autocomplete-input')
    await user.click(input)
    await user.type(input, 'bug')
    await user.keyboard('{Enter}')

    expect(onChange).not.toHaveBeenCalled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S3. 컴포넌트 멀티셀렉트
// ─────────────────────────────────────────────────────────────────────────────

describe('FilterBar — S3 컴포넌트 멀티셀렉트', () => {
  it('S3a: useComponents 결과가 체크박스로 렌더된다', () => {
    renderBar()
    expect(screen.getByRole('checkbox', { name: '프론트엔드' })).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: '백엔드' })).toBeInTheDocument()
  })

  it('S3b: 컴포넌트 체크 → onChange({ componentIds: [id] })가 호출된다', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    renderBar(emptyFilter, onChange)

    const checkbox = screen.getByRole('checkbox', { name: '프론트엔드' })
    await user.click(checkbox)

    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({ componentIds: ['comp-uuid-0001'] }),
    )
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S4. 활성 필터 칩 + 제거
// ─────────────────────────────────────────────────────────────────────────────

describe('FilterBar — S4 활성 필터 칩', () => {
  it('S4a: 칩이 없으면(leadingChips/담당자/라벨 전부 없음) "적용된 필터" 리스트가 렌더되지 않는다', () => {
    renderBar(emptyFilter)
    expect(screen.queryByRole('list', { name: '적용된 필터' })).not.toBeInTheDocument()
  })

  it('S4b: 담당자 칩 제거 버튼의 aria-label은 "{name} 제거" 형식이다', () => {
    renderBar({ ...emptyFilter, assigneeIds: ['user-uuid-0001'] })

    const chipList = screen.getByRole('list', { name: '적용된 필터' })
    const removeBtn = within(chipList).getByRole('button', {
      name: filterBarLabels.chip.removeAriaLabel('김앨리스'),
    })
    expect(removeBtn).toBeInTheDocument()
  })

  it('S4c: 담당자 칩 ✕ 클릭 → 해당 id만 빠진 onChange가 호출된다', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    renderBar(
      { ...emptyFilter, assigneeIds: ['user-uuid-0001', 'user-uuid-0002'] },
      onChange,
    )

    const chipList = screen.getByRole('list', { name: '적용된 필터' })
    const removeBtn = within(chipList).getByRole('button', {
      name: filterBarLabels.chip.removeAriaLabel('김앨리스'),
    })
    await user.click(removeBtn)

    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({ assigneeIds: ['user-uuid-0002'] }),
    )
  })

  it('S4d: 라벨 칩 ✕ 클릭 → 해당 라벨만 빠진 onChange가 호출된다', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    renderBar({ ...emptyFilter, labels: ['bug', 'feature'] }, onChange)

    const chipList = screen.getByRole('list', { name: '적용된 필터' })
    const removeBtn = within(chipList).getByRole('button', {
      name: filterBarLabels.chip.removeAriaLabel('bug'),
    })
    await user.click(removeBtn)

    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({ labels: ['feature'] }),
    )
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S5. leadingSection / leadingChips 슬롯
// ─────────────────────────────────────────────────────────────────────────────

describe('FilterBar — S5 leadingSection / leadingChips 슬롯', () => {
  it('S5a: leadingSection이 전달되면 렌더된다', () => {
    renderBar(emptyFilter, vi.fn(), {
      leadingSection: <div data-testid="leading-slot">상태 셀렉터</div>,
    })
    expect(screen.getByTestId('leading-slot')).toBeInTheDocument()
  })

  it('S5b: leadingSection이 없으면 렌더되지 않는다', () => {
    renderBar()
    expect(screen.queryByTestId('leading-slot')).not.toBeInTheDocument()
  })

  it('S5c: leadingChips가 담당자/라벨 칩보다 앞에 렌더된다', () => {
    const leadingChips: FilterChipData[] = [
      { id: 'status-open', label: 'Open', onRemove: vi.fn() },
    ]
    renderBar({ ...emptyFilter, assigneeIds: ['user-uuid-0001'] }, vi.fn(), { leadingChips })

    const chipList = screen.getByRole('list', { name: '적용된 필터' })
    const chipTexts = within(chipList).getAllByRole('listitem').map((el) => el.textContent)
    expect(chipTexts[0]).toContain('Open')
  })

  it('S5d: leadingChips 항목의 ✕ 클릭 시 해당 항목의 onRemove가 호출된다 (FilterBar onChange 아님)', async () => {
    const user = userEvent.setup()
    const onRemove = vi.fn()
    const leadingChips: FilterChipData[] = [{ id: 'status-open', label: 'Open', onRemove }]
    renderBar(emptyFilter, vi.fn(), { leadingChips })

    const chipList = screen.getByRole('list', { name: '적용된 필터' })
    const removeBtn = within(chipList).getByRole('button', {
      name: filterBarLabels.chip.removeAriaLabel('Open'),
    })
    await user.click(removeBtn)

    expect(onRemove).toHaveBeenCalledTimes(1)
  })

  it('S5e: leadingChips만 있어도 "적용된 필터" 리스트가 렌더된다', () => {
    const leadingChips: FilterChipData[] = [{ id: 'status-open', label: 'Open', onRemove: vi.fn() }]
    renderBar(emptyFilter, vi.fn(), { leadingChips })
    expect(screen.getByRole('list', { name: '적용된 필터' })).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S6. 초기화 버튼
// ─────────────────────────────────────────────────────────────────────────────

describe('FilterBar — S6 초기화 버튼', () => {
  it('S6a: "초기화" 버튼이 렌더된다', () => {
    renderBar()
    expect(screen.getByRole('button', { name: filterBarLabels.filter.reset })).toBeInTheDocument()
  })

  it('S6b: "초기화" 클릭 → 공통 필드가 빈 값으로 채워진 onChange가 호출된다', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    const value: BoardCardFilterParams = {
      assigneeIds: ['user-uuid-0001'],
      includeUnassigned: true,
      labels: ['bug'],
      componentIds: ['comp-uuid-0001'],
    }
    renderBar(value, onChange)

    await user.click(screen.getByRole('button', { name: filterBarLabels.filter.reset }))

    expect(onChange).toHaveBeenCalledWith({
      assigneeIds: [],
      includeUnassigned: false,
      labels: [],
      componentIds: [],
    })
  })

  it('S6c: onReset prop이 있으면 "초기화" 클릭 시 onReset만 호출되고 기본 공통-clear onChange는 호출되지 않는다', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    const onReset = vi.fn()
    const value: BoardCardFilterParams = {
      assigneeIds: ['user-uuid-0001'],
      includeUnassigned: true,
      labels: ['bug'],
      componentIds: ['comp-uuid-0001'],
    }
    render(
      <FilterBar
        projectKey="ATLAS"
        value={value}
        onChange={onChange}
        idPrefix="test-filter"
        onReset={onReset}
      />,
      { wrapper: makeWrapper() },
    )

    await user.click(screen.getByRole('button', { name: filterBarLabels.filter.reset }))

    expect(onReset).toHaveBeenCalledTimes(1)
    expect(onChange).not.toHaveBeenCalled()
  })

  it('S6d: onReset 미전달 시 초기화 클릭 → 담당자 검색 입력이 비워진다', async () => {
    const user = userEvent.setup()
    renderBar()

    const input = screen.getByRole('textbox', { name: /담당자/ })
    await user.click(input)
    await user.type(input, '앨리스')
    expect(input).toHaveValue('앨리스')

    await user.click(screen.getByRole('button', { name: filterBarLabels.filter.reset }))

    expect(input).toHaveValue('')
  })

  it('S6e: onReset 미전달 시 초기화 클릭 → 라벨 검색 입력이 비워진다', async () => {
    const user = userEvent.setup()
    renderBar()

    const labelInput = screen.getByTestId('label-autocomplete-input')
    await user.click(labelInput)
    await user.type(labelInput, 'b')
    expect(labelInput).toHaveValue('b')

    await user.click(screen.getByRole('button', { name: filterBarLabels.filter.reset }))

    expect(labelInput).toHaveValue('')
  })

  it('S6f: onReset 전달 시(이슈 필터 시뮬레이션)에도 초기화 클릭 → 담당자 검색 입력이 비워진다 (회귀 방지)', async () => {
    const user = userEvent.setup()
    const onReset = vi.fn()
    render(
      <FilterBar
        projectKey="ATLAS"
        value={emptyFilter}
        onChange={vi.fn()}
        idPrefix="test-filter"
        onReset={onReset}
      />,
      { wrapper: makeWrapper() },
    )

    const input = screen.getByRole('textbox', { name: /담당자/ })
    await user.click(input)
    await user.type(input, '앨리스')
    expect(input).toHaveValue('앨리스')

    await user.click(screen.getByRole('button', { name: filterBarLabels.filter.reset }))

    expect(input).toHaveValue('')
    expect(onReset).toHaveBeenCalledTimes(1)
  })

  it('S6g: onReset 전달 시(이슈 필터 시뮬레이션)에도 초기화 클릭 → 라벨 검색 입력이 비워진다 (회귀 방지)', async () => {
    const user = userEvent.setup()
    const onReset = vi.fn()
    render(
      <FilterBar
        projectKey="ATLAS"
        value={emptyFilter}
        onChange={vi.fn()}
        idPrefix="test-filter"
        onReset={onReset}
      />,
      { wrapper: makeWrapper() },
    )

    const labelInput = screen.getByTestId('label-autocomplete-input')
    await user.click(labelInput)
    await user.type(labelInput, 'b')
    expect(labelInput).toHaveValue('b')

    await user.click(screen.getByRole('button', { name: filterBarLabels.filter.reset }))

    expect(labelInput).toHaveValue('')
    expect(onReset).toHaveBeenCalledTimes(1)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S7. 활성 필터 카운트 (extraActiveCount 포함)
// ─────────────────────────────────────────────────────────────────────────────

describe('FilterBar — S7 활성 필터 카운트', () => {
  it('S7a: 활성 필터가 없으면 카운트 표시가 없다', () => {
    renderBar(emptyFilter)
    expect(screen.queryByText(/개 적용/)).not.toBeInTheDocument()
  })

  it('S7b: 담당자 1 + 라벨 1 + 컴포넌트 1 = "3개 적용 중"', () => {
    renderBar({
      assigneeIds: ['user-uuid-0001'],
      includeUnassigned: false,
      labels: ['bug'],
      componentIds: ['comp-uuid-0001'],
    })
    expect(screen.getByText(/3개 적용 중/)).toBeInTheDocument()
  })

  it('S7c: includeUnassigned도 카운트에 포함된다', () => {
    renderBar({ ...emptyFilter, includeUnassigned: true })
    expect(screen.getByText(/1개 적용 중/)).toBeInTheDocument()
  })

  it('S7d: extraActiveCount가 activeCount에 가산된다', () => {
    renderBar({ ...emptyFilter, labels: ['bug'] }, vi.fn(), { extraActiveCount: 2 })
    // 라벨 1개 + extraActiveCount 2 = 3개
    expect(screen.getByText(/3개 적용 중/)).toBeInTheDocument()
  })

  it('S7e: extraActiveCount만 있어도(다른 필드 0) 카운트가 표시된다', () => {
    renderBar(emptyFilter, vi.fn(), { extraActiveCount: 1 })
    expect(screen.getByText(/1개 적용 중/)).toBeInTheDocument()
  })
})
