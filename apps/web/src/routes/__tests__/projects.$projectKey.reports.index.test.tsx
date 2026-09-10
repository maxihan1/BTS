// 리포트 착지 화면 단위 테스트 — 4카드(제목+질문) · 링크 href 전수 · h1 부재 (Jira 패리티 JR-2)
import { describe, it, expect } from 'vitest'
import { render, screen, within } from '@testing-library/react'
import {
  RouterProvider,
  createRouter,
  createRoute,
  createRootRoute,
  createMemoryHistory,
} from '@tanstack/react-router'
import { ProjectReportsIndexPage } from '@/routes/projects.$projectKey.reports.index'
import { PROJECT_REPORT_LINKS } from '@/components/project/project-report-links'

/** 테스트에 쓰는 프로젝트 키 */
const PROJECT_KEY = 'ATLAS'

/** 착지 라우트 경로 */
const REPORTS_INDEX_PATH = `/projects/${PROJECT_KEY}/reports`

/**
 * 격리된 최소 route tree(메모리 히스토리)에 착지 화면을 마운트한다.
 *
 * 목적지 라우트 4개를 등록하지 않는다 — `Link` 의 href 는 `to` + params 로만 계산되므로
 * **지금 있는 위치**만 실재하면 된다 (`ProjectNavTabs.test.tsx` 선례).
 */
function renderPage() {
  const rootRoute = createRootRoute()
  const catchAllRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '$',
    component: () => <ProjectReportsIndexPage projectKey={PROJECT_KEY} />,
  })
  const testRouter = createRouter({
    routeTree: rootRoute.addChildren([catchAllRoute]),
    history: createMemoryHistory({ initialEntries: [REPORTS_INDEX_PATH] }),
    defaultPreload: false,
  })

  return render(<RouterProvider router={testRouter} />)
}

/** `to` 의 `$projectKey` 를 치환한 실제 경로 */
function hrefOf(to: string): string {
  return to.replace('$projectKey', PROJECT_KEY)
}

describe('ProjectReportsIndexPage', () => {
  it('카드가 정확히 4장이다 — 리포트 정본 목록과 개수가 같다', async () => {
    renderPage()

    const cards = await screen.findAllByRole('listitem')
    expect(cards).toHaveLength(PROJECT_REPORT_LINKS.length)
    expect(cards).toHaveLength(4)
  })

  it('카드마다 제목과 「답하는 질문」이 함께 있다 (전수 · ★리뷰 D-4)', async () => {
    // 🛑 제목만 보면 「벨로시티」와 「사이클/리드 타임」 중 무엇을 눌러야 할지 모른다.
    //    질문 한 줄이 빠지는 회귀를 카드 단위로 잡는다 — 문서 전역 조회로는 짝이 안 보인다.
    renderPage()

    const cards = await screen.findAllByRole('listitem')

    PROJECT_REPORT_LINKS.forEach((link, index) => {
      const card = cards[index]
      expect(card).toBeDefined()
      if (card === undefined) return
      expect(within(card).getByText(link.label)).toBeInTheDocument()
      expect(within(card).getByText(link.question)).toBeInTheDocument()
    })
  })

  it('링크 4개의 href 가 리포트 4경로와 정확히 같다', async () => {
    renderPage()

    await screen.findAllByRole('listitem')
    const hrefs = screen.getAllByRole('link').map((link) => link.getAttribute('href'))

    expect(hrefs).toEqual(PROJECT_REPORT_LINKS.map((link) => hrefOf(link.to)))
  })

  it('<h1> 을 두지 않는다 — 셸의 ProjectViewHeader 가 h1 을 단독 소유한다', async () => {
    // e2e 의 `<h1>` 단독 계약. 화면이 h1 을 하나 더 내면 heading 조회가 strict mode 로 죽는다.
    renderPage()

    await screen.findAllByRole('listitem')
    expect(screen.queryByRole('heading', { level: 1 })).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 2 })).toBeInTheDocument()
  })
})
