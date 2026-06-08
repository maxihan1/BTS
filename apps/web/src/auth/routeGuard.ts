// 라우트 가드 헬퍼 — requireAuth / redirectIfAuth / isSafeReturnTo / requirePasswordChanged / requireSystemAdmin / composeGuards
import { redirect } from '@tanstack/react-router'
import { useAuthStore } from './authStore'

/** beforeLoad 컨텍스트 중 가드에서 사용하는 최소 구조 */
interface GuardContext {
  location: { href: string; pathname: string }
}

/** 가드 함수 타입 */
type Guard = (ctx: GuardContext) => void

/**
 * returnTo 값이 안전한 내부 경로인지 검사한다.
 *
 * 허용 조건.
 * - 반드시 단일 슬래시(/)로 시작
 *
 * 차단 조건.
 * - `//` 시작 — 프로토콜 상대 URL (open redirect)
 * - `http:` / `https:` / `javascript:` / `data:` 등 스킴 포함
 * - 슬래시로 시작하지 않는 상대 경로
 * - 빈 문자열
 */
export function isSafeReturnTo(value: string): boolean {
  if (!value) return false
  // // 시작 — 프로토콜 상대 URL 차단
  if (value.startsWith('//')) return false
  // 스킴 포함 (대소문자 무시) — http(s):, javascript:, data: 등
  if (/^[a-zA-Z][a-zA-Z0-9+\-.]*:/i.test(value)) return false
  // 단일 슬래시로 시작하는 내부 경로만 허용
  return value.startsWith('/')
}

/**
 * 보호된 라우트에서 호출. 미인증 상태이면 /login?returnTo=<현재경로> 로 throw redirect.
 *
 * 사용 예.
 * ```ts
 * createRoute({ beforeLoad: requireAuth })
 * ```
 */
export function requireAuth({ location }: GuardContext): void {
  const accessToken = useAuthStore.getState().accessToken
  if (!accessToken) {
    throw redirect({
      to: '/login',
      search: { returnTo: location.href },
    })
  }
}

/**
 * 로그인 페이지에서 호출. 이미 인증된 상태이면 returnTo(검증 후) 또는 /dashboard 로 throw redirect.
 *
 * 사용 예.
 * ```ts
 * createRoute({ beforeLoad: redirectIfAuth })
 * ```
 */
export function redirectIfAuth({ location }: GuardContext): void {
  const accessToken = useAuthStore.getState().accessToken
  if (!accessToken) return

  const qIdx = location.href.indexOf('?')
  const params = new URLSearchParams(qIdx !== -1 ? location.href.slice(qIdx + 1) : '')
  const returnTo = params.get('returnTo')

  const safeTo = returnTo !== null && isSafeReturnTo(returnTo) ? returnTo : '/dashboard'

  throw redirect({ to: safeTo })
}

/**
 * 비밀번호 변경 강제 가드. 보호된 라우트에서 requireAuth 다음에 체인한다.
 *
 * - user.mustChangePassword === true이면 /settings/password 로 throw redirect.
 * - 현재 경로가 /settings/password이면 통과 (무한 redirect 방지).
 * - user null이면 통과 (미인증 상태는 requireAuth가 이미 처리).
 *
 * 사용 예.
 * ```ts
 * beforeLoad: (ctx) => { requireAuth(ctx); requirePasswordChanged(ctx) }
 * ```
 */
export function requirePasswordChanged({ location }: GuardContext): void {
  const user = useAuthStore.getState().user
  if (user === null) return
  if (location.pathname === '/settings/password') return
  if (user.mustChangePassword === true) {
    throw redirect({ to: '/settings/password' })
  }
}

/**
 * 시스템 관리자 전용 가드. 관리자 전용 라우트의 beforeLoad에서 사용한다.
 *
 * - user.isSystemAdmin === true이면 통과.
 * - user null 또는 isSystemAdmin !== true이면 /dashboard 로 throw redirect (deny-by-default).
 *
 * 보안 주의. `=== true` 명시 비교로 undefined · null · 'admin' 등을 admin으로 오인하지 않는다.
 *
 * 사용 예.
 * ```ts
 * beforeLoad: (ctx) => { requireAuth(ctx); requireSystemAdmin(ctx) }
 * ```
 */
export function requireSystemAdmin({ location: _location }: GuardContext): void {
  const user = useAuthStore.getState().user
  if (user?.isSystemAdmin === true) return
  throw redirect({ to: '/dashboard' })
}

/**
 * 여러 가드를 순차 실행하는 합성 헬퍼. 앞 가드가 redirect를 throw하면 뒤 가드는 실행되지 않는다.
 *
 * 사용 예.
 * ```ts
 * beforeLoad: composeGuards(requireAuth, requirePasswordChanged)
 * ```
 */
export function composeGuards(...guards: Guard[]): (ctx: GuardContext) => void {
  return (ctx) => {
    for (const guard of guards) {
      guard(ctx)
    }
  }
}
