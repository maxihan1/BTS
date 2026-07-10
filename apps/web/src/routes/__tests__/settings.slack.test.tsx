// Slack 연결 설정 페이지 라우트 단위 테스트 — 조립 렌더 + requireAuthAndPasswordChanged 가드 (FR-SL-02 D6 Task 8)
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { isRedirect } from '@tanstack/react-router'
import { QueryClientProvider, QueryClient } from '@tanstack/react-query'
import { composeGuards, requireAuth, requirePasswordChanged, requireMfaEnrolled } from '@/auth/routeGuard'
import { useAuthStore } from '@/auth/authStore'
import { makeWhoami } from '@/mocks/auth-fixtures'
import { getMyConnection } from '@/api/slack'
import { SlackSettingsPage, SlackSettingsRouteAdapter } from '@/routes/settings.slack'

// ─────────────────────────────────────────────────────────────────────────────
// 의존 모듈 mock — SlackUserConnectionCard.test.tsx/admin.slack.test.tsx 동일 패턴
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('@/api/slack', () => ({
  getMyConnection: vi.fn(),
  connectSlack: vi.fn(),
  disconnectSlack: vi.fn(),
}))

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
      <SlackSettingsPage />
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  vi.mocked(getMyConnection).mockResolvedValue({ connected: false, workspaceName: null, linkedAt: null })
})

afterEach(() => {
  vi.restoreAllMocks()
  useAuthStore.getState().clearSession()
})

// ─────────────────────────────────────────────────────────────────────────────
// SlackSettingsPage 조립 렌더
// ─────────────────────────────────────────────────────────────────────────────

describe('SlackSettingsPage — 조립 렌더', () => {
  it('페이지 제목이 렌더된다', () => {
    renderPage()
    expect(screen.getByRole('heading', { name: 'Slack 연결', level: 1 })).toBeInTheDocument()
  })

  it('SlackUserConnectionCard가 조립되어 미연결 상태의 연결 버튼이 렌더된다', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Slack 연결' })).toBeInTheDocument()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// RouteAdapter
// ─────────────────────────────────────────────────────────────────────────────

describe('SlackSettingsRouteAdapter', () => {
  it('RouteAdapter가 SlackSettingsPage를 렌더한다', () => {
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    render(
      <QueryClientProvider client={client}>
        <SlackSettingsRouteAdapter />
      </QueryClientProvider>,
    )
    expect(screen.getByRole('heading', { name: 'Slack 연결', level: 1 })).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// requireAuthAndPasswordChanged 가드 — /settings/slack (settings.calendar 동일 가드)
// ─────────────────────────────────────────────────────────────────────────────

describe('requireAuthAndPasswordChanged 가드 — /settings/slack', () => {
  const slackGuard = composeGuards(requireAuth, requirePasswordChanged, requireMfaEnrolled)

  it('미인증 상태 → /login 리다이렉트', () => {
    useAuthStore.setState({ accessToken: null, user: null })

    let thrown: unknown
    try {
      slackGuard(makeCtx('/settings/slack'))
    } catch (e) {
      thrown = e
    }

    expect(thrown).toBeDefined()
    expect(isRedirect(thrown)).toBe(true)
    const r = thrown as RedirectResponse
    expect(r.options.to).toBe('/login')
  })

  it('mustChangePassword=true → /settings/password 리다이렉트', () => {
    useAuthStore.setState({
      accessToken: 'valid-token',
      user: makeWhoami({ mustChangePassword: true }),
    })

    let thrown: unknown
    try {
      slackGuard(makeCtx('/settings/slack'))
    } catch (e) {
      thrown = e
    }

    expect(thrown).toBeDefined()
    expect(isRedirect(thrown)).toBe(true)
    const r = thrown as RedirectResponse
    expect(r.options.to).toBe('/settings/password')
  })

  it('정상 인증 + mustChangePassword=false + mfaEnrollmentRequired=false → throw 없음', () => {
    useAuthStore.setState({
      accessToken: 'valid-token',
      user: makeWhoami({ mustChangePassword: false, mfaEnrollmentRequired: false }),
    })

    expect(() => slackGuard(makeCtx('/settings/slack'))).not.toThrow()
  })
})
