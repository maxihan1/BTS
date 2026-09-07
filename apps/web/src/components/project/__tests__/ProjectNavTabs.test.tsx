// ProjectNavTabs 계약 테스트 — nav+Link · 정본 9탭 통합 · Radix Tabs 미사용 (Jira 패리티 J5)
import { describe, it, expect } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import {
  RouterProvider,
  createRouter,
  createRoute,
  createRootRoute,
  createMemoryHistory,
} from '@tanstack/react-router'
import { ProjectNavTabs } from '@/components/project/ProjectNavTabs'
import {
  PROJECT_VIEW_TABS,
  resolveActiveTabIndex,
  resolveTabHref,
} from '@/components/project/project-view-tabs'

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 헬퍼 — 격리된 최소 route tree(메모리 히스토리)에 탭바를 마운트한다.
//
// 탭 목적지 라우트를 전부 등록하지는 않는다. `Link` 의 href 해석은 `to` 문자열 + params 로
// 이뤄지고, 활성 판정은 라우터의 현재 location 과 비교하므로 **지금 있는 위치**만 실재하면 된다.
// ─────────────────────────────────────────────────────────────────────────────

function renderTabs(
  options: {
    readonly pathname?: string
    readonly boardScope?: {
      readonly board?: Readonly<Record<string, string>>
      readonly backlog?: Readonly<Record<string, string>>
    }
  } = {},
) {
  const pathname = options.pathname ?? '/projects/ATLAS'
  const rootRoute = createRootRoute()
  const catchAllRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '$',
    component: () => (
      <ProjectNavTabs
        projectKey="ATLAS"
        pathname={pathname}
        {...(options.boardScope === undefined ? {} : { boardScope: options.boardScope })}
      />
    ),
  })
  const testRouter = createRouter({
    routeTree: rootRoute.addChildren([catchAllRoute]),
    history: createMemoryHistory({ initialEntries: [pathname] }),
    defaultPreload: false,
  })

  return render(<RouterProvider router={testRouter} />)
}

/**
 * 렌더한 뒤 nav 안의 링크를 라벨→href 로 뽑는다.
 *
 * 🛑 렌더를 헬퍼 안에 둔다 — 밖에 두면 호출을 빠뜨렸을 때 「nav 를 못 찾는다」로 죽어
 * 무엇이 틀렸는지가 안 보인다(실제로 이 파일에서 5건이 그렇게 죽었다).
 */
async function renderAndReadLinks(
  options: Parameters<typeof renderTabs>[0] = {},
): Promise<ReadonlyArray<readonly [string, string]>> {
  renderTabs(options)
  const nav = await screen.findByRole('navigation', { name: '프로젝트 뷰 전환' })
  return within(nav)
    .getAllByRole('link')
    .map((link) => [link.textContent ?? '', link.getAttribute('href') ?? ''] as const)
}

describe('ProjectNavTabs', () => {
  it('aria-label="프로젝트 뷰 전환" nav를 렌더한다', async () => {
    renderTabs()

    const nav = await screen.findByRole('navigation', { name: '프로젝트 뷰 전환' })
    expect(nav).toBeInTheDocument()
  })

  it('role="tablist"가 존재하지 않는다 — Radix Tabs가 아닌 nav+Link만 사용한다', async () => {
    renderTabs()

    await screen.findByRole('navigation', { name: '프로젝트 뷰 전환' })
    expect(screen.queryByRole('tablist')).not.toBeInTheDocument()
    expect(screen.queryByRole('tab')).not.toBeInTheDocument()
  })

  it('정본 9탭을 순서대로 렌더한다 — 화면마다 다른 집합을 넘기지 않는다', async () => {
    // 옛 계약(`links` prop 으로 board 2 · backlog 5)을 대체하는 자리다.
    const links = await renderAndReadLinks()
    expect(links.map(([label]) => label)).toEqual(PROJECT_VIEW_TABS.map((tab) => tab.label))
  })

  it('프로젝트 스코프 탭의 href 에 projectKey 가 반영된다', async () => {
    const links = new Map(await renderAndReadLinks())

    expect(links.get('요약')).toBe('/projects/ATLAS')
    expect(links.get('타임라인')).toBe('/projects/ATLAS/timeline')
    expect(links.get('보드')).toBe('/projects/ATLAS/board')
    expect(links.get('백로그')).toBe('/projects/ATLAS/backlog')
    expect(links.get('컴포넌트')).toBe('/projects/ATLAS/settings/components')
    expect(links.get('버전')).toBe('/projects/ATLAS/settings/versions')
  })

  it('캘린더·대시보드·이슈도 프로젝트 스코프 경로로 간다 (편차 X9 폐기 · J5-12)', async () => {
    // 🛑 이 셋이 전역 경로(`/calendar`·`/dashboards`·`/issues`)로 되돌아가면 누르는 순간
    //    `ProjectViewChrome` 의 마운트 조건이 깨져 **헤더와 탭바가 통째로 사라진다.**
    //    「링크가 존재한다」만 단언하면 그 회귀가 초록으로 통과한다 — 목적지를 못박는다.
    const links = new Map(await renderAndReadLinks())

    expect(links.get('캘린더')).toBe('/projects/ATLAS/calendar')
    expect(links.get('대시보드')).toBe('/projects/ATLAS/dashboards')
    expect(links.get('이슈')).toBe('/projects/ATLAS/issues')
  })

  it('9탭 어느 것도 프로젝트 밖으로 나가지 않는다 (전수)', async () => {
    // 위 두 단언은 이름을 아는 탭만 본다. 새 탭이 전역 경로로 추가되면 안 잡힌다.
    const links = await renderAndReadLinks()
    expect(links.length).toBe(9)

    const escaping = links.filter(([, href]) => !href.startsWith('/projects/ATLAS'))
    expect(escaping).toEqual([])
  })

  it('보드·백로그 탭에 각자의 보드 스코프가 실린다 (편차 X7 승계)', async () => {
    const scrumId = 'b0a1c2d3-e4f5-4678-9abc-def012345678'
    const links = new Map(
      await renderAndReadLinks({
        boardScope: { board: { board: scrumId }, backlog: { board: scrumId } },
      }),
    )

    expect(links.get('보드')).toBe(`/projects/ATLAS/board?board=${scrumId}`)
    expect(links.get('백로그')).toBe(`/projects/ATLAS/backlog?board=${scrumId}`)
    // 다른 탭에는 안 붙는다 — 스코프를 아무 데나 실으면 화면마다 다른 뜻이 된다.
    expect(links.get('타임라인')).toBe('/projects/ATLAS/timeline')
    expect(links.get('컴포넌트')).toBe('/projects/ATLAS/settings/components')
  })

  it('칸반이면 보드 탭만 스코프를 받고 백로그 탭은 안 받는다', async () => {
    // 두 탭의 규칙이 갈리는 자리다 — 칸반 id 로 스코프된 백로그는 스프린트가 사라진다.
    const kanbanId = 'c1b2a3d4-e5f6-4789-abcd-ef0123456789'
    const links = new Map(await renderAndReadLinks({ boardScope: { board: { board: kanbanId } } }))

    expect(links.get('보드')).toBe(`/projects/ATLAS/board?board=${kanbanId}`)
    expect(links.get('백로그')).toBe('/projects/ATLAS/backlog')
  })

  it('보드 스코프가 없으면 두 탭 다 쿼리가 붙지 않는다', async () => {
    const links = new Map(await renderAndReadLinks())

    expect(links.get('보드')).toBe('/projects/ATLAS/board')
    expect(links.get('백로그')).toBe('/projects/ATLAS/backlog')
  })

  it('요약 화면에서는 요약 탭만 aria-current 다', async () => {
    renderTabs({ pathname: '/projects/ATLAS' })

    const nav = await screen.findByRole('navigation', { name: '프로젝트 뷰 전환' })
    const current = within(nav)
      .getAllByRole('link')
      .filter((link) => link.getAttribute('aria-current') === 'page')
      .map((link) => link.textContent)

    expect(current).toEqual(['요약'])
  })

  it('하위 화면에서는 요약 탭이 aria-current 가 아니다 (exact 봉인)', async () => {
    // 🛑 `/projects/ATLAS` 는 모든 하위 경로의 접두사다. exact 가 빠지면 여기서 2개가 된다.
    renderTabs({ pathname: '/projects/ATLAS/board' })

    const nav = await screen.findByRole('navigation', { name: '프로젝트 뷰 전환' })
    const current = within(nav)
      .getAllByRole('link')
      .filter((link) => link.getAttribute('aria-current') === 'page')
      .map((link) => link.textContent)

    expect(current).toEqual(['보드'])
  })

  it('두 층 대조 — 라우터의 aria-current 와 순수 함수의 결론이 목적지 전수에서 같다', async () => {
    // ① 시각·ARIA 는 `Link`(라우터)가 판정하고 ② 오버플로 핀 고정은 `resolveActiveTabIndex` 가
    // 판정한다. 어긋나면 「강조된 탭」과 「접힘에서 지켜 주는 탭」이 달라진다 — 좁은 화면에서
    // 지금 보는 화면이 팝오버 안으로 사라지는 증상이다.
    //
    // 🛑 href 생성 일치(`project-view-tabs.test.ts`)로는 이걸 못 잡는다. 거기서 보는 것은
    //    「어디로 가는가」이고 여기서 보는 것은 「어디에 있는가」다.
    const scoped = PROJECT_VIEW_TABS.filter((tab) => tab.usesProjectParam)
    expect(scoped.length).toBeGreaterThan(0)

    for (const tab of scoped) {
      const pathname = resolveTabHref(tab, 'ATLAS')
      const { unmount } = renderTabs({ pathname })

      const nav = await screen.findByRole('navigation', { name: '프로젝트 뷰 전환' })
      const routerSaysActive = within(nav)
        .getAllByRole('link')
        .filter((link) => link.getAttribute('aria-current') === 'page')
        .map((link) => link.textContent)

      const index = resolveActiveTabIndex(pathname, 'ATLAS')
      const pureFnSaysActive = index === -1 ? [] : [PROJECT_VIEW_TABS[index]?.label]

      expect(routerSaysActive, `${pathname} 에서 두 층의 판정이 다르다`).toEqual(pureFnSaysActive)
      unmount()
    }
  })

  it('레이아웃이 없는 환경(jsdom)에서는 접히지 않고 「더 보기」도 남지 않는다', async () => {
    // `clientWidth` 가 0 이면 전량 가시다(`use-tab-overflow` 규칙 ①). 측정 시도가 끝나면
    // 트리거는 사라진다 — 남아 있으면 접힌 탭 0 개짜리 빈 팝오버가 화면에 생긴다.
    renderTabs()

    const nav = await screen.findByRole('navigation', { name: '프로젝트 뷰 전환' })
    expect(within(nav).getAllByRole('link')).toHaveLength(PROJECT_VIEW_TABS.length)
    expect(within(nav).queryByRole('button', { name: '더 보기' })).not.toBeInTheDocument()
  })
})
