// 대시보드 상세 라우트 단위 테스트 — DashboardDetailPage 타일 CRUD, 권한 게이팅, OCC 409 (FR-DB-01 Task 8)
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { Dashboard } from '@/api/dashboards'
import { useAuthStore } from '@/auth/authStore'

// ─────────────────────────────────────────────────────────────────────────────
// mock — TanStack Router, use-dashboards, DashboardGrid, DashboardForm, sonner, FavoriteButton
// ─────────────────────────────────────────────────────────────────────────────

const { mockNavigate } = vi.hoisted(() => ({ mockNavigate: vi.fn() }))

vi.mock('@tanstack/react-router', () => ({
  useParams: () => ({ dashboardId: 'a0000000-0000-4000-8000-000000000001' }),
  useNavigate: () => mockNavigate,
}))

vi.mock('sonner', () => ({
  toast: { success: vi.fn(), error: vi.fn() },
}))

// DashboardGrid mock — tiles/canEdit props를 캡처해 단언에 활용
let capturedGridTiles: unknown[] | null = null
let capturedGridCanEdit: boolean | null = null
let capturedGridOnLayoutChange: ((tiles: unknown[]) => void) | null = null
let capturedGridOnDeleteTile: ((id: string) => void) | null = null

vi.mock('@/components/dashboard/DashboardGrid', () => ({
  DashboardGrid: (props: {
    tiles: unknown[]
    canEdit: boolean
    onLayoutChange: (tiles: unknown[]) => void
    onDeleteTile: (id: string) => void
    onEditTitle: (id: string, title: string) => void
  }) => {
    capturedGridTiles = props.tiles
    capturedGridCanEdit = props.canEdit
    capturedGridOnLayoutChange = props.onLayoutChange
    capturedGridOnDeleteTile = props.onDeleteTile
    return (
      <div data-testid="dashboard-grid" data-can-edit={String(props.canEdit)}>
        {(props.tiles as Array<{ i: string; title: string }>).map((t) => (
          <div key={t.i} data-testid={`tile-${t.i}`}>
            {t.title}
          </div>
        ))}
      </div>
    )
  },
}))

// FavoriteButton mock — targetType·targetId props를 캡처해 단언에 활용
let capturedFavTargetType: string | null = null
let capturedFavTargetId: string | null = null

vi.mock('@/components/favorite/FavoriteButton', () => ({
  FavoriteButton: (props: { targetType: string; targetId: string }) => {
    capturedFavTargetType = props.targetType
    capturedFavTargetId = props.targetId
    return (
      <button
        type="button"
        data-testid="favorite-button"
        aria-label="즐겨찾기"
        data-target-type={props.targetType}
        data-target-id={props.targetId}
      >
        ★
      </button>
    )
  },
}))

// DashboardForm mock — 편집 설정 모달 내부에서 렌더됨 (추후 설정 폼 시나리오에서 활용)
vi.mock('@/components/dashboard/DashboardForm', () => ({
  DashboardForm: (props: {
    mode: string
    isPending: boolean
    onSubmit: (payload: Record<string, unknown>) => void
  }) => (
    <div data-testid="dashboard-form" data-mode={props.mode}>
      <button
        type="button"
        data-testid="mock-form-submit"
        onClick={() => props.onSubmit({ name: '수정된 이름' })}
      >
        저장
      </button>
    </div>
  ),
}))

// use-dashboards 훅 mock
const mockUseDashboard = vi.fn()
const mockUseUpdateDashboard = vi.fn()
const mockUseDeleteDashboard = vi.fn()
const mockMutateAsync = vi.fn()
const mockDeleteMutateAsync = vi.fn()

vi.mock('@/hooks/use-dashboards', () => ({
  useDashboard: (id: string) => mockUseDashboard(id),
  useUpdateDashboard: () => mockUseUpdateDashboard(),
  useDeleteDashboard: () => mockUseDeleteDashboard(),
  dashboardKeys: {
    detail: (id: string) => ['dashboard', id] as const,
    list: () => ['dashboards'] as const,
  },
}))

// ─────────────────────────────────────────────────────────────────────────────
// fixtures
// ─────────────────────────────────────────────────────────────────────────────

const ALICE_USER_ID = '00000000-0000-4000-8000-000000000001'
const BOB_USER_ID = '00000000-0000-4000-8000-000000000002'

const DASHBOARD_OWNED: Dashboard = {
  id: 'a0000000-0000-4000-8000-000000000001',
  ownerId: ALICE_USER_ID,
  name: '내 대시보드',
  description: '테스트용',
  visibility: 'PRIVATE',
  layout: JSON.stringify([{ i: 'tile-1', x: 0, y: 0, w: 6, h: 4, title: '위젯 1' }]),
  sharedUserIds: [],
  createdAt: '2026-06-01T00:00:00.000Z',
  updatedAt: '2026-06-01T00:00:00.000Z',
  version: 0,
}

const DASHBOARD_NOT_OWNED: Dashboard = {
  id: 'b0000000-0000-4000-8000-000000000001',
  ownerId: BOB_USER_ID,
  name: 'Bob의 대시보드',
  description: null,
  visibility: 'ORG',
  layout: '[]',
  sharedUserIds: [],
  createdAt: '2026-06-01T00:00:00.000Z',
  updatedAt: '2026-06-01T00:00:00.000Z',
  version: 0,
}

const DASHBOARD_CORRUPT_LAYOUT: Dashboard = {
  ...DASHBOARD_OWNED,
  layout: 'BROKEN_JSON{{{{',
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function makeClient(): QueryClient {
  return new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
}

async function renderDetailPage(dashboardId = 'a0000000-0000-4000-8000-000000000001') {
  const { DashboardDetailPage } = await import('@/routes/dashboards.$dashboardId')
  return render(
    <QueryClientProvider client={makeClient()}>
      <DashboardDetailPage dashboardId={dashboardId} currentUserId={ALICE_USER_ID} />
    </QueryClientProvider>,
  )
}

async function renderAdapter() {
  const { DashboardDetailRouteAdapter } = await import('@/routes/dashboards.$dashboardId')
  return render(
    <QueryClientProvider client={makeClient()}>
      <DashboardDetailRouteAdapter />
    </QueryClientProvider>,
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// beforeEach
// ─────────────────────────────────────────────────────────────────────────────

beforeEach(() => {
  capturedGridTiles = null
  capturedGridCanEdit = null
  capturedGridOnLayoutChange = null
  capturedGridOnDeleteTile = null
  capturedFavTargetType = null
  capturedFavTargetId = null

  vi.clearAllMocks()

  // 기본 auth 상태 — alice로 로그인
  useAuthStore.setState({
    accessToken: 'test-token',
    user: {
      userId: ALICE_USER_ID,
      username: 'alice',
      email: 'alice@example.com',
      authMethod: 'local',
      mustChangePassword: false,
      isSystemAdmin: false,
      mfaEnrollmentRequired: false,
    },
  })

  // 기본 mutation mock — 성공
  mockMutateAsync.mockResolvedValue({ ...DASHBOARD_OWNED, version: 1 })
  mockUseUpdateDashboard.mockReturnValue({ mutateAsync: mockMutateAsync, isPending: false })

  // 기본 delete mutation mock — 성공
  mockDeleteMutateAsync.mockResolvedValue(undefined)
  mockUseDeleteDashboard.mockReturnValue({ mutateAsync: mockDeleteMutateAsync, isPending: false })
})

afterEach(() => {
  useAuthStore.setState({ accessToken: null, user: null })
})

// ─────────────────────────────────────────────────────────────────────────────
// 로딩/에러/404
// ─────────────────────────────────────────────────────────────────────────────

describe('로딩·에러·404', () => {
  /**
   * T-DB8-L1. 로딩 중이면 로딩 스켈레톤/안내 문구가 렌더된다.
   */
  it('T-DB8-L1: 로딩 중이면 로딩 표시가 렌더된다', async () => {
    mockUseDashboard.mockReturnValue({ data: undefined, isLoading: true, isError: false })
    await renderDetailPage()
    expect(screen.getByText(/불러오는 중/i)).toBeInTheDocument()
  })

  /**
   * T-DB8-L2. 404(isError) 이면 "찾을 수 없음" 안내가 렌더된다 (EC5).
   */
  it('T-DB8-L2: 에러이면 대시보드를 찾을 수 없음 안내가 렌더된다', async () => {
    mockUseDashboard.mockReturnValue({ data: undefined, isLoading: false, isError: true })
    await renderDetailPage()
    expect(screen.getByText(/찾을 수 없/i)).toBeInTheDocument()
  })

  /**
   * T-DB8-L3. 정상 응답이면 대시보드 제목이 렌더된다.
   */
  it('T-DB8-L3: 정상 응답이면 대시보드 이름이 렌더된다', async () => {
    mockUseDashboard.mockReturnValue({ data: DASHBOARD_OWNED, isLoading: false, isError: false })
    await renderDetailPage()
    await waitFor(() => expect(screen.getByText('내 대시보드')).toBeInTheDocument())
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 권한 게이팅 (EC3 / S7)
// ─────────────────────────────────────────────────────────────────────────────

describe('권한 게이팅', () => {
  /**
   * T-DB8-P1. 소유자이면 canEdit=true가 DashboardGrid에 전달된다.
   */
  it('T-DB8-P1: 소유자이면 DashboardGrid에 canEdit=true가 전달된다', async () => {
    mockUseDashboard.mockReturnValue({ data: DASHBOARD_OWNED, isLoading: false, isError: false })
    await renderDetailPage()
    await waitFor(() => expect(screen.getByTestId('dashboard-grid')).toBeInTheDocument())
    expect(capturedGridCanEdit).toBe(true)
  })

  /**
   * T-DB8-P2. 비소유자이면 canEdit=false가 DashboardGrid에 전달된다 (EC3).
   */
  it('T-DB8-P2: 비소유자이면 DashboardGrid에 canEdit=false가 전달된다', async () => {
    mockUseDashboard.mockReturnValue({ data: DASHBOARD_NOT_OWNED, isLoading: false, isError: false })
    await renderDetailPage()
    await waitFor(() => expect(screen.getByTestId('dashboard-grid')).toBeInTheDocument())
    expect(capturedGridCanEdit).toBe(false)
  })

  /**
   * T-DB8-P3. 비소유자이면 "위젯 추가" 버튼이 없다 (EC3).
   */
  it('T-DB8-P3: 비소유자이면 위젯 추가 버튼이 없다', async () => {
    mockUseDashboard.mockReturnValue({ data: DASHBOARD_NOT_OWNED, isLoading: false, isError: false })
    await renderDetailPage()
    await waitFor(() => expect(screen.getByTestId('dashboard-grid')).toBeInTheDocument())
    expect(screen.queryByRole('button', { name: /위젯 추가/i })).toBeNull()
  })

  /**
   * T-DB8-P4. 소유자이면 "저장" 버튼이 있다.
   */
  it('T-DB8-P4: 소유자이면 저장 버튼이 렌더된다', async () => {
    mockUseDashboard.mockReturnValue({ data: DASHBOARD_OWNED, isLoading: false, isError: false })
    await renderDetailPage()
    await waitFor(() => expect(screen.getByRole('button', { name: /저장/i })).toBeInTheDocument())
  })

  /**
   * T-DB8-P5. 비소유자이면 "저장" 버튼이 없다 (EC3).
   */
  it('T-DB8-P5: 비소유자이면 저장 버튼이 없다', async () => {
    mockUseDashboard.mockReturnValue({ data: DASHBOARD_NOT_OWNED, isLoading: false, isError: false })
    await renderDetailPage()
    await waitFor(() => expect(screen.getByTestId('dashboard-grid')).toBeInTheDocument())
    expect(screen.queryByRole('button', { name: /저장/i })).toBeNull()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 타일 추가/삭제 로컬 state
// ─────────────────────────────────────────────────────────────────────────────

describe('타일 추가·삭제', () => {
  /**
   * T-DB8-A1. "위젯 추가" 클릭 시 tiles에 새 타일이 추가된다.
   */
  it('T-DB8-A1: 위젯 추가 클릭 시 tiles에 새 타일이 추가된다', async () => {
    const user = userEvent.setup()
    mockUseDashboard.mockReturnValue({ data: DASHBOARD_OWNED, isLoading: false, isError: false })
    await renderDetailPage()
    await waitFor(() => expect(screen.getByRole('button', { name: /위젯 추가/i })).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: /위젯 추가/i }))
    // 초기 1개 + 새로운 1개 = 2개
    expect(capturedGridTiles?.length).toBe(2)
  })

  /**
   * T-DB8-A2. 타일 삭제(onDeleteTile) 시 해당 타일이 로컬 state에서 제거된다.
   */
  it('T-DB8-A2: onDeleteTile 호출 시 해당 tiles가 로컬 state에서 제거된다', async () => {
    mockUseDashboard.mockReturnValue({ data: DASHBOARD_OWNED, isLoading: false, isError: false })
    await renderDetailPage()
    await waitFor(() => expect(screen.getByTestId('dashboard-grid')).toBeInTheDocument())
    // onDeleteTile 콜백을 직접 호출
    capturedGridOnDeleteTile?.('tile-1')
    await waitFor(() => expect(capturedGridTiles?.length).toBe(0))
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// dirty 표시 + 저장
// ─────────────────────────────────────────────────────────────────────────────

describe('dirty 표시 · 저장', () => {
  /**
   * T-DB8-D1. 타일 변경 후 dirty 상태임을 사용자에게 알린다(미저장 변경 표시).
   */
  it('T-DB8-D1: 타일 변경 후 미저장 변경 표시가 나타난다', async () => {
    mockUseDashboard.mockReturnValue({ data: DASHBOARD_OWNED, isLoading: false, isError: false })
    await renderDetailPage()
    await waitFor(() => expect(screen.getByTestId('dashboard-grid')).toBeInTheDocument())
    // onLayoutChange 호출로 dirty 유발
    const newTile = [{ i: 'tile-1', x: 2, y: 1, w: 6, h: 4, title: '위젯 1' }]
    capturedGridOnLayoutChange?.(newTile as never)
    await waitFor(() => expect(screen.getByText(/저장되지 않은/i)).toBeInTheDocument())
  })

  /**
   * T-DB8-D2. 저장 클릭 시 useUpdateDashboard.mutateAsync가 layout+version으로 호출된다.
   * dirty=true가 선행돼야 저장 버튼이 활성화되므로 위젯 추가로 먼저 dirty를 유발한다.
   */
  it('T-DB8-D2: 저장 클릭 시 mutateAsync가 layout과 version을 포함해 호출된다', async () => {
    const user = userEvent.setup()
    mockUseDashboard.mockReturnValue({ data: DASHBOARD_OWNED, isLoading: false, isError: false })
    await renderDetailPage()
    // dirty 유발 — 위젯 추가
    await waitFor(() => expect(screen.getByRole('button', { name: /위젯 추가/i })).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: /위젯 추가/i }))
    // 저장 버튼 활성화 확인 후 클릭
    await waitFor(() => {
      const saveBtn = screen.getByRole('button', { name: /저장/i })
      expect(saveBtn).not.toBeDisabled()
    })
    await user.click(screen.getByRole('button', { name: /저장/i }))
    await waitFor(() => expect(mockMutateAsync).toHaveBeenCalled())
    const callArg = mockMutateAsync.mock.calls[0]?.[0] as {
      id: string
      body: { layout: string; version: number }
    }
    expect(callArg?.body?.layout).toBeDefined()
    expect(typeof callArg?.body?.version).toBe('number')
  })

  /**
   * T-DB8-D3. 저장 중(isPending=true)이면 저장 버튼이 disabled된다.
   */
  it('T-DB8-D3: 저장 중이면 저장 버튼이 disabled된다', async () => {
    mockUseDashboard.mockReturnValue({ data: DASHBOARD_OWNED, isLoading: false, isError: false })
    mockUseUpdateDashboard.mockReturnValue({ mutateAsync: mockMutateAsync, isPending: true })
    await renderDetailPage()
    await waitFor(() => expect(screen.getByRole('button', { name: /저장|저장 중/i })).toBeInTheDocument())
    expect(screen.getByRole('button', { name: /저장|저장 중/i })).toBeDisabled()
  })

  /**
   * T-DB8-D4. dirty=false이면 저장 버튼이 disabled된다 (codereview 1번).
   * 초기 렌더 시 tiles는 서버에서 온 그대로이므로 dirty=false — 버튼이 비활성이어야 한다.
   */
  it('T-DB8-D4: dirty=false이면 저장 버튼이 disabled된다', async () => {
    mockUseDashboard.mockReturnValue({ data: DASHBOARD_OWNED, isLoading: false, isError: false })
    await renderDetailPage()
    await waitFor(() => expect(screen.getByRole('button', { name: /저장/i })).toBeInTheDocument())
    // 타일 변경 없음 → dirty=false → disabled
    expect(screen.getByRole('button', { name: /저장/i })).toBeDisabled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 설정 저장 (codereview 2번 — layout 포함 검증)
// ─────────────────────────────────────────────────────────────────────────────

describe('설정 저장', () => {
  /**
   * T-DB8-S1. 설정 저장 시 PATCH body에 layout이 포함된다 (codereview 2번 — 데이터 손실 방지).
   * 설정 저장 후 invalidate→refetch 흐름에서 useEffect가 tiles를 초기화하지 않도록,
   * 설정 PATCH에도 현재 로컬 tiles의 serializeLayout을 포함해야 한다.
   */
  it('T-DB8-S1: 설정 저장 시 PATCH body에 layout 필드가 포함된다', async () => {
    const user = userEvent.setup()
    mockUseDashboard.mockReturnValue({ data: DASHBOARD_OWNED, isLoading: false, isError: false })
    await renderDetailPage()
    // 설정 다이얼로그 열기
    await waitFor(() =>
      expect(screen.getByRole('button', { name: /설정/i })).toBeInTheDocument(),
    )
    await user.click(screen.getByRole('button', { name: /설정/i }))
    // DashboardForm mock 폼 제출 클릭
    await waitFor(() => expect(screen.getByTestId('mock-form-submit')).toBeInTheDocument())
    await user.click(screen.getByTestId('mock-form-submit'))
    await waitFor(() => expect(mockMutateAsync).toHaveBeenCalled())
    const callArg = mockMutateAsync.mock.calls[0]?.[0] as {
      id: string
      body: Record<string, unknown>
    }
    // 설정 PATCH body에 layout 필드가 있어야 한다
    expect(callArg?.body?.layout).toBeDefined()
    // layout은 직렬화된 JSON 문자열이어야 한다
    expect(typeof callArg?.body?.layout).toBe('string')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// OCC 409 처리 (EC4/S8)
// ─────────────────────────────────────────────────────────────────────────────

describe('OCC 409 처리', () => {
  /**
   * T-DB8-C1. 409 충돌 시 toast.error가 호출된다.
   * dirty=true가 선행돼야 저장 버튼이 활성화되므로 위젯 추가로 먼저 dirty를 유발한다.
   */
  it('T-DB8-C1: 409 충돌 시 toast.error가 호출된다', async () => {
    const { toast } = await import('sonner')
    const user = userEvent.setup()
    mockUseDashboard.mockReturnValue({ data: DASHBOARD_OWNED, isLoading: false, isError: false })
    mockMutateAsync.mockRejectedValue({ status: 409 })
    await renderDetailPage()
    // dirty 유발 → 저장 버튼 활성화
    await waitFor(() => expect(screen.getByRole('button', { name: /위젯 추가/i })).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: /위젯 추가/i }))
    await waitFor(() => {
      const saveBtn = screen.getByRole('button', { name: /저장/i })
      expect(saveBtn).not.toBeDisabled()
    })
    await user.click(screen.getByRole('button', { name: /저장/i }))
    await waitFor(() => expect(toast.error).toHaveBeenCalled())
  })

  /**
   * T-DB8-C2. 409 충돌 시 로컬 tiles를 덮어쓰지 않는다 (invalidate 금지).
   * onError에서 queryClient.invalidateQueries를 호출하면 tiles가 서버 값으로 덮이므로 금지.
   * 로컬 tiles 수가 유지됨을 확인한다.
   */
  it('T-DB8-C2: 409 충돌 시 로컬 tiles를 보존한다 (invalidate/refetch 금지)', async () => {
    const user = userEvent.setup()
    mockUseDashboard.mockReturnValue({ data: DASHBOARD_OWNED, isLoading: false, isError: false })
    mockMutateAsync.mockRejectedValue({ status: 409 })
    await renderDetailPage()
    await waitFor(() => expect(screen.getByRole('button', { name: /위젯 추가/i })).toBeInTheDocument())
    // 타일 추가 → dirty 상태 유발
    await user.click(screen.getByRole('button', { name: /위젯 추가/i }))
    const tilesBeforeSave = capturedGridTiles?.length ?? 0
    // 저장 시도 → 409
    await user.click(screen.getByRole('button', { name: /저장/i }))
    await waitFor(() => {
      // tiles 수가 409 전과 동일하게 유지돼야 한다
      expect(capturedGridTiles?.length).toBe(tilesBeforeSave)
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 손상 layout 폴백 (EC6)
// ─────────────────────────────────────────────────────────────────────────────

describe('손상 layout 폴백', () => {
  /**
   * T-DB8-EC6. layout이 손상된 JSON이면 빈 tiles로 폴백한다 (EC6).
   */
  it('T-DB8-EC6: 손상된 layout이면 빈 tiles[]로 폴백한다', async () => {
    mockUseDashboard.mockReturnValue({
      data: DASHBOARD_CORRUPT_LAYOUT,
      isLoading: false,
      isError: false,
    })
    await renderDetailPage()
    await waitFor(() => expect(screen.getByTestId('dashboard-grid')).toBeInTheDocument())
    expect(capturedGridTiles?.length).toBe(0)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 대시보드 삭제 (S6)
// ─────────────────────────────────────────────────────────────────────────────

describe('대시보드 삭제', () => {
  /**
   * T-DB8-DEL1. 소유자에게 삭제 버튼이 노출된다.
   */
  it('T-DB8-DEL1: 소유자이면 삭제 버튼이 헤더에 렌더된다', async () => {
    mockUseDashboard.mockReturnValue({ data: DASHBOARD_OWNED, isLoading: false, isError: false })
    await renderDetailPage()
    await waitFor(() => expect(screen.getByRole('button', { name: /삭제/i })).toBeInTheDocument())
  })

  /**
   * T-DB8-DEL2. 비소유자에게 삭제 버튼이 없다 (권한 게이팅 일관).
   */
  it('T-DB8-DEL2: 비소유자이면 삭제 버튼이 없다', async () => {
    mockUseDashboard.mockReturnValue({ data: DASHBOARD_NOT_OWNED, isLoading: false, isError: false })
    await renderDetailPage()
    await waitFor(() => expect(screen.getByTestId('dashboard-grid')).toBeInTheDocument())
    // 삭제 버튼이 DOM에 없어야 한다
    expect(screen.queryByRole('button', { name: /^삭제$/ })).toBeNull()
  })

  /**
   * T-DB8-DEL3. 삭제 버튼 클릭 → 인라인 확인 UI(확인/취소) 노출.
   */
  it('T-DB8-DEL3: 삭제 버튼 클릭 시 인라인 확인 UI가 나타난다', async () => {
    const user = userEvent.setup()
    mockUseDashboard.mockReturnValue({ data: DASHBOARD_OWNED, isLoading: false, isError: false })
    await renderDetailPage()
    await waitFor(() => expect(screen.getByRole('button', { name: /^삭제$/ })).toBeInTheDocument())
    // 삭제 버튼 클릭
    await user.click(screen.getByRole('button', { name: /^삭제$/ }))
    // 확인 + 취소 버튼이 나타난다
    await waitFor(() => {
      expect(screen.getByRole('button', { name: /확인/i })).toBeInTheDocument()
      expect(screen.getByRole('button', { name: /취소/i })).toBeInTheDocument()
    })
  })

  /**
   * T-DB8-DEL4. 확인 클릭 → useDeleteDashboard.mutateAsync 호출 + /dashboards 네비게이션.
   */
  it('T-DB8-DEL4: 확인 클릭 시 deleteAsync 호출 후 /dashboards로 이동한다', async () => {
    const user = userEvent.setup()
    mockUseDashboard.mockReturnValue({ data: DASHBOARD_OWNED, isLoading: false, isError: false })
    await renderDetailPage()
    await waitFor(() => expect(screen.getByRole('button', { name: /^삭제$/ })).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: /^삭제$/ }))
    await waitFor(() => expect(screen.getByRole('button', { name: /확인/i })).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: /확인/i }))
    await waitFor(() => {
      expect(mockDeleteMutateAsync).toHaveBeenCalledWith('a0000000-0000-4000-8000-000000000001')
      expect(mockNavigate).toHaveBeenCalledWith({ to: '/dashboards' })
    })
  })

  /**
   * T-DB8-DEL5. 취소 클릭 → useDeleteDashboard.mutateAsync 호출 안 함.
   */
  it('T-DB8-DEL5: 취소 클릭 시 삭제가 실행되지 않는다', async () => {
    const user = userEvent.setup()
    mockUseDashboard.mockReturnValue({ data: DASHBOARD_OWNED, isLoading: false, isError: false })
    await renderDetailPage()
    await waitFor(() => expect(screen.getByRole('button', { name: /^삭제$/ })).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: /^삭제$/ }))
    await waitFor(() => expect(screen.getByRole('button', { name: /취소/i })).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: /취소/i }))
    // 확인 UI가 사라지고 mutateAsync가 호출되지 않아야 한다
    await waitFor(() => expect(screen.queryByRole('button', { name: /확인/i })).toBeNull())
    expect(mockDeleteMutateAsync).not.toHaveBeenCalled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// RouteAdapter
// ─────────────────────────────────────────────────────────────────────────────

describe('DashboardDetailRouteAdapter', () => {
  /**
   * T-DB8-RA1. RouteAdapter가 useParams에서 dashboardId를 추출해 DashboardDetailPage에 전달한다.
   */
  it('T-DB8-RA1: RouteAdapter가 useParams dashboardId를 DashboardDetailPage에 전달한다', async () => {
    mockUseDashboard.mockReturnValue({ data: DASHBOARD_OWNED, isLoading: false, isError: false })
    await renderAdapter()
    await waitFor(() =>
      expect(mockUseDashboard).toHaveBeenCalledWith('a0000000-0000-4000-8000-000000000001'),
    )
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// FavoriteButton 렌더 (FR-UX-02 Task 7)
// ─────────────────────────────────────────────────────────────────────────────

describe('FavoriteButton 렌더', () => {
  /**
   * T-UX02-FAV1. 대시보드 로드 후 헤더 영역에 FavoriteButton이 렌더된다.
   * targetType="DASHBOARD", targetId=dashboardId UUID가 전달돼야 한다.
   */
  it('T-UX02-FAV1: 대시보드 로드 후 헤더에 FavoriteButton(DASHBOARD, dashboardId)이 렌더된다', async () => {
    mockUseDashboard.mockReturnValue({ data: DASHBOARD_OWNED, isLoading: false, isError: false })
    await renderDetailPage()
    await waitFor(() => expect(screen.getByTestId('favorite-button')).toBeInTheDocument())
    expect(capturedFavTargetType).toBe('DASHBOARD')
    expect(capturedFavTargetId).toBe(DASHBOARD_OWNED.id)
  })

  /**
   * T-UX02-FAV2. 로딩 중에는 FavoriteButton이 렌더되지 않는다 (데이터 미수신).
   */
  it('T-UX02-FAV2: 로딩 중에는 FavoriteButton이 렌더되지 않는다', async () => {
    mockUseDashboard.mockReturnValue({ data: undefined, isLoading: true, isError: false })
    await renderDetailPage()
    expect(screen.queryByTestId('favorite-button')).toBeNull()
  })

  /**
   * T-UX02-FAV3. 비소유자도 헤더에 FavoriteButton이 렌더된다 (즐겨찾기는 권한 무관).
   */
  it('T-UX02-FAV3: 비소유자도 FavoriteButton이 렌더된다', async () => {
    mockUseDashboard.mockReturnValue({ data: DASHBOARD_NOT_OWNED, isLoading: false, isError: false })
    await renderDetailPage()
    await waitFor(() => expect(screen.getByTestId('favorite-button')).toBeInTheDocument())
    expect(capturedFavTargetType).toBe('DASHBOARD')
    expect(capturedFavTargetId).toBe(DASHBOARD_NOT_OWNED.id)
  })
})
