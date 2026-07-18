// 전역 권한 관리자 페이지 라우트 단위 테스트 — 조립 렌더 + RouteAdapter (FR-PM-10 D6/D7 Task 6)
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { server } from '@/test/server'
import { globalPermissionHandlers, resetGlobalPermissionStore } from '@/mocks/global-permission-handlers'
import { groupHandlers, resetGroupStore } from '@/mocks/group-handlers'
import { userHandlers } from '@/mocks/user-handlers'
import { useAuthStore } from '@/auth/authStore'
import {
  GlobalPermissionsPage,
  AdminGlobalPermissionsRouteAdapter,
} from '@/routes/admin.global-permissions'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function createWrapper() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return function Wrapper({ children }: { children: React.ReactNode }) {
    return <QueryClientProvider client={client}>{children}</QueryClientProvider>
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 인증 상태 + MSW 핸들러 초기화 (GlobalPermissionList.test.tsx 동일 패턴)
// ─────────────────────────────────────────────────────────────────────────────

beforeEach(() => {
  vi.clearAllMocks()
  resetGlobalPermissionStore()
  resetGroupStore()
  server.use(...globalPermissionHandlers, ...userHandlers, ...groupHandlers)
  useAuthStore.setState({
    accessToken: 'mock-access-token-alice',
    user: {
      userId: '00000000-0000-4000-8000-000000000001',
      username: 'alice',
      email: 'alice@example.com',
      authMethod: 'local',
      mustChangePassword: false,
      isSystemAdmin: true,
      mfaEnrollmentRequired: false,
    },
  })
  document.cookie = 'XSRF-TOKEN=test-csrf-token'
})

afterEach(() => {
  useAuthStore.setState({ accessToken: null, user: null })
})

// ─────────────────────────────────────────────────────────────────────────────
// 조립 렌더 — 페이지가 GlobalPermissionList를 렌더(FR-1)
// ─────────────────────────────────────────────────────────────────────────────

describe('GlobalPermissionsPage — 조립 렌더', () => {
  it('T1: GlobalPermissionList의 h1 heading "전역 권한 관리"가 렌더된다', async () => {
    render(<GlobalPermissionsPage />, { wrapper: createWrapper() })

    await waitFor(() => {
      expect(
        screen.getByRole('heading', { level: 1, name: '전역 권한 관리' }),
      ).toBeInTheDocument()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// RouteAdapter
// ─────────────────────────────────────────────────────────────────────────────

describe('AdminGlobalPermissionsRouteAdapter', () => {
  it('T2: RouteAdapter가 GlobalPermissionsPage를 렌더한다', async () => {
    render(<AdminGlobalPermissionsRouteAdapter />, { wrapper: createWrapper() })

    await waitFor(() => {
      expect(
        screen.getByRole('heading', { level: 1, name: '전역 권한 관리' }),
      ).toBeInTheDocument()
    })
  })
})
