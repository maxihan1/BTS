// 캘린더 구독 설정 페이지 라우트 단위 테스트 — 조립 렌더 + requireAuthAndPasswordChanged 가드 (FR-CA-02 Task 9)
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import { isRedirect } from '@tanstack/react-router'
import { QueryClientProvider, QueryClient } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { aliceUser, mockAccessToken, makeWhoami } from '@/mocks/auth-fixtures'
import { useAuthStore } from '@/auth/authStore'
import { composeGuards, requireAuth, requirePasswordChanged, requireMfaEnrolled } from '@/auth/routeGuard'
import { CalendarFeedSettingsPage } from '@/routes/settings.calendar'

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
      <CalendarFeedSettingsPage />
    </QueryClientProvider>,
  )
}

const ALICE_TOKEN = mockAccessToken('alice')

beforeEach(() => {
  server.use(
    http.get('/api/v1/users/me/calendar/feed', () => HttpResponse.json({ enabled: false })),
  )
  useAuthStore.getState().setSession({ accessToken: ALICE_TOKEN, user: aliceUser })
})

afterEach(() => {
  useAuthStore.getState().clearSession()
  server.resetHandlers()
})

// ─────────────────────────────────────────────────────────────────────────────
// CalendarFeedSettingsPage 조립 렌더
// ─────────────────────────────────────────────────────────────────────────────

describe('CalendarFeedSettingsPage — 조립 렌더', () => {
  it('페이지 제목이 렌더된다', () => {
    renderPage()
    expect(screen.getByRole('heading', { name: '캘린더 연동', level: 1 })).toBeInTheDocument()
  })

  it('CalendarFeedCard가 조립되어 미발급 상태의 발급 버튼이 렌더된다', async () => {
    renderPage()
    expect(await screen.findByRole('button', { name: '구독 URL 발급' })).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// requireAuthAndPasswordChanged 가드 — /settings/calendar (settings.keymap/pats 동일 가드)
// ─────────────────────────────────────────────────────────────────────────────

describe('requireAuthAndPasswordChanged 가드 — /settings/calendar', () => {
  const calendarGuard = composeGuards(requireAuth, requirePasswordChanged, requireMfaEnrolled)

  it('미인증 상태 → /login 리다이렉트', () => {
    useAuthStore.setState({ accessToken: null, user: null })

    let thrown: unknown
    try {
      calendarGuard(makeCtx('/settings/calendar'))
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
      calendarGuard(makeCtx('/settings/calendar'))
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

    expect(() => calendarGuard(makeCtx('/settings/calendar'))).not.toThrow()
  })
})
