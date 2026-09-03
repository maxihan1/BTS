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

  /**
   * 보드 스코프 전파 (FR-BD-04 PR ⑥ · 편차 X7 부분 해소).
   *
   * `search` 가 없는 링크는 지금 모양 그대로 두고, 있는 링크만 쿼리를 싣는다.
   * 이 nav 는 board·backlog 말고도 여러 화면이 쓰므로 **옵셔널**이어야 한다.
   */
  it('링크에 search 가 있으면 href 에 쿼리로 실린다', async () => {
    const boardId = 'b0a1c2d3-e4f5-4678-9abc-def012345678'
    renderProjectNavTabs([
      { to: '/projects/$projectKey/backlog', label: '백로그', search: { board: boardId } },
      { to: '/projects/$projectKey/timeline', label: '타임라인' },
    ])

    const nav = await screen.findByRole('navigation', { name: '프로젝트 뷰 전환' })
    const [withSearch, withoutSearch] = within(nav).getAllByRole('link')
    if (withSearch === undefined || withoutSearch === undefined) {
      throw new Error('링크 2개가 렌더되어야 한다')
    }

    expect(withSearch).toHaveAttribute('href', `/projects/ATLAS/backlog?board=${boardId}`)
    // search 가 없는 링크는 쿼리가 붙지 않는다 — 기존 소비처 회귀 0
    expect(withoutSearch).toHaveAttribute('href', '/projects/ATLAS/timeline')
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
