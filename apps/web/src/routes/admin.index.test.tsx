// /admin 인덱스 허브 페이지 테스트 — PageHeader 제목 '관리' 1개 + 관리 카드 링크 7개 + SYSTEM_ADMIN 가드 배선 음성 테스트 (FR-UX-06 PR13 Task 5, PL-5)
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import {
  RouterProvider,
  createRouter,
  createRoute,
  createRootRoute,
  createMemoryHistory,
  isRedirect,
} from '@tanstack/react-router'
import { AdminIndexPage } from './admin.index'
import { router } from '@/router'
import { useAuthStore } from '@/auth/authStore'
import { makeWhoami } from '@/mocks/auth-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 렌더 헬퍼 — memory router(실 Link) 위에 AdminIndexPage 마운트.
// settings.index.test.tsx 관례 재사용. 카드가 가리키는 7개 하위 라우트를 전부
// 더미 컴포넌트로 등록해 Link href 해석이 실제 라우트에 근거하게 한다(존재하지
// 않는 경로로의 죽은 링크 방지).
// ─────────────────────────────────────────────────────────────────────────────

/** 관리 허브가 가리켜야 하는 실재 하위 라우트 7개 — ADMIN_HUB_LINKS와 동일 출처를 별도로 단언 */
const EXPECTED_ADMIN_CHILD_PATHS = [
  '/admin/workflow-schemes',
  '/admin/audit-logs',
  '/admin/global-permissions',
  '/admin/notification-policies',
  '/admin/webhooks',
  '/admin/slack',
  '/admin/users/new',
] as const

function renderAdminIndex() {
  const rootRoute = createRootRoute()
  const adminIndexRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/admin',
    component: AdminIndexPage,
  })
  const childRoutes = EXPECTED_ADMIN_CHILD_PATHS.map((path) =>
    createRoute({
      getParentRoute: () => rootRoute,
      path,
      component: () => null,
    }),
  )
  const testRouter = createRouter({
    routeTree: rootRoute.addChildren([adminIndexRoute, ...childRoutes]),
    history: createMemoryHistory({ initialEntries: ['/admin'] }),
    defaultPreload: false,
  })
  return render(<RouterProvider router={testRouter} />)
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 (a) — 페이지 렌더
// ─────────────────────────────────────────────────────────────────────────────

describe('AdminIndexPage', () => {
  it("PageHeader title '관리'가 h1로 정확히 1개 렌더된다", async () => {
    renderAdminIndex()

    const headings = await screen.findAllByRole('heading', { level: 1, name: '관리' })
    expect(headings).toHaveLength(1)
  })

  it('관리 하위 라우트 7개(워크플로우 스킴·감사 로그·전역 권한·알림 정책·Webhook·Slack 연결·사용자 추가)로 이동하는 카드 링크가 정확히 7개 렌더된다', async () => {
    renderAdminIndex()

    // 렌더 정착 앵커 — h1을 먼저 기다린 뒤 링크를 조회한다. 앵커 없이 즉시
    // querySelector만 검사하면 미정착 상태에서도 통과하는 공허한(vacuous) 참이 된다.
    await screen.findByRole('heading', { level: 1, name: '관리' })
    const links = screen.getAllByRole('link')
    expect(links).toHaveLength(EXPECTED_ADMIN_CHILD_PATHS.length)
  })

  it.each(EXPECTED_ADMIN_CHILD_PATHS)('%s로 이동하는 카드 링크가 존재한다', async (path) => {
    renderAdminIndex()

    await screen.findByRole('heading', { level: 1, name: '관리' })
    const links = screen.getAllByRole('link')
    const hrefs = links.map((link) => link.getAttribute('href'))
    expect(hrefs).toContain(path)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 (b) — SYSTEM_ADMIN 가드 배선 음성 테스트
//
// routeGuard.ts의 개별 가드 함수를 다시 조합해 테스트하는 게 아니라, router.ts에
// 실제 등록된 adminIndexRoute의 beforeLoad를 router.routesById로 조회해 직접
// 호출한다 — "가드 함수 자체는 옳지만 라우트에 배선을 빼먹었다" 케이스를 잡기
// 위한 배선 검증이다(vacuous 방지, routeGuard.test.tsx는 가드 함수 단위 테스트라
// 이 배선 갭을 잡지 못한다). adminIndexRoute가 없으면(RED) 조회 결과가
// undefined라 beforeLoad 호출 자체가 실행되지 않고 throw가 발생하지 않아
// 첫 테스트가 실패한다.
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

describe('adminIndexRoute beforeLoad — SYSTEM_ADMIN 가드 배선', () => {
  beforeEach(() => {
    useAuthStore.setState({ accessToken: null, user: null })
  })

  afterEach(() => {
    useAuthStore.setState({ accessToken: null, user: null })
  })

  it('비-admin(isSystemAdmin:false) 컨텍스트로 실행 → /dashboard로 redirect throw', () => {
    useAuthStore.setState({
      accessToken: 'valid-token',
      user: makeWhoami({ isSystemAdmin: false }),
    })

    const beforeLoad = byId['/_shell/admin']?.options.beforeLoad
    expect(beforeLoad).toBeTypeOf('function')

    let thrown: unknown
    try {
      beforeLoad?.(makeCtx('/admin'))
    } catch (e) {
      thrown = e
    }

    expect(thrown).toBeDefined()
    expect(isRedirect(thrown)).toBe(true)
    const r = thrown as RedirectResponse
    expect(r.options.to).toBe('/dashboard')
  })

  it('admin(isSystemAdmin:true) 컨텍스트로 실행 → throw 없음 (통과)', () => {
    useAuthStore.setState({
      accessToken: 'valid-token',
      user: makeWhoami({ isSystemAdmin: true }),
    })

    const beforeLoad = byId['/_shell/admin']?.options.beforeLoad
    expect(beforeLoad).toBeTypeOf('function')

    expect(() => beforeLoad?.(makeCtx('/admin'))).not.toThrow()
  })
})
