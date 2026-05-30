// 비밀번호 변경 설정 페이지 라우트 단위 테스트 — 렌더 + requireAuth 가드 검증
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import { isRedirect } from '@tanstack/react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useAuthStore } from '@/auth/authStore'
import { requireAuth } from '@/auth/routeGuard'
import { PasswordSettingsPage } from '@/routes/settings.password'

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
      <PasswordSettingsPage />
    </QueryClientProvider>,
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 인증 상태 초기화
// ─────────────────────────────────────────────────────────────────────────────

beforeEach(() => {
  useAuthStore.setState({ accessToken: 'valid-token', user: null })
})

afterEach(() => {
  useAuthStore.setState({ accessToken: null, user: null })
})

// ─────────────────────────────────────────────────────────────────────────────
// PasswordSettingsPage 렌더 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('PasswordSettingsPage', () => {
  /**
   * T5-P1. 인증 상태에서 페이지 마운트 — 제목 "비밀번호 변경"이 렌더된다.
   */
  it('T5-P1: 인증 상태에서 제목 "비밀번호 변경"이 렌더된다', () => {
    renderPage()
    expect(screen.getByRole('heading', { name: '비밀번호 변경', level: 1 })).toBeInTheDocument()
  })

  /**
   * T5-P2. 인증 상태에서 페이지 마운트 — ChangePasswordForm의 식별 label이 렌더된다.
   * "현재 비밀번호" label 존재로 ChangePasswordForm 렌더 여부를 확인한다.
   */
  it('T5-P2: ChangePasswordForm의 "현재 비밀번호" 레이블이 렌더된다', () => {
    renderPage()
    expect(screen.getByLabelText('현재 비밀번호')).toBeInTheDocument()
  })

  /**
   * T5-P3. 인증 상태에서 페이지 마운트 — 설명 문구가 렌더된다.
   */
  it('T5-P3: 페이지 설명 문구가 렌더된다', () => {
    renderPage()
    expect(screen.getByText(/비밀번호를 변경하세요/)).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// requireAuth 가드 — /settings/password 라우트 레벨 보호
// ─────────────────────────────────────────────────────────────────────────────

describe('requireAuth 가드 — /settings/password', () => {
  /**
   * T5-G1. 미인증 상태에서 requireAuth 호출 → /login 리다이렉트.
   * router.ts의 beforeLoad: requireAuth 등록과 동일한 동작을 단위 검증한다.
   */
  it('T5-G1: 미인증 상태에서 requireAuth → /login 리다이렉트', () => {
    useAuthStore.setState({ accessToken: null })

    let thrown: unknown
    try {
      requireAuth(makeCtx('/settings/password'))
    } catch (e) {
      thrown = e
    }

    expect(thrown).toBeDefined()
    expect(isRedirect(thrown)).toBe(true)
    const r = thrown as RedirectResponse
    expect(r.options.to).toBe('/login')
  })

  /**
   * T5-G2. 인증 상태에서 requireAuth 호출 → throw 없음 (통과).
   */
  it('T5-G2: 인증 상태에서 requireAuth → throw 없음', () => {
    useAuthStore.setState({ accessToken: 'valid-token' })
    expect(() => requireAuth(makeCtx('/settings/password'))).not.toThrow()
  })
})
