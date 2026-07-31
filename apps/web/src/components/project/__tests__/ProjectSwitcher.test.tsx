// 프로젝트 스위처 테스트 — listbox ARIA·MRU 정렬·§1-B 3갈래 착지점·키보드 (FR-UX-08 PR-A Task 5, RED)
//
// ★ 라우터를 mock 하지 않고 실 memory router 를 쓴다. §1-B 의 핵심 계약이 "선택 후 URL 이
//   어떻게 되는가" 이고, `navigate` 스파이는 그 결과가 아니라 호출만 본다 — 검색 파라미터
//   치환에서 `...prev` 를 안 펼쳐 다른 파라미터가 날아가는 결함(E7-b)을 스파이는 못 잡는다.
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
import { server } from '@/test/server'
import { projectListFixtures, projectListHandlers } from '@/mocks/project-list-handlers'
import { useActiveProject } from '@/hooks/use-active-project'
import { useRecentProjects } from '@/hooks/use-recent-projects'
import { ProjectSwitcher } from '../ProjectSwitcher'

/** 백엔드 정렬(name 오름차순) 재현 — 프론트 재정렬 금지 원칙의 기준선 */
const sortedFixtures = [...projectListFixtures].sort((a, b) => a.name.localeCompare(b.name))

const atlas = sortedFixtures.find((f) => f.key === 'ATLAS')
if (atlas === undefined) throw new Error('projectListFixtures 에 ATLAS 가 필요하다')
const other = sortedFixtures.find((f) => f.key !== 'ATLAS')
if (other === undefined) throw new Error('두 번째 프로젝트 fixture 가 필요하다')

// ─────────────────────────────────────────────────────────────────────────────
// 렌더 헬퍼 — 실 memory router. 선택 후 location 을 직접 단언한다
// ─────────────────────────────────────────────────────────────────────────────

function buildRouter(initialPath: string) {
  const rootRoute = createRootRoute()
  const boardRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/projects/$projectKey/board',
    component: ProjectSwitcher,
  })
  const issuesRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/issues',
    validateSearch: (
      search: Record<string, unknown>,
    ): { projectKey?: string; status?: string; selected?: string } => ({
      projectKey: typeof search['projectKey'] === 'string' ? search['projectKey'] : undefined,
      status: typeof search['status'] === 'string' ? search['status'] : undefined,
      selected: typeof search['selected'] === 'string' ? search['selected'] : undefined,
    }),
    component: ProjectSwitcher,
  })
  const dashboardsRoute = createRoute({
    getParentRoute: () => rootRoute,
    path: '/dashboards',
    component: ProjectSwitcher,
  })
  return createRouter({
    routeTree: rootRoute.addChildren([boardRoute, issuesRoute, dashboardsRoute]),
    history: createMemoryHistory({ initialEntries: [initialPath] }),
    defaultPreload: false,
  })
}

function renderSwitcher(initialPath = '/dashboards') {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  const router = buildRouter(initialPath)
  const view = render(
    <QueryClientProvider client={queryClient}>
      <RouterProvider router={router} />
    </QueryClientProvider>,
  )
  return { ...view, router, queryClient }
}

/** 트리거를 찾아 연다 — 열림 후 listbox 를 돌려준다 */
async function openSwitcher(user: ReturnType<typeof userEvent.setup>) {
  const trigger = await screen.findByRole('button', { name: /프로젝트 선택/ })
  await user.click(trigger)
  return { trigger, listbox: await screen.findByRole('listbox') }
}

describe('ProjectSwitcher — 프로젝트 전환 (F12)', () => {
  beforeEach(() => {
    localStorage.clear()
    useActiveProject.setState({ activeProjectKey: null })
    useRecentProjects.setState({ recentProjectKeys: [] })
    // 전역 등록에 더해 명시 등록 (use-projects.test.tsx·ProjectTree.test.tsx 동일 관례)
    server.use(...projectListHandlers)
  })

  // ── FR8/NFR3 — ARIA 계약 ────────────────────────────────────────────────

  it('T5-1 (FR8): 열면 listbox 와 option 이 나오고, navigation 랜드마크는 생기지 않는다', async () => {
    const user = userEvent.setup()
    renderSwitcher()

    // ★ 스위처를 <nav> 로 만들면 navLabels.projectNav('프로젝트')가
    //   projectViewNav('프로젝트 뷰 전환')의 substring 이라 e2e 계약이 깨진다 (ADR §D5)
    expect(screen.queryAllByRole('navigation')).toHaveLength(0)

    const { listbox } = await openSwitcher(user)
    expect(within(listbox).getAllByRole('option').length).toBeGreaterThan(0)
    expect(screen.queryAllByRole('navigation')).toHaveLength(0)
  })

  it('T5-2: 트리거가 현재 활성 프로젝트 이름을 표시한다', async () => {
    useActiveProject.setState({ activeProjectKey: atlas.key })
    renderSwitcher()

    const trigger = await screen.findByRole('button', { name: /프로젝트 선택/ })
    await waitFor(() => {
      expect(trigger).toHaveTextContent(atlas.name)
    })
  })

  it('T5-3 (CONCERN C3): 긴 이름은 시각적으로 잘리되 접근가능 이름은 온전하다', async () => {
    useActiveProject.setState({ activeProjectKey: atlas.key })
    renderSwitcher()

    const trigger = await screen.findByRole('button', { name: /프로젝트 선택/ })
    const label = await screen.findByTestId('project-switcher-current')
    // 시각 truncate — 폭 제한 + 말줄임
    expect(label.className).toMatch(/truncate/)
    expect(label.className).toMatch(/max-w-/)
    // 접근가능 이름은 잘리지 않는다
    expect(trigger).toHaveAccessibleName(expect.stringContaining(atlas.name) as unknown as string)
  })

  // ── FR10/S3/E1/E11 — 정렬 ───────────────────────────────────────────────

  it('T5-4 (FR10, S3): 최근 방문 그룹이 MRU 순으로 위, 나머지는 백엔드 순서 그대로', async () => {
    useRecentProjects.setState({ recentProjectKeys: [other.key, atlas.key] })
    const user = userEvent.setup()
    renderSwitcher()

    const { listbox } = await openSwitcher(user)
    await waitFor(() => {
      expect(within(listbox).getAllByRole('option').length).toBe(sortedFixtures.length)
    })
    const options = within(listbox).getAllByRole('option')
    const keys = options.map((o) => o.getAttribute('data-project-key'))

    // 최근 2건이 MRU 순으로 맨 앞
    expect(keys.slice(0, 2)).toEqual([other.key, atlas.key])
    // 나머지는 백엔드 순서(name 오름차순) 그대로
    const restExpected = sortedFixtures
      .filter((f) => f.key !== other.key && f.key !== atlas.key)
      .map((f) => f.key)
    expect(keys.slice(2)).toEqual(restExpected)
  })

  it('T5-5 (E11): 최근 그룹에 나온 프로젝트가 나머지 구간에 중복 등장하지 않는다', async () => {
    useRecentProjects.setState({ recentProjectKeys: [atlas.key] })
    const user = userEvent.setup()
    renderSwitcher()

    const { listbox } = await openSwitcher(user)
    await waitFor(() => {
      expect(within(listbox).getAllByRole('option').length).toBe(sortedFixtures.length)
    })
    const keys = within(listbox)
      .getAllByRole('option')
      .map((o) => o.getAttribute('data-project-key'))
    expect(keys.filter((k) => k === atlas.key)).toHaveLength(1)
  })

  it('T5-6 (E1): 최근 목록의 접근 불가 키는 조용히 탈락한다', async () => {
    useRecentProjects.setState({ recentProjectKeys: ['GHOST', atlas.key] })
    const user = userEvent.setup()
    renderSwitcher()

    const { listbox } = await openSwitcher(user)
    await waitFor(() => {
      expect(within(listbox).getAllByRole('option').length).toBe(sortedFixtures.length)
    })
    const keys = within(listbox)
      .getAllByRole('option')
      .map((o) => o.getAttribute('data-project-key'))
    expect(keys).not.toContain('GHOST')
    expect(keys[0]).toBe(atlas.key)
  })

  // ── FR11 / §1-B — 착지점 3갈래 (리뷰 BLOCKER-1) ─────────────────────────

  it('T5-7 (§1-B ①): 경로 파라미터가 있으면 같은 하위 경로로 치환 이동한다', async () => {
    const user = userEvent.setup()
    const { router } = renderSwitcher(`/projects/${atlas.key}/board`)

    const { listbox } = await openSwitcher(user)
    await user.click(await within(listbox).findByRole('option', { name: other.name }))

    await waitFor(() => {
      expect(router.state.location.pathname).toBe(`/projects/${other.key}/board`)
    })
  })

  it('T5-8 (§1-B ②, ★BLOCKER-1): 검색 파라미터만 있으면 그 파라미터를 치환한다', async () => {
    const user = userEvent.setup()
    const { router } = renderSwitcher(`/issues?projectKey=${atlas.key}`)

    const { listbox } = await openSwitcher(user)
    await user.click(await within(listbox).findByRole('option', { name: other.name }))

    // ★ 초안이 빠뜨린 분기다. 활성값만 바꾸면 해소①(URL)이 이겨 화면이 안 바뀌고,
    //   useResolvedActiveProject 가 원래 키를 되기록해 선택이 즉시 되돌려진다.
    await waitFor(() => {
      expect(router.state.location.pathname).toBe('/issues')
      expect(router.state.location.search).toMatchObject({ projectKey: other.key })
    })
  })

  it('T5-9 (E7-b, ★...prev 미펼침 판별식): 검색 파라미터 치환이 다른 파라미터를 지우지 않는다', async () => {
    const user = userEvent.setup()
    const { router } = renderSwitcher(
      `/issues?projectKey=${atlas.key}&status=open&selected=ATLAS-3`,
    )

    const { listbox } = await openSwitcher(user)
    await user.click(await within(listbox).findByRole('option', { name: other.name }))

    // FR-UX-07 FR4-b 가 겪은 함정 — TanStack `search` 는 객체형이면 병합이 아니라 치환이고
    // 전 필드가 optional 이라 타입 체크로도 안 잡힌다
    await waitFor(() => {
      expect(router.state.location.search).toMatchObject({
        projectKey: other.key,
        status: 'open',
        selected: 'ATLAS-3',
      })
    })
  })

  it('T5-10 (§1-B ③): URL 이 프로젝트를 안 담으면 라우트를 유지하고 활성값만 바꾼다', async () => {
    const user = userEvent.setup()
    const { router } = renderSwitcher('/dashboards')

    const { listbox } = await openSwitcher(user)
    await user.click(await within(listbox).findByRole('option', { name: other.name }))

    await waitFor(() => {
      expect(useActiveProject.getState().activeProjectKey).toBe(other.key)
    })
    // 원치 않는 화면 이동을 만들지 않는다
    expect(router.state.location.pathname).toBe('/dashboards')
  })

  it('T5-13b (코드리뷰 CR1): 경로·검색 파라미터가 동시에 있으면 경로가 이긴다', async () => {
    // ProjectTree 는 `pathProjectKey ?? searchProjectKey`(경로 우선)인데 스위처가
    // 반대 순서면 같은 화면에서 트리는 A 를 활성 표시하고 스위처는 B 를 표시한다.
    // 두 소비처의 우선순위는 반드시 같아야 한다.
    const user = userEvent.setup()
    renderSwitcher(`/projects/${atlas.key}/board?projectKey=${other.key}`)

    const trigger = await screen.findByRole('button', { name: /프로젝트 선택/ })
    await waitFor(() => {
      expect(trigger).toHaveTextContent(atlas.name)
    })

    // 착지점도 경로 분기여야 한다 — 검색 분기로 가면 경로의 프로젝트가 안 바뀐다
    const { listbox } = await openSwitcher(user)
    await user.click(await within(listbox).findByRole('option', { name: other.name }))
    await waitFor(() => {
      expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
    })
  })

  // ── NFR6 — 키보드 ───────────────────────────────────────────────────────

  it('T5-11 (NFR6): 키보드만으로 열고 이동하고 선택할 수 있다', async () => {
    const user = userEvent.setup()
    const { router } = renderSwitcher('/dashboards')

    const trigger = await screen.findByRole('button', { name: /프로젝트 선택/ })
    trigger.focus()
    await user.keyboard('{Enter}')

    const listbox = await screen.findByRole('listbox')
    await waitFor(() => {
      expect(within(listbox).getAllByRole('option').length).toBe(sortedFixtures.length)
    })

    await user.keyboard('{ArrowDown}')
    await user.keyboard('{Enter}')

    await waitFor(() => {
      expect(useActiveProject.getState().activeProjectKey).toBe(sortedFixtures[0]?.key)
    })
    expect(router.state.location.pathname).toBe('/dashboards')
  })

  it('T5-12 (NFR6): Esc 로 닫힌다', async () => {
    const user = userEvent.setup()
    renderSwitcher('/dashboards')

    await openSwitcher(user)
    await user.keyboard('{Escape}')

    await waitFor(() => {
      expect(screen.queryByRole('listbox')).not.toBeInTheDocument()
    })
  })

  // ── S10 — 빈 상태 ───────────────────────────────────────────────────────

  it('T5-13 (S10): 접근 가능한 프로젝트가 0개면 스위처를 렌더하지 않는다', async () => {
    const { queryClient } = renderSwitcher('/dashboards')
    queryClient.setQueryData(['projects', false], [])

    await waitFor(() => {
      expect(screen.queryByRole('button', { name: /프로젝트 선택/ })).not.toBeInTheDocument()
    })
  })
})
