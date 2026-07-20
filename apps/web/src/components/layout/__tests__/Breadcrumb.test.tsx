// Breadcrumb 컴포넌트 테스트 — nav[aria-label='탐색 경로'] 1개, 마지막 항목 비링크 aria-current, 빈 배열 미렌더 (FR-UX-06 PR13 Task 1, PL-3·PL-9)
import { describe, it, expect } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import {
  RouterProvider,
  createRouter,
  createRoute,
  createRootRoute,
  createMemoryHistory,
} from '@tanstack/react-router'
import { navLabels } from '@/i18n/nav-labels'
import { Breadcrumb, type BreadcrumbItem } from '../Breadcrumb'

// ─────────────────────────────────────────────────────────────────────────────
// 렌더 헬퍼 — memory router(실 Link) 위에 Breadcrumb 마운트. ProjectTree.test.tsx 관례 재사용.
// ─────────────────────────────────────────────────────────────────────────────

function buildRouter(items: readonly BreadcrumbItem[]) {
  const rootRoute = createRootRoute()
  const homeRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/',
    component: () => (
      <div data-testid="breadcrumb-host">
        <Breadcrumb items={items} />
      </div>
    ),
  })
  const settingsRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/settings',
    component: () => null,
  })
  const workflowSchemesRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/settings/workflow-schemes',
    component: () => null,
  })
  return createRouter({
    routeTree: rootRoute.addChildren([homeRoute, settingsRoute, workflowSchemesRoute]),
    history: createMemoryHistory({ initialEntries: ['/'] }),
    defaultPreload: false,
  })
}

function renderBreadcrumb(items: readonly BreadcrumbItem[]) {
  return render(<RouterProvider router={buildRouter(items)} />)
}

async function findBreadcrumbNav() {
  return screen.findByRole('navigation', { name: navLabels.breadcrumb })
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('Breadcrumb', () => {
  it("items 3개 → nav[aria-label='탐색 경로']가 정확히 1개다", async () => {
    renderBreadcrumb([
      { label: '설정', to: '/settings' },
      { label: '워크플로우 스킴', to: '/settings/workflow-schemes' },
      { label: '상세' },
    ])

    const navs = await screen.findAllByRole('navigation', { name: navLabels.breadcrumb })
    expect(navs).toHaveLength(1)
  })

  it('items 3개 → <ol>로 렌더된다', async () => {
    renderBreadcrumb([
      { label: '설정', to: '/settings' },
      { label: '워크플로우 스킴', to: '/settings/workflow-schemes' },
      { label: '상세' },
    ])

    const nav = await findBreadcrumbNav()
    expect(nav.querySelector('ol')).not.toBeNull()
  })

  it('items 3개 → 마지막 항목은 aria-current="page"이고 링크가 아니다', async () => {
    renderBreadcrumb([
      { label: '설정', to: '/settings' },
      { label: '워크플로우 스킴', to: '/settings/workflow-schemes' },
      { label: '상세' },
    ])

    const nav = await findBreadcrumbNav()
    const current = within(nav).getByText('상세')
    expect(current.tagName).not.toBe('A')
    expect(current).toHaveAttribute('aria-current', 'page')
    expect(within(nav).queryByRole('link', { name: '상세' })).not.toBeInTheDocument()
  })

  it('items 3개 → 앞 2개는 <a href> 링크다', async () => {
    renderBreadcrumb([
      { label: '설정', to: '/settings' },
      { label: '워크플로우 스킴', to: '/settings/workflow-schemes' },
      { label: '상세' },
    ])

    const nav = await findBreadcrumbNav()
    const settingsLink = within(nav).getByRole('link', { name: '설정' })
    expect(settingsLink.tagName).toBe('A')
    expect(settingsLink).toHaveAttribute('href', '/settings')

    const workflowLink = within(nav).getByRole('link', { name: '워크플로우 스킴' })
    expect(workflowLink.tagName).toBe('A')
    expect(workflowLink).toHaveAttribute('href', '/settings/workflow-schemes')
  })

  it('items 1개 → 링크가 0개다(현재 페이지만 표시)', async () => {
    renderBreadcrumb([{ label: '설정' }])

    const nav = await findBreadcrumbNav()
    expect(within(nav).queryAllByRole('link')).toHaveLength(0)
    expect(within(nav).getByText('설정')).toHaveAttribute('aria-current', 'page')
  })

  it('items 빈 배열 → nav를 렌더하지 않는다(null 반환)', async () => {
    renderBreadcrumb([])

    await screen.findByTestId('breadcrumb-host')
    expect(screen.queryByRole('navigation', { name: navLabels.breadcrumb })).not.toBeInTheDocument()
  })
})
