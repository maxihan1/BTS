// TanStack Router 라우트 트리 단위 테스트 — memory history 기반 렌더 검증
import { describe, it, expect, afterEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { RouterProvider, createRouter, createMemoryHistory } from '@tanstack/react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { routeTree } from './router'
import { useAuthStore } from './auth/authStore'

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
      user: { username: 'tester', email: 't@t', authMethod: 'local', userId: 'u1' },
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
      user: { username: 'tester', email: 't@t', authMethod: 'local', userId: 'u1' },
    })
    renderWithRoute('/issues')
    expect(await screen.findByText(/이슈 목록/)).toBeInTheDocument()
  })

  it('/issues 라우트 — requireAuth: true 가 staticData에 선언됨', () => {
    // routeTree에서 /issues 라우트를 찾아 staticData 확인
    const routes = routeTree.children ?? []
    const issuesRoute = routes.find(
      (r) => (r as { path?: string }).path === '/issues',
    )
    expect(issuesRoute).toBeDefined()
    expect((issuesRoute as { options?: { staticData?: { requireAuth?: boolean } } })
      ?.options?.staticData?.requireAuth).toBe(true)
  })

  it('/issues/new 라우트 마운트 (인증 상태) → IssueCreateRouteAdapter 렌더', async () => {
    useAuthStore.getState().setSession({
      accessToken: 'test-access-token',
      user: { username: 'tester', email: 't@t', authMethod: 'local', userId: 'u1' },
    })
    renderWithRoute('/issues/new')
    expect(await screen.findByText(/이슈 생성/)).toBeInTheDocument()
  })

  it('/issues/$key 라우트 마운트 (인증 상태) → IssueDetailRouteAdapter 렌더', async () => {
    useAuthStore.getState().setSession({
      accessToken: 'test-access-token',
      user: { username: 'tester', email: 't@t', authMethod: 'local', userId: 'u1' },
    })
    renderWithRoute('/issues/ATLAS-1')
    expect(await screen.findByText(/이슈 상세/)).toBeInTheDocument()
  })

  it('dashboard에 /issues 네비 링크 1개 존재', async () => {
    useAuthStore.getState().setSession({
      accessToken: 'test-access-token',
      user: { username: 'tester', email: 't@t', authMethod: 'local', userId: 'u1' },
    })
    renderWithRoute('/dashboard')
    await screen.findByText(/환영합니다/)
    const issueLinks = screen.getAllByRole('link', { name: /이슈/ })
    expect(issueLinks).toHaveLength(1)
    expect(issueLinks[0]).toHaveAttribute('href', '/issues')
  })
})
