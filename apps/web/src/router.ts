// TanStack Router 라우트 트리 정의 — code-based 패턴, 4개 라우트 (/, /login, /dashboard, /workflows/$key)
import { createRouter, createRoute, createRootRoute } from '@tanstack/react-router'
import { requireAuth, redirectIfAuth } from './auth/routeGuard'
import { RootLayout } from './routes/__root'
import { IndexPage } from './routes/index'
import { LoginPage } from './routes/login'
import { DashboardPage } from './routes/dashboard'
import { WorkflowDetailRouteAdapter } from './routes/workflows.$key'

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

export const routeTree = rootRoute.addChildren([
  indexRoute,
  loginRoute,
  dashboardRoute,
  workflowsKeyRoute,
])

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
