// 백로그 라우트 페이지 단위 테스트 — RouteAdapter useParams/useSearch 추출 + BacklogPage canManage·URL 필터 전달
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useAuthStore } from '@/auth/authStore'
import { emptyBacklogFilter } from '@/lib/backlog-filter'
import type { BacklogFilter } from '@/lib/backlog-filter'
import {
  BacklogRouteAdapter,
  BacklogPage,
} from '@/routes/projects.$projectKey.backlog'

// TanStack Router mock — RouteAdapter 단위 테스트용.
//
// ★`useSearch`·`useNavigate` 는 팩토리 **안에서 직접 참조하면 TDZ 로 죽는다**(vi.mock 은
//   import 보다 위로 끌어올려진다). 반환 함수 안에서 **지연 호출**해야 한다
//   (`routes/__tests__/admin.slack.test.tsx` 선례).
const mockNavigate = vi.fn()
const mockUseSearch = vi.fn((): Record<string, unknown> => ({}))

vi.mock('@tanstack/react-router', () => ({
  useParams: () => ({ projectKey: 'ATLAS' }),
  useSearch: () => mockUseSearch(),
  useNavigate: () => mockNavigate,
  Link: ({
    to,
    params,
    children,
    className,
  }: {
    to: string
    params?: Record<string, string>
    children: React.ReactNode
    className?: string
  }) => {
    // params 치환: $projectKey → 실제 값 (TanStack Router 동작 모사)
    const resolvedTo = params !== undefined
      ? Object.entries(params).reduce(
          (acc, [key, val]) => acc.replace(`$${key}`, val),
          to,
        )
      : to
    return <a href={resolvedTo} className={className}>{children}</a>
  },
}))

/**
 * 제어형 `BacklogBoard` 가 받은 필터 변경 콜백.
 *
 * 라우트가 그 호출을 URL 로 옮기는지 재는 **유일한 통로**다 — mock 이 이 prop 을 버리면
 * 배선을 통째로 삭제해도 이 파일이 초록으로 남는다 (`BacklogBoard.test.tsx:66` 함정).
 */
let capturedOnFilterChange: ((next: BacklogFilter) => void) | undefined

// BacklogBoard는 별도 통합 테스트에서 검증하므로 라우트 단위 테스트에서 격리
vi.mock('@/components/backlog/BacklogBoard', () => ({
  BacklogBoard: ({
    projectKey,
    canManageSprint,
    canReorderIssue,
    filter,
    onFilterChange,
  }: {
    projectKey: string
    canManageSprint?: boolean
    canReorderIssue?: boolean
    filter: BacklogFilter
    onFilterChange: (next: BacklogFilter) => void
  }) => {
    capturedOnFilterChange = onFilterChange
    return (
      <div
        data-testid="backlog-board"
        data-project-key={projectKey}
        data-can-manage-sprint={String(canManageSprint ?? true)}
        data-can-reorder-issue={String(canReorderIssue ?? true)}
        data-filter={JSON.stringify(filter)}
      >
        backlog-board-mock
      </div>
    )
  },
}))

// useProjectPermissions mock — 기본값 CREATE=true, UPDATE=true (ADMIN 역할)
vi.mock('@/hooks/use-project-permissions', () => ({
  useProjectPermissions: () => ({
    data: {
      projectKey: 'ATLAS',
      permissions: {
        CREATE: true,
        UPDATE: true,
        MANAGE_COMPONENTS: true,
        MANAGE_VERSIONS: true,
        MANAGE_CUSTOM_FIELDS: true,
        MANAGE_FIELD_PERMISSIONS: true,
        MANAGE_TEMPLATES: true,
      },
    },
    isLoading: false,
    isError: false,
  }),
}))

function makeClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
}

function renderPage(projectKey = 'ATLAS', filter: BacklogFilter = emptyBacklogFilter()) {
  return render(
    <QueryClientProvider client={makeClient()}>
      <BacklogPage projectKey={projectKey} filter={filter} />
    </QueryClientProvider>,
  )
}

function renderAdapter() {
  return render(
    <QueryClientProvider client={makeClient()}>
      <BacklogRouteAdapter />
    </QueryClientProvider>,
  )
}

describe('BacklogPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useAuthStore.setState({
      accessToken: 'mock-access-token-alice',
      user: {
        userId: '00000000-0000-4000-8000-000000000001',
        username: 'alice',
        email: 'alice@example.com',
        authMethod: 'local',
        mustChangePassword: false,
        isSystemAdmin: false,
        mfaEnrollmentRequired: false,
      },
    })
  })

  afterEach(() => {
    useAuthStore.setState({ accessToken: null, user: null })
  })

  /**
   * T-BL-R1. BacklogPage가 BacklogBoard를 렌더하고 projectKey를 전달한다.
   */
  it('T-BL-R1: BacklogPage가 BacklogBoard에 projectKey를 전달한다', () => {
    renderPage('ATLAS')

    const board = screen.getByTestId('backlog-board')
    expect(board).toBeInTheDocument()
    expect(board).toHaveAttribute('data-project-key', 'ATLAS')
  })

  /**
   * T-BL-R2. CREATE 권한이 있으면 canManageSprint=true를 BacklogBoard에 전달한다.
   */
  it('T-BL-R2: CREATE 권한이 있으면 canManageSprint=true를 전달한다', () => {
    renderPage('ATLAS')

    const board = screen.getByTestId('backlog-board')
    expect(board).toHaveAttribute('data-can-manage-sprint', 'true')
  })

  /**
   * T-BL-R2b. UPDATE 권한이 있으면 canReorderIssue=true를 BacklogBoard에 전달한다.
   */
  it('T-BL-R2b: UPDATE 권한이 있으면 canReorderIssue=true를 전달한다', () => {
    renderPage('ATLAS')

    const board = screen.getByTestId('backlog-board')
    expect(board).toHaveAttribute('data-can-reorder-issue', 'true')
  })

  /**
   * T-BL-R3. Page가 "백로그" h1 헤더를 렌더한다.
   */
  it('T-BL-R3: BacklogPage가 "백로그" 헤더를 렌더한다', () => {
    renderPage('ATLAS')

    expect(screen.getByRole('heading', { level: 1, name: '백로그' })).toBeInTheDocument()
  })

  /**
   * T-BL-R4. 보드로 이동하는 링크가 존재한다.
   */
  it('T-BL-R4: 보드 링크가 존재한다', () => {
    renderPage('ATLAS')

    const boardLink = screen.getByRole('link', { name: '보드' })
    expect(boardLink).toBeInTheDocument()
    expect(boardLink).toHaveAttribute('href', '/projects/ATLAS/board')
  })

  /**
   * T-BL-R4b. 타임라인으로 이동하는 링크가 nav에 존재한다.
   */
  it('T-BL-R4b: 타임라인 링크가 nav에 존재한다', () => {
    renderPage('ATLAS')

    const nav = screen.getByRole('navigation', { name: '프로젝트 뷰 전환' })
    const timelineLink = within(nav).getByRole('link', { name: '타임라인' })
    expect(timelineLink).toBeInTheDocument()
    expect(timelineLink).toHaveAttribute('href', '/projects/ATLAS/timeline')
  })

  /**
   * T-BL-R4c. 벨로시티 보고로 이동하는 링크가 nav에 존재한다 (FR-RP-02 D6/D7 Task 5).
   */
  it('T-BL-R4c: 벨로시티 링크가 nav에 존재한다', () => {
    renderPage('ATLAS')

    const nav = screen.getByRole('navigation', { name: '프로젝트 뷰 전환' })
    const velocityLink = within(nav).getByRole('link', { name: '벨로시티' })
    expect(velocityLink).toBeInTheDocument()
    expect(velocityLink).toHaveAttribute('href', '/projects/ATLAS/reports/velocity')
  })

  /**
   * T-BL-R4d. 누적 흐름도(CFD) 보고로 이동하는 링크가 nav에 존재한다 (FR-RP-03 D6/D7 Task 7).
   */
  it('T-BL-R4d: 누적 흐름도 링크가 nav에 존재한다', () => {
    renderPage('ATLAS')

    const nav = screen.getByRole('navigation', { name: '프로젝트 뷰 전환' })
    const cfdLink = within(nav).getByRole('link', { name: '누적 흐름도' })
    expect(cfdLink).toBeInTheDocument()
    expect(cfdLink).toHaveAttribute('href', '/projects/ATLAS/reports/cfd')
  })
})

describe('BacklogRouteAdapter', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    useAuthStore.setState({
      accessToken: 'mock-access-token-alice',
      user: {
        userId: '00000000-0000-4000-8000-000000000001',
        username: 'alice',
        email: 'alice@example.com',
        authMethod: 'local',
        mustChangePassword: false,
        isSystemAdmin: false,
        mfaEnrollmentRequired: false,
      },
    })
  })

  afterEach(() => {
    useAuthStore.setState({ accessToken: null, user: null })
  })

  /**
   * T-BL-R5. RouteAdapter가 useParams에서 $projectKey를 추출해 BacklogPage에 전달한다.
   */
  it('T-BL-R5: RouteAdapter가 useParams $projectKey를 BacklogPage에 전달한다', async () => {
    renderAdapter()

    await waitFor(() => {
      const board = screen.getByTestId('backlog-board')
      expect(board).toBeInTheDocument()
      expect(board).toHaveAttribute('data-project-key', 'ATLAS')
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// URL search ↔ 필터 왕복 (FR-UX-13 F16 · F16-9)
//
// ★상태 소유자는 **URL 한 곳**이다. `BacklogBoard` 는 제어형이라 필터 state 를 갖지 않고,
//   여기서 재는 것은 「URL → 필터」와 「필터 → URL」 두 방향의 배선뿐이다.
// ─────────────────────────────────────────────────────────────────────────────

/** mock `BacklogBoard` 가 받은 필터를 되읽는다 — 「URL 이 초기 필터가 되는가」의 관측 지점 */
function readFilterFromBoard(): BacklogFilter {
  const raw = screen.getByTestId('backlog-board').getAttribute('data-filter')
  expect(raw).not.toBeNull()
  return JSON.parse(raw ?? 'null') as BacklogFilter
}

/** 마지막 `navigate` 호출의 인자 — `search` 키 잔존까지 보려고 통째로 꺼낸다 */
function lastNavigateArg(): Record<string, unknown> {
  const call = mockNavigate.mock.calls.at(-1)
  expect(call).toBeDefined()
  return (call?.[0] ?? {}) as Record<string, unknown>
}

describe('BacklogPage — URL search 필터 배선 (FR-UX-13 F16 F16-9)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockUseSearch.mockReturnValue({})
    capturedOnFilterChange = undefined
    useAuthStore.setState({
      accessToken: 'mock-access-token-alice',
      user: {
        userId: '00000000-0000-4000-8000-000000000001',
        username: 'alice',
        email: 'alice@example.com',
        authMethod: 'local',
        mustChangePassword: false,
        isSystemAdmin: false,
        mfaEnrollmentRequired: false,
      },
    })
  })

  afterEach(() => {
    useAuthStore.setState({ accessToken: null, user: null })
  })

  it('T-BL-F1: URL 의 q·assignee·epic 이 초기 필터가 된다 (미배정 센티널 포함)', () => {
    mockUseSearch.mockReturnValue({
      q: '로그인',
      assignee: ['11111111-1111-4111-8111-111111111111', 'unassigned'],
      epic: ['ATLAS-100'],
    })

    renderAdapter()

    expect(readFilterFromBoard()).toEqual({
      query: '로그인',
      assigneeIds: ['11111111-1111-4111-8111-111111111111'],
      includeUnassigned: true,
      epicKeys: ['ATLAS-100'],
    })
  })

  it('T-BL-F1b: 단일 문자열 파라미터도 배열 축으로 정규화된다', () => {
    mockUseSearch.mockReturnValue({ epic: 'ATLAS-100', assignee: 'unassigned' })

    renderAdapter()

    expect(readFilterFromBoard()).toEqual({
      query: '',
      assigneeIds: [],
      includeUnassigned: true,
      epicKeys: ['ATLAS-100'],
    })
  })

  it('T-BL-F2: 필터 변경이 navigate 로 URL 에 반영된다 (미배정 → assignee=unassigned)', () => {
    renderAdapter()

    act(() => {
      capturedOnFilterChange?.({
        query: '로그인',
        assigneeIds: ['11111111-1111-4111-8111-111111111111'],
        includeUnassigned: true,
        epicKeys: ['ATLAS-100'],
      })
    })

    expect(mockNavigate).toHaveBeenCalledWith({
      to: '/projects/$projectKey/backlog',
      params: { projectKey: 'ATLAS' },
      search: {
        q: '로그인',
        assignee: ['11111111-1111-4111-8111-111111111111', 'unassigned'],
        epic: ['ATLAS-100'],
      },
      replace: true,
    })
  })

  it('★T-BL-F3: 초기화하면 URL 에 빈 파라미터가 남지 않는다 (?q=&epic= 잔존 금지)', () => {
    mockUseSearch.mockReturnValue({ q: '로그인', assignee: ['unassigned'], epic: ['ATLAS-100'] })

    renderAdapter()
    act(() => {
      capturedOnFilterChange?.(emptyBacklogFilter())
    })

    const search = lastNavigateArg()['search']
    // ★`toEqual({})` 은 `{ q: undefined }` 도 통과시킨다 — 키 자체가 없어야 URL 이 깨끗하다.
    //   그래서 값이 아니라 **키 목록**을 잰다.
    expect(Object.keys(search as Record<string, unknown>)).toHaveLength(0)
  })

  it('★T-BL-F4: URL 이 바뀌지 않는 변경은 navigate 하지 않는다 (공백 검색어 무한 왕복 차단)', () => {
    mockUseSearch.mockReturnValue({ epic: ['ATLAS-100'] })

    renderAdapter()
    // 공백만 있는 검색어는 `filterToSearch` 가 `q` 를 통째로 생략한다 → URL 은 그대로다.
    // 그런데도 navigate 하면 라우터가 새 search 객체를 만들고, 필터바의 디바운스가 다시
    // 같은 값을 올려 **무한 왕복**이 된다.
    act(() => {
      capturedOnFilterChange?.({
        query: '   ',
        assigneeIds: [],
        includeUnassigned: false,
        epicKeys: ['ATLAS-100'],
      })
    })

    expect(mockNavigate).not.toHaveBeenCalled()
  })

  it('★T-BL-F4 짝: 실제로 URL 이 바뀌는 변경은 그대로 navigate 한다', () => {
    mockUseSearch.mockReturnValue({ epic: ['ATLAS-100'] })

    renderAdapter()
    act(() => {
      capturedOnFilterChange?.({
        query: '로그인',
        assigneeIds: [],
        includeUnassigned: false,
        epicKeys: ['ATLAS-100'],
      })
    })

    expect(mockNavigate).toHaveBeenCalledTimes(1)
    expect(lastNavigateArg()['search']).toEqual({ q: '로그인', epic: ['ATLAS-100'] })
  })
})
