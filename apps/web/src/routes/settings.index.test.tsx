// /settings 인덱스 허브 페이지 테스트 — PageHeader 제목 '설정' 1개 + 개인 설정 카드 링크 11개 실재 하위 라우트 참조 (FR-UX-06 PR13 Task 4, PL-4)
import { describe, it, expect } from 'vitest'
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
// Breadcrumb.test.tsx/PageHeader.test.tsx 관례 재사용. 카드가 가리키는 11개
// 하위 라우트를 전부 더미 컴포넌트로 등록해 Link href 해석이 실제 라우트에
// 근거하게 한다(존재하지 않는 경로로의 죽은 링크 방지).
// ─────────────────────────────────────────────────────────────────────────────

/** 개인 설정 허브가 가리켜야 하는 실재 하위 라우트 11개 — SETTINGS_HUB_LINKS와 동일 출처를 별도로 단언 */
const EXPECTED_SETTINGS_CHILD_PATHS = [
  '/settings/profile',
  '/settings/preferences',
  '/settings/keymap',
  '/settings/calendar',
  '/settings/slack',
  '/settings/mfa',
  '/settings/pats',
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
  it("PageHeader title '설정'이 h1로 정확히 1개 렌더된다", async () => {
    renderSettingsIndex()

    const headings = await screen.findAllByRole('heading', { level: 1, name: '설정' })
    expect(headings).toHaveLength(1)
  })

  it('개인 설정 하위 라우트 11개로 이동하는 카드 링크가 정확히 11개 렌더된다', async () => {
    renderSettingsIndex()

    // 렌더 정착 앵커 — h1을 먼저 기다린 뒤 링크를 조회한다. 앵커 없이 즉시
    // querySelector만 검사하면 미정착 상태에서도 통과하는 공허한(vacuous) 참이 된다.
    await screen.findByRole('heading', { level: 1, name: '설정' })
    const links = screen.getAllByRole('link')
    expect(links).toHaveLength(EXPECTED_SETTINGS_CHILD_PATHS.length)
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
