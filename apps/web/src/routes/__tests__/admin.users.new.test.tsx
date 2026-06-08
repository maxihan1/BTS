// 사용자 생성 라우트 단위 테스트 — requireSystemAdmin 가드 검증 + 페이지 렌더
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import { isRedirect } from '@tanstack/react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useAuthStore } from '@/auth/authStore'
import { composeGuards, requireAuth, requireSystemAdmin } from '@/auth/routeGuard'
import { AdminUsersNewPage } from '@/routes/admin.users.new'
import { makeWhoami } from '@/mocks/auth-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

interface MinimalBeforeLoadContext {
  location: { href: string; pathname: string }
}

interface RedirectResponse extends Response {
  options: {
    to: string
    search?: Record<string, string>
  }
}

const makeCtx = (pathname: string): MinimalBeforeLoadContext => ({
  location: { href: pathname, pathname },
})

function renderPage(): ReturnType<typeof render> {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={client}>
      <AdminUsersNewPage />
    </QueryClientProvider>,
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 인증 상태 초기화
// ─────────────────────────────────────────────────────────────────────────────

beforeEach(() => {
  useAuthStore.setState({
    accessToken: 'valid-token',
    user: makeWhoami({ isSystemAdmin: true }),
  })
  document.cookie = 'XSRF-TOKEN=test-xsrf; path=/'
})

afterEach(() => {
  useAuthStore.setState({ accessToken: null, user: null })
  document.cookie = 'XSRF-TOKEN=; max-age=0; path=/'
})

// ─────────────────────────────────────────────────────────────────────────────
// AdminUsersNewPage 렌더 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('AdminUsersNewPage', () => {
  /**
   * T8-R-1. 시스템 관리자 인증 상태에서 페이지 마운트 — 제목이 렌더된다.
   */
  it('T8-R-1: 시스템 관리자 인증 상태에서 페이지 제목이 렌더된다', () => {
    renderPage()
    expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument()
  })

  /**
   * T8-R-2. 시스템 관리자 인증 상태에서 CreateUserForm 폼 필드가 렌더된다.
   */
  it('T8-R-2: CreateUserForm의 "사용자 이름" 레이블이 렌더된다', () => {
    renderPage()
    expect(screen.getByLabelText('사용자 이름')).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// requireSystemAdmin 가드 — /admin/users/new 라우트 레벨 보호
// ─────────────────────────────────────────────────────────────────────────────

describe('composeGuards(requireAuth, requireSystemAdmin) 가드 — /admin/users/new', () => {
  const adminUsersNewGuard = composeGuards(requireAuth, requireSystemAdmin)

  /**
   * T8-G-1. 미인증 상태 → /login 리다이렉트.
   */
  it('T8-G-1: 미인증 상태 → /login 리다이렉트', () => {
    useAuthStore.setState({ accessToken: null, user: null })

    let thrown: unknown
    try {
      adminUsersNewGuard(makeCtx('/admin/users/new'))
    } catch (e) {
      thrown = e
    }

    expect(thrown).toBeDefined()
    expect(isRedirect(thrown)).toBe(true)
    const r = thrown as RedirectResponse
    expect(r.options.to).toBe('/login')
  })

  /**
   * T8-G-2. 인증됐지만 isSystemAdmin=false → /dashboard 리다이렉트.
   */
  it('T8-G-2: 인증됐지만 isSystemAdmin=false → /dashboard 리다이렉트', () => {
    useAuthStore.setState({
      accessToken: 'valid-token',
      user: makeWhoami({ isSystemAdmin: false }),
    })

    let thrown: unknown
    try {
      adminUsersNewGuard(makeCtx('/admin/users/new'))
    } catch (e) {
      thrown = e
    }

    expect(thrown).toBeDefined()
    expect(isRedirect(thrown)).toBe(true)
    const r = thrown as RedirectResponse
    expect(r.options.to).toBe('/dashboard')
  })

  /**
   * T8-G-3. 시스템 관리자 → throw 없음 (통과).
   */
  it('T8-G-3: 시스템 관리자 → throw 없음 (통과)', () => {
    useAuthStore.setState({
      accessToken: 'valid-token',
      user: makeWhoami({ isSystemAdmin: true }),
    })

    expect(() => adminUsersNewGuard(makeCtx('/admin/users/new'))).not.toThrow()
  })
})
