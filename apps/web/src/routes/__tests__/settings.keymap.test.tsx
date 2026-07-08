// 단축키 설정 페이지 라우트 단위 테스트 — 렌더 + requireAuthAndPasswordChanged 가드 (FR-PF-03 Task 9)
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import { isRedirect } from '@tanstack/react-router'
import { QueryClientProvider, QueryClient } from '@tanstack/react-query'
import { server } from '@/test/server'
import { aliceUser, mockAccessToken, makeWhoami } from '@/mocks/auth-fixtures'
import { useAuthStore } from '@/auth/authStore'
import { composeGuards, requireAuth, requirePasswordChanged, requireMfaEnrolled } from '@/auth/routeGuard'
import { resetKeymapStore } from '@/mocks/keymap-handlers'
import { KeymapSettingsPage } from '@/routes/settings.keymap'

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
      <KeymapSettingsPage />
    </QueryClientProvider>,
  )
}

const ALICE_TOKEN = mockAccessToken('alice')

beforeEach(() => {
  resetKeymapStore()
  useAuthStore.getState().setSession({ accessToken: ALICE_TOKEN, user: aliceUser })
})

afterEach(() => {
  useAuthStore.getState().clearSession()
  server.resetHandlers()
})

// ─────────────────────────────────────────────────────────────────────────────
// KeymapSettingsPage 렌더 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('KeymapSettingsPage', () => {
  it('T1: 제목 "단축키 설정"이 렌더된다', () => {
    renderPage()
    expect(screen.getByRole('heading', { name: '단축키 설정', level: 1 })).toBeInTheDocument()
  })

  it('T2: KeymapForm의 action 키 캡처 input이 렌더된다', async () => {
    renderPage()
    expect(await screen.findByLabelText('단축키 도움말 단축키 입력')).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// requireAuthAndPasswordChanged 가드 — /settings/keymap
// ─────────────────────────────────────────────────────────────────────────────

describe('requireAuthAndPasswordChanged 가드 — /settings/keymap', () => {
  const keymapGuard = composeGuards(requireAuth, requirePasswordChanged, requireMfaEnrolled)

  it('T3: 미인증 상태 → /login 리다이렉트', () => {
    useAuthStore.setState({ accessToken: null, user: null })

    let thrown: unknown
    try {
      keymapGuard(makeCtx('/settings/keymap'))
    } catch (e) {
      thrown = e
    }

    expect(thrown).toBeDefined()
    expect(isRedirect(thrown)).toBe(true)
    const r = thrown as RedirectResponse
    expect(r.options.to).toBe('/login')
  })

  it('T4: mustChangePassword=true → /settings/password 리다이렉트', () => {
    useAuthStore.setState({
      accessToken: 'valid-token',
      user: makeWhoami({ mustChangePassword: true }),
    })

    let thrown: unknown
    try {
      keymapGuard(makeCtx('/settings/keymap'))
    } catch (e) {
      thrown = e
    }

    expect(thrown).toBeDefined()
    expect(isRedirect(thrown)).toBe(true)
    const r = thrown as RedirectResponse
    expect(r.options.to).toBe('/settings/password')
  })

  it('T5: mfaEnrollmentRequired=true → /settings/mfa 리다이렉트', () => {
    useAuthStore.setState({
      accessToken: 'valid-token',
      user: makeWhoami({ mustChangePassword: false, mfaEnrollmentRequired: true }),
    })

    let thrown: unknown
    try {
      keymapGuard(makeCtx('/settings/keymap'))
    } catch (e) {
      thrown = e
    }

    expect(thrown).toBeDefined()
    expect(isRedirect(thrown)).toBe(true)
    const r = thrown as RedirectResponse
    expect(r.options.to).toBe('/settings/mfa')
  })

  it('T6: 정상 인증 + mustChangePassword=false + mfaEnrollmentRequired=false → throw 없음', () => {
    useAuthStore.setState({
      accessToken: 'valid-token',
      user: makeWhoami({ mustChangePassword: false, mfaEnrollmentRequired: false }),
    })

    expect(() => keymapGuard(makeCtx('/settings/keymap'))).not.toThrow()
  })
})
