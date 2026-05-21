// TanStack Router 라우트 트리 정의 — code-based 패턴, 3개 라우트 (/, /login, /dashboard)
import { createRouter, createRoute, createRootRoute } from '@tanstack/react-router'
import { RootLayout } from './routes/__root'
import { IndexPage } from './routes/index'
import { LoginPage } from './routes/login'
import { DashboardPage } from './routes/dashboard'

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
})

const dashboardRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: '/dashboard',
  component: DashboardPage,
})

export const routeTree = rootRoute.addChildren([
  indexRoute,
  loginRoute,
  dashboardRoute,
])

export const router = createRouter({ routeTree })

declare module '@tanstack/react-router' {
  interface Register {
    router: typeof router
  }
}
