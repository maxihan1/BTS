// ProjectTree 컴포넌트 테스트 — 2단 그룹 아코디언, 활성 자동펼침, 접힘레일, 빈/에러 상태 (FR-UX-06 PR12 Task 2)
import { describe, it, expect, beforeEach } from 'vitest'
import { render, screen, within, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import {
  RouterProvider,
  createRouter,
  createRoute,
  createRootRoute,
  createMemoryHistory,
} from '@tanstack/react-router'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { projectListHandlers, projectListFixtures } from '@/mocks/project-list-handlers'
import { useSidebarCollapsed } from '@/hooks/use-sidebar-collapsed'
import { navLabels } from '@/i18n/nav-labels'
import { ProjectTree } from '../ProjectTree'

// ─────────────────────────────────────────────────────────────────────────────
// 서브링크 실경로 계약 (router.ts 실측, S3 죽은 링크 0 방지) — ATLAS 프로젝트 기준
// ─────────────────────────────────────────────────────────────────────────────

const DIRECT_LINK_CONTRACT: ReadonlyArray<readonly [string, string]> = [
  ['보드', '/projects/ATLAS/board'],
  ['백로그', '/projects/ATLAS/backlog'],
  ['타임라인', '/projects/ATLAS/timeline'],
]

const REPORT_LINK_CONTRACT: ReadonlyArray<readonly [string, string]> = [
  ['벨로시티', '/projects/ATLAS/reports/velocity'],
  ['누적 흐름도(CFD)', '/projects/ATLAS/reports/cfd'],
  ['사이클/리드 타임', '/projects/ATLAS/reports/cycle-time'],
  ['작업 로그', '/projects/ATLAS/reports/worklog'],
]

const SETTINGS_LINK_CONTRACT: ReadonlyArray<readonly [string, string]> = [
  ['워크플로우 스킴', '/projects/ATLAS/settings/workflow-scheme'],
  ['멤버', '/projects/ATLAS/settings/members'],
  ['컴포넌트', '/projects/ATLAS/settings/components'],
  ['버전', '/projects/ATLAS/settings/versions'],
  ['커스텀 필드', '/projects/ATLAS/settings/custom-fields'],
  ['이슈 템플릿', '/projects/ATLAS/settings/issue-templates'],
  ['필드 권한', '/projects/ATLAS/settings/field-permissions'],
  ['자동화', '/projects/ATLAS/settings/automation'],
  ['Slack 채널', '/projects/ATLAS/settings/slack-channels'],
  ['프로젝트 리드', '/projects/ATLAS/settings/project-lead'],
  ['가져오기', '/projects/ATLAS/settings/import'],
]

/** fixture를 name 오름차순 정렬 — MSW 핸들러(project-list-handlers.ts)와 동일 정렬 재현 */
const sortedFixtures = [...projectListFixtures].sort((a, b) => a.name.localeCompare(b.name))

/** name='Atlas 프로젝트' fixture — 활성 프로젝트 시나리오(ATLAS) 전용 헬퍼 */
const atlasFixture = sortedFixtures.find((f) => f.key === 'ATLAS')
if (atlasFixture === undefined) {
  throw new Error('projectListFixtures에 ATLAS 항목이 필요하다')
}

// ─────────────────────────────────────────────────────────────────────────────
// 렌더 헬퍼 — memory router(실 Link/useParams) + QueryClient. MSW는 전역 핸들러 소비
// (project-list-handlers가 mocks/handlers.ts에 전역 등록됨, T1)
// ─────────────────────────────────────────────────────────────────────────────

function buildRouter(initialPath: string) {
  const rootRoute = createRootRoute()
  const projectBoardRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/projects/$projectKey/board',
    component: ProjectTree,
  })
  const outsideRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/dashboards',
    component: ProjectTree,
  })
  return createRouter({
    routeTree: rootRoute.addChildren([projectBoardRoute, outsideRoute]),
    history: createMemoryHistory({ initialEntries: [initialPath] }),
    defaultPreload: false,
  })
}

function renderProjectTree(initialPath = '/dashboards') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={buildRouter(initialPath)} />
    </QueryClientProvider>,
  )
}

// Testing Library `getByRole` name 매칭은 (Playwright와 달리) 기본이 이미 완전일치이므로
// `exact` 옵션 자체가 없다(ByRoleOptions에 미정의) — '프로젝트 뷰 전환'과 substring 충돌 없음.
async function findProjectNav() {
  return screen.findByRole('navigation', { name: navLabels.projectNav })
}

beforeEach(() => {
  // useSidebarCollapsed는 모듈 전역 zustand 싱글톤 — 이전 테스트의 상태가 누출되지 않도록 리셋
  useSidebarCollapsed.setState({ collapsed: false })
  // mocks/handlers.ts 전역 등록에 더해 명시 등록(use-projects.test.tsx와 동일 관례)
  server.use(...projectListHandlers)
})

// ─────────────────────────────────────────────────────────────────────────────
// 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('ProjectTree', () => {
  it('<nav aria-label="프로젝트">(exact)가 존재한다 (FR1)', async () => {
    renderProjectTree()

    const nav = await findProjectNav()
    expect(nav).toBeInTheDocument()
  })

  it('프로젝트 목록을 name 오름차순으로 렌더한다 (FR2)', async () => {
    renderProjectTree()

    const nav = await findProjectNav()
    await waitFor(() => {
      expect(within(nav).getAllByRole('link')).toHaveLength(sortedFixtures.length)
    })
    const links = within(nav).getAllByRole('link')
    expect(links.map((link) => link.textContent)).toEqual(sortedFixtures.map((f) => f.name))
  })

  it('각 행은 디스클로저 버튼(aria-expanded)과 프로젝트명 링크(→ board)로 구성된다 (FR3)', async () => {
    renderProjectTree()

    const nav = await findProjectNav()
    for (const fixture of sortedFixtures) {
      const link = await within(nav).findByRole('link', { name: fixture.name })
      expect(link).toHaveAttribute('href', `/projects/${fixture.key}/board`)
      const button = within(nav).getByRole('button', { name: `${fixture.name} 하위 메뉴` })
      expect(button).toHaveAttribute('aria-expanded', 'false')
    }
  })

  it('디스클로저 클릭 시 직접링크 3 + 리포트(4)·설정(11) 그룹이 펼쳐진다 — 죽은 링크 0 (FR4)', async () => {
    const user = userEvent.setup()
    renderProjectTree()

    const nav = await findProjectNav()
    const atlasToggle = await within(nav).findByRole('button', {
      name: `${atlasFixture.name} 하위 메뉴`,
    })
    await user.click(atlasToggle)
    expect(atlasToggle).toHaveAttribute('aria-expanded', 'true')

    for (const [label, href] of DIRECT_LINK_CONTRACT) {
      expect(within(nav).getByRole('link', { name: label })).toHaveAttribute('href', href)
    }

    const reportsToggle = within(nav).getByRole('button', { name: '리포트' })
    expect(reportsToggle).toHaveAttribute('aria-expanded', 'false')
    await user.click(reportsToggle)
    expect(reportsToggle).toHaveAttribute('aria-expanded', 'true')
    for (const [label, href] of REPORT_LINK_CONTRACT) {
      expect(within(nav).getByRole('link', { name: label })).toHaveAttribute('href', href)
    }

    const settingsToggle = within(nav).getByRole('button', { name: '프로젝트 설정' })
    expect(settingsToggle).toHaveAttribute('aria-expanded', 'false')
    await user.click(settingsToggle)
    expect(settingsToggle).toHaveAttribute('aria-expanded', 'true')
    for (const [label, href] of SETTINGS_LINK_CONTRACT) {
      expect(within(nav).getByRole('link', { name: label })).toHaveAttribute('href', href)
    }
  })

  it('활성 프로젝트($projectKey 일치)는 자동 펼침 + aria-current="page" (FR5)', async () => {
    renderProjectTree(`/projects/${atlasFixture.key}/board`)

    const nav = await findProjectNav()
    const atlasToggle = await within(nav).findByRole('button', {
      name: `${atlasFixture.name} 하위 메뉴`,
    })
    expect(atlasToggle).toHaveAttribute('aria-expanded', 'true')
    const atlasLink = within(nav).getByRole('link', { name: atlasFixture.name })
    expect(atlasLink).toHaveAttribute('aria-current', 'page')

    for (const fixture of sortedFixtures.filter((f) => f.key !== atlasFixture.key)) {
      const button = within(nav).getByRole('button', { name: `${fixture.name} 하위 메뉴` })
      expect(button).toHaveAttribute('aria-expanded', 'false')
      const link = within(nav).getByRole('link', { name: fixture.name })
      expect(link).not.toHaveAttribute('aria-current')
    }
  })

  it('프로젝트 컨텍스트 밖(/dashboards)이면 전부 접힘 (FR5)', async () => {
    renderProjectTree('/dashboards')

    const nav = await findProjectNav()
    for (const fixture of sortedFixtures) {
      const button = await within(nav).findByRole('button', { name: `${fixture.name} 하위 메뉴` })
      expect(button).toHaveAttribute('aria-expanded', 'false')
    }
  })

  it('사이드바 트리 안에 <h1>이 없다', async () => {
    renderProjectTree()

    await findProjectNav()
    expect(screen.queryByRole('heading', { level: 1 })).not.toBeInTheDocument()
  })

  it('빈 목록이면 빈 상태 문구를 표시한다', async () => {
    server.use(http.get('/api/v1/projects', () => HttpResponse.json({ data: [] })))
    renderProjectTree()

    const nav = await findProjectNav()
    await waitFor(() => {
      expect(within(nav).queryAllByRole('listitem')).toHaveLength(0)
    })
    expect(within(nav).getByText(/프로젝트가 없습니다/)).toBeInTheDocument()
  })

  it('에러 응답이면 조용히 트리를 표시하지 않는다(fail-safe, 앱 차단 금지)', async () => {
    server.use(
      http.get('/api/v1/projects', () => HttpResponse.json({ detail: '서버 오류' }, { status: 500 })),
    )
    renderProjectTree()

    await waitFor(() => {
      expect(
        screen.queryByRole('navigation', { name: navLabels.projectNav }),
      ).not.toBeInTheDocument()
    })
  })

  it('사이드바 접힘 시 프로젝트를 아이콘/이니셜만 표시하고 그룹 펼침이 비활성화된다 (FR6)', async () => {
    useSidebarCollapsed.setState({ collapsed: true })
    renderProjectTree()

    const nav = await findProjectNav()
    for (const fixture of sortedFixtures) {
      const link = await within(nav).findByRole('link', { name: fixture.name })
      expect(link).toHaveAttribute('href', `/projects/${fixture.key}/board`)
      expect(link.querySelector('span.sr-only')?.textContent).toBe(fixture.name)
    }
    expect(within(nav).queryAllByRole('button', { name: /하위 메뉴/ })).toHaveLength(0)
  })
})
