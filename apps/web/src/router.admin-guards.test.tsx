// /admin/workflow-schemes 3라우트 SYSTEM_ADMIN 가드 배선 음성 회귀 테스트 — URL 직접도달 권한 갭 봉합 (FR-UX-06 PR13 Task 8, PL-8)
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { isRedirect } from '@tanstack/react-router'
import { router } from './router'
import { useAuthStore } from './auth/authStore'
import { makeWhoami } from './mocks/auth-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// routeGuard.ts의 개별 가드 함수를 재조합해 테스트하는 게 아니라, router.ts에 실제
// 등록된 3개 workflow-schemes 라우트의 beforeLoad를 router.routesById로 조회해 직접
// 호출한다 — "가드 함수 자체는 옳지만 라우트에 SYSTEM_ADMIN 배선을 빼먹었다" 케이스를
// 잡기 위한 배선 검증이다(vacuous 방지). admin.index.test.tsx (b) 섹션과 동형 패턴.
//
// 갭의 본질. 사이드바 admin nav는 isSystemAdmin으로 게이팅되나 URL 직접 도달은 일반
// 인증자도 통과했다(3라우트가 requireAuthAndPasswordChanged만 걸려 SYSTEM_ADMIN 미요구).
// 이 테스트가 비-admin 컨텍스트에서 /dashboard redirect를 요구해 그 갭을 봉인한다.
// ─────────────────────────────────────────────────────────────────────────────

/** TanStack Router beforeLoad 컨텍스트 중 가드에서 사용하는 최소 형태 (routeGuard.test.tsx와 동형) */
interface MinimalBeforeLoadContext {
  location: { href: string; pathname: string }
}

/** redirect() 반환 타입 — Response & { options: { to, ... } } */
interface RedirectResponse extends Response {
  options: { to: string }
}

const makeCtx = (pathname: string): MinimalBeforeLoadContext => ({
  location: { href: pathname, pathname },
})

/** router.routesById 값 타입 — 회귀 가드에 필요한 필드만 좁혀서 읽는다(router.shell.test.tsx 관례) */
const byId = router.routesById as Record<
  string,
  { options: { beforeLoad?: (ctx: MinimalBeforeLoadContext) => void } }
>

/**
 * SYSTEM_ADMIN 봉합 대상 3라우트 — routesById 키(_shell pathless 재부모화) + 실제 경로.
 * $schemeKey는 임의 세그먼트(WF-1)로 실경로를 재현한다(가드는 pathname을 스킴키로 파싱하지 않음).
 */
const GUARDED_WORKFLOW_SCHEME_ROUTES = [
  { id: '/_shell/admin/workflow-schemes', pathname: '/admin/workflow-schemes' },
  { id: '/_shell/admin/workflow-schemes/new', pathname: '/admin/workflow-schemes/new' },
  { id: '/_shell/admin/workflow-schemes/$schemeKey', pathname: '/admin/workflow-schemes/WF-1' },
] as const

describe('workflow-schemes 3라우트 beforeLoad — SYSTEM_ADMIN 가드 배선', () => {
  beforeEach(() => {
    useAuthStore.setState({ accessToken: null, user: null })
  })

  afterEach(() => {
    useAuthStore.setState({ accessToken: null, user: null })
  })

  // 비-admin: 인증됨(accessToken) + 비밀번호 변경 완료(mustChangePassword:false) + MFA 등록 완료
  // (mfaEnrollmentRequired:false)이지만 isSystemAdmin:false. 유일하게 걸려야 하는 게이트가
  // SYSTEM_ADMIN이므로, redirect가 나면 그 원인은 requireSystemAdmin으로 특정된다(판별자 있음).
  it.each(GUARDED_WORKFLOW_SCHEME_ROUTES)(
    '$id — 비-admin(isSystemAdmin:false·인증·비번변경완료·MFA완료) → /dashboard redirect throw',
    ({ id, pathname }) => {
      useAuthStore.setState({
        accessToken: 'valid-token',
        user: makeWhoami({ isSystemAdmin: false }),
      })

      const beforeLoad = byId[id]?.options.beforeLoad
      expect(beforeLoad).toBeTypeOf('function')

      let thrown: unknown
      try {
        beforeLoad?.(makeCtx(pathname))
      } catch (e) {
        thrown = e
      }

      expect(thrown).toBeDefined()
      expect(isRedirect(thrown)).toBe(true)
      const r = thrown as RedirectResponse
      expect(r.options.to).toBe('/dashboard')
    },
  )

  // admin: isSystemAdmin:true + 나머지 게이트 통과 조건 → 4-가드 전부 통과, throw 없음.
  it.each(GUARDED_WORKFLOW_SCHEME_ROUTES)(
    '$id — admin(isSystemAdmin:true) → throw 없음 (통과)',
    ({ id, pathname }) => {
      useAuthStore.setState({
        accessToken: 'valid-token',
        user: makeWhoami({ isSystemAdmin: true }),
      })

      const beforeLoad = byId[id]?.options.beforeLoad
      expect(beforeLoad).toBeTypeOf('function')

      expect(() => beforeLoad?.(makeCtx(pathname))).not.toThrow()
    },
  )
})
