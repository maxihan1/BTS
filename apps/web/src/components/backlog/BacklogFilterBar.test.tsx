// BacklogFilterBar 단위 테스트 — 제목 검색 디바운스·에픽 칩·에픽 컨트롤 부재(C4)·활성 개수 가산 (FR-UX-13 F16 Task 5)
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, within, act } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { ReactNode } from 'react'
import type { UserSummary } from '@/api/users'
import type { Component } from '@/api/components'
import { filterBarLabels } from '@/i18n/filter-bar-labels'
import { NO_EPIC, emptyBacklogFilter } from '@/lib/backlog-filter'
import type { BacklogFilter } from '@/lib/backlog-filter'

// ─────────────────────────────────────────────────────────────────────────────
// 훅 mock — 데이터 페칭 없이 순수 UI 단위 테스트 (FilterBar.test.tsx 관례 동일)
// ─────────────────────────────────────────────────────────────────────────────

const mockUsers: UserSummary[] = [
  { id: 'user-uuid-0001', username: 'alice', displayName: '김앨리스', email: null },
]

const mockComponents: Component[] = [
  {
    id: 'comp-uuid-0001',
    projectId: '00000000-0000-4000-8000-000000000099',
    name: '프론트엔드',
    description: null,
    leadUserId: null,
  },
]

vi.mock('@/hooks/use-users', () => ({
  useUsers: vi.fn(() => ({ data: mockUsers, isLoading: false })),
  useUsersByIds: vi.fn(() => ({ data: mockUsers, isLoading: false })),
}))

vi.mock('@/hooks/use-labels', () => ({
  useLabels: vi.fn(() => ({ data: ['bug', 'feature'], isLoading: false })),
}))

vi.mock('@/hooks/use-components', () => ({
  useComponents: vi.fn(() => ({ data: mockComponents, isLoading: false })),
}))

// ─────────────────────────────────────────────────────────────────────────────
// import — RED 단계: 아직 존재하지 않음
// ─────────────────────────────────────────────────────────────────────────────

import {
  BacklogFilterBar,
  BACKLOG_SEARCH_LABEL,
  NO_EPIC_LABEL,
  SEARCH_DEBOUNCE_MS,
} from './BacklogFilterBar'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처 / 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

const EPIC_ALPHA = 'ATLAS-100'
const EPIC_BETA = 'ATLAS-200'
/** 이름 해석에 실패했거나 아직 로딩 중인 에픽 — F16-6 은 이때 **키를 그대로** 보이라고 한다 */
const EPIC_UNRESOLVED = 'ATLAS-900'

const EPIC_ALPHA_NAME = '결제 개편'
const EPIC_BETA_NAME = '알림 리팩터'

const epicNames: ReadonlyMap<string, string> = new Map([
  [EPIC_ALPHA, EPIC_ALPHA_NAME],
  [EPIC_BETA, EPIC_BETA_NAME],
])

function makeWrapper() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return function Wrapper({ children }: { children: ReactNode }) {
    return <QueryClientProvider client={qc}>{children}</QueryClientProvider>
  }
}

function renderBar(
  value: BacklogFilter = emptyBacklogFilter(),
  onChange: (next: BacklogFilter) => void = vi.fn(),
) {
  return render(
    <BacklogFilterBar
      projectKey="ATLAS"
      value={value}
      onChange={onChange}
      epicNames={epicNames}
    />,
    { wrapper: makeWrapper() },
  )
}

/** 제목 검색 입력. 이름은 `검색` 단독이 아니어야 한다 (즉사 계약 §2 — 아래 S1c 가 증인) */
function searchInput(): HTMLElement {
  return screen.getByRole('textbox', { name: BACKLOG_SEARCH_LABEL })
}

/** 활성 필터 칩 목록 — `FilterBar` 가 소유한 단일 list(`적용된 필터`) */
function chipList(): HTMLElement {
  return screen.getByRole('list', { name: '적용된 필터' })
}

// ─────────────────────────────────────────────────────────────────────────────
// ★★ C4 짝 테스트의 공유 셀렉터
//
// 에픽 선택 컨트롤의 계약은 **`role="checkbox"` + 접근명 = 에픽 이름**이다.
// 이 파일은 짝의 절반(③-a — 필터바에 **0개**)만 소유한다.
// **짝의 나머지 절반(③-b — 패널에는 **있다**)은 `BacklogEpicPanel.test.tsx` 가 소유한다.**
// 부재 단언 단독은 컴포넌트가 아무것도 렌더하지 않아도 통과하는 **공허 테스트**라,
// 두 파일이 **같은 셀렉터**를 써야 「어디에도 없음」과 「패널에만 있음」이 구분된다.
// 셀렉터를 여기서 바꾸면 `BacklogEpicPanel.test.tsx` 도 같은 PR 에서 함께 고쳐야 한다.
// ─────────────────────────────────────────────────────────────────────────────

const EPIC_CONTROL_ROLE = 'checkbox' as const

/** 에픽 선택 컨트롤 후보를 이름별로 전부 긁는다. 해석 이름·미해석 키·센티널 라벨 전부. */
function queryEpicControls(): HTMLElement[] {
  const names = [
    EPIC_ALPHA_NAME,
    EPIC_BETA_NAME,
    EPIC_ALPHA,
    EPIC_BETA,
    EPIC_UNRESOLVED,
    NO_EPIC_LABEL,
  ]
  return names.flatMap((name) => screen.queryAllByRole(EPIC_CONTROL_ROLE, { name }))
}

// ─────────────────────────────────────────────────────────────────────────────
// S1. 제목 검색 — leadingSection 주입 + 250ms 디바운스 (N3)
// ─────────────────────────────────────────────────────────────────────────────

describe('BacklogFilterBar — S1 제목 검색', () => {
  // ★userEvent 는 fake timer 와 병용하면 내부 async 타이밍으로 hang 한다
  // (CommandPalette.test.tsx / ExportDialog.test.tsx C3 교훈) — 동기 fireEvent 로 입력한다.
  beforeEach(() => {
    vi.useFakeTimers()
  })
  afterEach(() => {
    vi.useRealTimers()
  })

  it('S1a: 검색 입력이 label 과 연결되고 idPrefix 기반 id 를 갖는다 (WCAG label 연결)', () => {
    renderBar()
    const input = searchInput()
    expect(input).toHaveAttribute('id', 'backlog-filter-query-input')

    const label = screen.getByText(BACKLOG_SEARCH_LABEL)
    expect(label.tagName).toBe('LABEL')
    expect(label).toHaveAttribute('for', 'backlog-filter-query-input')
  })

  it('S1b: 249ms 까지는 조용하고 250ms 에 정확히 한 번 나간다 (N3 디바운스)', () => {
    const onChange = vi.fn()
    renderBar(emptyBacklogFilter(), onChange)

    fireEvent.change(searchInput(), { target: { value: '결제' } })

    act(() => {
      vi.advanceTimersByTime(SEARCH_DEBOUNCE_MS - 1)
    })
    expect(onChange).not.toHaveBeenCalled()

    act(() => {
      vi.advanceTimersByTime(1)
    })
    expect(onChange).toHaveBeenCalledTimes(1)
    expect(onChange).toHaveBeenCalledWith({
      query: '결제',
      assigneeIds: [],
      includeUnassigned: false,
      epicKeys: [],
    })
  })

  it('S1b2: 연속 타이핑은 마지막 값 한 번으로 합쳐진다 (throttle 아님)', () => {
    const onChange = vi.fn()
    renderBar(emptyBacklogFilter(), onChange)

    fireEvent.change(searchInput(), { target: { value: '결' } })
    act(() => {
      vi.advanceTimersByTime(200)
    })
    fireEvent.change(searchInput(), { target: { value: '결제' } })
    act(() => {
      vi.advanceTimersByTime(200)
    })
    expect(onChange).not.toHaveBeenCalled()

    act(() => {
      vi.advanceTimersByTime(50)
    })
    expect(onChange).toHaveBeenCalledTimes(1)
    expect(onChange).toHaveBeenCalledWith(expect.objectContaining({ query: '결제' }))
  })

  it('S1c: ★즉사 계약 — 검색 입력의 접근명이 `검색`·`전역 검색` 과 겹치지 않는다', () => {
    // 상단바 전역 검색이 `전역 검색`(`role="searchbox"`), AQL 페이지 제출 버튼이 `검색` 이다.
    // e2e 가 `exact: true` 로 둘을 가르고 있어 새 `검색` 을 만들면 즉사한다 (계약 §2).
    renderBar()
    expect(searchInput()).toBeInTheDocument() // 비-공허 짝 — 입력 자체는 있다
    expect(screen.queryByRole('textbox', { name: '검색' })).not.toBeInTheDocument()
    expect(screen.queryByRole('textbox', { name: '전역 검색' })).not.toBeInTheDocument()
    expect(screen.queryAllByRole('searchbox')).toHaveLength(0)
  })

  it('S1d: 부모가 준 query 가 입력의 초기값이 된다 (URL 왕복 복원)', () => {
    renderBar({ ...emptyBacklogFilter(), query: '결제' })
    expect(searchInput()).toHaveValue('결제')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S2. 에픽 활성 칩 — 표시 + ✕ 제거 (F16-3 / F16-6)
// ─────────────────────────────────────────────────────────────────────────────

describe('BacklogFilterBar — S2 에픽 활성 칩', () => {
  it('S2a: 선택된 에픽이 `적용된 필터` list 안에 이름으로 나타난다', () => {
    renderBar({ ...emptyBacklogFilter(), epicKeys: [EPIC_ALPHA, EPIC_BETA] })
    const list = within(chipList())
    expect(list.getByText(EPIC_ALPHA_NAME)).toBeInTheDocument()
    expect(list.getByText(EPIC_BETA_NAME)).toBeInTheDocument()
  })

  it('S2b: 이름 미해석 에픽은 키를 그대로 보인다 (F16-6 — 빈 값 금지)', () => {
    renderBar({ ...emptyBacklogFilter(), epicKeys: [EPIC_UNRESOLVED] })
    expect(within(chipList()).getByText(EPIC_UNRESOLVED)).toBeInTheDocument()
  })

  it('S2c: NO_EPIC 센티널은 「에픽 없음」으로 보인다 — `__none__` 이 새면 안 된다', () => {
    renderBar({ ...emptyBacklogFilter(), epicKeys: [NO_EPIC] })
    expect(within(chipList()).getByText(NO_EPIC_LABEL)).toBeInTheDocument()
    expect(screen.queryByText(NO_EPIC)).not.toBeInTheDocument()
  })

  it('S2d: 칩의 ✕ 로 해당 에픽만 빠진다', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    renderBar({ ...emptyBacklogFilter(), epicKeys: [EPIC_ALPHA, EPIC_BETA] }, onChange)

    await user.click(
      screen.getByRole('button', { name: filterBarLabels.chip.removeAriaLabel(EPIC_ALPHA_NAME) }),
    )

    expect(onChange).toHaveBeenCalledWith(
      expect.objectContaining({ epicKeys: [EPIC_BETA] }),
    )
  })

  it('S2e: 동명 list 를 새로 만들지 않는다 — `적용된 필터` list 는 정확히 1개', () => {
    renderBar({ ...emptyBacklogFilter(), epicKeys: [EPIC_ALPHA] })
    expect(screen.getAllByRole('list', { name: '적용된 필터' })).toHaveLength(1)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S3. ★★ C4 에픽 상태 단일 소유권 — 필터바는 **입력 컨트롤을 갖지 않는다**
//
// 짝의 나머지 절반(패널에는 있다)은 `BacklogEpicPanel.test.tsx` 가 소유한다 (위 셀렉터 주석).
// ─────────────────────────────────────────────────────────────────────────────

describe('BacklogFilterBar — S3 에픽 단일 소유권 (C4)', () => {
  it('S3a: 에픽 선택 체크박스가 0개다 — 선택 전', () => {
    renderBar()
    // 비-공허 짝: 이 트리에 체크박스 조회 자체는 살아 있다(FilterBar 의 「미배정」)
    expect(screen.getByRole(EPIC_CONTROL_ROLE, { name: filterBarLabels.filter.unassigned }))
      .toBeInTheDocument()
    expect(queryEpicControls()).toHaveLength(0)
  })

  it('S3b: 에픽 선택 체크박스가 0개다 — 이미 2개가 걸린 상태에서도(칩만 있다)', () => {
    renderBar({ ...emptyBacklogFilter(), epicKeys: [EPIC_ALPHA, EPIC_BETA, NO_EPIC] })
    // 칩은 있다 — 그러니 「에픽을 아예 안 그렸다」로 통과한 것이 아니다
    expect(within(chipList()).getByText(EPIC_ALPHA_NAME)).toBeInTheDocument()
    expect(queryEpicControls()).toHaveLength(0)
  })

  it('S3c: 필터바 안에 에픽용 combobox·listbox·radio 도 없다 (역할만 바꾼 우회 차단)', () => {
    renderBar({ ...emptyBacklogFilter(), epicKeys: [EPIC_ALPHA] })
    for (const role of ['combobox', 'listbox', 'radio'] as const) {
      expect(screen.queryAllByRole(role, { name: EPIC_ALPHA_NAME })).toHaveLength(0)
      expect(screen.queryAllByRole(role, { name: NO_EPIC_LABEL })).toHaveLength(0)
    }
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S4. ★ R2 활성 개수 가산 — extraActiveCount
// ─────────────────────────────────────────────────────────────────────────────

describe('BacklogFilterBar — S4 활성 개수 가산 (R2)', () => {
  it('S4a: 에픽 2 + 검색어 1 이면 「3개 적용 중」이다 (가산 누락이면 2 나 1 이 된다)', () => {
    renderBar({ ...emptyBacklogFilter(), query: '결제', epicKeys: [EPIC_ALPHA, EPIC_BETA] })
    expect(screen.getByText(filterBarLabels.count.applied(3))).toBeInTheDocument()
  })

  it('S4b: 담당자·미배정까지 더해도 축이 전부 합산된다 (2+1+1+1 = 5)', () => {
    renderBar({
      query: '결제',
      assigneeIds: ['user-uuid-0001'],
      includeUnassigned: true,
      epicKeys: [EPIC_ALPHA, EPIC_BETA],
    })
    expect(screen.getByText(filterBarLabels.count.applied(5))).toBeInTheDocument()
  })

  it('S4c: 공백만 있는 검색어는 조건으로 세지 않는다 (isEmptyFilter 와 같은 판정)', () => {
    renderBar({ ...emptyBacklogFilter(), query: '   ', epicKeys: [EPIC_ALPHA] })
    expect(screen.getByText(filterBarLabels.count.applied(1))).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S5. 라벨·컴포넌트 섹션 부재 — hiddenSections 소비 (F16-2 / Task 2 prop)
// ─────────────────────────────────────────────────────────────────────────────

describe('BacklogFilterBar — S5 라벨·컴포넌트 섹션 제외', () => {
  it('S5a: 라벨·컴포넌트 섹션이 렌더되지 않는다 — 눌러도 안 걸리는 장식 필터 금지', () => {
    renderBar()
    expect(screen.queryByText(filterBarLabels.filter.labelLabel)).not.toBeInTheDocument()
    expect(screen.queryByText(filterBarLabels.filter.componentLabel)).not.toBeInTheDocument()
  })

  it('S5b: 비-공허 짝 — 담당자·미배정·초기화는 그대로 남는다', () => {
    renderBar()
    expect(screen.getByRole('textbox', { name: filterBarLabels.filter.assigneeLabel }))
      .toBeInTheDocument()
    expect(screen.getByRole('checkbox', { name: filterBarLabels.filter.unassigned }))
      .toBeInTheDocument()
    expect(screen.getByRole('button', { name: filterBarLabels.filter.reset })).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S6. 초기화 — 4축 전부 (onReset 위임)
// ─────────────────────────────────────────────────────────────────────────────

describe('BacklogFilterBar — S6 초기화', () => {
  it('S6a: 초기화가 query·담당자·미배정·에픽 4축을 한 번에 비운다', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    renderBar(
      {
        query: '결제',
        assigneeIds: ['user-uuid-0001'],
        includeUnassigned: true,
        epicKeys: [EPIC_ALPHA, NO_EPIC],
      },
      onChange,
    )

    await user.click(screen.getByRole('button', { name: filterBarLabels.filter.reset }))

    expect(onChange).toHaveBeenCalledWith({
      query: '',
      assigneeIds: [],
      includeUnassigned: false,
      epicKeys: [],
    })
  })

  it('S6b: 초기화하면 검색 입력의 로컬 값도 즉시 비워진다 (부모 갱신을 기다리지 않는다)', async () => {
    const user = userEvent.setup()
    renderBar({ ...emptyBacklogFilter(), query: '결제' })

    await user.click(screen.getByRole('button', { name: filterBarLabels.filter.reset }))

    expect(searchInput()).toHaveValue('')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S7. FilterBar 경계 — 담당자 축 왕복 시 백로그에 없는 축이 새지 않는다
// ─────────────────────────────────────────────────────────────────────────────

describe('BacklogFilterBar — S7 FilterBar 경계 왕복', () => {
  it('S7a: 미배정 토글이 BacklogFilter 형태 그대로 나간다 — labels/componentIds 유출 없음', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    renderBar(emptyBacklogFilter(), onChange)

    await user.click(screen.getByRole('checkbox', { name: filterBarLabels.filter.unassigned }))

    expect(onChange).toHaveBeenCalledTimes(1)
    const next = onChange.mock.calls[0]?.[0]
    expect(next).toEqual({
      query: '',
      assigneeIds: [],
      includeUnassigned: true,
      epicKeys: [],
    })
  })

  it('S7b: 담당자 선택도 다른 축을 보존한 채 나간다', async () => {
    const user = userEvent.setup()
    const onChange = vi.fn()
    renderBar({ ...emptyBacklogFilter(), query: '결제', epicKeys: [EPIC_ALPHA] }, onChange)

    const input = screen.getByRole('textbox', { name: filterBarLabels.filter.assigneeLabel })
    await user.click(input)
    await user.type(input, '앨리스')
    await user.click(await screen.findByRole('button', { name: '김앨리스' }))

    expect(onChange).toHaveBeenCalledWith({
      query: '결제',
      assigneeIds: ['user-uuid-0001'],
      includeUnassigned: false,
      epicKeys: [EPIC_ALPHA],
    })
  })
})
