// 감사 로그 관리자 라우트 단위 테스트 — requireSystemAdmin 가드 + 페이지 렌더
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { isRedirect } from '@tanstack/react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useAuthStore } from '@/auth/authStore'
import { composeGuards, requireAuth, requireSystemAdmin } from '@/auth/routeGuard'
import { makeWhoami } from '@/mocks/auth-fixtures'
import { AdminAuditLogsPage } from '@/routes/admin.audit-logs'

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
      <AdminAuditLogsPage />
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
})

afterEach(() => {
  useAuthStore.setState({ accessToken: null, user: null })
})

// ─────────────────────────────────────────────────────────────────────────────
// 페이지 렌더 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('AdminAuditLogsPage', () => {
  it('시스템 관리자 인증 상태에서 페이지 제목이 렌더된다', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument()
    })
  })

  it('페이지 제목이 "인증 감사 로그"를 포함한다', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByRole('heading', { name: /인증 감사 로그/ })).toBeInTheDocument()
    })
  })

  it('필터 컴포넌트가 렌더된다', async () => {
    renderPage()
    await waitFor(() => {
      // 이벤트 유형 Select(combobox)가 렌더되어야 함
      expect(screen.getByRole('combobox')).toBeInTheDocument()
    })
  })

  it('테이블 또는 로딩/빈 상태가 렌더된다', async () => {
    renderPage()
    // 테이블이나 로딩/빈 상태 중 하나가 렌더되어야 함
    await waitFor(() => {
      const hasTable = screen.queryByRole('table') !== null
      const hasStatus = screen.queryByRole('status') !== null
      const hasEmpty = screen.queryByText('조건에 맞는 로그가 없습니다.') !== null
      expect(hasTable || hasStatus || hasEmpty).toBe(true)
    })
  })

  it('이전/다음 페이지네이션 버튼이 렌더된다', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByRole('button', { name: '이전' })).toBeInTheDocument()
      expect(screen.getByRole('button', { name: '다음' })).toBeInTheDocument()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// requireSystemAdmin 가드 — /admin/audit-logs 라우트 레벨 보호
// ─────────────────────────────────────────────────────────────────────────────

describe('composeGuards(requireAuth, requireSystemAdmin) 가드 — /admin/audit-logs', () => {
  const auditLogsGuard = composeGuards(requireAuth, requireSystemAdmin)

  it('미인증 상태 → /login 리다이렉트', () => {
    useAuthStore.setState({ accessToken: null, user: null })

    let thrown: unknown
    try {
      auditLogsGuard(makeCtx('/admin/audit-logs'))
    } catch (e) {
      thrown = e
    }

    expect(thrown).toBeDefined()
    expect(isRedirect(thrown)).toBe(true)
    const r = thrown as RedirectResponse
    expect(r.options.to).toBe('/login')
  })

  it('인증됐지만 isSystemAdmin=false → /dashboard 리다이렉트', () => {
    useAuthStore.setState({
      accessToken: 'valid-token',
      user: makeWhoami({ isSystemAdmin: false }),
    })

    let thrown: unknown
    try {
      auditLogsGuard(makeCtx('/admin/audit-logs'))
    } catch (e) {
      thrown = e
    }

    expect(thrown).toBeDefined()
    expect(isRedirect(thrown)).toBe(true)
    const r = thrown as RedirectResponse
    expect(r.options.to).toBe('/dashboard')
  })

  it('시스템 관리자 → throw 없음 (통과)', () => {
    useAuthStore.setState({
      accessToken: 'valid-token',
      user: makeWhoami({ isSystemAdmin: true }),
    })

    expect(() => auditLogsGuard(makeCtx('/admin/audit-logs'))).not.toThrow()
  })
})
