// ProjectTree 컴포넌트 테스트 — 2단 그룹 아코디언, 활성 자동펼침, 접힘레일, 빈/에러 상태 (FR-UX-06 PR12 Task 2). "모든 프로젝트"·"일반" 링크는 FR-PJ PR-5 Task 7
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
import { useProjectTreeExpanded } from '@/hooks/use-project-tree-expanded'
import { useActiveProject } from '@/hooks/use-active-project'
import { useRecentProjects } from '@/hooks/use-recent-projects'
import { useAuthStore } from '@/auth/authStore'
import { navLabels } from '@/i18n/nav-labels'
import { boardLabels } from '@/i18n/board-labels'
import { ProjectTree } from '../ProjectTree'

// ─────────────────────────────────────────────────────────────────────────────
// 서브링크 실경로 계약 (router.ts 실측, S3 죽은 링크 0 방지) — ATLAS 프로젝트 기준
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 직접 링크 2종. **「보드」가 여기 없다** — 캠페인 PR ⑩(J2)이 그 한 줄을 보드 **목록**으로
 * 갈랐다. 나머지 14개(백로그·타임라인·리포트 4·설정 12)는 그 화면들의 유일한 진입로라
 * 그대로 남는다. 이 배열에 「보드」를 되돌리면 같은 목적지가 두 줄이 된다.
 */
const DIRECT_LINK_CONTRACT: ReadonlyArray<readonly [string, string]> = [
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
  ['일반', '/projects/ATLAS/settings/details'],
  ['워크플로우', '/projects/ATLAS/settings/workflows'],
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
  /** 프로젝트 기본 착지 — 프로젝트명 링크가 가리키는 요약 라우트 (Jira 패리티 J4) */
  const projectSummaryRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/projects/$projectKey',
    component: ProjectTree,
  })
  const outsideRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/dashboards',
    component: ProjectTree,
  })
  /**
   * 검색 파라미터로만 프로젝트를 지정하는 경로 (FR-UX-08 FR7 / S6).
   * `/projects/$projectKey/*` 와 달리 경로 파라미터가 없어, 트리가 `useParams`만 보면
   * 활성 프로젝트를 못 찾는다 — 그것이 이 PR 이 닫는 선재 갭이다.
   */
  const issuesRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/issues',
    validateSearch: (search: Record<string, unknown>): { projectKey?: string } => ({
      projectKey: typeof search['projectKey'] === 'string' ? search['projectKey'] : undefined,
    }),
    component: ProjectTree,
  })
  return createRouter({
    routeTree: rootRoute.addChildren([
      projectBoardRoute,
      projectSummaryRoute,
      outsideRoute,
      issuesRoute,
    ]),
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

/**
 * ATLAS 행 디스클로저 버튼의 접근성 이름.
 *
 * 상수로 빼 두는 이유 — `function` 선언은 호이스팅되므로 그 안에서 `atlasFixture` 를 읽으면
 * 위쪽 `throw` 가드의 narrowing 이 닿지 않아 `possibly undefined` 가 된다.
 */
const ATLAS_TOGGLE_NAME = `${atlasFixture.name} 하위 메뉴`

/** ATLAS 행의 디스클로저 버튼 — 보드 목록 계열 테스트가 매번 여는 자리다 */
async function findAtlasToggle(nav: HTMLElement) {
  return within(nav).findByRole('button', { name: ATLAS_TOGGLE_NAME })
}

// ─────────────────────────────────────────────────────────────────────────────
// 보드 목록 스텁 (캠페인 PR ⑩ · J2)
// ─────────────────────────────────────────────────────────────────────────────

/** 삭제 가능한 스크럼 보드 */
const scrumBoard = {
  boardId: '11111111-1111-4111-8111-111111111111',
  projectKey: 'ATLAS',
  name: 'Atlas 스프린트 보드',
  boardType: 'SCRUM' as const,
  canDelete: true,
}

/** 삭제 가능한 칸반 보드 — 한 프로젝트에 보드가 여럿이라는 것 자체가 이 PR 의 전제다 */
const kanbanBoard = {
  boardId: '22222222-2222-4222-8222-222222222222',
  projectKey: 'ATLAS',
  name: 'Atlas 지원 보드',
  boardType: 'KANBAN' as const,
  canDelete: true,
}

/**
 * `canDelete` 를 **아예 싣지 않은** 보드 — fail-closed 판별식의 입력이다.
 * 스키마가 `.optional()` 이라 파싱은 통과하고, 소비처가 `=== true` 가 아니면 여기서 red 다.
 */
const boardWithoutCanDelete = {
  boardId: '33333333-3333-4333-8333-333333333333',
  projectKey: 'ATLAS',
  name: 'Atlas 읽기전용 보드',
  boardType: 'KANBAN' as const,
}

/**
 * `GET /api/v1/boards?projectKey=` 를 프로젝트별로 스텁하고, **실제로 요청된 키**를 모아 돌려준다.
 *
 * 반환하는 집합이 「펼친 것만 조회한다」 계약의 판별 근거다 — 응답만 스텁하면 조회가 몇 건
 * 나갔는지가 화면에 드러나지 않아 회귀를 못 잡는다.
 *
 * @param byProject 프로젝트 키 → 그 프로젝트의 보드 목록. 표에 없는 키는 빈 배열로 답한다
 */
function stubBoards(byProject: Record<string, ReadonlyArray<Record<string, unknown>>>): Set<string> {
  const requestedKeys = new Set<string>()
  server.use(
    http.get('*/api/v1/boards', ({ request }) => {
      const projectKey = new URL(request.url).searchParams.get('projectKey') ?? ''
      requestedKeys.add(projectKey)
      return HttpResponse.json({ data: byProject[projectKey] ?? [] })
    }),
  )
  return requestedKeys
}

/** `GET /api/v1/favorites?targetType=PROJECT` 를 주어진 프로젝트 키 목록으로 스텁한다 */
function stubProjectFavorites(projectKeys: readonly string[]): void {
  server.use(
    http.get('*/api/v1/favorites', () =>
      HttpResponse.json({
        data: {
          items: projectKeys.map((key, index) => ({
            id: `fav-${String(index)}`,
            targetType: 'PROJECT',
            targetId: key,
            createdAt: '2026-09-04T00:00:00.000Z',
          })),
        },
      }),
    ),
  )
}

/** whoami 에 `canCreateProject: true` 를 실어 트리 헤더 `＋` 를 여는 조건을 만든다 */
function grantCreateProject(): void {
  useAuthStore.setState({
    user: {
      userId: '99999999-9999-4999-8999-999999999999',
      username: 'maxi',
      displayName: 'Maxi',
      canCreateProject: true,
    } as never,
  })
}

beforeEach(() => {
  // useSidebarCollapsed는 모듈 전역 zustand 싱글톤 — 이전 테스트의 상태가 누출되지 않도록 리셋
  useSidebarCollapsed.setState({ collapsed: false })
  // ★ FR-UX-08 — 펼침 집합이 영속되면서 같은 성질이 생겼다. 이 리셋이 없으면 아래
  //   "나머지는 접힘" 계열 단언이 순서 의존이 되어 거짓 실패·거짓 통과가 둘 다 가능하다
  //   (plan 리뷰 BLOCKER-2). 활성 프로젝트 저장값도 해소 ②의 입력이므로 함께 비운다.
  localStorage.clear()
  useProjectTreeExpanded.setState({ expandedKeys: new Set<string>() })
  useActiveProject.setState({ activeProjectKey: null })
  // ★ 3그룹 분할의 두 입력(최근 방문 · whoami)도 모듈 전역이다. 리셋하지 않으면 앞 테스트가
  //   심은 최근 목록·생성 권한이 다음 테스트의 그룹 구성을 바꾼다.
  useRecentProjects.setState({ recentProjectKeys: [] })
  useAuthStore.setState({ user: null })
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
    // 프로젝트 목록 <ul>로 스코핑 — "모든 프로젝트" 링크(nav 최상단, G2)는 리스트 밖이라 미포함.
    const list = await within(nav).findByRole('list')
    await waitFor(() => {
      expect(within(list).getAllByRole('link')).toHaveLength(sortedFixtures.length)
    })
    const links = within(list).getAllByRole('link')
    expect(links.map((link) => link.textContent)).toEqual(sortedFixtures.map((f) => f.name))
  })

  it('nav 최상단에 "모든 프로젝트" 링크(→ /projects)가 존재한다 (G2, FR-PJ PR-5 Task 7)', async () => {
    renderProjectTree()

    const nav = await findProjectNav()
    // exact 매칭 — "모든 프로젝트"는 nav aria-label "프로젝트"의 substring이 아니므로
    // getByRole 조회가 다른 요소와 혼선 없이 단독 식별된다.
    const link = await within(nav).findByRole('link', { name: '모든 프로젝트' })
    expect(link).toHaveAttribute('href', '/projects')
  })

  it('각 행은 디스클로저 버튼(aria-expanded)과 프로젝트명 링크(→ 요약)로 구성된다 (FR3 · J4)', async () => {
    renderProjectTree()

    const nav = await findProjectNav()
    for (const fixture of sortedFixtures) {
      const link = await within(nav).findByRole('link', { name: fixture.name })
      expect(link).toHaveAttribute('href', `/projects/${fixture.key}`)
      const button = within(nav).getByRole('button', { name: `${fixture.name} 하위 메뉴` })
      expect(button).toHaveAttribute('aria-expanded', 'false')
    }
  })

  it('디스클로저 클릭 시 직접링크 2 + 리포트(4)·설정(12) 그룹이 펼쳐진다 — 죽은 링크 0 (FR4)', async () => {
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
      expect(link).toHaveAttribute('href', `/projects/${fixture.key}`)
      expect(link.querySelector('span.sr-only')?.textContent).toBe(fixture.name)
    }
    expect(within(nav).queryAllByRole('button', { name: /하위 메뉴/ })).toHaveLength(0)
  })

  // ───────────────────────────────────────────────────────────────────────────
  // FR-UX-08 PR-A Task 4 — 펼침 영속(FR6) + 검색 파라미터 활성 인식(FR7)
  //
  // ★ FR-UX-06 PR12 의 FR5("라우트가 바뀌면 이전 수동 펼침을 덮어쓴다")를 정정한다.
  //   자동펼침은 이제 **더하기만** 한다 (ADR §D2).
  // ───────────────────────────────────────────────────────────────────────────

  it('T4-1 (FR6, S4): 펼친 상태가 언마운트/재마운트를 건너 유지된다 (영속)', async () => {
    const user = userEvent.setup()
    const first = renderProjectTree('/dashboards')

    const nav = await findProjectNav()
    const toggle = await within(nav).findByRole('button', {
      name: `${atlasFixture.name} 하위 메뉴`,
    })
    await user.click(toggle)
    expect(toggle).toHaveAttribute('aria-expanded', 'true')

    // 새로고침에 해당 — 트리를 통째로 내렸다가 다시 올린다
    first.unmount()
    renderProjectTree('/dashboards')

    const nav2 = await findProjectNav()
    const toggle2 = await within(nav2).findByRole('button', {
      name: `${atlasFixture.name} 하위 메뉴`,
    })
    expect(toggle2).toHaveAttribute('aria-expanded', 'true')
  })

  it('T4-2 (FR6, S5): 활성 프로젝트 자동펼침이 기존 수동 펼침을 지우지 않는다 (덮어쓰기 폐지)', async () => {
    const other = sortedFixtures.find((f) => f.key !== atlasFixture.key)
    if (other === undefined) throw new Error('두 번째 프로젝트 fixture 가 필요하다')

    const user = userEvent.setup()
    const first = renderProjectTree('/dashboards')

    // 사용자가 other 를 수동으로 펼친다
    const nav = await findProjectNav()
    const otherToggle = await within(nav).findByRole('button', {
      name: `${other.name} 하위 메뉴`,
    })
    await user.click(otherToggle)
    expect(otherToggle).toHaveAttribute('aria-expanded', 'true')

    // 라우트가 ATLAS 로 바뀐다 — 자동펼침이 발동한다
    first.unmount()
    renderProjectTree(`/projects/${atlasFixture.key}/board`)

    const nav2 = await findProjectNav()
    // ★ 판별식: 덮어쓰기가 남아 있으면 other 가 접힌다
    const otherToggle2 = await within(nav2).findByRole('button', {
      name: `${other.name} 하위 메뉴`,
    })
    expect(otherToggle2).toHaveAttribute('aria-expanded', 'true')
    // 자동펼침 자체는 정상 동작해야 한다
    const atlasToggle2 = within(nav2).getByRole('button', {
      name: `${atlasFixture.name} 하위 메뉴`,
    })
    expect(atlasToggle2).toHaveAttribute('aria-expanded', 'true')
  })

  it('T4-3 (FR7, S6): 검색 파라미터 ?projectKey= 만 있어도 자동 펼침한다 (선재 갭)', async () => {
    renderProjectTree(`/issues?projectKey=${atlasFixture.key}`)

    const nav = await findProjectNav()
    const atlasToggle = await within(nav).findByRole('button', {
      name: `${atlasFixture.name} 하위 메뉴`,
    })
    // ★ 기존 구현은 useParams(경로 파라미터)만 봐서 여기가 'false' 였다
    expect(atlasToggle).toHaveAttribute('aria-expanded', 'true')
  })

  it('T4-4 (FR7): 검색 파라미터로 온 프로젝트에는 aria-current="page" 를 붙이지 않는다', async () => {
    renderProjectTree(`/issues?projectKey=${atlasFixture.key}`)

    const nav = await findProjectNav()
    const atlasLink = await within(nav).findByRole('link', { name: atlasFixture.name })
    // 사용자는 그 프로젝트의 보드 페이지에 있지 않다 — aria-current="page" 는 거짓말이 된다.
    // 경로 파라미터 일치일 때만 부여한다.
    expect(atlasLink).not.toHaveAttribute('aria-current')
  })

  it('T4-5 (E13): 영속된 펼침 키 중 접근 불가 프로젝트는 조용히 무시된다', async () => {
    useProjectTreeExpanded.setState({
      expandedKeys: new Set([atlasFixture.key, 'GHOST-PROJECT']),
    })

    renderProjectTree('/dashboards')

    const nav = await findProjectNav()
    // 실재 프로젝트는 펼쳐지고
    const atlasToggle = await within(nav).findByRole('button', {
      name: `${atlasFixture.name} 하위 메뉴`,
    })
    expect(atlasToggle).toHaveAttribute('aria-expanded', 'true')
    // 유령 키는 어떤 행도 만들지 않는다 (렌더는 목록 기준이므로 자연 무시)
    expect(within(nav).queryByRole('button', { name: /GHOST-PROJECT/ })).not.toBeInTheDocument()
  })

  // ───────────────────────────────────────────────────────────────────────────
  // 캠페인 PR ⑩ (J2) — 보드 목록 · 3그룹 · 스페이스/보드 `⋯` · 트리 헤더 `＋`
  // ───────────────────────────────────────────────────────────────────────────

  describe('보드 목록 (J2)', () => {
    it('펼치면 「보드」 링크 대신 보드 목록이 오고, 각 링크가 ?board=<id> 를 싣는다', async () => {
      const user = userEvent.setup()
      stubBoards({ ATLAS: [scrumBoard, kanbanBoard] })
      renderProjectTree()

      const nav = await findProjectNav()
      await user.click(await findAtlasToggle(nav))

      // ★ 판별식: 「보드」 한 줄이 되살아나면 여기서 잡힌다 (같은 목적지 두 줄)
      expect(within(nav).queryByRole('link', { name: '보드' })).not.toBeInTheDocument()

      expect(await within(nav).findByRole('link', { name: scrumBoard.name })).toHaveAttribute(
        'href',
        `/projects/ATLAS/board?board=${scrumBoard.boardId}`,
      )
      expect(within(nav).getByRole('link', { name: kanbanBoard.name })).toHaveAttribute(
        'href',
        `/projects/ATLAS/board?board=${kanbanBoard.boardId}`,
      )
    })

    it('보드가 0건이면 「보드 없음」을 낸다 — 조회 전에는 그 문구를 내지 않는다', async () => {
      const user = userEvent.setup()
      stubBoards({ ATLAS: [] })
      renderProjectTree()

      const nav = await findProjectNav()
      await user.click(await findAtlasToggle(nav))

      expect(await within(nav).findByText('보드 없음')).toBeInTheDocument()
    })

    it('펼치지 않은 프로젝트의 보드는 조회하지 않는다 (요청 수 = 펼친 개수)', async () => {
      const user = userEvent.setup()
      const requestedKeys = stubBoards({ ATLAS: [scrumBoard] })
      renderProjectTree()

      const nav = await findProjectNav()
      await user.click(await findAtlasToggle(nav))
      await within(nav).findByRole('link', { name: scrumBoard.name })

      // ★ 행마다 useBoards 를 부르면 fixture 전량(3건)이 여기 담긴다
      expect([...requestedKeys]).toEqual(['ATLAS'])
    })

    it('canDelete 가 true 일 때만 보드 `⋯` 가 뜬다 — undefined 는 fail-closed', async () => {
      const user = userEvent.setup()
      stubBoards({ ATLAS: [scrumBoard, boardWithoutCanDelete] })
      renderProjectTree()

      const nav = await findProjectNav()
      await user.click(await findAtlasToggle(nav))
      await within(nav).findByRole('link', { name: scrumBoard.name })

      expect(
        within(nav).getByRole('button', { name: `보드 관리, ${scrumBoard.name}` }),
      ).toBeInTheDocument()
      // ★ 필드 부재를 「삭제 가능」으로 읽으면 여기가 red 다
      expect(
        within(nav).queryByRole('button', { name: `보드 관리, ${boardWithoutCanDelete.name}` }),
      ).not.toBeInTheDocument()
    })

    it('★ 사이드바 `⋯` 는 삭제만 낸다 — 이름 변경·보드 설정은 보드 화면 헤더의 몫이다', async () => {
      const user = userEvent.setup()
      stubBoards({ ATLAS: [scrumBoard] })
      renderProjectTree()

      const nav = await findProjectNav()
      await user.click(await findAtlasToggle(nav))
      await user.click(
        await within(nav).findByRole('button', { name: `보드 관리, ${scrumBoard.name}` }),
      )

      expect(await screen.findByRole('menuitem', { name: boardLabels.actions.deleteItem })).toBeInTheDocument()
      // ★ 판별식 (Maxi 확정 2026-09-04, 코드리뷰 CONCERNS-1).
      //   `canRename` 에 `canDelete` 근사를 다시 물리면 여기가 red 다. 사이드바가 가진 유일한
      //   권한 신호는 SOFT_DELETE 판정이고 이름 변경은 CREATE 권한이라, 근사로 열면 CREATE 만
      //   가진 사용자(기본 스킴 MEMBER)에게서 기능을 빼앗는 방향으로 틀린다.
      expect(
        screen.queryByRole('menuitem', { name: boardLabels.actions.renameItem }),
      ).not.toBeInTheDocument()
      // 보드 설정(부채 177 · #452)도 같은 CREATE 축이라 같은 판단을 받는다 — 항목이 늘어날
      // 때마다 근사가 슬며시 되살아나는 자리라 함께 못 박는다.
      expect(
        screen.queryByRole('menuitem', { name: boardLabels.actions.settingsItem }),
      ).not.toBeInTheDocument()
    })
  })

  describe('3그룹 분할 (J2)', () => {
    it('즐겨찾기·최근이 비면 「추가 스페이스」 그룹만 렌더한다 (빈 그룹은 헤더까지 사라진다)', async () => {
      renderProjectTree()

      const nav = await findProjectNav()
      expect(await within(nav).findByText(navLabels.treeMoreGroup)).toBeInTheDocument()
      expect(within(nav).queryByText(navLabels.treeStarredGroup)).not.toBeInTheDocument()
      expect(within(nav).queryByText(navLabels.treeRecentGroup)).not.toBeInTheDocument()
    })

    it('즐겨찾기는 「별표 표시됨」, 최근은 「최근 방문」으로 갈리고 어느 프로젝트도 두 번 안 나온다', async () => {
      stubProjectFavorites(['ATLAS'])
      // ATLAS 는 즐겨찾기이기도 하다 — 별표가 이긴다(partitionProjectsForTree 계약)
      useRecentProjects.setState({ recentProjectKeys: ['ATLAS', 'ZETA'] })
      renderProjectTree()

      const nav = await findProjectNav()
      await within(nav).findByText(navLabels.treeStarredGroup)
      expect(within(nav).getByText(navLabels.treeRecentGroup)).toBeInTheDocument()
      expect(within(nav).getByText(navLabels.treeMoreGroup)).toBeInTheDocument()

      // 같은 프로젝트가 두 구간에 걸치면 링크가 둘이 되어 getByRole 이 strict 로 죽는다
      expect(within(nav).getByRole('link', { name: 'Atlas 프로젝트' })).toBeInTheDocument()
    })
  })

  describe('스페이스 `⋯` 와 트리 헤더 `＋` (J2)', () => {
    it('스페이스 `⋯` 는 보드 `⋯` 와 다른 접근성 이름을 쓴다', async () => {
      renderProjectTree()

      const nav = await findProjectNav()
      const trigger = await within(nav).findByRole('button', {
        name: `스페이스 관리, ${atlasFixture.name}`,
      })
      expect(trigger).toBeInTheDocument()
    })

    it('스페이스 `⋯` 에 별표 토글과 프로젝트 설정이 있다', async () => {
      const user = userEvent.setup()
      renderProjectTree()

      const nav = await findProjectNav()
      await user.click(
        await within(nav).findByRole('button', { name: `스페이스 관리, ${atlasFixture.name}` }),
      )

      expect(await screen.findByRole('menuitem', { name: '즐겨찾기에 추가' })).toBeInTheDocument()
      expect(screen.getByRole('menuitem', { name: '프로젝트 설정' })).toHaveAttribute(
        'href',
        '/projects/ATLAS/settings/details',
      )
    })

    it('canCreateProject 가 참일 때만 트리 헤더 `＋`(→ /projects/new)를 렌더한다', async () => {
      const withoutPermission = renderProjectTree()
      const nav = await findProjectNav()
      expect(within(nav).queryByRole('link', { name: '새 프로젝트' })).not.toBeInTheDocument()
      withoutPermission.unmount()

      grantCreateProject()
      renderProjectTree()
      const nav2 = await findProjectNav()
      expect(within(nav2).getByRole('link', { name: '새 프로젝트' })).toHaveAttribute(
        'href',
        '/projects/new',
      )
    })
  })
})
