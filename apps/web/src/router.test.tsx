// TanStack Router 라우트 트리 단위 테스트 — memory history 기반 렌더 검증
import { describe, it, expect, afterEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import { RouterProvider, createRouter, createMemoryHistory } from '@tanstack/react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { routeTree } from './router'
import { useAuthStore } from './auth/authStore'

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
})
