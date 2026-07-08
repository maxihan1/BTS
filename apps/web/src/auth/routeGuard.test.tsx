// 라우트 가드 헬퍼 단위 테스트 — requireAuth / redirectIfAuth / isSafeReturnTo / requirePasswordChanged / requireSystemAdmin / requireMfaEnrolled
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { isRedirect } from '@tanstack/react-router'
import { useAuthStore } from './authStore'
import { requireAuth, redirectIfAuth, isSafeReturnTo, requirePasswordChanged, requireSystemAdmin, requireMfaEnrolled } from './routeGuard'
import { makeWhoami } from '@/mocks/auth-fixtures'

// TanStack Router beforeLoad 컨텍스트 중 가드에서 사용하는 최소 형태
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

const makeCtx = (pathname: string, search = ''): MinimalBeforeLoadContext => ({
  location: {
    href: pathname + search,
    pathname,
  },
})

beforeEach(() => {
  useAuthStore.setState({ accessToken: null, user: null })
})

afterEach(() => {
  useAuthStore.setState({ accessToken: null, user: null })
})

// ─────────────────────────────────────────────
// isSafeReturnTo
// ─────────────────────────────────────────────
describe('isSafeReturnTo', () => {
  it('슬래시로 시작하는 내부 경로 → 허용', () => {
    expect(isSafeReturnTo('/dashboard')).toBe(true)
    expect(isSafeReturnTo('/issues/PROJ-1')).toBe(true)
    expect(isSafeReturnTo('/login?next=foo')).toBe(true)
  })

  it('// 로 시작하는 경로 → 차단 (프로토콜 상대 URL, open redirect)', () => {
    expect(isSafeReturnTo('//evil.com')).toBe(false)
    expect(isSafeReturnTo('//evil.com/path')).toBe(false)
  })

  it('http/https 절대 URL → 차단', () => {
    expect(isSafeReturnTo('http://evil.com')).toBe(false)
    expect(isSafeReturnTo('https://evil.com')).toBe(false)
    expect(isSafeReturnTo('http://evil.com/path')).toBe(false)
  })

  it('javascript: 스킴 → 차단 (XSS)', () => {
    expect(isSafeReturnTo('javascript:alert(1)')).toBe(false)
    expect(isSafeReturnTo('JavaScript:alert(1)')).toBe(false)
  })

  it('data: 스킴 → 차단', () => {
    expect(isSafeReturnTo('data:text/html,<script>alert(1)</script>')).toBe(false)
  })

  it('빈 문자열 → 차단', () => {
    expect(isSafeReturnTo('')).toBe(false)
  })

  it('슬래시 없이 시작하는 상대 경로 → 차단 (경로 탈출 위험)', () => {
    expect(isSafeReturnTo('evil.com')).toBe(false)
    expect(isSafeReturnTo('dashboard')).toBe(false)
  })
})

// ─────────────────────────────────────────────
// requireAuth
// ─────────────────────────────────────────────
describe('requireAuth', () => {
  it('미인증 상태에서 호출 → redirect throw (to: /login)', () => {
    useAuthStore.setState({ accessToken: null })

    let thrown: unknown
    try {
      requireAuth(makeCtx('/dashboard'))
    } catch (e) {
      thrown = e
    }

    expect(thrown).toBeDefined()
    expect(isRedirect(thrown)).toBe(true)
    const r = thrown as RedirectResponse
    expect(r.options.to).toBe('/login')
  })

  it('미인증 → redirect search에 returnTo가 현재 location.href', () => {
    useAuthStore.setState({ accessToken: null })

    let thrown: unknown
    try {
      requireAuth(makeCtx('/dashboard', '?foo=bar'))
    } catch (e) {
      thrown = e
    }

    const r = thrown as RedirectResponse
    expect(r.options.search?.['returnTo']).toBe('/dashboard?foo=bar')
  })

  it('인증 상태에서 호출 → throw 없음 (통과)', () => {
    useAuthStore.setState({ accessToken: 'valid-token' })

    expect(() => requireAuth(makeCtx('/dashboard'))).not.toThrow()
  })
})

// ─────────────────────────────────────────────
// redirectIfAuth
// ─────────────────────────────────────────────
describe('redirectIfAuth', () => {
  it('미인증 상태에서 /login 진입 → throw 없음 (통과)', () => {
    useAuthStore.setState({ accessToken: null })

    expect(() => redirectIfAuth(makeCtx('/login'))).not.toThrow()
  })

  it('인증 상태에서 /login 진입 → redirect throw', () => {
    useAuthStore.setState({ accessToken: 'valid-token' })

    let thrown: unknown
    try {
      redirectIfAuth(makeCtx('/login'))
    } catch (e) {
      thrown = e
    }

    expect(thrown).toBeDefined()
    expect(isRedirect(thrown)).toBe(true)
  })

  it('인증 상태 + 안전한 returnTo → returnTo로 리다이렉트', () => {
    useAuthStore.setState({ accessToken: 'valid-token' })

    let thrown: unknown
    try {
      redirectIfAuth(makeCtx('/login', '?returnTo=/dashboard'))
    } catch (e) {
      thrown = e
    }

    const r = thrown as RedirectResponse
    expect(r.options.to).toBe('/dashboard')
  })

  it('인증 상태 + 외부 URL returnTo (http://evil.com) → start_page 매핑 폴백(/dashboards)으로 리다이렉트', () => {
    useAuthStore.setState({ accessToken: 'valid-token', user: null })

    let thrown: unknown
    try {
      redirectIfAuth(makeCtx('/login', '?returnTo=http://evil.com'))
    } catch (e) {
      thrown = e
    }

    const r = thrown as RedirectResponse
    expect(r.options.to).toBe('/dashboards')
  })

  it('인증 상태 + 프로토콜 상대 URL (//evil.com) → start_page 매핑 폴백(/dashboards)으로 리다이렉트', () => {
    useAuthStore.setState({ accessToken: 'valid-token', user: null })

    let thrown: unknown
    try {
      redirectIfAuth(makeCtx('/login', '?returnTo=//evil.com'))
    } catch (e) {
      thrown = e
    }

    const r = thrown as RedirectResponse
    expect(r.options.to).toBe('/dashboards')
  })

  it('인증 상태 + javascript: returnTo → start_page 매핑 폴백(/dashboards)으로 리다이렉트', () => {
    useAuthStore.setState({ accessToken: 'valid-token', user: null })

    let thrown: unknown
    try {
      redirectIfAuth(makeCtx('/login', '?returnTo=javascript:alert(1)'))
    } catch (e) {
      thrown = e
    }

    const r = thrown as RedirectResponse
    expect(r.options.to).toBe('/dashboards')
  })

  it('인증 상태 + returnTo 없음 + user.startPage 없음 → /dashboards 로 리다이렉트', () => {
    useAuthStore.setState({ accessToken: 'valid-token', user: null })

    let thrown: unknown
    try {
      redirectIfAuth(makeCtx('/login'))
    } catch (e) {
      thrown = e
    }

    const r = thrown as RedirectResponse
    expect(r.options.to).toBe('/dashboards')
  })

  // ───────────────────────────────────────────
  // FR-PF-02 Task 7 — returnTo 없을 때 start_page 매핑 우선순위
  // 게이트1 확정 우선순위: returnTo(안전 검증 통과) > start_page 매핑 > /dashboards
  // ───────────────────────────────────────────
  it('returnTo 없음 + user.startPage="issues" → /issues 로 리다이렉트', () => {
    useAuthStore.setState({
      accessToken: 'valid-token',
      user: makeWhoami({ startPage: 'issues' }),
    })

    let thrown: unknown
    try {
      redirectIfAuth(makeCtx('/login'))
    } catch (e) {
      thrown = e
    }

    const r = thrown as RedirectResponse
    expect(r.options.to).toBe('/issues')
  })

  it('returnTo가 있고 안전하면 user.startPage와 무관하게 returnTo가 우선한다', () => {
    useAuthStore.setState({
      accessToken: 'valid-token',
      user: makeWhoami({ startPage: 'issues' }),
    })

    let thrown: unknown
    try {
      redirectIfAuth(makeCtx('/login', '?returnTo=/dashboard'))
    } catch (e) {
      thrown = e
    }

    const r = thrown as RedirectResponse
    expect(r.options.to).toBe('/dashboard')
  })
})

// ─────────────────────────────────────────────
// requirePasswordChanged
// ─────────────────────────────────────────────
describe('requirePasswordChanged', () => {
  it('mustChangePassword=true인 유저 → /settings/password 로 redirect', () => {
    useAuthStore.setState({
      accessToken: 'valid-token',
      user: makeWhoami({ mustChangePassword: true }),
    })

    let thrown: unknown
    try {
      requirePasswordChanged(makeCtx('/dashboard'))
    } catch (e) {
      thrown = e
    }

    expect(thrown).toBeDefined()
    expect(isRedirect(thrown)).toBe(true)
    const r = thrown as RedirectResponse
    expect(r.options.to).toBe('/settings/password')
  })

  it('mustChangePassword=false인 유저 → throw 없음 (통과)', () => {
    useAuthStore.setState({
      accessToken: 'valid-token',
      user: makeWhoami({ mustChangePassword: false }),
    })

    expect(() => requirePasswordChanged(makeCtx('/dashboard'))).not.toThrow()
  })

  it('이미 /settings/password 경로이면 mustChangePassword=true여도 통과 (무한 redirect 방지)', () => {
    useAuthStore.setState({
      accessToken: 'valid-token',
      user: makeWhoami({ mustChangePassword: true }),
    })

    expect(() => requirePasswordChanged(makeCtx('/settings/password'))).not.toThrow()
  })

  it('user null이면 통과 (requireAuth가 먼저 처리)', () => {
    useAuthStore.setState({ accessToken: null, user: null })

    expect(() => requirePasswordChanged(makeCtx('/dashboard'))).not.toThrow()
  })
})

// ─────────────────────────────────────────────
// requireMfaEnrolled
// ─────────────────────────────────────────────
describe('requireMfaEnrolled', () => {
  it('user null이면 통과 (requireAuth가 먼저 처리)', () => {
    useAuthStore.setState({ accessToken: null, user: null })

    expect(() => requireMfaEnrolled(makeCtx('/dashboard'))).not.toThrow()
  })

  it('이미 /settings/mfa 경로이면 mfaEnrollmentRequired=true여도 통과 (무한 redirect 방지)', () => {
    useAuthStore.setState({
      accessToken: 'valid-token',
      user: makeWhoami({ mfaEnrollmentRequired: true }),
    })

    expect(() => requireMfaEnrolled(makeCtx('/settings/mfa'))).not.toThrow()
  })

  it('mfaEnrollmentRequired=true인 유저가 다른 경로 접근 → /settings/mfa 로 redirect', () => {
    useAuthStore.setState({
      accessToken: 'valid-token',
      user: makeWhoami({ mfaEnrollmentRequired: true }),
    })

    let thrown: unknown
    try {
      requireMfaEnrolled(makeCtx('/dashboard'))
    } catch (e) {
      thrown = e
    }

    expect(thrown).toBeDefined()
    expect(isRedirect(thrown)).toBe(true)
    const r = thrown as RedirectResponse
    expect(r.options.to).toBe('/settings/mfa')
  })

  it('mfaEnrollmentRequired=false인 유저 → throw 없음 (통과)', () => {
    useAuthStore.setState({
      accessToken: 'valid-token',
      user: makeWhoami({ mfaEnrollmentRequired: false }),
    })

    expect(() => requireMfaEnrolled(makeCtx('/dashboard'))).not.toThrow()
  })
})

// ─────────────────────────────────────────────
// requireSystemAdmin
// ─────────────────────────────────────────────
describe('requireSystemAdmin', () => {
  it('isSystemAdmin=true인 유저 → throw 없음 (통과)', () => {
    useAuthStore.setState({
      accessToken: 'valid-token',
      user: makeWhoami({ isSystemAdmin: true }),
    })

    expect(() => requireSystemAdmin()).not.toThrow()
  })

  it('isSystemAdmin=false인 유저 → /dashboard 로 redirect', () => {
    useAuthStore.setState({
      accessToken: 'valid-token',
      user: makeWhoami({ isSystemAdmin: false }),
    })

    let thrown: unknown
    try {
      requireSystemAdmin()
    } catch (e) {
      thrown = e
    }

    expect(thrown).toBeDefined()
    expect(isRedirect(thrown)).toBe(true)
    const r = thrown as RedirectResponse
    expect(r.options.to).toBe('/dashboard')
  })

  it('user null이면 → /dashboard 로 redirect (deny-by-default)', () => {
    useAuthStore.setState({ accessToken: null, user: null })

    let thrown: unknown
    try {
      requireSystemAdmin()
    } catch (e) {
      thrown = e
    }

    expect(thrown).toBeDefined()
    expect(isRedirect(thrown)).toBe(true)
    const r = thrown as RedirectResponse
    expect(r.options.to).toBe('/dashboard')
  })
})
