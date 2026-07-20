// ProjectNavTabs 계약 테스트 — nav+Link 렌더, props.links 순서 보존, Radix Tabs 미사용 (FR-UX-06 PR12 Task 4)
import { describe, it, expect } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import {
  RouterProvider,
  createRouter,
  createRoute,
  createRootRoute,
  createMemoryHistory,
} from '@tanstack/react-router'
import { ProjectNavTabs, type ProjectNavTabLink } from '@/components/project/ProjectNavTabs'

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 헬퍼 — 격리된 최소 route tree(메모리 히스토리)에 ProjectNavTabs를 마운트한다.
// board/backlog 실 라우트를 등록하지 않아도 Link href 해석에는 영향 없다(§inbox.test.tsx 관례).
// ─────────────────────────────────────────────────────────────────────────────

const DEFAULT_LINKS: ProjectNavTabLink[] = [
  { to: '/projects/$projectKey/backlog', label: '백로그' },
  { to: '/projects/$projectKey/timeline', label: '타임라인' },
]

function renderProjectNavTabs(links: ProjectNavTabLink[] = DEFAULT_LINKS) {
  const rootRoute = createRootRoute()
  const testRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/test',
    component: () => <ProjectNavTabs projectKey="ATLAS" links={links} />,
  })
  const memoryHistory = createMemoryHistory({ initialEntries: ['/test'] })
  const testRouter = createRouter({
    routeTree: rootRoute.addChildren([testRoute]),
    history: memoryHistory,
    defaultPreload: false,
  })

  return render(<RouterProvider router={testRouter} />)
}

describe('ProjectNavTabs', () => {
  it('aria-label="프로젝트 뷰 전환" nav를 렌더한다', async () => {
    renderProjectNavTabs()

    const nav = await screen.findByRole('navigation', { name: '프로젝트 뷰 전환' })
    expect(nav).toBeInTheDocument()
  })

  it('props.links 순서대로 Link를 렌더하고 projectKey가 href에 반영된다', async () => {
    renderProjectNavTabs()

    const nav = await screen.findByRole('navigation', { name: '프로젝트 뷰 전환' })
    const links = within(nav).getAllByRole('link')
    expect(links).toHaveLength(2)

    const [firstLink, secondLink] = links
    if (firstLink === undefined || secondLink === undefined) {
      throw new Error('링크 2개가 렌더되어야 한다')
    }
    expect(firstLink).toHaveTextContent('백로그')
    expect(firstLink).toHaveAttribute('href', '/projects/ATLAS/backlog')
    expect(secondLink).toHaveTextContent('타임라인')
    expect(secondLink).toHaveAttribute('href', '/projects/ATLAS/timeline')
  })

  it('links 순서를 바꾸면 렌더 순서도 그대로 따라간다', async () => {
    renderProjectNavTabs([
      { to: '/projects/$projectKey/timeline', label: '타임라인' },
      { to: '/projects/$projectKey/backlog', label: '백로그' },
    ])

    const nav = await screen.findByRole('navigation', { name: '프로젝트 뷰 전환' })
    const links = within(nav).getAllByRole('link')
    const [firstLink, secondLink] = links
    if (firstLink === undefined || secondLink === undefined) {
      throw new Error('링크 2개가 렌더되어야 한다')
    }
    expect(firstLink).toHaveTextContent('타임라인')
    expect(secondLink).toHaveTextContent('백로그')
  })

  it('role="tablist"가 존재하지 않는다 — Radix Tabs가 아닌 nav+Link만 사용한다', async () => {
    renderProjectNavTabs()

    await screen.findByRole('navigation', { name: '프로젝트 뷰 전환' })
    expect(screen.queryByRole('tablist')).not.toBeInTheDocument()
    expect(screen.queryByRole('tab')).not.toBeInTheDocument()
  })

  it('빈 links 배열이면 nav만 렌더되고 링크는 없다', async () => {
    renderProjectNavTabs([])

    const nav = await screen.findByRole('navigation', { name: '프로젝트 뷰 전환' })
    expect(within(nav).queryAllByRole('link')).toHaveLength(0)
  })
})
