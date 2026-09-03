// 라우트 가드 헬퍼 — requireAuth / redirectIfAuth / isSafeReturnTo / resolvePostLoginNav / requirePasswordChanged / requireMfaEnrolled / requireSystemAdmin / composeGuards
import { redirect } from '@tanstack/react-router'
import { useAuthStore } from './authStore'
import { resolveStartPageNav } from '@/lib/start-page'
import type { StartPageNav } from '@/lib/start-page'

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
 * 로그인 후 이동할 목적지를 우선순위에 따라 해석한다.
 *
 * 목적지 우선순위(게이트1 확정, FR-PF-02 Task 7). returnTo(안전 검증 통과) > start_page 매핑 > /dashboards.
 * - rawReturnTo가 있고 {@link isSafeReturnTo}를 통과하면 그 경로로 이동.
 * - 아니면 {@link resolveStartPageNav}로 startPage를 해석해 이동
 *   (화이트리스트 밖 값·userId 부재 시 내부적으로 `/dashboards`로 폴백).
 *
 * `routes/login.tsx`(handleSuccess)와 {@link redirectIfAuth} 양쪽에서 재사용한다.
 * returnTo 원본 문자열을 추출하는 방식(전자는 `window.location.search`, 후자는
 * `location.href` 파싱)은 호출부마다 정당하게 다르므로 그대로 유지하고,
 * 우선순위 판정 로직만 이 함수로 공유한다(중복 정의 금지).
 *
 * @param rawReturnTo 쿼리파라미터에서 추출한 returnTo 원본 값(없으면 null)
 * @param startPage 사용자 환경설정 startPage 값(whoami 등에서 온 느슨한 string)
 * @param userId 현재 로그인 사용자 id
 * @returns 이동할 라우트(`{ to, search? }`)
 */
export function resolvePostLoginNav(
  rawReturnTo: string | null,
  startPage: string | undefined,
  userId: string | undefined,
): StartPageNav {
  if (rawReturnTo !== null && isSafeReturnTo(rawReturnTo)) {
    return { to: rawReturnTo }
  }
  return resolveStartPageNav(startPage, userId)
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
 * 로그인 페이지에서 호출. 이미 인증된 상태이면 목적지로 throw redirect한다.
 *
 * 목적지 우선순위는 {@link resolvePostLoginNav} 참조(returnTo(안전 검증 통과) > start_page 매핑 > /dashboards).
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

  const user = useAuthStore.getState().user
  throw redirect(resolvePostLoginNav(returnTo, user?.startPage, user?.userId))
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
 * MFA 등록 강제 가드 (FR-MF-04). 보호된 라우트에서 requirePasswordChanged 다음에 체인한다.
 *
 * 결정 근거. docs/decisions/2026-06-12-mfa-enforcement-policy.md D4 — 백엔드 게이트(MfaEnrollmentGateFilter)
 * 와 동형으로 프론트도 강제 리다이렉트를 적용해 UX 단락을 방지한다. 권위 출처는 백엔드(403)이며,
 * 이 가드는 additive UX 편의 계층이다.
 *
 * 순서 관계. requirePasswordChanged 다음에 위치해야 한다.
 * mustChangePassword 조건이 mfaEnrollmentRequired보다 우선하므로,
 * 비밀번호 변경 강제가 먼저 처리된 후 MFA 등록 강제가 실행된다.
 *
 * - user.mfaEnrollmentRequired === true이면 /settings/mfa 로 throw redirect.
 * - 현재 경로가 /settings/mfa이면 통과 (무한 redirect 방지).
 * - user null이면 통과 (미인증 상태는 requireAuth가 이미 처리).
 *
 * 사용 예.
 * ```ts
 * beforeLoad: (ctx) => { requireAuth(ctx); requirePasswordChanged(ctx); requireMfaEnrolled(ctx) }
 * ```
 */
export function requireMfaEnrolled({ location }: GuardContext): void {
  const user = useAuthStore.getState().user
  if (user === null) return
  if (location.pathname === '/settings/mfa') return
  if (user.mfaEnrollmentRequired === true) {
    throw redirect({ to: '/settings/mfa' })
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
export function requireSystemAdmin(): void {
  const user = useAuthStore.getState().user
  if (user?.isSystemAdmin === true) return
  throw redirect({ to: '/dashboard' })
}

/**
 * 인덱스 라우트(`/`) 전용 가드. **항상 throw한다.**
 *
 * `/`는 자체 화면을 갖지 않는다. 통과시키면 빈 화면이 남으므로 언제나 어딘가로 보낸다.
 * 목적지는 {@link resolvePostLoginNav}가 정한다(start_page 매핑 > /dashboards).
 *
 * returnTo를 보지 않는 이유. 인덱스는 로그인 후 착지점이지 되돌아갈 곳이 아니다.
 * 미인증 처리는 앞에 체인된 {@link requireAuth}가 이미 담당한다.
 *
 * 사용 예.
 * ```ts
 * beforeLoad: composeGuards(requireAuthAndPasswordChanged, redirectToStartPage)
 * ```
 */
export function redirectToStartPage(): void {
  const user = useAuthStore.getState().user
  throw redirect(resolvePostLoginNav(null, user?.startPage, user?.userId))
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
