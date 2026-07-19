// TanStack Router 라우트 트리 단위 테스트 — memory history 기반 렌더 검증
import { describe, it, expect, afterEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { RouterProvider, createRouter, createMemoryHistory } from '@tanstack/react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { routeTree } from './router'
import { useAuthStore } from './auth/authStore'
import { server } from '@/test/server'
import { burndownHandlers, resetBurndownStore, seedBurndown, DEFAULT_BURNDOWN } from '@/mocks/burndown-handlers'
import { velocityHandlers, resetVelocityStore, seedVelocity, DEFAULT_VELOCITY } from '@/mocks/velocity-handlers'

// issues 어댑터들은 useQuery/useParams 등 라우터 컨텍스트 의존 — 최소 mock
vi.mock('./routes/issues.index', () => ({
  IssueListRouteAdapter: () => <div>이슈 목록</div>,
}))
vi.mock('./routes/issues.new', () => ({
  IssueCreateRouteAdapter: () => <div>이슈 생성</div>,
}))
vi.mock('./routes/issues.$key', () => ({
  IssueDetailRouteAdapter: () => <div>이슈 상세</div>,
}))
// admin.webhooks 어댑터들도 useQuery 등 라우터 컨텍스트 의존 — 최소 mock (Task 9)
vi.mock('./routes/admin.webhooks', () => ({
  AdminWebhooksRouteAdapter: () => <div>Webhook 관리 placeholder</div>,
}))
vi.mock('./routes/admin.webhooks.$id.deliveries', () => ({
  WebhookDeliveriesRouteAdapter: () => <div>Webhook 발송 이력 placeholder</div>,
}))
// settings.pats 어댑터도 useQuery 등 라우터 컨텍스트 의존 — 최소 mock (FR-API-04 Task 8)
vi.mock('./routes/settings.pats', () => ({
  SettingsPatsRouteAdapter: () => <div>PAT 관리 placeholder</div>,
}))

function renderWithRoute(path: string) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const memoryHistory = createMemoryHistory({ initialEntries: [path] })
  const testRouter = createRouter({ routeTree, history: memoryHistory })
  return render(
    <QueryClientProvider client={client}>
      <RouterProvider router={testRouter} />
    </QueryClientProvider>,
  )
}

describe('Router', () => {
  afterEach(() => {
    useAuthStore.getState().clearSession()
  })

  it('/login 라우트 마운트 → LoginPage placeholder 렌더', async () => {
    renderWithRoute('/login')
    expect(await screen.findByText(/BTS 로그인/)).toBeInTheDocument()
  })

  it('/dashboard 라우트 마운트 (인증 상태) → DashboardPage placeholder 렌더', async () => {
    useAuthStore.getState().setSession({
      accessToken: 'test-access-token',
      user: { username: 'tester', email: 't@t', authMethod: 'local', userId: 'u1', mustChangePassword: false, isSystemAdmin: false, mfaEnrollmentRequired: false },
    })
    renderWithRoute('/dashboard')
    expect(await screen.findByText(/환영합니다/)).toBeInTheDocument()
  })

  // 미인증 상태에서 /dashboard 진입 시 /login 리다이렉트는 routeGuard.test.tsx 가 검증
  it('/ 라우트 마운트 → 인덱스 placeholder 렌더', async () => {
    renderWithRoute('/')
    expect(await screen.findByText(/홈/)).toBeInTheDocument()
  })

  // ─── Task 8: 이슈 라우트 3개 + 네비 링크 ────────────────────────────────────

  it('/issues 라우트 마운트 (인증 상태) → IssueListRouteAdapter 렌더', async () => {
    useAuthStore.getState().setSession({
      accessToken: 'test-access-token',
      user: { username: 'tester', email: 't@t', authMethod: 'local', userId: 'u1', mustChangePassword: false, isSystemAdmin: false, mfaEnrollmentRequired: false },
    })
    renderWithRoute('/issues')
    expect(await screen.findByText(/이슈 목록/)).toBeInTheDocument()
  })

  it('/issues 라우트 — 미인증 상태에서 /login 으로 리다이렉트 (requireAuth 가드 적용 증거)', async () => {
    // clearSession 상태(미인증) — requireAuth 가드가 /login 으로 redirect
    renderWithRoute('/issues')
    expect(await screen.findByText(/BTS 로그인/)).toBeInTheDocument()
  })

  it('/issues/new 라우트 마운트 (인증 상태) → IssueCreateRouteAdapter 렌더', async () => {
    useAuthStore.getState().setSession({
      accessToken: 'test-access-token',
      user: { username: 'tester', email: 't@t', authMethod: 'local', userId: 'u1', mustChangePassword: false, isSystemAdmin: false, mfaEnrollmentRequired: false },
    })
    renderWithRoute('/issues/new')
    expect(await screen.findByText(/이슈 생성/)).toBeInTheDocument()
  })

  it('/issues/$key 라우트 마운트 (인증 상태) → IssueDetailRouteAdapter 렌더', async () => {
    useAuthStore.getState().setSession({
      accessToken: 'test-access-token',
      user: { username: 'tester', email: 't@t', authMethod: 'local', userId: 'u1', mustChangePassword: false, isSystemAdmin: false, mfaEnrollmentRequired: false },
    })
    renderWithRoute('/issues/ATLAS-1')
    expect(await screen.findByText(/이슈 상세/)).toBeInTheDocument()
  })

  it('dashboard에 /issues 네비 링크이 존재한다 (사이드바 메인 nav + 페이지 자체 링크, FR-UX-06 PR11부터 2개)', async () => {
    useAuthStore.getState().setSession({
      accessToken: 'test-access-token',
      user: { username: 'tester', email: 't@t', authMethod: 'local', userId: 'u1', mustChangePassword: false, isSystemAdmin: false, mfaEnrollmentRequired: false },
    })
    renderWithRoute('/dashboard')
    await screen.findByText(/환영합니다/)
    // PR11부터 사이드바(Sidebar) 메인 nav가 `이슈` 링크(FR3)를 신설해 페이지 자체 링크와
    // 함께 2개가 된다 — 둘 다 동일한 /issues로 연결되므로 의도된 중복(회귀 아님)이다.
    const issueLinks = screen.getAllByRole('link', { name: /이슈/ })
    expect(issueLinks).toHaveLength(2)
    for (const link of issueLinks) {
      expect(link).toHaveAttribute('href', '/issues')
    }
  })

  // ─── Task 3: MFA 등록 강제 게이트 라우터 합성 ────────────────────────────────

  it('mfaEnrollmentRequired:true 세션에서 보호 라우트(/dashboard) 접근 시 /settings/mfa 로 리다이렉트', async () => {
    useAuthStore.getState().setSession({
      accessToken: 'test-access-token',
      user: { username: 'tester', email: 't@t', authMethod: 'local', userId: 'u1', mustChangePassword: false, isSystemAdmin: false, mfaEnrollmentRequired: true },
    })
    renderWithRoute('/dashboard')
    // MFA 등록 설정 페이지로 리다이렉트됨
    expect(await screen.findByText(/2단계 인증/)).toBeInTheDocument()
  })

  it('mfaEnrollmentRequired:true 세션에서 /settings/mfa 라우트 자신은 통과(리다이렉트 루프 없음)', async () => {
    useAuthStore.getState().setSession({
      accessToken: 'test-access-token',
      user: { username: 'tester', email: 't@t', authMethod: 'local', userId: 'u1', mustChangePassword: false, isSystemAdmin: false, mfaEnrollmentRequired: true },
    })
    renderWithRoute('/settings/mfa')
    // /settings/mfa 자신은 통과해야 함 — MFA 설정 페이지 렌더됨
    expect(await screen.findByText(/2단계 인증/)).toBeInTheDocument()
  })

  it('mustChangePassword:true 이고 mfaEnrollmentRequired:true 이면 비밀번호 변경이 우선(/settings/password 로 리다이렉트)', async () => {
    useAuthStore.getState().setSession({
      accessToken: 'test-access-token',
      user: { username: 'tester', email: 't@t', authMethod: 'local', userId: 'u1', mustChangePassword: true, isSystemAdmin: false, mfaEnrollmentRequired: true },
    })
    renderWithRoute('/dashboard')
    // requirePasswordChanged 가 requireMfaEnrolled 보다 먼저 실행 — 비밀번호 변경 페이지로 리다이렉트
    expect(await screen.findByRole('heading', { name: /비밀번호 변경/, level: 1 }, { timeout: 3000 })).toBeInTheDocument()
  })

  it('mfaEnrollmentRequired:false 세션에서 보호 라우트(/issues) 접근 시 정상 렌더', async () => {
    useAuthStore.getState().setSession({
      accessToken: 'test-access-token',
      user: { username: 'tester', email: 't@t', authMethod: 'local', userId: 'u1', mustChangePassword: false, isSystemAdmin: false, mfaEnrollmentRequired: false },
    })
    renderWithRoute('/issues')
    expect(await screen.findByText(/이슈 목록/)).toBeInTheDocument()
  })

  // ─── Task 9: admin.webhooks 라우트 등록 + requireSystemAdmin 가드 (FR-API-03 PR4) ────

  it('/admin/webhooks 라우트 마운트 (isSystemAdmin) → AdminWebhooksRouteAdapter 렌더', async () => {
    useAuthStore.getState().setSession({
      accessToken: 'test-access-token',
      user: { username: 'admin', email: 'a@t', authMethod: 'local', userId: 'u1', mustChangePassword: false, isSystemAdmin: true, mfaEnrollmentRequired: false },
    })
    renderWithRoute('/admin/webhooks')
    expect(await screen.findByText('Webhook 관리 placeholder')).toBeInTheDocument()
  })

  it('/admin/webhooks 라우트 — 비관리자 접근 시 /dashboard 로 리다이렉트 (requireSystemAdmin 가드)', async () => {
    useAuthStore.getState().setSession({
      accessToken: 'test-access-token',
      user: { username: 'tester', email: 't@t', authMethod: 'local', userId: 'u1', mustChangePassword: false, isSystemAdmin: false, mfaEnrollmentRequired: false },
    })
    renderWithRoute('/admin/webhooks')
    expect(await screen.findByText(/환영합니다/)).toBeInTheDocument()
  })

  it('/admin/webhooks/$id/deliveries 라우트 마운트 (isSystemAdmin) → WebhookDeliveriesRouteAdapter 렌더', async () => {
    useAuthStore.getState().setSession({
      accessToken: 'test-access-token',
      user: { username: 'admin', email: 'a@t', authMethod: 'local', userId: 'u1', mustChangePassword: false, isSystemAdmin: true, mfaEnrollmentRequired: false },
    })
    renderWithRoute('/admin/webhooks/wh-1/deliveries')
    expect(await screen.findByText('Webhook 발송 이력 placeholder')).toBeInTheDocument()
  })

  it('/admin/webhooks/$id/deliveries 라우트 — 비관리자 접근 시 /dashboard 로 리다이렉트', async () => {
    useAuthStore.getState().setSession({
      accessToken: 'test-access-token',
      user: { username: 'tester', email: 't@t', authMethod: 'local', userId: 'u1', mustChangePassword: false, isSystemAdmin: false, mfaEnrollmentRequired: false },
    })
    renderWithRoute('/admin/webhooks/wh-1/deliveries')
    expect(await screen.findByText(/환영합니다/)).toBeInTheDocument()
  })

  // ─── FR-API-04 Task 8: /settings/pats 라우트 등록 + 가드 체인 ───────────────

  it('/settings/pats 라우트 마운트 (인증 상태) → SettingsPatsRouteAdapter 렌더', async () => {
    useAuthStore.getState().setSession({
      accessToken: 'test-access-token',
      user: { username: 'tester', email: 't@t', authMethod: 'local', userId: 'u1', mustChangePassword: false, isSystemAdmin: false, mfaEnrollmentRequired: false },
    })
    renderWithRoute('/settings/pats')
    expect(await screen.findByText('PAT 관리 placeholder')).toBeInTheDocument()
  })

  it('/settings/pats 라우트 — 미인증 상태에서 /login 으로 리다이렉트 (requireAuth 가드 적용 증거)', async () => {
    renderWithRoute('/settings/pats')
    expect(await screen.findByText(/BTS 로그인/)).toBeInTheDocument()
  })

  it('/settings/pats 라우트 — mustChangePassword:true 이면 /settings/password 로 리다이렉트 (requirePasswordChanged 가드 적용 증거)', async () => {
    useAuthStore.getState().setSession({
      accessToken: 'test-access-token',
      user: { username: 'tester', email: 't@t', authMethod: 'local', userId: 'u1', mustChangePassword: true, isSystemAdmin: false, mfaEnrollmentRequired: false },
    })
    renderWithRoute('/settings/pats')
    expect(await screen.findByRole('heading', { name: /비밀번호 변경/, level: 1 }, { timeout: 3000 })).toBeInTheDocument()
  })

  // ─── FR-RP-01 D6/D7 Task-4: /projects/$projectKey/sprints/$sprintId/burndown 라우트 등록 ───

  it('/projects/ATLAS/sprints/:sprintId/burndown 라우트 — 미인증 상태에서 /login 으로 리다이렉트 (requireAuth 가드 적용 증거)', async () => {
    renderWithRoute('/projects/ATLAS/sprints/a0000000-0000-4000-8000-000000000001/burndown')
    expect(await screen.findByText(/BTS 로그인/)).toBeInTheDocument()
  })

  it('/projects/ATLAS/sprints/:sprintId/burndown 라우트 마운트 (인증 상태) → 페이지 제목 h1 렌더', async () => {
    // 이 라우트는 useQuery로 실제 fetchSprintBurndown을 호출하므로 burndownHandlers를 MSW에 등록 + 시드한다.
    resetBurndownStore()
    seedBurndown(DEFAULT_BURNDOWN)
    server.use(...burndownHandlers)

    useAuthStore.getState().setSession({
      accessToken: 'test-access-token',
      user: { username: 'tester', email: 't@t', authMethod: 'local', userId: 'u1', mustChangePassword: false, isSystemAdmin: false, mfaEnrollmentRequired: false },
    })
    renderWithRoute('/projects/ATLAS/sprints/a0000000-0000-4000-8000-000000000001/burndown')
    expect(
      await screen.findByRole('heading', { name: '번다운 / 번업 차트', level: 1 }),
    ).toBeInTheDocument()
  })

  // ─── FR-RP-02 D6/D7 Task-4: /projects/$projectKey/reports/velocity 라우트 등록 ───

  it('/projects/ATLAS/reports/velocity 라우트 — 미인증 상태에서 /login 으로 리다이렉트 (requireAuth 가드 적용 증거)', async () => {
    renderWithRoute('/projects/ATLAS/reports/velocity')
    expect(await screen.findByText(/BTS 로그인/)).toBeInTheDocument()
  })

  it('/projects/ATLAS/reports/velocity 라우트 마운트 (인증 상태) → 페이지 제목 h1 렌더', async () => {
    // 이 라우트는 useQuery로 실제 fetchProjectVelocity를 호출하므로 velocityHandlers를 MSW에 등록 + 시드한다.
    resetVelocityStore()
    seedVelocity(DEFAULT_VELOCITY)
    server.use(...velocityHandlers)

    useAuthStore.getState().setSession({
      accessToken: 'test-access-token',
      user: { username: 'tester', email: 't@t', authMethod: 'local', userId: 'u1', mustChangePassword: false, isSystemAdmin: false, mfaEnrollmentRequired: false },
    })
    renderWithRoute('/projects/ATLAS/reports/velocity')
    expect(
      await screen.findByRole('heading', { name: '벨로시티 차트', level: 1 }),
    ).toBeInTheDocument()
  })
})
