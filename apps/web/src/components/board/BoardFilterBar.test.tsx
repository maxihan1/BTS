// BoardFilterBar 컴포넌트 단위 테스트 — 담당자/라벨/컴포넌트 필터 + 칩 + 초기화 (FR-BD-02 Task-5)
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import type { BoardCardFilterParams } from '@/api/boards'
import type { UserSummary } from '@/api/users'
import type { Component } from '@/api/components'
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
  // 담당자 접근성 라벨(FilterBar S1a)·미배정 토글(FilterBar S1d)은 FilterBar.test에 동등 커버됨.
  // 이 케이스는 board-filter idPrefix 경로로 담당자 위임이 실제로 동작함을 확인하는 위임 스모크로 유지.
  it('S1b: 입력 후 검색 결과가 표시되면 클릭 시 onChange(assigneeIds 포함)가 호출된다 (위임 스모크)', async () => {
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
})

// ─────────────────────────────────────────────────────────────────────────────
// S2. 라벨 자동완성
// ─────────────────────────────────────────────────────────────────────────────

describe('BoardFilterBar — S2 라벨 자동완성', () => {
  // 라벨 input 존재(FilterBar S2a/S2b)·선택 후 칩 표시(FilterBar S2c)는 FilterBar.test에 동등 커버됨.
  // 이 케이스는 board-filter idPrefix 경로로 라벨 위임이 실제로 동작함을 확인하는 위임 스모크로 유지.
  it('S2b: 라벨 input에 값 입력 후 드롭다운에서 라벨 선택 → onChange(labels 포함)가 호출된다 (위임 스모크)', async () => {
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
})

// 컴포넌트 체크박스 렌더·onChange(FilterBar S3a/S3b)는 FilterBar.test에 동등 커버되어 제거됨.
// 담당자/라벨 칩 aria-label·제거(FilterBar S4b/S4c/S4d)도 FilterBar.test에 동등 커버되어 제거됨.

// ─────────────────────────────────────────────────────────────────────────────
// S8. 담당자 칩 라벨 안정 표시 (P2-1 버그 수정 — useUsersByIds 사용)
// useUsers(검색)가 선택 담당자를 포함하지 않아도 칩이 UUID 대신 이름을 표시해야 한다.
// ─────────────────────────────────────────────────────────────────────────────

describe('BoardFilterBar — S8 담당자 칩 라벨 안정 표시 (useUsersByIds)', () => {
  // 담당자 칩 라벨 안정 표시(useUsersByIds, FilterBar S1e)는 FilterBar.test에 동등 커버되어 제거됨.
  it('S8b: useUsersByIds가 로딩 중일 때 칩이 크래시하지 않고 렌더된다', () => {
    // useUsers 빈 배열, useUsersByIds는 로딩 중
    vi.mocked(useUsersModule.useUsers).mockReturnValueOnce(
      { data: [], isLoading: false } as unknown as ReturnType<typeof useUsersModule.useUsers>,
    )

    vi.mocked(useUsersModule.useUsersByIds).mockReturnValueOnce(
      { data: undefined, isLoading: true } as unknown as ReturnType<typeof useUsersModule.useUsersByIds>,
    )

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
  // "초기화" 버튼 렌더(FilterBar S6a)는 FilterBar.test에 동등 커버되어 제거됨.
  // 이 케이스는 board-filter idPrefix 경로로 초기화(공통 필드 비움) 위임이 실제로 동작함을 확인하는 위임 스모크로 유지.
  it('S5b: "초기화" 클릭 → 빈 필터 onChange가 호출된다 (위임 스모크)', async () => {
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

// 활성 필터 카운트(FilterBar S7a/S7b/S7c)·필터 컨트롤 접근성 라벨(FilterBar S1a/S2a/S1d)·
// 칩 제거 버튼 aria-label(FilterBar S4b/S4d)은 FilterBar.test에 동등 커버되어 제거됨.
