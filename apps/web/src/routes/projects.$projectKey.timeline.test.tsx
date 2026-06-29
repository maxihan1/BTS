// 타임라인 라우트 페이지 단위 테스트 — TimelinePage 렌더 시나리오 (FR-TL-01 Task 6, FR-TL-02 D6)
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { TimelineItem } from '@/api/timeline'
import type { UserSummary } from '@/api/users'
import { ApiError } from '@/api/client'
import type { DependencyEdge } from '@/lib/timeline-layout'

// ─────────────────────────────────────────────────────────────────────────────
// mock — TanStack Router, use-timeline, @/api/users, GanttChart
// ─────────────────────────────────────────────────────────────────────────────

const { mockNavigate } = vi.hoisted(() => ({ mockNavigate: vi.fn() }))

vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => mockNavigate,
  useParams: () => ({ projectKey: 'ATLAS' }),
}))

// GanttChart — onSelectIssue·assigneeNames·items·deps를 캡처해 단언에 활용
const mockGanttChartProps: Array<{
  items: TimelineItem[]
  assigneeNames: Map<string, string>
  onSelectIssue: (key: string) => void
  deps?: DependencyEdge[]
}> = []

vi.mock('@/components/timeline/GanttChart', () => ({
  GanttChart: ({
    items,
    assigneeNames,
    onSelectIssue,
    deps,
  }: {
    items: TimelineItem[]
    assigneeNames: Map<string, string>
    onSelectIssue: (key: string) => void
    deps?: DependencyEdge[]
  }) => {
    mockGanttChartProps.push({ items, assigneeNames, onSelectIssue, deps })
    return <div data-testid="gantt-chart">GanttChart</div>
  },
}))

// use-timeline 훅 mock — 각 테스트에서 덮어씀
const mockUseTimeline = vi.fn()
const mockUseTimelineDeps = vi.fn()
vi.mock('@/hooks/use-timeline', () => ({
  useTimeline: (projectKey: string) => mockUseTimeline(projectKey),
  useTimelineDeps: (projectKey: string) => mockUseTimelineDeps(projectKey),
}))

// @/api/users mock
const mockFetchUsers = vi.fn()
vi.mock('@/api/users', () => ({
  fetchUsers: () => mockFetchUsers(),
}))

// ─────────────────────────────────────────────────────────────────────────────
// fixture
// ─────────────────────────────────────────────────────────────────────────────

const ALICE_ID = 'c3d4e5f6-a7b8-4890-abcd-ef1234567893'
const UNKNOWN_USER_ID = 'd4e5f6a7-b8c9-4890-abcd-ef1234567894'

const ITEM_1: TimelineItem = {
  key: 'ATLAS-1',
  summary: '첫 번째 이슈',
  issueType: 'story',
  currentStateKey: 'in-progress',
  assigneeId: ALICE_ID,
  startDate: '2026-06-01',
  dueDate: '2026-06-30',
  targetDate: null,
  epicKey: null,
}

const ITEM_UNASSIGNED: TimelineItem = {
  key: 'ATLAS-2',
  summary: '미배정 이슈',
  issueType: 'task',
  currentStateKey: 'todo',
  assigneeId: null,
  startDate: '2026-06-01',
  dueDate: '2026-06-15',
  targetDate: null,
  epicKey: null,
}

const ITEM_UNKNOWN_USER: TimelineItem = {
  key: 'ATLAS-3',
  summary: '미해석 담당자 이슈',
  issueType: 'bug',
  currentStateKey: 'todo',
  assigneeId: UNKNOWN_USER_ID,
  startDate: '2026-06-01',
  dueDate: '2026-06-10',
  targetDate: null,
  epicKey: null,
}

const USERS: UserSummary[] = [
  {
    id: ALICE_ID,
    username: 'alice',
    displayName: '앨리스',
    email: 'alice@example.com',
  },
]

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

async function renderTimelinePage(projectKey = 'ATLAS') {
  const { TimelinePage } = await import('@/routes/projects.$projectKey.timeline')
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
  return render(
    <QueryClientProvider client={client}>
      <TimelinePage projectKey={projectKey} />
    </QueryClientProvider>,
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('TimelinePage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockGanttChartProps.length = 0
    mockFetchUsers.mockResolvedValue(USERS)
    // deps 훅 기본값 — best-effort: 에러여도 간트를 차단하지 않음 (EC6)
    mockUseTimelineDeps.mockReturnValue({ data: undefined, isLoading: false, error: null, isError: false })
  })

  afterEach(() => {
    vi.clearAllMocks()
    mockGanttChartProps.length = 0
  })

  /**
   * T-TL6-R-1. 타임라인 로딩 중 — 스켈레톤이 렌더된다.
   */
  it('T-TL6-R-1: 타임라인 로딩 중 스켈레톤이 렌더된다', async () => {
    mockUseTimeline.mockReturnValue({ data: undefined, isLoading: true, error: null, isError: false })

    await renderTimelinePage()

    expect(screen.queryByTestId('gantt-chart')).not.toBeInTheDocument()
    expect(document.querySelector('.animate-pulse')).toBeInTheDocument()
  })

  /**
   * T-TL6-R-2. 정상 data — GanttChart가 렌더된다.
   */
  it('T-TL6-R-2: 정상 data 시 GanttChart가 렌더된다', async () => {
    mockUseTimeline.mockReturnValue({
      data: { items: [ITEM_1], truncated: false },
      isLoading: false,
      error: null,
      isError: false,
    })

    await renderTimelinePage()

    await waitFor(() => {
      expect(screen.getByTestId('gantt-chart')).toBeInTheDocument()
    })
  })

  /**
   * T-TL6-R-3. 빈 상태(S4) — items가 빈 배열이면 안내 문구가 렌더되고 GanttChart는 없다.
   */
  it('T-TL6-R-3: items 빈 배열이면 S4 안내가 렌더되고 GanttChart는 없다', async () => {
    mockUseTimeline.mockReturnValue({
      data: { items: [], truncated: false },
      isLoading: false,
      error: null,
      isError: false,
    })

    await renderTimelinePage()

    await waitFor(() => {
      expect(screen.getByText(/표시할 이슈가 없습니다/i)).toBeInTheDocument()
    })
    expect(screen.queryByTestId('gantt-chart')).not.toBeInTheDocument()
  })

  /**
   * T-TL6-R-4. truncated=true(S5) — 경고 배너가 표시된다.
   */
  it('T-TL6-R-4: truncated=true이면 S5 경고 배너가 표시된다', async () => {
    mockUseTimeline.mockReturnValue({
      data: { items: [ITEM_1], truncated: true },
      isLoading: false,
      error: null,
      isError: false,
    })

    await renderTimelinePage()

    await waitFor(() => {
      expect(screen.getByRole('alert')).toBeInTheDocument()
    })
  })

  /**
   * T-TL6-R-5. 403 AGILE_ACCESS_DENIED(S6) — 접근 거부 안내 화면이 렌더된다.
   */
  it('T-TL6-R-5: 403 AGILE_ACCESS_DENIED 에러 시 접근 권한 없음 안내가 렌더된다', async () => {
    mockUseTimeline.mockReturnValue({
      data: undefined,
      isLoading: false,
      error: new ApiError(403, { errorCode: 'AGILE_ACCESS_DENIED' }),
      isError: true,
    })

    await renderTimelinePage()

    await waitFor(() => {
      expect(screen.getByText(/접근 권한이 없습니다/i)).toBeInTheDocument()
    })
    expect(screen.queryByTestId('gantt-chart')).not.toBeInTheDocument()
  })

  /**
   * T-TL6-R-6. 막대 클릭 → /issues/$key navigate.
   */
  it('T-TL6-R-6: onSelectIssue 호출 시 /issues/$key 로 navigate한다', async () => {
    mockUseTimeline.mockReturnValue({
      data: { items: [ITEM_1], truncated: false },
      isLoading: false,
      error: null,
      isError: false,
    })

    await renderTimelinePage()

    await waitFor(() => {
      expect(screen.getByTestId('gantt-chart')).toBeInTheDocument()
    })

    const lastCall = mockGanttChartProps[mockGanttChartProps.length - 1]
    lastCall?.onSelectIssue('ATLAS-1')

    expect(mockNavigate).toHaveBeenCalledWith({
      to: '/issues/$key',
      params: { key: 'ATLAS-1' },
    })
  })

  // ─────────────────────────────────────────────────────────────────────────────
  // FR-TL-02 D6 — deps 오버레이 통합
  // ─────────────────────────────────────────────────────────────────────────────

  /**
   * T-TL5-T-1. TimelinePage가 useTimelineDeps로 deps를 조회하여 GanttChart에 주입한다.
   */
  it('T-TL5-T-1: TimelinePage가 useTimelineDeps deps를 GanttChart에 주입한다', async () => {
    mockUseTimeline.mockReturnValue({
      data: { items: [ITEM_1], truncated: false },
      isLoading: false,
      error: null,
      isError: false,
    })
    mockUseTimelineDeps.mockReturnValue({
      data: { deps: [{ blockerKey: 'ATLAS-1', blockedKey: 'ATLAS-2' }], truncated: false },
      isLoading: false,
      error: null,
      isError: false,
    })

    await renderTimelinePage()

    await waitFor(() => {
      const lastCall = mockGanttChartProps[mockGanttChartProps.length - 1]
      expect(lastCall?.deps).toEqual([{ blockerKey: 'ATLAS-1', blockedKey: 'ATLAS-2' }])
    })
  })

  /**
   * T-TL5-T-2. deps.truncated=true → deps 누락 경고 배너가 표시된다.
   * 기존 timeline truncated 배너(S5)와 구분된 문구.
   */
  it('T-TL5-T-2: deps.truncated=true 시 deps 누락 경고 배너가 표시된다', async () => {
    mockUseTimeline.mockReturnValue({
      data: { items: [ITEM_1], truncated: false },
      isLoading: false,
      error: null,
      isError: false,
    })
    mockUseTimelineDeps.mockReturnValue({
      data: { deps: [], truncated: true },
      isLoading: false,
      error: null,
      isError: false,
    })

    await renderTimelinePage()

    await waitFor(() => {
      expect(screen.getByText('일부 의존 라인이 생략되었습니다')).toBeInTheDocument()
    })
  })

  /**
   * T-TL5-T-3. deps 에러여도 GanttChart가 정상 렌더되고 deps=[] 폴백이 전달된다 (EC6).
   */
  it('T-TL5-T-3: deps 에러 시에도 GanttChart가 렌더되고 deps=[] 폴백이 전달된다 (EC6)', async () => {
    mockUseTimeline.mockReturnValue({
      data: { items: [ITEM_1], truncated: false },
      isLoading: false,
      error: null,
      isError: false,
    })
    mockUseTimelineDeps.mockReturnValue({
      data: undefined,
      isLoading: false,
      error: new Error('Network Error'),
      isError: true,
    })

    await renderTimelinePage()

    await waitFor(() => {
      expect(screen.getByTestId('gantt-chart')).toBeInTheDocument()
    })
    const lastCall = mockGanttChartProps[mockGanttChartProps.length - 1]
    expect(lastCall?.deps).toEqual([])
  })

  /**
   * T-TL5-T-4. 403 접근거부 시 GanttChart 미표시 → deps 배너도 미표시된다 (S7).
   */
  it('T-TL5-T-4: 403 접근거부 시 GanttChart·deps 배너 모두 미표시된다 (S7)', async () => {
    mockUseTimeline.mockReturnValue({
      data: undefined,
      isLoading: false,
      error: new ApiError(403, { errorCode: 'AGILE_ACCESS_DENIED' }),
      isError: true,
    })
    mockUseTimelineDeps.mockReturnValue({
      data: { deps: [], truncated: true },
      isLoading: false,
      error: null,
      isError: false,
    })

    await renderTimelinePage()

    await waitFor(() => {
      expect(screen.getByText(/접근 권한이 없습니다/)).toBeInTheDocument()
    })
    expect(screen.queryByTestId('gantt-chart')).not.toBeInTheDocument()
    expect(screen.queryByText('일부 의존 라인이 생략되었습니다')).not.toBeInTheDocument()
  })

  /**
   * T-TL6-R-7. C2 — assigneeId가 userMap에 있으면 GanttChart assigneeNames에 name이 전달된다.
   * assigneeId=null 또는 미해석 userId는 맵에서 제외한다(GanttChart 자체 fallback 위임).
   */
  it('T-TL6-R-7: assigneeId가 userMap에 있으면 GanttChart에 name이 전달된다', async () => {
    mockUseTimeline.mockReturnValue({
      data: { items: [ITEM_1, ITEM_UNASSIGNED, ITEM_UNKNOWN_USER], truncated: false },
      isLoading: false,
      error: null,
      isError: false,
    })

    await renderTimelinePage()

    // users 로드 후 assigneeNames가 갱신된 렌더를 기다린다
    await waitFor(() => {
      const lastCall = mockGanttChartProps[mockGanttChartProps.length - 1]
      expect(lastCall?.assigneeNames.get('ATLAS-1')).toBe('앨리스')
    })

    const lastCall = mockGanttChartProps[mockGanttChartProps.length - 1]
    const names = lastCall?.assigneeNames
    // ATLAS-2: assigneeId=null → 맵에 없음(GanttChart "미배정" fallback)
    expect(names?.get('ATLAS-2')).toBeUndefined()
    // ATLAS-3: UNKNOWN_USER_ID → 맵에 없음(GanttChart "알 수 없음" fallback)
    expect(names?.get('ATLAS-3')).toBeUndefined()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// TimelineRouteAdapter 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('TimelineRouteAdapter', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockGanttChartProps.length = 0
    mockFetchUsers.mockResolvedValue([])
    mockUseTimeline.mockReturnValue({ data: undefined, isLoading: true, error: null, isError: false })
    mockUseTimelineDeps.mockReturnValue({ data: undefined, isLoading: false, error: null, isError: false })
  })

  afterEach(() => {
    vi.clearAllMocks()
    mockGanttChartProps.length = 0
  })

  /**
   * T-TL6-A-1. TimelineRouteAdapter가 useParams에서 projectKey를 추출해 useTimeline에 전달한다.
   */
  it('T-TL6-A-1: TimelineRouteAdapter가 useParams의 projectKey를 useTimeline에 전달한다', async () => {
    const { TimelineRouteAdapter } = await import('@/routes/projects.$projectKey.timeline')
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })

    render(
      <QueryClientProvider client={client}>
        <TimelineRouteAdapter />
      </QueryClientProvider>,
    )

    await waitFor(() => {
      expect(mockUseTimeline).toHaveBeenCalledWith('ATLAS')
    })
  })
})
