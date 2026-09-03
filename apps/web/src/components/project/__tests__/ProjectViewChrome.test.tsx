// ProjectViewChrome 마운트 조건 + 보드 스코프 승계 계약 (Jira 패리티 J5 · 편차 X7)
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'

// ─────────────────────────────────────────────────────────────────────────────
// 라우터·쿼리 훅을 모킹한다.
//
// 이 컴포넌트의 책임은 **판정 3개**뿐이다 — ① projectKey 유무로 마운트를 가른다
// ② `?board=` 가 있을 때만 보드 목록을 묻는다 ③ 두 스코프를 각 탭 규칙대로 넘긴다.
// 실 라우터를 세우면 그 셋이 라우팅 잡음에 묻힌다.
// ─────────────────────────────────────────────────────────────────────────────

const mockUseParams = vi.fn<() => { projectKey?: string }>()
const mockUseSearch = vi.fn<() => { board?: string }>()
const mockUseBoards = vi.fn<(projectKey: string) => { data: unknown }>()
/** ProjectNavTabs 가 실제로 받은 props — 배선을 여기서 읽는다 */
const navTabsProps: Record<string, unknown>[] = []

vi.mock('@tanstack/react-router', () => ({
  useParams: () => mockUseParams(),
  useSearch: () => mockUseSearch(),
  useRouterState: () => '/projects/ATLAS/board',
}))

vi.mock('@/hooks/use-boards', () => ({
  useBoards: (projectKey: string) => mockUseBoards(projectKey),
}))

vi.mock('@/components/project/ProjectNavTabs', () => ({
  ProjectNavTabs: (props: Record<string, unknown>) => {
    navTabsProps.push(props)
    return <nav aria-label="프로젝트 뷰 전환" />
  },
}))

const { ProjectViewChrome } = await import('@/components/project/ProjectViewChrome')

const SCRUM = { boardId: 'b-scrum', boardType: 'SCRUM' }
const KANBAN = { boardId: 'b-kanban', boardType: 'KANBAN' }

beforeEach(() => {
  navTabsProps.length = 0
  mockUseParams.mockReturnValue({ projectKey: 'ATLAS' })
  mockUseSearch.mockReturnValue({})
  mockUseBoards.mockReturnValue({ data: [SCRUM, KANBAN] })
})

/** 마지막으로 넘어간 props — 배선 단언의 단일 진입점 */
function lastProps(): Record<string, unknown> {
  const props = navTabsProps[navTabsProps.length - 1]
  if (props === undefined) throw new Error('ProjectNavTabs 가 렌더되지 않았다')
  return props
}

describe('ProjectViewChrome — 마운트 조건', () => {
  it('경로에 projectKey 가 있으면 탭바를 렌더한다', () => {
    render(<ProjectViewChrome />)

    expect(screen.getByRole('navigation', { name: '프로젝트 뷰 전환' })).toBeInTheDocument()
  })

  it('projectKey 가 없으면 아무것도 렌더하지 않는다', () => {
    // `/issues`·`/calendar`·`/dashboards`·`/projects/new` 가 이 분기다.
    mockUseParams.mockReturnValue({})
    render(<ProjectViewChrome />)

    expect(screen.queryByRole('navigation', { name: '프로젝트 뷰 전환' })).not.toBeInTheDocument()
  })

  it('projectKey 가 빈 문자열이면 렌더하지 않는다', () => {
    mockUseParams.mockReturnValue({ projectKey: '' })
    render(<ProjectViewChrome />)

    expect(screen.queryByRole('navigation', { name: '프로젝트 뷰 전환' })).not.toBeInTheDocument()
  })
})

describe('ProjectViewChrome — 보드 목록 조회 비용', () => {
  it('`?board=` 가 없으면 빈 키를 넘겨 쿼리를 끈다', () => {
    // `useBoards` 의 `enabled: projectKey.length > 0` 를 인자로 끈다. 이걸 안 하면 프로젝트
    // 하위 20여 화면 전부가 열릴 때마다 보드 목록을 부른다.
    render(<ProjectViewChrome />)

    expect(mockUseBoards).toHaveBeenCalledWith('')
  })

  it('`?board=` 가 있으면 그 프로젝트의 보드 목록을 묻는다', () => {
    mockUseSearch.mockReturnValue({ board: 'b-scrum' })
    render(<ProjectViewChrome />)

    expect(mockUseBoards).toHaveBeenCalledWith('ATLAS')
  })
})

describe('ProjectViewChrome — 편차 X7 스코프 승계', () => {
  it('스크럼 보드면 보드·백로그 두 탭이 모두 스코프를 받는다', () => {
    mockUseSearch.mockReturnValue({ board: 'b-scrum' })
    render(<ProjectViewChrome />)

    expect(lastProps()['boardScope']).toEqual({
      board: { board: 'b-scrum' },
      backlog: { board: 'b-scrum' },
    })
  })

  it('칸반 보드면 보드 탭만 받는다 — 백로그는 스크럼 전용이다', () => {
    mockUseSearch.mockReturnValue({ board: 'b-kanban' })
    render(<ProjectViewChrome />)

    expect(lastProps()['boardScope']).toEqual({
      board: { board: 'b-kanban' },
      backlog: undefined,
    })
  })

  it('보드 목록이 아직 없으면 백로그 탭은 받지 않는다 (종류를 모른다)', () => {
    mockUseSearch.mockReturnValue({ board: 'b-scrum' })
    mockUseBoards.mockReturnValue({ data: undefined })
    render(<ProjectViewChrome />)

    expect(lastProps()['boardScope']).toEqual({
      board: { board: 'b-scrum' },
      backlog: undefined,
    })
  })

  it('`?board=` 가 없으면 두 탭 다 스코프가 없다', () => {
    render(<ProjectViewChrome />)

    expect(lastProps()['boardScope']).toEqual({ board: undefined, backlog: undefined })
  })

  it('현재 pathname 을 그대로 넘긴다 — 오버플로 핀 고정의 재료다', () => {
    render(<ProjectViewChrome />)

    expect(lastProps()['pathname']).toBe('/projects/ATLAS/board')
    expect(lastProps()['projectKey']).toBe('ATLAS')
  })
})
