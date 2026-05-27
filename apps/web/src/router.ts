// TanStack Router 라우트 트리 정의 — code-based 패턴, 7개 라우트 (/, /login, /dashboard, /workflows/$key, /issues, /issues/new, /issues/$key)
import { createRouter, createRoute, createRootRoute } from '@tanstack/react-router'
import { requireAuth, redirectIfAuth } from './auth/routeGuard'
import { RootLayout } from './routes/__root'
import { IndexPage } from './routes/index'
import { LoginPage } from './routes/login'
import { DashboardPage } from './routes/dashboard'
import { WorkflowDetailRouteAdapter } from './routes/workflows.$key'
import { IssueListRouteAdapter } from './routes/issues.index'
import { IssueCreateRouteAdapter } from './routes/issues.new'
import { IssueDetailRouteAdapter } from './routes/issues.$key'

const rootRoute = createRootRoute({
  component: RootLayout,
})

const indexRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/',
  // T13 라우트 가드에서 dashboard / login 으로 리다이렉트 예정
  component: IndexPage,
})

const loginRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/login',
  component: LoginPage,
  staticData: { requireAuth: false },
  beforeLoad: redirectIfAuth,
})

const dashboardRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/dashboard',
  component: DashboardPage,
  staticData: { requireAuth: true },
  beforeLoad: requireAuth,
})

const workflowsKeyRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/workflows/$key',
  component: WorkflowDetailRouteAdapter,
})

/** 이슈 목록 라우트 — /issues, requireAuth. validateSearch로 page 쿼리 파라미터 타입 선언 */
const issuesIndexRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/issues',
  component: IssueListRouteAdapter,
  staticData: { requireAuth: true },
  beforeLoad: requireAuth,
  validateSearch: (search: Record<string, unknown>): { page?: number } => ({
    page: typeof search['page'] === 'number' ? search['page'] : undefined,
  }),
})

/** 이슈 생성 라우트 — /issues/new, requireAuth */
const issuesNewRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/issues/new',
  component: IssueCreateRouteAdapter,
  staticData: { requireAuth: true },
  beforeLoad: requireAuth,
})

/** 이슈 상세 라우트 — /issues/$key, requireAuth */
const issuesKeyRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/issues/$key',
  component: IssueDetailRouteAdapter,
  staticData: { requireAuth: true },
  beforeLoad: requireAuth,
})

/**
 * 전체 라우트 트리.
 * 7개 라우트: / · /login · /dashboard · /workflows/$key · /issues · /issues/new · /issues/$key
 * requireAuth 라우트: /dashboard · /issues · /issues/new · /issues/$key
 */
export const routeTree = rootRoute.addChildren([
  indexRoute,
  loginRoute,
  dashboardRoute,
  workflowsKeyRoute,
  issuesIndexRoute,
  issuesNewRoute,
  issuesKeyRoute,
])

/** 앱 전역 라우터 인스턴스 — Register 모듈 증강으로 전체 타입 안전 navigate 보장 */
export const router = createRouter({ routeTree })

declare module '@tanstack/react-router' {
  interface Register {
    router: typeof router
  }
  // 라우트별 인증 필요 여부를 staticData 로 선언.
  // 실제 가드 로직은 T13 beforeLoad 에서 구현.
  interface StaticDataRouteOption {
    requireAuth?: boolean
  }
}
