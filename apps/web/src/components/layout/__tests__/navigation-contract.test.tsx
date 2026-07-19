// aria-label 네비게이션 계약 회귀 가드 — Header→Sidebar 이관(PR11) 전후로 라벨/게이팅/검색단일/h1금지가 깨지지 않는지 고정한다
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import { RouterProvider, createRouter, createMemoryHistory } from '@tanstack/react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { routeTree } from '@/router'
import { useAuthStore } from '@/auth/authStore'
import type { WhoamiResponse } from '@/api/schemas'
import { server } from '@/test/server'
import { projectListHandlers } from '@/mocks/project-list-handlers'
import { navLabels } from '@/i18n/nav-labels'

/**
 * 이 테스트는 characterization(회귀) 가드다 — 현재 `Header.tsx`가 `메인 메뉴`/`관리 메뉴` nav와
 * `검색` 버튼을 이미 소유하므로 작성 즉시 green이다. 목적은 이관(Header→Sidebar) 도중 이 라벨들이
 * 깨지면 Playwright e2e보다 먼저 이 vitest가 잡는 것 — 계약: `Header.tsx:91`(메인 메뉴),
 * `Header.tsx:107`(관리 메뉴), `Header.tsx:124`(검색), e2e `dashboard.spec.ts:440` ·
 * `calendar.spec.ts:88` · `notification-policies`/`audit-logs`/`webhook` spec(관리 nav 기본 펼침).
 *
 * Header/Sidebar를 직접 import해 렌더하지 않고, 실제 `routeTree`를 `RouterProvider`로 마운트해
 * `/dashboard`(인증 필요, `_shell` 하위) 경로를 렌더한다 — 크롬 조립 주체가 RootLayout(Header)이든
 * 추후 ShellLayout(Sidebar/TopBar)이든 무관하게 같은 어서션이 성립해야 한다(마이그레이션 무관성).
 */

/** 관리 nav 게이팅 검증용 whoami mock — isSystemAdmin만 가변, 나머지는 고정 필드 */
function createAuthUser(isSystemAdmin: boolean): WhoamiResponse {
  return {
    username: 'alice',
    email: 'alice@bts.local',
    authMethod: 'local',
    userId: 'u1',
    mustChangePassword: false,
    isSystemAdmin,
    mfaEnrollmentRequired: false,
  }
}

/**
 * 인증 세션을 세팅한 뒤 `/dashboard` 경로로 라우터 트리 전체를 렌더한다.
 *
 * @param isSystemAdmin 관리 nav 게이팅용 사용자 플래그
 */
function renderAuthenticatedShell(isSystemAdmin: boolean) {
  useAuthStore.getState().setSession({
    accessToken: 'test-access-token',
    user: createAuthUser(isSystemAdmin),
  })
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  const memoryHistory = createMemoryHistory({ initialEntries: ['/dashboard'] })
  const testRouter = createRouter({ routeTree, history: memoryHistory })
  return render(
    <QueryClientProvider client={client}>
      <RouterProvider router={testRouter} />
    </QueryClientProvider>,
  )
}

describe('navigation-contract (aria-label 4종 회귀 가드)', () => {
  beforeEach(() => {
    // Sidebar가 배선하는 ProjectTree(useProjects, FR-UX-06 PR12 Task 3)가 조회하는 엔드포인트 —
    // mocks/handlers.ts 전역 등록에 더해 명시 등록한다(use-projects.test.tsx·ProjectTree.test.tsx와
    // 동일 관례). 핸들러가 없으면 ProjectTree가 쿼리 에러로 null을 반환해 신규 '프로젝트' nav
    // 어서션이 실패한다.
    server.use(...projectListHandlers)
  })

  afterEach(() => {
    useAuthStore.getState().clearSession()
  })

  it('메인 메뉴 nav가 존재하고 대시보드·캘린더 링크를 포함한다 (dashboard.spec:440 · calendar.spec:88 계약)', async () => {
    renderAuthenticatedShell(false)

    const mainNav = await screen.findByRole('navigation', { name: '메인 메뉴' })
    expect(within(mainNav).getByRole('link', { name: '대시보드' })).toBeInTheDocument()
    expect(within(mainNav).getByRole('link', { name: '캘린더' })).toBeInTheDocument()
  })

  it('isSystemAdmin=true — 관리 메뉴 nav가 기본 펼침 상태로 존재하고 6링크를 전부 포함한다', async () => {
    renderAuthenticatedShell(true)

    const adminNav = await screen.findByRole('navigation', { name: '관리 메뉴' })
    expect(within(adminNav).getByRole('link', { name: '워크플로우 스킴' })).toBeInTheDocument()
    expect(within(adminNav).getByRole('link', { name: '감사 로그' })).toBeInTheDocument()
    expect(within(adminNav).getByRole('link', { name: '전역 권한' })).toBeInTheDocument()
    expect(within(adminNav).getByRole('link', { name: '알림 정책' })).toBeInTheDocument()
    expect(within(adminNav).getByRole('link', { name: 'Webhook' })).toBeInTheDocument()
    expect(within(adminNav).getByRole('link', { name: 'Slack 연결' })).toBeInTheDocument()
  })

  it('isSystemAdmin=false — 관리 메뉴 nav가 렌더되지 않는다', async () => {
    renderAuthenticatedShell(false)

    // 메인 메뉴가 그려질 때까지 기다려 라우터 마운트 완료를 보장한 뒤 관리 nav 부재를 확인한다
    await screen.findByRole('navigation', { name: '메인 메뉴' })
    expect(screen.queryByRole('navigation', { name: '관리 메뉴' })).not.toBeInTheDocument()
  })

  it('프로젝트 — aria-label="프로젝트" nav가 존재한다 (FR-UX-06 PR12 FR1 계약, ProjectTree)', async () => {
    renderAuthenticatedShell(false)

    // Testing Library `getByRole`의 `name` 매칭은 기본이 완전일치이므로, '프로젝트 뷰 전환'
    // (projectViewNav)과 substring 충돌 없이 이 nav만 조회된다(exact 옵션이 별도로 없는 이유는
    // navLabels.projectNav의 JSDoc 참고).
    const projectNav = await screen.findByRole('navigation', { name: navLabels.projectNav })
    expect(projectNav).toBeInTheDocument()
  })

  it('검색 — aria-label="검색" 버튼은 정확히 1개다 (strict 단일, 사이드바 내 검색 항목 추가 금지)', async () => {
    renderAuthenticatedShell(false)

    await screen.findByRole('navigation', { name: '메인 메뉴' })
    expect(screen.getAllByRole('button', { name: '검색' })).toHaveLength(1)
  })

  it('메인/관리 nav 영역에 <h1>이 없다 (e2e 34건 h1 level 1 의존 회귀 방지 — 현재 trivially true)', async () => {
    renderAuthenticatedShell(true)

    const mainNav = await screen.findByRole('navigation', { name: '메인 메뉴' })
    const adminNav = await screen.findByRole('navigation', { name: '관리 메뉴' })
    expect(within(mainNav).queryByRole('heading', { level: 1 })).not.toBeInTheDocument()
    expect(within(adminNav).queryByRole('heading', { level: 1 })).not.toBeInTheDocument()
  })
})
