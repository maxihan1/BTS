// /settings 인덱스 허브 페이지 테스트 — PageHeader 제목 '설정' 1개 + 개인 설정 카드 링크 10개 실재 하위 라우트 참조 + PAT 바깥 링크 (FR-UX-06 PR13 Task 4, PL-4 · FR-API-04 S20)
import { describe, it, expect, afterEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import {
  RouterProvider,
  createRouter,
  createRoute,
  createRootRoute,
  createMemoryHistory,
} from '@tanstack/react-router'
import { SettingsIndexPage } from './settings.index'

// ─────────────────────────────────────────────────────────────────────────────
// 렌더 헬퍼 — memory router(실 Link) 위에 SettingsIndexPage 마운트.
// Breadcrumb.test.tsx/PageHeader.test.tsx 관례 재사용. 카드가 가리키는 10개
// 하위 라우트를 전부 더미 컴포넌트로 등록해 Link href 해석이 실제 라우트에
// 근거하게 한다(존재하지 않는 경로로의 죽은 링크 방지).
// ─────────────────────────────────────────────────────────────────────────────

/** 개인 설정 허브가 가리켜야 하는 실재 하위 라우트 10개 — SETTINGS_HUB_LINKS와 동일 출처를 별도로 단언 */
const EXPECTED_SETTINGS_CHILD_PATHS = [
  '/settings/profile',
  '/settings/preferences',
  '/settings/keymap',
  '/settings/calendar',
  '/settings/slack',
  '/settings/mfa',
  '/settings/notifications',
  '/settings/sessions',
  '/settings/password',
  '/settings/account-links',
] as const

function renderSettingsIndex() {
  const rootRoute = createRootRoute()
  const settingsIndexRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/settings',
    component: SettingsIndexPage,
  })
  const childRoutes = EXPECTED_SETTINGS_CHILD_PATHS.map((path) =>
    createRoute({
      getParentRoute: () => rootRoute,
      path,
      component: () => null,
    }),
  )
  const router = createRouter({
    routeTree: rootRoute.addChildren([settingsIndexRoute, ...childRoutes]),
    history: createMemoryHistory({ initialEntries: ['/settings'] }),
    defaultPreload: false,
  })
  return render(<RouterProvider router={router} />)
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('SettingsIndexPage', () => {
  afterEach(() => {
    vi.unstubAllEnvs()
  })

  it("PageHeader title '설정'이 h1로 정확히 1개 렌더된다", async () => {
    renderSettingsIndex()

    const headings = await screen.findAllByRole('heading', { level: 1, name: '설정' })
    expect(headings).toHaveLength(1)
  })

  it('개인 설정 하위 라우트 10개로 이동하는 카드 링크가 정확히 10개 렌더된다 (Auth 계정 URL 없음)', async () => {
    vi.stubEnv('VITE_AUTH_ACCOUNT_URL', '')
    renderSettingsIndex()

    // 렌더 정착 앵커 — h1을 먼저 기다린 뒤 링크를 조회한다. 앵커 없이 즉시
    // querySelector만 검사하면 미정착 상태에서도 통과하는 공허한(vacuous) 참이 된다.
    await screen.findByRole('heading', { level: 1, name: '설정' })
    const links = screen.getAllByRole('link')
    expect(links).toHaveLength(EXPECTED_SETTINGS_CHILD_PATHS.length)
    // href 없는 <a> 는 link role 이 아니라 위 개수로는 「카드가 남았다」를 못 잡는다 — 글자로 직접 본다
    expect(screen.queryByText('Personal Access Token')).not.toBeInTheDocument()
  })

  it('VITE_AUTH_ACCOUNT_URL 이 있으면 Auth 계정 화면으로 가는 Personal Access Token 카드를 덧붙인다 (FR-API-04 S20)', async () => {
    vi.stubEnv('VITE_AUTH_ACCOUNT_URL', 'https://auth.example.test/account')
    renderSettingsIndex()

    await screen.findByRole('heading', { level: 1, name: '설정' })
    expect(screen.getAllByRole('link')).toHaveLength(EXPECTED_SETTINGS_CHILD_PATHS.length + 1)
    expect(screen.getByRole('link', { name: /Personal Access Token/ })).toHaveAttribute(
      'href',
      'https://auth.example.test/account',
    )
  })

  it.each(EXPECTED_SETTINGS_CHILD_PATHS)(
    '%s로 이동하는 카드 링크가 존재한다',
    async (path) => {
      renderSettingsIndex()

      await screen.findByRole('heading', { level: 1, name: '설정' })
      const links = screen.getAllByRole('link')
      const hrefs = links.map((link) => link.getAttribute('href'))
      expect(hrefs).toContain(path)
    },
  )
})
