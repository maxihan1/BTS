// BoardFilterBar 컴포넌트 단위 테스트 — 담당자/라벨/컴포넌트 필터 + 칩 + 초기화 (FR-BD-02 Task-5)
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import type { BoardCardFilterParams } from '@/api/boards'
import type { UserSummary } from '@/api/users'
import type { Component } from '@/api/components'
import { boardFilterLabels } from '@/i18n/board-filter-labels'
import * as useUsersModule from '@/hooks/use-users'

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

import { BoardFilterBar } from './BoardFilterBar'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function renderBar(
  value: BoardCardFilterParams = emptyFilter,
  onChange: (next: BoardCardFilterParams) => void = vi.fn(),
) {
  return render(
    <BoardFilterBar projectKey="ATLAS" value={value} onChange={onChange} />,
    { wrapper: makeWrapper() },
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// S1. 담당자 typeahead
// ─────────────────────────────────────────────────────────────────────────────

describe('BoardFilterBar — S1 담당자 typeahead', () => {
  it('S1a: 담당자 input이 접근 가능한 label을 갖는다', () => {
    renderBar()
    // aria-label 또는 htmlFor 연결로 접근 가능해야 한다
    expect(screen.getByRole('textbox', { name: /담당자/ })).toBeInTheDocument()
  })

  it('S1b: 입력 후 검색 결과가 표시되면 클릭 시 onChange(assigneeIds 포함)가 호출된다', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    renderBar(emptyFilter, onChange)

    const input = screen.getByRole('textbox', { name: /담당자/ })
    await user.click(input)
    await user.type(input, '앨리스')

    // 검색 결과 목록에서 첫 번째 사용자 선택
    const option = await screen.findByRole('button', { name: '김앨리스' })
    await user.click(option)

    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({ assigneeIds: ['user-uuid-0001'] }),
    )
  })

  it('S1c: "미배정" 토글 → onChange({ includeUnassigned: true })가 호출된다', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    renderBar(emptyFilter, onChange)

    const unassignedCheckbox = screen.getByRole('checkbox', { name: /미배정/ })
    await user.click(unassignedCheckbox)

    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({ includeUnassigned: true }),
    )
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S2. 라벨 자동완성
// ─────────────────────────────────────────────────────────────────────────────

describe('BoardFilterBar — S2 라벨 자동완성', () => {
  it('S2a: 라벨 input이 접근 가능한 label을 갖는다', () => {
    renderBar()
    // LabelAutocompleteInput 래퍼의 data-testid로 input을 찾고, 섹션 label 텍스트가 DOM에 있어야 함
    expect(screen.getByTestId('label-autocomplete-input')).toBeInTheDocument()
    expect(screen.getByText(boardFilterLabels.filter.labelLabel)).toBeInTheDocument()
  })

  it('S2b: 라벨 input에 값 입력 후 드롭다운에서 라벨 선택 → onChange(labels 포함)가 호출된다', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    renderBar(emptyFilter, onChange)

    const input = screen.getByTestId('label-autocomplete-input')
    await user.click(input)
    await user.type(input, 'b')

    // 드롭다운 후보에서 'bug' 클릭
    const option = await screen.findByText('bug')
    await user.click(option)

    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({ labels: ['bug'] }),
    )
  })

  it('S2c: 라벨 선택 후 칩이 표시된다', async () => {
    renderBar({ ...emptyFilter, labels: ['bug'] })

    expect(screen.getByText('bug')).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S3. 컴포넌트 체크박스
// ─────────────────────────────────────────────────────────────────────────────

describe('BoardFilterBar — S3 컴포넌트 체크박스', () => {
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
// S4. 필터 칩 제거
// ─────────────────────────────────────────────────────────────────────────────

describe('BoardFilterBar — S4 필터 칩 제거', () => {
  it('S4a: 담당자 칩의 ✕ 버튼은 aria-label이 있다', () => {
    const value: BoardCardFilterParams = {
      ...emptyFilter,
      assigneeIds: ['user-uuid-0001'],
    }
    renderBar(value)

    // 칩 제거 버튼에 aria-label이 있어야 함
    const removeBtn = screen.getByRole('button', { name: /김앨리스.*제거|제거.*김앨리스/ })
    expect(removeBtn).toBeInTheDocument()
  })

  it('S4b: 담당자 칩 ✕ 클릭 → 해당 id만 빠진 onChange가 호출된다', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    const value: BoardCardFilterParams = {
      ...emptyFilter,
      assigneeIds: ['user-uuid-0001', 'user-uuid-0002'],
    }
    renderBar(value, onChange)

    const removeBtn = screen.getByRole('button', { name: /김앨리스.*제거|제거.*김앨리스/ })
    await user.click(removeBtn)

    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({ assigneeIds: ['user-uuid-0002'] }),
    )
  })

  it('S4c: 라벨 칩 ✕ 클릭 → 해당 라벨만 빠진 onChange가 호출된다', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    const value: BoardCardFilterParams = {
      ...emptyFilter,
      labels: ['bug', 'feature'],
    }
    renderBar(value, onChange)

    const removeBtn = screen.getByRole('button', { name: /bug.*제거|제거.*bug/ })
    await user.click(removeBtn)

    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({ labels: ['feature'] }),
    )
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S8. 담당자 칩 라벨 안정 표시 (P2-1 버그 수정 — useUsersByIds 사용)
// useUsers(검색)가 선택 담당자를 포함하지 않아도 칩이 UUID 대신 이름을 표시해야 한다.
// ─────────────────────────────────────────────────────────────────────────────

describe('BoardFilterBar — S8 담당자 칩 라벨 안정 표시 (useUsersByIds)', () => {
  it('S8a: useUsers가 빈 배열이어도 useUsersByIds 결과로 칩에 displayName이 표시된다', () => {
    // useUsers(검색)는 빈 결과 — 검색어가 비워진 상황 시뮬레이션
    vi.mocked(useUsersModule.useUsers).mockReturnValueOnce({
      data: [],
      isLoading: false,
    } as ReturnType<typeof useUsersModule.useUsers>)

    // useUsersByIds는 선택된 담당자 정보를 반환
    vi.mocked(useUsersModule.useUsersByIds).mockReturnValueOnce({
      data: [{ id: 'user-uuid-0001', username: 'alice', displayName: '김앨리스', email: null }],
      isLoading: false,
    } as ReturnType<typeof useUsersModule.useUsersByIds>)

    const value: BoardCardFilterParams = {
      ...emptyFilter,
      assigneeIds: ['user-uuid-0001'],
    }
    renderBar(value)

    // 칩에 UUID가 아닌 displayName이 표시되어야 한다
    expect(screen.getByText('김앨리스')).toBeInTheDocument()
    expect(screen.queryByText('user-uuid-0001')).not.toBeInTheDocument()
  })

  it('S8b: useUsersByIds가 로딩 중일 때 칩이 크래시하지 않고 렌더된다', () => {
    // useUsers 빈 배열, useUsersByIds는 로딩 중
    vi.mocked(useUsersModule.useUsers).mockReturnValueOnce({
      data: [],
      isLoading: false,
    } as ReturnType<typeof useUsersModule.useUsers>)

    vi.mocked(useUsersModule.useUsersByIds).mockReturnValueOnce({
      data: undefined,
      isLoading: true,
    } as ReturnType<typeof useUsersModule.useUsersByIds>)

    const value: BoardCardFilterParams = {
      ...emptyFilter,
      assigneeIds: ['user-uuid-0001'],
    }
    renderBar(value)

    // 로딩 중에는 칩이 크래시하지 않고 렌더되어야 한다
    const chips = screen.getByRole('list', { name: '적용된 필터' })
    expect(chips).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S5. 초기화 버튼
// ─────────────────────────────────────────────────────────────────────────────

describe('BoardFilterBar — S5 초기화 버튼', () => {
  it('S5a: "초기화" 버튼이 렌더된다', () => {
    renderBar()
    expect(screen.getByRole('button', { name: '초기화' })).toBeInTheDocument()
  })

  it('S5b: "초기화" 클릭 → 빈 필터 onChange가 호출된다', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    const value: BoardCardFilterParams = {
      assigneeIds: ['user-uuid-0001'],
      includeUnassigned: true,
      labels: ['bug'],
      componentIds: ['comp-uuid-0001'],
    }
    renderBar(value, onChange)

    await user.click(screen.getByRole('button', { name: '초기화' }))

    expect(onChange).toHaveBeenCalledWith({
      assigneeIds: [],
      includeUnassigned: false,
      labels: [],
      componentIds: [],
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S6. 활성 필터 카운트 표시 (D1/D3 디자인 보강)
// ─────────────────────────────────────────────────────────────────────────────

describe('BoardFilterBar — S6 활성 필터 카운트', () => {
  it('S6a: 활성 필터가 없으면 카운트 표시가 없다', () => {
    renderBar(emptyFilter)
    expect(screen.queryByText(/개 적용/)).not.toBeInTheDocument()
  })

  it('S6b: 활성 필터가 1개 이상이면 "N개 적용 중" 텍스트가 표시된다', () => {
    const value: BoardCardFilterParams = {
      ...emptyFilter,
      labels: ['bug'],
      componentIds: ['comp-uuid-0001'],
    }
    renderBar(value)

    expect(screen.getByText(/2개 적용 중/)).toBeInTheDocument()
  })

  it('S6c: includeUnassigned도 카운트에 포함된다', () => {
    const value: BoardCardFilterParams = {
      ...emptyFilter,
      includeUnassigned: true,
    }
    renderBar(value)

    expect(screen.getByText(/1개 적용 중/)).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S7. 접근성
// ─────────────────────────────────────────────────────────────────────────────

describe('BoardFilterBar — S7 접근성', () => {
  it('S7a: 각 필터 컨트롤이 접근 가능한 label을 갖는다', () => {
    renderBar()

    // 담당자 textbox — aria-label 직접 연결
    expect(screen.getByRole('textbox', { name: /담당자/ })).toBeInTheDocument()
    // 라벨 textbox — LabelAutocompleteInput의 data-testid + 섹션 label 텍스트로 확인
    expect(screen.getByTestId('label-autocomplete-input')).toBeInTheDocument()
    expect(screen.getByText(boardFilterLabels.filter.labelLabel)).toBeInTheDocument()
    // 미배정 체크박스
    expect(screen.getByRole('checkbox', { name: /미배정/ })).toBeInTheDocument()
  })

  it('S7b: value에 담당자가 있을 때 칩 제거 버튼에 aria-label이 있다', () => {
    const value: BoardCardFilterParams = {
      ...emptyFilter,
      assigneeIds: ['user-uuid-0001'],
    }
    renderBar(value)

    const removeBtns = screen.getAllByRole('button', { name: /제거/ })
    expect(removeBtns.length).toBeGreaterThanOrEqual(1)
    for (const btn of removeBtns) {
      expect(btn).toHaveAttribute('aria-label')
    }
  })

  it('S7c: value에 라벨이 있을 때 칩 제거 버튼에 aria-label이 있다', () => {
    const value: BoardCardFilterParams = {
      ...emptyFilter,
      labels: ['bug'],
    }
    renderBar(value)

    const removeBtns = screen.getAllByRole('button', { name: /제거/ })
    expect(removeBtns.length).toBeGreaterThanOrEqual(1)
    for (const btn of removeBtns) {
      expect(btn).toHaveAttribute('aria-label')
    }
  })
})
