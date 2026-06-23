// 대시보드 목록+생성 라우트 단위 테스트 — DashboardsListPage + DashboardsRouteAdapter (FR-DB-01 Task 7)
import type { ReactNode } from 'react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { DashboardPage, Dashboard } from '@/api/dashboards'

// ─────────────────────────────────────────────────────────────────────────────
// mock — TanStack Router, use-dashboards, authStore, DashboardForm
// ─────────────────────────────────────────────────────────────────────────────

const { mockNavigate } = vi.hoisted(() => ({ mockNavigate: vi.fn() }))

vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => mockNavigate,
  // Link mock — 라우터 컨텍스트 없이 단위 테스트 가능하도록 <a>로 렌더
  Link: ({
    to,
    params,
    children,
    className,
  }: {
    to: string
    params?: Record<string, string>
    children: ReactNode
    className?: string
  }) => {
    // $dashboardId 파라미터 치환
    const href = params !== undefined
      ? Object.entries(params).reduce(
          (acc, [k, v]) => acc.replace(`$${k}`, v),
          to,
        )
      : to
    return (
      <a href={href} className={className} data-testid="dashboard-card-link">
        {children}
      </a>
    )
  },
}))

// DashboardForm mock — onSubmit을 data-testid 버튼으로 노출
vi.mock('@/components/dashboard/DashboardForm', () => ({
  DashboardForm: ({
    onSubmit,
    isPending,
  }: {
    mode: string
    onSubmit: (payload: Record<string, unknown>) => void
    isPending: boolean
  }) => (
    <div data-testid="dashboard-form" data-is-pending={String(isPending)}>
      <button
        type="button"
        data-testid="mock-submit"
        onClick={() =>
          onSubmit({ name: '새 대시보드', visibility: 'PRIVATE', sharedUserIds: [] })
        }
      >
        대시보드 만들기
      </button>
    </div>
  ),
}))

// use-dashboards 훅 mock
const mockUseDashboards = vi.fn()
const mockUseCreateDashboard = vi.fn()

vi.mock('@/hooks/use-dashboards', () => ({
  useDashboards: () => mockUseDashboards(),
  useCreateDashboard: () => mockUseCreateDashboard(),
}))

// authStore mock — 현재 사용자 ID 주입
const mockUseAuthUser = vi.fn()
vi.mock('@/auth/authStore', () => ({
  useAuthUser: () => mockUseAuthUser(),
}))

// ─────────────────────────────────────────────────────────────────────────────
// fixture
// ─────────────────────────────────────────────────────────────────────────────

const ALICE_ID = '00000000-0000-4000-8000-000000000001'
const BOB_ID = '00000000-0000-4000-8000-000000000002'

const ALICE_USER = {
  userId: ALICE_ID,
  username: 'alice',
  email: 'alice@example.com',
  authMethod: 'LOCAL',
  mustChangePassword: false,
  isSystemAdmin: false,
  mfaEnrollmentRequired: false,
}

const DASHBOARD_ALICE: Dashboard = {
  id: 'a0000000-0000-4000-8000-000000000001',
  ownerId: ALICE_ID,
  name: '내 첫 대시보드',
  description: '기본 대시보드입니다',
  visibility: 'PRIVATE',
  layout: '[]',
  sharedUserIds: [],
  createdAt: '2026-06-01T00:00:00.000Z',
  updatedAt: '2026-06-01T00:00:00.000Z',
  version: 0,
}

const DASHBOARD_BOB: Dashboard = {
  id: 'b0000000-0000-4000-8000-000000000001',
  ownerId: BOB_ID,
  name: 'Bob의 팀 대시보드',
  description: null,
  visibility: 'ORG',
  layout: '[]',
  sharedUserIds: [],
  createdAt: '2026-06-01T00:00:00.000Z',
  updatedAt: '2026-06-01T00:00:00.000Z',
  version: 0,
}

const PAGE_WITH_TWO: DashboardPage = {
  items: [DASHBOARD_ALICE, DASHBOARD_BOB],
  total: 2,
  limit: 50,
  offset: 0,
}

const PAGE_EMPTY: DashboardPage = {
  items: [],
  total: 0,
  limit: 50,
  offset: 0,
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

async function renderDashboardsListPage() {
  const { DashboardsListPage } = await import('@/routes/dashboards')
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
  return render(
    <QueryClientProvider client={client}>
      <DashboardsListPage />
    </QueryClientProvider>,
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('DashboardsListPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockUseAuthUser.mockReturnValue(ALICE_USER)
    mockUseCreateDashboard.mockReturnValue({ mutate: vi.fn(), isPending: false })
  })

  /**
   * T-DB7-R-1. 로딩 중 — 스켈레톤이 렌더된다.
   */
  it('T-DB7-R-1: 로딩 중 스켈레톤이 렌더된다', async () => {
    mockUseDashboards.mockReturnValue({
      data: undefined,
      isLoading: true,
      isError: false,
    })

    await renderDashboardsListPage()

    // 스켈레톤이 렌더돼야 함 (aria-hidden="true" 요소)
    const skeletons = document.querySelectorAll('[aria-hidden="true"]')
    expect(skeletons.length).toBeGreaterThan(0)
    // 카드 및 폼은 없어야 함
    expect(screen.queryByTestId('dashboard-form')).not.toBeInTheDocument()
  })

  /**
   * T-DB7-R-2. 에러(isError) → "불러오지 못했습니다" + "다시 시도" 버튼.
   */
  it('T-DB7-R-2: 에러 시 에러 메시지와 다시 시도 버튼이 렌더된다', async () => {
    mockUseDashboards.mockReturnValue({
      data: undefined,
      isLoading: false,
      isError: true,
      refetch: vi.fn(),
    })

    await renderDashboardsListPage()

    await waitFor(() => {
      expect(screen.getByText(/불러오지 못했습니다/i)).toBeInTheDocument()
    })
    expect(screen.getByRole('button', { name: /다시 시도/i })).toBeInTheDocument()
  })

  /**
   * T-DB7-R-3. 다시 시도 버튼 클릭 → refetch 호출.
   */
  it('T-DB7-R-3: 다시 시도 버튼 클릭 시 refetch가 호출된다', async () => {
    const user = userEvent.setup()
    const mockRefetch = vi.fn()
    mockUseDashboards.mockReturnValue({
      data: undefined,
      isLoading: false,
      isError: true,
      refetch: mockRefetch,
    })

    await renderDashboardsListPage()

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /다시 시도/i })).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: /다시 시도/i }))
    expect(mockRefetch).toHaveBeenCalledOnce()
  })

  /**
   * T-DB7-R-4. 빈 목록(EC1) → 빈 상태 제목 + 맥락 문구 + "대시보드 만들기" CTA.
   */
  it('T-DB7-R-4: 빈 목록(EC1)이면 빈 상태 제목 + 맥락 문구 + CTA가 렌더된다', async () => {
    mockUseDashboards.mockReturnValue({
      data: PAGE_EMPTY,
      isLoading: false,
      isError: false,
    })

    await renderDashboardsListPage()

    await waitFor(() => {
      expect(screen.getByText(/아직 대시보드가 없습니다/i)).toBeInTheDocument()
    })
    expect(screen.getByText(/새 대시보드를 만들어/i)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /대시보드 만들기/i })).toBeInTheDocument()
  })

  /**
   * T-DB7-R-5. 목록 카드 렌더 — 이름, 설명, visibility 배지, 소유 여부 표시.
   */
  it('T-DB7-R-5: 목록 카드에 이름·설명·visibility 배지·소유 여부가 렌더된다', async () => {
    mockUseDashboards.mockReturnValue({
      data: PAGE_WITH_TWO,
      isLoading: false,
      isError: false,
    })

    await renderDashboardsListPage()

    await waitFor(() => {
      expect(screen.getByText('내 첫 대시보드')).toBeInTheDocument()
    })

    // 이름
    expect(screen.getByText('Bob의 팀 대시보드')).toBeInTheDocument()

    // 설명 — alice 대시보드에만 있음
    expect(screen.getByText('기본 대시보드입니다')).toBeInTheDocument()

    // visibility 배지
    expect(screen.getByText('나만 보기')).toBeInTheDocument()  // ALICE PRIVATE
    expect(screen.getByText('전체 공유')).toBeInTheDocument()  // BOB ORG

    // 소유 여부 — alice 카드에만 "내 대시보드" 배지
    const ownerBadges = screen.getAllByText('내 대시보드')
    expect(ownerBadges).toHaveLength(1)
  })

  /**
   * T-DB7-R-5b. 카드 클릭 → /dashboards/$dashboardId로 네비게이션.
   * Link href가 올바른 dashboardId를 포함하는지 검증한다.
   */
  it('T-DB7-R-5b: 카드에 /dashboards/$dashboardId 경로로의 Link가 렌더된다', async () => {
    mockUseDashboards.mockReturnValue({
      data: PAGE_WITH_TWO,
      isLoading: false,
      isError: false,
    })

    await renderDashboardsListPage()

    await waitFor(() => {
      expect(screen.getByText('내 첫 대시보드')).toBeInTheDocument()
    })

    // Link mock이 렌더한 <a> 태그들의 href를 검증
    const links = screen.getAllByTestId('dashboard-card-link')
    const hrefs = links.map((el) => el.getAttribute('href'))

    // alice 카드의 Link href가 alice 대시보드 id를 포함해야 함
    expect(hrefs).toContain(expect.stringContaining(DASHBOARD_ALICE.id) as unknown)
    // bob 카드의 Link href가 bob 대시보드 id를 포함해야 함
    expect(hrefs).toContain(expect.stringContaining(DASHBOARD_BOB.id) as unknown)
  })

  /**
   * T-DB7-R-6. "대시보드 만들기" 버튼 클릭 → DashboardForm 다이얼로그/섹션 표시.
   */
  it('T-DB7-R-6: 대시보드 만들기 버튼 클릭 시 DashboardForm이 표시된다', async () => {
    const user = userEvent.setup()
    mockUseDashboards.mockReturnValue({
      data: PAGE_WITH_TWO,
      isLoading: false,
      isError: false,
    })

    await renderDashboardsListPage()

    await waitFor(() => {
      expect(screen.getByText('내 첫 대시보드')).toBeInTheDocument()
    })

    // "대시보드 만들기" 버튼 클릭 (헤더 영역)
    const createBtn = screen.getByRole('button', { name: /대시보드 만들기/i })
    await user.click(createBtn)

    await waitFor(() => {
      expect(screen.getByTestId('dashboard-form')).toBeInTheDocument()
    })
  })

  /**
   * T-DB7-R-7. DashboardForm 제출 → useCreateDashboard.mutate 호출.
   */
  it('T-DB7-R-7: DashboardForm 제출 시 useCreateDashboard.mutate가 호출된다', async () => {
    const user = userEvent.setup()
    const mockMutate = vi.fn()
    mockUseCreateDashboard.mockReturnValue({ mutate: mockMutate, isPending: false })
    mockUseDashboards.mockReturnValue({
      data: PAGE_WITH_TWO,
      isLoading: false,
      isError: false,
    })

    await renderDashboardsListPage()

    await waitFor(() => {
      expect(screen.getByText('내 첫 대시보드')).toBeInTheDocument()
    })

    // 폼 열기
    await user.click(screen.getByRole('button', { name: /대시보드 만들기/i }))
    await waitFor(() => {
      expect(screen.getByTestId('dashboard-form')).toBeInTheDocument()
    })

    // mock submit 버튼으로 제출 트리거
    await user.click(screen.getByTestId('mock-submit'))

    expect(mockMutate).toHaveBeenCalledWith(
      expect.objectContaining({ name: '새 대시보드' }),
      expect.objectContaining({ onSuccess: expect.any(Function) as unknown }),
    )
  })

  /**
   * T-DB7-R-8. useCreateDashboard 성공 시 navigate('/dashboards/$dashboardId')가 호출된다.
   */
  it('T-DB7-R-8: 생성 성공 시 navigate가 새 대시보드 상세 경로로 호출된다', async () => {
    const user = userEvent.setup()
    const mockMutate = vi.fn()
    mockUseCreateDashboard.mockReturnValue({ mutate: mockMutate, isPending: false })
    mockUseDashboards.mockReturnValue({
      data: PAGE_EMPTY,
      isLoading: false,
      isError: false,
    })

    await renderDashboardsListPage()

    // 빈 상태의 CTA 버튼 클릭
    await waitFor(() => {
      expect(screen.getByRole('button', { name: /대시보드 만들기/i })).toBeInTheDocument()
    })
    await user.click(screen.getByRole('button', { name: /대시보드 만들기/i }))
    await waitFor(() => {
      expect(screen.getByTestId('dashboard-form')).toBeInTheDocument()
    })

    await user.click(screen.getByTestId('mock-submit'))

    // mutate onSuccess 콜백을 직접 실행해 네비게이션 검증
    expect(mockMutate).toHaveBeenCalled()
    const callArgs = mockMutate.mock.calls[0]
    const options = callArgs?.[1] as { onSuccess?: (data: Dashboard) => void } | undefined
    options?.onSuccess?.(DASHBOARD_ALICE)

    await waitFor(() => {
      expect(mockNavigate).toHaveBeenCalledWith(
        expect.objectContaining({
          to: '/dashboards/$dashboardId',
          params: expect.objectContaining({ dashboardId: DASHBOARD_ALICE.id }) as unknown,
        }),
      )
    })
  })

  /**
   * T-DB7-R-9. isPending=true이면 DashboardForm에 isPending=true가 전달된다.
   */
  it('T-DB7-R-9: isPending=true이면 DashboardForm에 isPending이 전달된다', async () => {
    const user = userEvent.setup()
    mockUseCreateDashboard.mockReturnValue({ mutate: vi.fn(), isPending: true })
    mockUseDashboards.mockReturnValue({
      data: PAGE_EMPTY,
      isLoading: false,
      isError: false,
    })

    await renderDashboardsListPage()

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /대시보드 만들기/i })).toBeInTheDocument()
    })
    await user.click(screen.getByRole('button', { name: /대시보드 만들기/i }))
    await waitFor(() => {
      expect(screen.getByTestId('dashboard-form')).toBeInTheDocument()
    })

    const form = screen.getByTestId('dashboard-form')
    expect(form.getAttribute('data-is-pending')).toBe('true')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// DashboardsRouteAdapter 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('DashboardsRouteAdapter', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockUseAuthUser.mockReturnValue(ALICE_USER)
    mockUseDashboards.mockReturnValue({ data: PAGE_EMPTY, isLoading: false, isError: false })
    mockUseCreateDashboard.mockReturnValue({ mutate: vi.fn(), isPending: false })
  })

  /**
   * T-DB7-A-1. DashboardsRouteAdapter가 렌더되면 DashboardsListPage가 표시된다.
   */
  it('T-DB7-A-1: DashboardsRouteAdapter가 렌더되면 DashboardsListPage 컨텐츠가 표시된다', async () => {
    const { DashboardsRouteAdapter } = await import('@/routes/dashboards')
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })

    render(
      <QueryClientProvider client={client}>
        <DashboardsRouteAdapter />
      </QueryClientProvider>,
    )

    await waitFor(() => {
      // 빈 상태 텍스트가 보이면 DashboardsListPage가 렌더된 것
      expect(screen.getByText(/아직 대시보드가 없습니다/i)).toBeInTheDocument()
    })
  })
})
