// ProjectReportsNav 계약 테스트 — nav+Link · 자기 자신 포함 4개 · aria-current (Jira 패리티 JR-2)
import type { ReactNode } from 'react'
import { describe, it, expect, vi } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import {
  RouterProvider,
  createRouter,
  createRoute,
  createRootRoute,
  createMemoryHistory,
} from '@tanstack/react-router'
import { ProjectReportsNav } from '@/components/project/ProjectReportsNav'
import { PROJECT_REPORT_LINKS } from '@/components/project/project-report-links'
import { navLabels } from '@/i18n/nav-labels'
import { VelocityReportPage } from '@/routes/projects.$projectKey.reports.velocity'
import { CfdReportPage } from '@/routes/projects.$projectKey.reports.cfd'
import { CycleTimeReportPage } from '@/routes/projects.$projectKey.reports.cycle-time'
import { ProjectWorklogReportPage } from '@/routes/projects.$projectKey.reports.worklog'

// ─────────────────────────────────────────────────────────────────────────────
// 무거운 리포트 본문 4종을 격리한다.
//
// 이 파일이 리포트 «화면» 4개까지 보는 이유 — 서브내비가 어느 화면에 걸렸는지는 그 화면들의
// 콜로케이트 단위 테스트에서는 볼 수 없다. 그 4개 파일이 `@tanstack/react-router` 를 통째로
// `vi.mock` 하고 있어 `Link` 가 스텁이기 때문이다(mock 이 삼킨 것은 유닛에 안 보인다).
// 그래서 「4화면 각각이 서브내비를 낸다」는 계약을 **라우터가 실재하는 여기**에 둔다.
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('@/components/velocity/VelocityReport', () => ({
  VelocityReport: () => <div data-testid="velocity-report" />,
}))
vi.mock('@/components/cfd/CfdReport', () => ({
  CfdReport: () => <div data-testid="cfd-report" />,
}))
vi.mock('@/components/cycle-time/CycleTimeReport', () => ({
  CycleTimeReport: () => <div data-testid="cycle-time-report" />,
}))
vi.mock('@/components/worklog/WorklogAggregateReport', () => ({
  WorklogAggregateReport: () => <div data-testid="worklog-report" />,
}))

/** 🔒 이 컴포넌트의 nav 이름 — `ProjectReportsNav.tsx` 의 `REPORTS_NAV_LABEL` 과 같아야 한다 */
const REPORTS_NAV_NAME = '리포트 전환'

/** 테스트에 쓰는 프로젝트 키 */
const PROJECT_KEY = 'ATLAS'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — 격리된 최소 route tree(메모리 히스토리)에 주어진 노드를 마운트한다.
//
// 목적지 라우트를 전부 등록하지는 않는다. `Link` 의 href 해석은 `to` + params 로 이뤄지고
// 활성 판정은 현재 location 과의 비교라, **지금 있는 위치**만 실재하면 된다
// (`ProjectNavTabs.test.tsx` 선례).
// ─────────────────────────────────────────────────────────────────────────────

function renderAt(pathname: string, node: ReactNode) {
  const rootRoute = createRootRoute()
  const catchAllRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '$',
    component: () => <>{node}</>,
  })
  const testRouter = createRouter({
    routeTree: rootRoute.addChildren([catchAllRoute]),
    history: createMemoryHistory({ initialEntries: [pathname] }),
    defaultPreload: false,
  })

  return render(<RouterProvider router={testRouter} />)
}

/** 서브내비를 특정 경로에서 렌더하고 nav 안의 링크를 라벨→href 로 뽑는다 */
async function renderNavAndReadLinks(
  pathname: string,
): Promise<ReadonlyArray<readonly [string, string]>> {
  renderAt(pathname, <ProjectReportsNav projectKey={PROJECT_KEY} />)
  const nav = await screen.findByRole('navigation', { name: REPORTS_NAV_NAME })
  return within(nav)
    .getAllByRole('link')
    .map((link) => [link.textContent ?? '', link.getAttribute('href') ?? ''] as const)
}

/** 지금 화면에서 `aria-current="page"` 인 서브내비 링크의 텍스트 목록 */
async function readCurrentLabels(): Promise<readonly string[]> {
  const nav = await screen.findByRole('navigation', { name: REPORTS_NAV_NAME })
  return within(nav)
    .getAllByRole('link')
    .filter((link) => link.getAttribute('aria-current') === 'page')
    .map((link) => link.textContent ?? '')
}

/** `to` 의 `$projectKey` 를 치환한 실제 경로 */
function hrefOf(to: string): string {
  return to.replace('$projectKey', PROJECT_KEY)
}

describe('ProjectReportsNav', () => {
  it('리포트 4종을 정본 순서 그대로 전부 렌더한다 — 자기 자신도 뺀 적이 없다', async () => {
    const links = await renderNavAndReadLinks(hrefOf('/projects/$projectKey/reports/velocity'))

    expect(links.map(([label]) => label)).toEqual(PROJECT_REPORT_LINKS.map((link) => link.label))
  })

  it('각 링크의 href 에 projectKey 가 반영된다 (전수)', async () => {
    const links = await renderNavAndReadLinks(hrefOf('/projects/$projectKey/reports/velocity'))

    expect(links.map(([, href]) => href)).toEqual(PROJECT_REPORT_LINKS.map((l) => hrefOf(l.to)))
  })

  it('리포트 4경로 전수에서 지금 보는 것 하나만 aria-current="page" 다', async () => {
    // 🛑 「현재 항목에 붙는다」만 보면 **전부에 붙는** 회귀가 통과한다. 개수까지 못박는다.
    expect(PROJECT_REPORT_LINKS.length).toBe(4)

    for (const link of PROJECT_REPORT_LINKS) {
      const { unmount } = renderAt(
        hrefOf(link.to),
        <ProjectReportsNav projectKey={PROJECT_KEY} />,
      )

      expect(await readCurrentLabels()).toEqual([link.label])

      unmount()
    }
  })

  it('착지 화면(/reports)에서는 아무 항목도 aria-current 가 아니다', async () => {
    // 착지는 「아직 안 골랐다」는 상태다 — 임의의 한 리포트가 강조돼 있으면 거짓말이 된다.
    renderAt(`/projects/${PROJECT_KEY}/reports`, <ProjectReportsNav projectKey={PROJECT_KEY} />)

    expect(await readCurrentLabels()).toEqual([])
  })

  it('리포트 하위 경로에서도 그 리포트가 계속 aria-current 다 (exact:false 봉인)', async () => {
    // 🛑 `exact: true` 로 바꾸면 여기서 **0개**가 된다 — 자기 하위로 들어갔는데 서브내비의
    //    위치 표시가 꺼지는 회귀다. 뮤테이션 실측으로 red 를 확인한 단언이다.
    //    (착지 화면 단언은 exact 어느 쪽에서도 통과하므로 이 회귀를 못 잡는다.)
    renderAt(
      `${hrefOf('/projects/$projectKey/reports/velocity')}/2026-Q3`,
      <ProjectReportsNav projectKey={PROJECT_KEY} />,
    )

    expect(await readCurrentLabels()).toEqual(['벨로시티'])
  })

  it('role="tablist"/"tab" 이 존재하지 않는다 — Radix Tabs 가 아닌 nav+Link 다', async () => {
    // 🔴 Tabs 로 바꾸면 `role="navigation"` 이 소멸해 이 저장소의 nav 계약이 즉사한다.
    renderAt(
      hrefOf('/projects/$projectKey/reports/cfd'),
      <ProjectReportsNav projectKey={PROJECT_KEY} />,
    )

    await screen.findByRole('navigation', { name: REPORTS_NAV_NAME })
    expect(screen.queryByRole('tablist')).not.toBeInTheDocument()
    expect(screen.queryByRole('tab')).not.toBeInTheDocument()
  })

  it('nav 이름이 navLabels.projectNav("프로젝트")를 substring 으로 품지 않는다', async () => {
    // 품으면 `getByRole('navigation', { name: '프로젝트' })`(사이드바 트리 조회)가 이것까지
    // 함께 잡아 Playwright strict mode 로 죽는다. 두 nav 는 한 화면에 공존한다.
    renderAt(
      hrefOf('/projects/$projectKey/reports/worklog'),
      <ProjectReportsNav projectKey={PROJECT_KEY} />,
    )

    const nav = await screen.findByRole('navigation', { name: REPORTS_NAV_NAME })
    const name = nav.getAttribute('aria-label') ?? ''
    expect(name).not.toBe('')
    expect(name.includes(navLabels.projectNav)).toBe(false)
  })
})

describe('리포트 4화면이 서브내비를 낸다 (A-7)', () => {
  it('4화면 전수에서 나머지 3개로 가는 링크가 그대로 있다', async () => {
    // 🛑 화면 하나만 보면 「4개 중 3개에만 넣었다」가 통과한다. 전수 순회로 못박는다.
    const screensUnderTest: ReadonlyArray<readonly [string, string, ReactNode]> = [
      ['벨로시티', '/projects/$projectKey/reports/velocity', <VelocityReportPage projectKey={PROJECT_KEY} />],
      ['누적 흐름도(CFD)', '/projects/$projectKey/reports/cfd', <CfdReportPage projectKey={PROJECT_KEY} />],
      ['사이클/리드 타임', '/projects/$projectKey/reports/cycle-time', <CycleTimeReportPage projectKey={PROJECT_KEY} />],
      ['작업 로그', '/projects/$projectKey/reports/worklog', <ProjectWorklogReportPage projectKey={PROJECT_KEY} />],
    ]
    expect(screensUnderTest.length).toBe(PROJECT_REPORT_LINKS.length)

    for (const [label, to, node] of screensUnderTest) {
      const { unmount } = renderAt(hrefOf(to), node)

      const nav = await screen.findByRole('navigation', { name: REPORTS_NAV_NAME })
      const others = within(nav)
        .getAllByRole('link')
        .map((link) => link.textContent ?? '')
        .filter((text) => text !== label)

      expect(others).toEqual(
        PROJECT_REPORT_LINKS.map((link) => link.label).filter((other) => other !== label),
      )

      unmount()
    }
  })
})
