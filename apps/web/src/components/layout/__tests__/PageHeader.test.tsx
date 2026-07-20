// PageHeader 컴포넌트 테스트 — title/description/breadcrumbs/actions 렌더 가드, h1 페이지당 1개 계약 (FR-UX-06 PR13 Task 3, PL-2)
import type { ReactNode } from 'react'
import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import {
  RouterProvider,
  createRouter,
  createRoute,
  createRootRoute,
  createMemoryHistory,
} from '@tanstack/react-router'
import { navLabels } from '@/i18n/nav-labels'
import { PageHeader } from '../PageHeader'
import type { BreadcrumbItem } from '../Breadcrumb'

// ─────────────────────────────────────────────────────────────────────────────
// 렌더 헬퍼 — breadcrumbs가 있으면 Breadcrumb이 TanStack Router Link를 사용하므로
// 항상 memory router로 감싼다(Breadcrumb.test.tsx 관례 재사용).
// ─────────────────────────────────────────────────────────────────────────────

interface RenderPageHeaderProps {
  readonly title: string
  readonly description?: string
  readonly breadcrumbs?: readonly BreadcrumbItem[]
  readonly actions?: ReactNode
}

function renderPageHeader(props: RenderPageHeaderProps) {
  const rootRoute = createRootRoute()
  const homeRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/',
    component: () => <PageHeader {...props} />,
  })
  const settingsRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/settings',
    component: () => null,
  })
  const router = createRouter({
    routeTree: rootRoute.addChildren([homeRoute, settingsRoute]),
    history: createMemoryHistory({ initialEntries: ['/'] }),
    defaultPreload: false,
  })
  return render(<RouterProvider router={router} />)
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('PageHeader', () => {
  it('title prop → h1이 정확히 1개, 텍스트가 일치하고 관용구 클래스를 가진다', async () => {
    renderPageHeader({ title: '설정' })

    const heading = await screen.findByRole('heading', { level: 1 })
    expect(heading).toHaveTextContent('설정')
    expect(heading).toHaveClass('text-xl', 'font-semibold')
  })

  it('description prop이 있으면 <p>가 렌더된다', async () => {
    renderPageHeader({ title: '설정', description: '개인 설정을 관리합니다' })

    const description = await screen.findByText('개인 설정을 관리합니다')
    expect(description.tagName).toBe('P')
    expect(description).toHaveClass('text-muted-foreground')
  })

  it('description prop이 없으면 <p>가 렌더되지 않는다', async () => {
    const { container } = renderPageHeader({ title: '설정' })

    // 렌더가 비동기(router match)로 정착될 때까지 h1을 앵커로 기다린 뒤 부재를 확인한다.
    // 앵커 없이 즉시 querySelector만 검사하면 미정착 상태에서도 통과하는 공허한(vacuous) 참이 된다.
    await screen.findByRole('heading', { level: 1 })
    expect(container.querySelector('p')).toBeNull()
  })

  it('breadcrumbs prop이 있으면 Breadcrumb 탐색경로 nav가 렌더된다', async () => {
    renderPageHeader({
      title: '상세',
      breadcrumbs: [{ label: '설정', to: '/settings' }, { label: '상세' }],
    })

    const nav = await screen.findByRole('navigation', { name: navLabels.breadcrumb })
    expect(nav).toBeInTheDocument()
  })

  it('breadcrumbs prop이 없으면 탐색경로 nav가 렌더되지 않는다', async () => {
    renderPageHeader({ title: '설정' })

    await screen.findByRole('heading', { level: 1 })
    expect(
      screen.queryByRole('navigation', { name: navLabels.breadcrumb }),
    ).not.toBeInTheDocument()
  })

  it('actions prop이 있으면 우측 슬롯에 렌더된다', async () => {
    renderPageHeader({ title: '설정', actions: <button type="button">새로 만들기</button> })

    const button = await screen.findByRole('button', { name: '새로 만들기' })
    expect(button).toBeInTheDocument()
  })

  it('actions prop이 없으면 아무 버튼도 렌더되지 않는다', async () => {
    renderPageHeader({ title: '설정' })

    await screen.findByRole('heading', { level: 1 })
    expect(screen.queryByRole('button')).not.toBeInTheDocument()
  })
})
