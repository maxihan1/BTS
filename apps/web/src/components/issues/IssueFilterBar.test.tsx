// IssueFilterBar 컴포넌트 단위 테스트 — status/담당자/라벨/컴포넌트 필터 + 칩 + 초기화 (FR-SR-01 Task 4)
import { describe, it, expect, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import type { IssueFilterParams } from '@/api/issues'
import type { UserSummary } from '@/api/users'
import type { Component } from '@/api/components'
import type { WorkflowView } from '@/api/workflows'
import * as useWorkflowsModule from '@/hooks/use-workflows'

// ─────────────────────────────────────────────────────────────────────────────
// 훅 mock — 데이터 페칭 없이 순수 UI 단위 테스트
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

const mockWorkflows: WorkflowView[] = [
  {
    key: 'software-default',
    name: '소프트웨어 기본',
    description: '',
    states: [
      { key: 'open', name: 'Open', category: 'TODO', displayOrder: 1 },
      { key: 'in_progress', name: 'In Progress', category: 'IN_PROGRESS', displayOrder: 2 },
      { key: 'done', name: 'Done', category: 'DONE', displayOrder: 3 },
    ],
    transitions: [],
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

vi.mock('@/hooks/use-workflows', () => ({
  useWorkflows: vi.fn(() => ({ data: mockWorkflows, isLoading: false, isError: false })),
  extractStatusOptions: vi.fn((workflows: WorkflowView[]) => {
    const seen = new Map<string, { key: string; name: string; displayOrder: number }>()
    for (const wf of workflows) {
      for (const state of wf.states) {
        if (!seen.has(state.key)) {
          seen.set(state.key, { key: state.key, name: state.name, displayOrder: state.displayOrder })
        }
      }
    }
    return [...seen.values()]
      .sort((a, b) => {
        const diff = a.displayOrder - b.displayOrder
        if (diff !== 0) return diff
        return a.key < b.key ? -1 : a.key > b.key ? 1 : 0
      })
      .map(({ key, name }) => ({ key, name }))
  }),
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

const emptyFilter: IssueFilterParams = {
  statusKeys: [],
  assigneeIds: [],
  includeUnassigned: false,
  labels: [],
  componentIds: [],
}

// ─────────────────────────────────────────────────────────────────────────────
// import — RED 단계: 아직 존재하지 않음
// ─────────────────────────────────────────────────────────────────────────────

import { IssueFilterBar } from './IssueFilterBar'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function renderBar(
  value: IssueFilterParams = emptyFilter,
  onChange: (next: IssueFilterParams) => void = vi.fn(),
) {
  return render(
    <IssueFilterBar projectKey="ATLAS" value={value} onChange={onChange} />,
    { wrapper: makeWrapper() },
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// S1. status 멀티셀렉트
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueFilterBar — S1 status 멀티셀렉트', () => {
  it('S1a: 상태 체크박스가 name으로 렌더된다 (key 아님)', () => {
    renderBar()
    // 체크박스 라벨이 name(Open, In Progress, Done)으로 표시되어야 한다
    expect(screen.getByRole('checkbox', { name: 'Open' })).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: 'In Progress' })).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: 'Done' })).toBeInTheDocument()
    // key('open', 'in_progress')가 아닌 name을 표시해야 한다
    expect(screen.queryByRole('checkbox', { name: 'open' })).not.toBeInTheDocument()
  })

  it('S1b: 상태 체크 → onChange({ statusKeys: [key] })가 호출된다', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    renderBar(emptyFilter, onChange)

    await user.click(screen.getByRole('checkbox', { name: 'Open' }))

    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({ statusKeys: ['open'] }),
    )
  })

  it('S1c: 두 상태 체크 → statusKeys에 두 키가 포함된다', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    renderBar({ ...emptyFilter, statusKeys: ['open'] }, onChange)

    await user.click(screen.getByRole('checkbox', { name: 'In Progress' }))

    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({ statusKeys: expect.arrayContaining(['open', 'in_progress']) }),
    )
  })

  it('S1d: 이미 선택된 상태를 다시 클릭하면 statusKeys에서 제거된다', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    renderBar({ ...emptyFilter, statusKeys: ['open', 'done'] }, onChange)

    await user.click(screen.getByRole('checkbox', { name: 'Open' }))

    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({ statusKeys: ['done'] }),
    )
  })

  it('S1e: 선택된 상태 키에 해당하는 칩에 name이 표시된다 (key 아님)', () => {
    renderBar({ ...emptyFilter, statusKeys: ['open', 'done'] })

    // 칩에 Open, Done이 표시되어야 한다
    const chipList = screen.getByRole('list', { name: '적용된 필터' })
    expect(within(chipList).getByText('Open')).toBeInTheDocument()
    expect(within(chipList).getByText('Done')).toBeInTheDocument()
    // 키('open', 'done')가 칩 텍스트로 직접 표시되면 안 된다
    expect(within(chipList).queryByText('open')).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S2. 담당자 typeahead
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueFilterBar — S2 담당자 typeahead', () => {
  // 담당자 접근성 라벨(FilterBar S1a)·미배정 토글(FilterBar S1d)은 FilterBar.test에 동등 커버됨.
  // 이 케이스는 issue-filter idPrefix 경로로 담당자 위임이 실제로 동작함을 확인하는 위임 스모크로 유지.
  it('S2b: 입력 후 검색 결과가 표시되면 클릭 시 onChange(assigneeIds 포함)가 호출된다 (위임 스모크)', async () => {
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
})

// ─────────────────────────────────────────────────────────────────────────────
// S3. 라벨 자동완성
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueFilterBar — S3 라벨 자동완성', () => {
  // 라벨 input 존재(FilterBar S2a/S2b)·선택 후 칩 표시(FilterBar S2c)는 FilterBar.test에 동등 커버됨.
  // 이 케이스는 issue-filter idPrefix 경로로 라벨 위임이 실제로 동작함을 확인하는 위임 스모크로 유지.
  it('S3b: 라벨 선택 → onChange(labels 포함)가 호출된다 (위임 스모크)', async () => {
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
})

// 컴포넌트 체크박스 렌더·onChange(FilterBar S3a/S3b)는 FilterBar.test에 동등 커버되어 제거됨.

// ─────────────────────────────────────────────────────────────────────────────
// S5. 칩 개별 제거
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueFilterBar — S5 칩 개별 제거', () => {
  it('S5a: 상태 칩 ✕ 클릭 → 해당 상태만 빠진 onChange가 호출된다', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    renderBar({ ...emptyFilter, statusKeys: ['open', 'done'] }, onChange)

    const chipList = screen.getByRole('list', { name: '적용된 필터' })
    const removeBtn = within(chipList).getByRole('button', { name: /Open.*제거|제거.*Open/ })
    await user.click(removeBtn)

    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({ statusKeys: ['done'] }),
    )
  })

  // 담당자/라벨 칩 제거(FilterBar S4c/S4d)는 FilterBar.test에 동등 커버되어 제거됨.
})

// ─────────────────────────────────────────────────────────────────────────────
// S6. 초기화 버튼
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueFilterBar — S6 초기화 버튼', () => {
  // "초기화" 버튼 렌더(FilterBar S6a)는 FilterBar.test에 동등 커버되어 제거됨.
  it('S6b: "초기화" 클릭 → statusKeys까지 비운 onChange가 호출된다 (onReset 위임)', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    renderBar({
      statusKeys: ['open'],
      assigneeIds: ['user-uuid-0001'],
      includeUnassigned: true,
      labels: ['bug'],
      componentIds: ['comp-uuid-0001'],
    }, onChange)

    await user.click(screen.getByRole('button', { name: '초기화' }))

    expect(onChange).toHaveBeenCalledWith({
      statusKeys: [],
      assigneeIds: [],
      includeUnassigned: false,
      labels: [],
      componentIds: [],
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S7. 활성 필터 카운트 표시
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueFilterBar — S7 활성 필터 카운트', () => {
  // 카운트 없음(FilterBar S7a)·includeUnassigned 반영(FilterBar S7c)은 FilterBar.test에 동등 커버되어 제거됨.
  it('S7b: statusKeys + labels 2개 → "2개 적용 중" 표시', () => {
    renderBar({ ...emptyFilter, statusKeys: ['open'], labels: ['bug'] })
    expect(screen.getByText(/2개 적용 중/)).toBeInTheDocument()
  })

  it('S7d: status 2개 + 담당자 1개 + 미배정 + 라벨 1개 + 컴포넌트 1개 = 6개', () => {
    renderBar({
      statusKeys: ['open', 'done'],
      assigneeIds: ['user-uuid-0001'],
      includeUnassigned: true,
      labels: ['bug'],
      componentIds: ['comp-uuid-0001'],
    })
    expect(screen.getByText(/6개 적용 중/)).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S8. 접근성 — label 연결, 칩 aria-label
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueFilterBar — S8 접근성', () => {
  it('S8a: 각 필터 컨트롤이 접근 가능한 label을 갖는다', () => {
    renderBar()

    expect(screen.getByRole('textbox', { name: /담당자/ })).toBeInTheDocument()
    expect(screen.getByTestId('label-autocomplete-input')).toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: /미배정/ })).toBeInTheDocument()
    // status 섹션
    expect(screen.getByRole('checkbox', { name: 'Open' })).toBeInTheDocument()
  })

  it('S8b: 상태 칩 제거 버튼에 aria-label이 있다', () => {
    renderBar({ ...emptyFilter, statusKeys: ['open'] })

    const chipList = screen.getByRole('list', { name: '적용된 필터' })
    const removeBtns = within(chipList).getAllByRole('button', { name: /제거/ })
    expect(removeBtns.length).toBeGreaterThanOrEqual(1)
    for (const btn of removeBtns) {
      expect(btn).toHaveAttribute('aria-label')
    }
  })

  // 담당자 칩 제거 버튼 aria-label(FilterBar S4b)은 FilterBar.test에 동등 커버되어 제거됨.
})

// 담당자 칩 라벨 안정 표시(useUsersByIds, FilterBar S1e)는 FilterBar.test에 동등 커버되어 제거됨.

// ─────────────────────────────────────────────────────────────────────────────
// S10. EC7 fail-safe — useWorkflows 로딩/에러 시 나머지 필터 정상 동작
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueFilterBar — S10 useWorkflows EC7 fail-safe', () => {
  it('S10a: useWorkflows 로딩 중에도 담당자 필터는 정상 렌더된다', () => {
    vi.mocked(useWorkflowsModule.useWorkflows).mockReturnValueOnce(
      { data: undefined, isLoading: true, isError: false } as unknown as ReturnType<typeof useWorkflowsModule.useWorkflows>,
    )
    renderBar()

    // 담당자 섹션은 정상 렌더되어야 한다
    expect(screen.getByRole('textbox', { name: /담당자/ })).toBeInTheDocument()
    // 상태 체크박스는 표시되지 않아야 한다 (옵션 없음)
    expect(screen.queryByRole('checkbox', { name: 'Open' })).not.toBeInTheDocument()
  })

  it('S10b: useWorkflows 에러 시 throw 없이 나머지 필터가 정상 렌더된다', () => {
    vi.mocked(useWorkflowsModule.useWorkflows).mockReturnValueOnce(
      { data: undefined, isLoading: false, isError: true } as unknown as ReturnType<typeof useWorkflowsModule.useWorkflows>,
    )
    // 에러가 throw되지 않아야 한다
    expect(() => renderBar()).not.toThrow()
    // 담당자 섹션은 정상 렌더
    expect(screen.getByRole('textbox', { name: /담당자/ })).toBeInTheDocument()
  })
})
