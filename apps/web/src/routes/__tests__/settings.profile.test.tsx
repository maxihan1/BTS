// 사용자 프로필 설정 페이지 라우트 단위 테스트 — 렌더 + requireAuth 가드 검증 (FR-PR-01 D6 Task 8)
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import { isRedirect } from '@tanstack/react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { server } from '@/test/server'
import { profileHandlers, resetProfileStore } from '@/mocks/profile-handlers'
import { authHandlers } from '@/mocks/auth-handlers'
import { aliceUser, mockAccessToken } from '@/mocks/auth-fixtures'
import { useAuthStore } from '@/auth/authStore'
import { requireAuth } from '@/auth/routeGuard'
import { profileLabels } from '@/i18n/profile-labels'
import { ProfileSettingsPage } from '@/routes/settings.profile'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

interface MinimalBeforeLoadContext {
  location: { href: string; pathname: string }
}

// redirect() 반환 타입 — Response & { options: { to, search, ... } }
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
      <ProfileSettingsPage />
    </QueryClientProvider>,
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 인증 상태 + MSW 프로필 핸들러 초기화
// ─────────────────────────────────────────────────────────────────────────────

beforeEach(() => {
  resetProfileStore()
  server.use(...profileHandlers, ...authHandlers)
  useAuthStore.getState().setSession({ accessToken: mockAccessToken('alice'), user: aliceUser })
})

afterEach(() => {
  useAuthStore.getState().clearSession()
})

// ─────────────────────────────────────────────────────────────────────────────
// ProfileSettingsPage 렌더 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('ProfileSettingsPage', () => {
  /**
   * T8-P1. 인증 상태에서 페이지 마운트 — 제목(profileLabels.page.heading)이 렌더된다.
   */
  it('T8-P1: 제목 "프로필"이 렌더된다', () => {
    renderPage()
    expect(
      screen.getByRole('heading', { name: profileLabels.page.heading, level: 1 }),
    ).toBeInTheDocument()
  })

  /**
   * T8-P2. 인증 상태에서 페이지 마운트 — ProfileForm의 "표시 이름" 레이블이 렌더된다.
   * (프로필 조회가 완료된 뒤에만 편집 폼이 나타나므로 waitFor로 대기)
   */
  it('T8-P2: ProfileForm의 "표시 이름" 레이블이 렌더된다', async () => {
    renderPage()
    await waitFor(() => {
      expect(screen.getByLabelText(profileLabels.form.displayNameLabel)).toBeInTheDocument()
    })
  })

  /**
   * T8-P3. 인증 상태에서 페이지 마운트 — 설명 문구가 렌더된다.
   */
  it('T8-P3: 페이지 설명 문구가 렌더된다', () => {
    renderPage()
    expect(screen.getByText(profileLabels.page.description)).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// requireAuth 가드 — /settings/profile 라우트 레벨 보호
// ─────────────────────────────────────────────────────────────────────────────

describe('requireAuth 가드 — /settings/profile', () => {
  /**
   * T8-G1. 미인증 상태에서 requireAuth 호출 → /login 리다이렉트.
   * router.ts의 beforeLoad: requireAuth 등록과 동일한 동작을 단위 검증한다.
   */
  it('T8-G1: 미인증 상태에서 requireAuth → /login 리다이렉트', () => {
    useAuthStore.setState({ accessToken: null })

    let thrown: unknown
    try {
      requireAuth(makeCtx('/settings/profile'))
    } catch (e) {
      thrown = e
    }

    expect(thrown).toBeDefined()
    expect(isRedirect(thrown)).toBe(true)
    const r = thrown as RedirectResponse
    expect(r.options.to).toBe('/login')
  })

  /**
   * T8-G2. 인증 상태에서 requireAuth 호출 → throw 없음 (통과).
   */
  it('T8-G2: 인증 상태에서 requireAuth → throw 없음', () => {
    useAuthStore.setState({ accessToken: 'valid-token' })
    expect(() => requireAuth(makeCtx('/settings/profile'))).not.toThrow()
  })
})
