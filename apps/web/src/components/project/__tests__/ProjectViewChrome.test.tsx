// ProjectViewChrome 마운트 조건 + 셸 모드 분기 + 보드 스코프 승계 (J5 · X7 · 편차 X-N1)
import type { ReactNode } from 'react'
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import { PROJECT_SETTINGS_NAV } from '@/components/project/project-shell-mode'

// ─────────────────────────────────────────────────────────────────────────────
// 라우터·쿼리 훅을 모킹한다.
//
// 이 컴포넌트의 책임은 **판정 3개**뿐이다 — ① projectKey 유무로 마운트를 가른다
// ② `?board=` 가 있을 때만 보드 목록을 묻는다 ③ 두 스코프를 각 탭 규칙대로 넘긴다.
// 실 라우터를 세우면 그 셋이 라우팅 잡음에 묻힌다.
// ─────────────────────────────────────────────────────────────────────────────

const mockUseParams = vi.fn<() => { projectKey?: string }>()
const mockUseSearch = vi.fn<() => { board?: string }>()
const mockUseRouterState = vi.fn<() => string>()
const mockUseBoards = vi.fn<(projectKey: string) => { data: unknown }>()
const mockUseProject = vi.fn<(key: string) => { data?: { name: string } }>()
/** ProjectNavTabs 가 실제로 받은 props — 배선을 여기서 읽는다 */
const navTabsProps: Record<string, unknown>[] = []
/** ProjectViewHeader 가 실제로 받은 props — 셸 모드 배선을 여기서 읽는다 */
const headerProps: Record<string, unknown>[] = []

vi.mock('@tanstack/react-router', () => ({
  useParams: () => mockUseParams(),
  useSearch: () => mockUseSearch(),
  useRouterState: () => mockUseRouterState(),
  // 실 헤더를 렌더하는 블록이 `Breadcrumb` 을 통해 쓴다.
  Link: ({ children, to }: { children: ReactNode; to: string }) => <a href={to}>{children}</a>,
}))

vi.mock('@/hooks/use-boards', () => ({
  useBoards: (projectKey: string) => mockUseBoards(projectKey),
}))

vi.mock('@/hooks/use-project', () => ({
  useProject: (key: string) => mockUseProject(key),
}))

vi.mock('@/components/favorite/FavoriteButton', () => ({
  FavoriteButton: () => <button type="button" data-testid="favorite" />,
}))

vi.mock('@/components/project/ProjectNavTabs', () => ({
  ProjectNavTabs: (props: Record<string, unknown>) => {
    navTabsProps.push(props)
    return <nav aria-label="프로젝트 뷰 전환" />
  },
}))

// 헤더 «렌더» 계약은 `ProjectViewHeader.test.tsx` 가 지킨다. 여기서는 **마운트 여부와 배선**만 본다.
vi.mock('@/components/project/ProjectViewHeader', () => ({
  ProjectViewHeader: (props: Record<string, unknown>) => {
    headerProps.push(props)
    return <header data-testid="project-header">{String(props['projectKey'])}</header>
  },
}))

const { ProjectViewChrome } = await import('@/components/project/ProjectViewChrome')

/**
 * **실** `ProjectViewHeader` — 위 `vi.mock` 을 우회해 진짜 모듈을 한 벌 더 잡아 둔다.
 *
 * 🛑 이게 없으면 「설정 모드에서 부제가 나온다」가 **mock 이 삼킨 prop** 이 된다. 크롬이
 *    `shellMode` 를 넘겼다는 것만 확인하고 헤더가 그것을 무시해도 초록이기 때문이다
 *    (`mock-swallowed-prop-is-invisible-to-unit-tests` 양식). 배선(위 mock)과 렌더(아래 실물)를
 *    같은 파일에서 둘 다 본다.
 */
const { ProjectViewHeader: RealProjectViewHeader } = await vi.importActual<
  typeof import('@/components/project/ProjectViewHeader')
>('@/components/project/ProjectViewHeader')

const SCRUM = { boardId: 'b-scrum', boardType: 'SCRUM' }
const KANBAN = { boardId: 'b-kanban', boardType: 'KANBAN' }

beforeEach(() => {
  navTabsProps.length = 0
  headerProps.length = 0
  mockUseParams.mockReturnValue({ projectKey: 'ATLAS' })
  mockUseSearch.mockReturnValue({})
  mockUseRouterState.mockReturnValue('/projects/ATLAS/board')
  mockUseBoards.mockReturnValue({ data: [SCRUM, KANBAN] })
  mockUseProject.mockReturnValue({ data: { name: 'Atlas 프로젝트' } })
})

/**
 * 크롬을 본문과 함께 렌더하고 **결과를 돌려준다**(경로별 순회가 `unmount` 를 쓴다).
 *
 * 🛑 본문은 **자식**이다. 크롬만 렌더하면 `ProjectChromeProvider` 가 본문을 감싸는지
 *    (= 페이지가 액션 포털과 h1 소유권 신호를 받는지) 이 파일이 영영 못 본다.
 */
function renderChrome(): ReturnType<typeof render> {
  return render(
    <ProjectViewChrome>
      <div data-testid="page-body">본문</div>
    </ProjectViewChrome>,
  )
}

/** 마지막으로 넘어간 props — 배선 단언의 단일 진입점 */
function lastProps(): Record<string, unknown> {
  const props = navTabsProps[navTabsProps.length - 1]
  if (props === undefined) throw new Error('ProjectNavTabs 가 렌더되지 않았다')
  return props
}

/** 마지막으로 헤더에 넘어간 props */
function lastHeaderProps(): Record<string, unknown> {
  const props = headerProps[headerProps.length - 1]
  if (props === undefined) throw new Error('ProjectViewHeader 가 렌더되지 않았다')
  return props
}

describe('ProjectViewChrome — 마운트 조건', () => {
  it('경로에 projectKey 가 있으면 탭바를 렌더한다', () => {
    renderChrome()

    expect(screen.getByRole('navigation', { name: '프로젝트 뷰 전환' })).toBeInTheDocument()
  })

  it('projectKey 가 없으면 아무것도 렌더하지 않는다', () => {
    // `/issues`·`/calendar`·`/dashboards`·`/projects/new` 가 이 분기다.
    mockUseParams.mockReturnValue({})
    renderChrome()

    expect(screen.queryByRole('navigation', { name: '프로젝트 뷰 전환' })).not.toBeInTheDocument()
  })

  it('projectKey 가 빈 문자열이면 렌더하지 않는다', () => {
    mockUseParams.mockReturnValue({ projectKey: '' })
    renderChrome()

    expect(screen.queryByRole('navigation', { name: '프로젝트 뷰 전환' })).not.toBeInTheDocument()
  })

  it('탭바와 함께 제목 헤더도 마운트한다 (J5-8)', () => {
    renderChrome()

    expect(screen.getByTestId('project-header')).toHaveTextContent('ATLAS')
  })

  it('projectKey 가 없으면 헤더도 렌더하지 않는다', () => {
    mockUseParams.mockReturnValue({})
    renderChrome()

    expect(screen.queryByTestId('project-header')).not.toBeInTheDocument()
  })

  it('헤더가 탭바보다 앞에 온다 — Jira 는 제목 아래 탭이다 (J5-8)', () => {
    // 🛑 순서를 바꾸면 화면은 「탭 → 제목」이 되어 지금 고치려는 그 배치로 되돌아간다.
    //    두 노드가 다 있다는 단언만으로는 이 회귀가 잡히지 않는다.
    renderChrome()

    const header = screen.getByTestId('project-header')
    const nav = screen.getByRole('navigation', { name: '프로젝트 뷰 전환' })
    expect(header.compareDocumentPosition(nav) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('크롬이 없어도 본문은 렌더한다', () => {
    // 전역 `/issues`·`/calendar` 가 이 분기다. 본문까지 사라지면 화면이 빈다.
    mockUseParams.mockReturnValue({})
    renderChrome()

    expect(screen.getByTestId('page-body')).toBeInTheDocument()
  })
})

describe('ProjectViewChrome — 설정 서브앱에서는 탭바를 렌더하지 않는다 (편차 X-N1 · A-5)', () => {
  /** 🔒 설정 모드 부제 — `ProjectViewHeader.tsx` 의 `PROJECT_SETTINGS_SUBTITLE` 과 같은 값이어야 한다 */
  const SETTINGS_SUBTITLE = '프로젝트 설정'

  /** `PROJECT_SETTINGS_NAV` 가 실제로 갖고 있는 설정 경로 전량 (`$projectKey` 치환 완료) */
  const settingsPaths = PROJECT_SETTINGS_NAV.flatMap((group) =>
    group.items.map((item) => item.to.replace('$projectKey', 'ATLAS')),
  )

  it('비-공허: 훑을 설정 경로가 4그룹 10항목이다', () => {
    // 목록이 비면 아래 전수 순회가 조용히 통과한다 — 「두 빈 집합은 같다」.
    expect(PROJECT_SETTINGS_NAV).toHaveLength(4)
    expect(settingsPaths).toHaveLength(10)
  })

  it('설정 경로 전수에서 `프로젝트 뷰 전환` nav 가 없다', () => {
    for (const pathname of settingsPaths) {
      mockUseRouterState.mockReturnValue(pathname)
      const { unmount } = renderChrome()

      expect(
        screen.queryByRole('navigation', { name: '프로젝트 뷰 전환' }),
        `${pathname} 에서 탭바가 남아 있다`,
      ).not.toBeInTheDocument()

      unmount()
    }
  })

  it('설정 경로에서도 제목 헤더는 남는다 — 사라지면 프로젝트 정체성이 0이다', () => {
    mockUseRouterState.mockReturnValue('/projects/ATLAS/settings/details')
    renderChrome()

    expect(screen.getByTestId('project-header')).toHaveTextContent('ATLAS')
    expect(screen.getByTestId('page-body')).toBeInTheDocument()
  })

  it('비설정 경로에서는 탭바가 그대로 있다 (부재 단언의 짝)', () => {
    // 🛑 이 짝이 없으면 「탭바를 아예 안 그린다」는 회귀가 위 단언을 통과한다.
    for (const pathname of ['/projects/ATLAS', '/projects/ATLAS/board', '/projects/ATLAS/reports']) {
      mockUseRouterState.mockReturnValue(pathname)
      const { unmount } = renderChrome()

      expect(
        screen.getByRole('navigation', { name: '프로젝트 뷰 전환' }),
        `${pathname} 에서 탭바가 사라졌다`,
      ).toBeInTheDocument()

      unmount()
    }
  })

  it('컴포넌트·버전은 `/settings/` 경로인데도 탭바가 남는다 (편차 X-N2)', () => {
    // 🛑 규칙은 「설정 «경로» = 탭바 없음」이 아니라 「설정 서브앱에 «속한» 경로 = 탭바 없음」이다.
    //    `pathname.includes('/settings/')` 로 판정하면 이 두 화면에서 정본 탭이 스스로를 지운다.
    for (const pathname of [
      '/projects/ATLAS/settings/components',
      '/projects/ATLAS/settings/versions',
    ]) {
      mockUseRouterState.mockReturnValue(pathname)
      const { unmount } = renderChrome()

      expect(
        screen.getByRole('navigation', { name: '프로젝트 뷰 전환' }),
        `${pathname} 에서 탭바가 사라졌다`,
      ).toBeInTheDocument()

      unmount()
    }
  })

  it('셸 모드를 헤더에 그대로 넘긴다 (부제의 재료)', () => {
    mockUseRouterState.mockReturnValue('/projects/ATLAS/settings/members')
    renderChrome()
    expect(lastHeaderProps()['shellMode']).toBe('settings')

    headerProps.length = 0
    mockUseRouterState.mockReturnValue('/projects/ATLAS/board')
    renderChrome()
    expect(lastHeaderProps()['shellMode']).toBe('tree')
  })

  it('실 헤더가 설정 모드에서 「프로젝트 설정」 부제를 낸다 (★리뷰 D-3)', () => {
    // 위 단언은 **넘겼다**만 본다. 헤더가 그 prop 을 무시해도 초록이므로 실물로 한 번 더 본다.
    render(<RealProjectViewHeader projectKey="ATLAS" onActionHost={() => {}} shellMode="settings" />)

    expect(screen.getByText(SETTINGS_SUBTITLE)).toBeInTheDocument()
    // 🛑 부제는 `<p>` 다 — `h1` 을 하나 더 만들면 e2e 의 `<h1>` 단독 계약이 즉사한다(C-3).
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1)
    expect(screen.queryByRole('heading', { name: SETTINGS_SUBTITLE })).not.toBeInTheDocument()
  })

  it('실 헤더는 트리 모드에서 부제를 내지 않는다 (짝)', () => {
    render(<RealProjectViewHeader projectKey="ATLAS" onActionHost={() => {}} shellMode="tree" />)

    expect(screen.queryByText(SETTINGS_SUBTITLE)).not.toBeInTheDocument()
  })
})

describe('ProjectViewChrome — 보드 목록 조회 비용', () => {
  it('`?board=` 가 없으면 빈 키를 넘겨 쿼리를 끈다', () => {
    // `useBoards` 의 `enabled: projectKey.length > 0` 를 인자로 끈다. 이걸 안 하면 프로젝트
    // 하위 20여 화면 전부가 열릴 때마다 보드 목록을 부른다.
    renderChrome()

    expect(mockUseBoards).toHaveBeenCalledWith('')
  })

  it('`?board=` 가 있으면 그 프로젝트의 보드 목록을 묻는다', () => {
    mockUseSearch.mockReturnValue({ board: 'b-scrum' })
    renderChrome()

    expect(mockUseBoards).toHaveBeenCalledWith('ATLAS')
  })
})

describe('ProjectViewChrome — 편차 X7 스코프 승계', () => {
  it('스크럼 보드면 보드·백로그 두 탭이 모두 스코프를 받는다', () => {
    mockUseSearch.mockReturnValue({ board: 'b-scrum' })
    renderChrome()

    expect(lastProps()['boardScope']).toEqual({
      board: { board: 'b-scrum' },
      backlog: { board: 'b-scrum' },
    })
  })

  it('칸반 보드면 보드 탭만 받는다 — 백로그는 스크럼 전용이다', () => {
    mockUseSearch.mockReturnValue({ board: 'b-kanban' })
    renderChrome()

    expect(lastProps()['boardScope']).toEqual({
      board: { board: 'b-kanban' },
      backlog: undefined,
    })
  })

  it('보드 목록이 아직 없으면 백로그 탭은 받지 않는다 (종류를 모른다)', () => {
    mockUseSearch.mockReturnValue({ board: 'b-scrum' })
    mockUseBoards.mockReturnValue({ data: undefined })
    renderChrome()

    expect(lastProps()['boardScope']).toEqual({
      board: { board: 'b-scrum' },
      backlog: undefined,
    })
  })

  it('`?board=` 가 없으면 두 탭 다 스코프가 없다', () => {
    renderChrome()

    expect(lastProps()['boardScope']).toEqual({ board: undefined, backlog: undefined })
  })

  it('현재 pathname 을 그대로 넘긴다 — 오버플로 핀 고정의 재료다', () => {
    renderChrome()

    expect(lastProps()['pathname']).toBe('/projects/ATLAS/board')
    expect(lastProps()['projectKey']).toBe('ATLAS')
  })
})
