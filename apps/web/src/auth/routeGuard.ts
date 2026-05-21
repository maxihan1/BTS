// 라우트 가드 헬퍼 — requireAuth / redirectIfAuth / isSafeReturnTo (open redirect 방지 포함)
import { redirect } from '@tanstack/react-router'
import { useAuthStore } from './authStore'

/** beforeLoad 컨텍스트 중 가드에서 사용하는 최소 구조 */
interface GuardContext {
  location: { href: string; pathname: string }
}

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
