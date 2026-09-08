// 프로젝트 CRUD 라우트 3종(목록·생성·설정 상세) _shell 등록 회귀 가드 — fullPath·가드 배선(basicGuardChain)·정적세그먼트 우선 (FR-PJ PR-5 Task 7)
import { describe, it, expect, afterEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import {
  RouterProvider,
  createRouter,
  createMemoryHistory,
  isRedirect,
} from '@tanstack/react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { routeTree, router } from '@/router'
import { useAuthStore } from '@/auth/authStore'
import { makeWhoami } from '@/mocks/auth-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// (a) 구조 — 3라우트가 `_shell` 자식으로 등록되고 fullPath가 정확한지 (router.shell.test.tsx
//     최상위 파일 관례 재사용, tanstack-pathless-layout-router-test-blind 회귀 방지)
// ─────────────────────────────────────────────────────────────────────────────

/** router.routesById 값 타입 — 회귀 가드에 필요한 필드만 좁혀서 읽는다(router.shell.test.tsx 관례) */
const byId = router.routesById as Record<string, { parentRoute?: { id?: string }; fullPath?: string }>

describe('프로젝트 CRUD 라우트 3종 등록 (_shell 재부모화, FR-PJ PR-5 Task 7)', () => {
  it('projectsIndexRoute(/projects)가 _shell 자식으로 등록되고 fullPath가 정확하다', () => {
    expect(byId['/_shell/projects']?.parentRoute?.id).toBe('/_shell')
    expect(byId['/_shell/projects']?.fullPath).toBe('/projects')
  })

  it('projectsNewRoute(/projects/new)가 _shell 자식으로 등록되고 fullPath가 정확하다', () => {
    expect(byId['/_shell/projects/new']?.parentRoute?.id).toBe('/_shell')
    expect(byId['/_shell/projects/new']?.fullPath).toBe('/projects/new')
  })

  it('projectDetailsSettingsRoute(/projects/$projectKey/settings/details)가 _shell 자식으로 등록되고 fullPath가 정확하다', () => {
    expect(byId['/_shell/projects/$projectKey/settings/details']?.parentRoute?.id).toBe('/_shell')
    expect(byId['/_shell/projects/$projectKey/settings/details']?.fullPath).toBe(
      '/projects/$projectKey/settings/details',
    )
  })

  it('라우트 카운트가 67개로 갱신된다 (65 + FR-WF-08 프로젝트 워크플로우 목록·편집기 2종, router.ts 헤더 주석과 동일 형식)', () => {
    const shellChildCount = Object.values(byId).filter(
      (route) => route.parentRoute?.id === '/_shell',
    ).length
    expect(shellChildCount).toBe(67)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// (b) 가드 배선 — 3라우트 전부 기존 프로젝트/설정 라우트와 동일 basicGuardChain
//     (requireAuth + requirePasswordChanged + requireMfaEnrolled). admin 가드 아님을
//     비-admin 통과 케이스로 증명한다 (admin.index.test.tsx / router.admin-guards.test.tsx 관례).
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

const byIdWithGuard = router.routesById as Record<
  string,
  { options: { beforeLoad?: (ctx: MinimalBeforeLoadContext) => void } }
>

/** 검증 대상 3라우트 — routesById 키(_shell pathless 재부모화) + 실제 경로 */
const PROJECT_CRUD_ROUTES = [
  { id: '/_shell/projects', pathname: '/projects' },
  { id: '/_shell/projects/new', pathname: '/projects/new' },
  { id: '/_shell/projects/$projectKey/settings/details', pathname: '/projects/ATLAS/settings/details' },
] as const

describe('프로젝트 CRUD 라우트 3종 beforeLoad — basicGuardChain 배선', () => {
  afterEach(() => {
    useAuthStore.setState({ accessToken: null, user: null })
  })

  it.each(PROJECT_CRUD_ROUTES)('$id — 미인증 → /login redirect throw (requireAuth)', ({ id, pathname }) => {
    useAuthStore.setState({ accessToken: null, user: null })

    const beforeLoad = byIdWithGuard[id]?.options.beforeLoad
    expect(beforeLoad).toBeTypeOf('function')

    let thrown: unknown
    try {
      beforeLoad?.(makeCtx(pathname))
    } catch (e) {
      thrown = e
    }

    expect(thrown).toBeDefined()
    expect(isRedirect(thrown)).toBe(true)
    expect((thrown as RedirectResponse).options.to).toBe('/login')
  })

  it.each(PROJECT_CRUD_ROUTES)(
    '$id — mustChangePassword:true → /settings/password redirect throw (requirePasswordChanged)',
    ({ id, pathname }) => {
      useAuthStore.setState({
        accessToken: 'valid-token',
        user: makeWhoami({ mustChangePassword: true }),
      })

      const beforeLoad = byIdWithGuard[id]?.options.beforeLoad
      let thrown: unknown
      try {
        beforeLoad?.(makeCtx(pathname))
      } catch (e) {
        thrown = e
      }

      expect(thrown).toBeDefined()
      expect(isRedirect(thrown)).toBe(true)
      expect((thrown as RedirectResponse).options.to).toBe('/settings/password')
    },
  )

  it.each(PROJECT_CRUD_ROUTES)(
    '$id — mfaEnrollmentRequired:true → /settings/mfa redirect throw (requireMfaEnrolled)',
    ({ id, pathname }) => {
      useAuthStore.setState({
        accessToken: 'valid-token',
        user: makeWhoami({ mfaEnrollmentRequired: true }),
      })

      const beforeLoad = byIdWithGuard[id]?.options.beforeLoad
      let thrown: unknown
      try {
        beforeLoad?.(makeCtx(pathname))
      } catch (e) {
        thrown = e
      }

      expect(thrown).toBeDefined()
      expect(isRedirect(thrown)).toBe(true)
      expect((thrown as RedirectResponse).options.to).toBe('/settings/mfa')
    },
  )

  // admin 가드가 아님을 증명 — isSystemAdmin:false 이면서 나머지 조건은 전부 통과인 세션이
  // throw 없이 통과해야 한다(requireSystemAdmin이 걸려 있었다면 /dashboard로 redirect됐을 것).
  it.each(PROJECT_CRUD_ROUTES)(
    '$id — 비-admin(isSystemAdmin:false)이지만 나머지 조건 통과 → throw 없음 (admin 가드 아님 증명)',
    ({ id, pathname }) => {
      useAuthStore.setState({
        accessToken: 'valid-token',
        user: makeWhoami({ isSystemAdmin: false }),
      })

      const beforeLoad = byIdWithGuard[id]?.options.beforeLoad
      // 라우트가 미등록이면 beforeLoad가 undefined라 `?.()` 호출이 조용히 no-op되어 아래
      // not.toThrow()가 공허하게(vacuously) 통과한다 — 이를 막기 위해 함수 존재를 먼저 강제한다.
      expect(beforeLoad).toBeTypeOf('function')
      expect(() => beforeLoad?.(makeCtx(pathname))).not.toThrow()
    },
  )
})

// ─────────────────────────────────────────────────────────────────────────────
// (c) 정적 세그먼트 우선 매칭 — /projects/new가 /projects/$projectKey류(설정/보드 등)에
//     흡수되지 않고 ProjectCreateRouteAdapter로 정확히 매칭되는지 실제 RouterProvider
//     렌더로 증명한다(issues.new vs issues.$key 선례, TanStack code-based가 자동 처리하나
//     등록 후 실측 확인).
// ─────────────────────────────────────────────────────────────────────────────

describe('정적 세그먼트 우선 매칭 — /projects/new', () => {
  afterEach(() => {
    useAuthStore.getState().clearSession()
  })

  it('/projects/new 로 이동하면 ProjectCreateRouteAdapter(새 프로젝트 폼)가 렌더된다', async () => {
    useAuthStore.getState().setSession({
      accessToken: 'test-access-token',
      user: makeWhoami(),
    })
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    const memoryHistory = createMemoryHistory({ initialEntries: ['/projects/new'] })
    const testRouter = createRouter({ routeTree, history: memoryHistory })

    render(
      <QueryClientProvider client={client}>
        <RouterProvider router={testRouter} />
      </QueryClientProvider>,
    )

    expect(await screen.findByRole('heading', { level: 1, name: '새 프로젝트' })).toBeInTheDocument()
  })
})
