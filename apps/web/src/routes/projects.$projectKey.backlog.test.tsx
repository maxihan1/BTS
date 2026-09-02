// 백로그 라우트 페이지 단위 테스트 — RouteAdapter useParams/useSearch 추출 + BacklogPage canManage·URL 필터 전달 + 보드 스위처
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { act, render, screen, waitFor, within } from '@testing-library/react'
import { userEvent } from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { useAuthStore } from '@/auth/authStore'
import type { BoardSummary } from '@/api/boards'
import { boardLabels } from '@/i18n/board-labels'
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
    boardId,
    canManageSprint,
    canReorderIssue,
    filter,
    onFilterChange,
  }: {
    projectKey: string
    boardId: string | undefined
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
        // ★`?board=` 가 보드까지 흘러가는지를 재는 유일한 통로다 — 문자열로 굳혀 두지 않으면
        //   어댑터가 board 를 통째로 버려도 이 파일이 초록으로 남는다 (FR-BD-04)
        data-board-id={boardId ?? ''}
        data-can-manage-sprint={String(canManageSprint ?? true)}
        data-can-reorder-issue={String(canReorderIssue ?? true)}
        data-filter={JSON.stringify(filter)}
      >
        backlog-board-mock
      </div>
    )
  },
}))

/** FR-BD-04 — 보드 스위처가 고르는 두 보드. 스크럼·칸반 두 종류를 함께 둔다 */
const BOARD_A: BoardSummary = {
  boardId: '00000000-0000-4000-8000-0000000000b1',
  projectKey: 'ATLAS',
  name: '스프린트 보드 A',
  boardType: 'SCRUM',
}
const BOARD_B: BoardSummary = {
  boardId: '00000000-0000-4000-8000-0000000000b2',
  projectKey: 'ATLAS',
  name: '스프린트 보드 B',
  boardType: 'SCRUM',
}
/**
 * FR-BD-04 — 같은 프로젝트에 실재하는 **칸반** 보드.
 *
 * 백로그 탭은 칸반에 없다(편차 X4). 이 보드가 스위처에 뜨면 사용자는 백로그에서 칸반을 고를 수
 * 있고, 거기서 만든 스프린트는 `boardId=<칸반>` 으로 붙는다 — 그런데 서버 `getBoard` 는
 * `boardType == SCRUM` 일 때만 활성 스프린트를 조회하므로 **그 스프린트는 어느 보드 화면에도
 * 영원히 안 나타난다**(엣지 E3 — 「칸반 완전 무변경」).
 */
const BOARD_KANBAN: BoardSummary = {
  boardId: '00000000-0000-4000-8000-0000000000b3',
  projectKey: 'ATLAS',
  name: '칸반 보드 C',
  boardType: 'KANBAN',
}

/** `useBoards` 가 돌려줄 목록. 「보드 0개」를 재는 테스트가 이 값을 비운다 */
let mockBoards: BoardSummary[] | undefined = [BOARD_A, BOARD_B]

// 보드 목록은 실제 스위처(`BoardSelectorDropdown`)가 소비한다 — 스위처는 **mock 하지 않는다**.
// 「생성 항목이 없다」는 그 컴포넌트의 실제 렌더로만 확인되고(showCreate=false), mock 으로
// 바꾸면 prop 을 넘겼다는 사실만 남아 계약이 화면에서 증발해도 초록이 된다.
vi.mock('@/hooks/use-boards', () => ({
  useBoards: () => ({ data: mockBoards, isLoading: false, isError: false }),
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

function renderPage(
  projectKey = 'ATLAS',
  filter: BacklogFilter = emptyBacklogFilter(),
  boardId: string | undefined = undefined,
) {
  return render(
    <QueryClientProvider client={makeClient()}>
      <BacklogPage projectKey={projectKey} boardId={boardId} filter={filter} />
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

// ─────────────────────────────────────────────────────────────────────────────
// FR-BD-04 — 보드 스코프 `?board=` + 보드 스위처 (E5 · E6)
//
// 백로그는 프로젝트가 아니라 **보드**에 속한다(J14). URL 의 board 축은 서버 쿼리이고
// 필터 3축은 클라이언트 필터라 **성격이 다르지만 같은 주소에 산다** — 한쪽을 바꿀 때
// 다른 쪽을 떨어뜨리지 않는 것이 이 절의 계약 전부다.
// ─────────────────────────────────────────────────────────────────────────────

/** 스위처 트리거 — 접근명이 「보드 선택, 현재 …」이라 정규식으로 잡는다 (e2e `board-manage.spec.ts:54` 선례) */
function boardSwitcherTrigger(): HTMLElement {
  return screen.getByRole('button', { name: /보드 선택/ })
}

describe('BacklogPage — 보드 스코프 `?board=` (FR-BD-04)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockUseSearch.mockReturnValue({})
    mockBoards = [BOARD_A, BOARD_B]
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

  /** T-BD04-RT-1. URL 의 `?board=` 가 어댑터를 지나 보드까지 그대로 간다 */
  it('T-BD04-RT-1: URL 의 board 가 BacklogBoard 로 전달된다', () => {
    mockUseSearch.mockReturnValue({ board: BOARD_B.boardId })

    renderAdapter()

    expect(screen.getByTestId('backlog-board')).toHaveAttribute(
      'data-board-id',
      BOARD_B.boardId,
    )
  })

  /**
   * T-BD04-RT-1b (E4). `?board=` 없이 들어오면 **URL 을 고치지 않는다** (편차 X6).
   *
   * 프론트가 기본 보드를 골라 주소에 써 넣으면 nav 링크·공유 링크의 모양이 달라진다 —
   * `project-tree.spec.ts:38,39` · `project-switcher.spec.ts:93,97` 가 그 href 를 글자로 잰다.
   */
  it('T-BD04-RT-1b(E4): board 없이 진입하면 URL 을 고치지 않는다', () => {
    renderAdapter()

    expect(screen.getByTestId('backlog-board')).toHaveAttribute('data-board-id', '')
    expect(mockNavigate).not.toHaveBeenCalled()
  })

  /**
   * ★T-BD04-RT-2 (E5). 필터를 바꿔도 `?board=` 가 URL 에 **남는다**.
   *
   * 종전 구현은 `filterToSearch(next)` 로 search 를 통째 교체해 board 를 증발시켰다.
   * 그러면 사용자는 필터를 한 번 건드리는 것만으로 모르는 사이 **기본 보드**로 갈아탄다.
   */
  it('★T-BD04-RT-2(E5): 필터를 바꿔도 board 가 URL 에 남는다', () => {
    mockUseSearch.mockReturnValue({ board: BOARD_B.boardId })

    renderAdapter()
    act(() => {
      capturedOnFilterChange?.({
        query: '로그인',
        assigneeIds: ['11111111-1111-4111-8111-111111111111'],
        includeUnassigned: true,
        epicKeys: ['ATLAS-100'],
      })
    })

    const search = lastNavigateArg()['search'] as Record<string, unknown>
    expect(search).toEqual({
      board: BOARD_B.boardId,
      q: '로그인',
      assignee: ['11111111-1111-4111-8111-111111111111', 'unassigned'],
      epic: ['ATLAS-100'],
    })
    // ★키 순서가 계약이다 — `isSameSearch` 가 `JSON.stringify` 비교라 순서가 흔들리면
    //   같은 URL 을 다르다고 읽어 무한 왕복 가드(T-BL-F4)가 통째로 무력해진다.
    expect(Object.keys(search)).toEqual(['board', 'q', 'assignee', 'epic'])
  })

  /** T-BD04-RT-2b (E5 짝). 초기화해도 board 만 남는다 — 빈 필터 키는 안 남는다 */
  it('T-BD04-RT-2b(E5): 필터를 초기화해도 board 는 남고 빈 필터 키는 안 남는다', () => {
    mockUseSearch.mockReturnValue({ board: BOARD_B.boardId, q: '로그인' })

    renderAdapter()
    act(() => {
      capturedOnFilterChange?.(emptyBacklogFilter())
    })

    expect(lastNavigateArg()['search']).toEqual({ board: BOARD_B.boardId })
  })

  /**
   * ★T-BD04-RT-3 (E6). 보드를 바꿔도 필터 3축이 **유지**된다.
   *
   * 담당자·에픽은 보드와 독립된 축이다. 보드를 바꿨다고 조건까지 풀리면 사용자는 방금 세운
   * 조건을 보드마다 다시 세워야 한다.
   */
  it('★T-BD04-RT-3(E6): 보드를 바꿔도 필터 3축이 유지된다', async () => {
    const user = userEvent.setup()
    mockUseSearch.mockReturnValue({
      board: BOARD_A.boardId,
      q: '로그인',
      assignee: ['11111111-1111-4111-8111-111111111111', 'unassigned'],
      epic: ['ATLAS-100'],
    })

    renderAdapter()

    await user.click(boardSwitcherTrigger())
    await user.click(await screen.findByRole('menuitemradio', { name: BOARD_B.name }))

    expect(mockNavigate).toHaveBeenCalledWith({
      to: '/projects/$projectKey/backlog',
      params: { projectKey: 'ATLAS' },
      search: {
        board: BOARD_B.boardId,
        q: '로그인',
        assignee: ['11111111-1111-4111-8111-111111111111', 'unassigned'],
        epic: ['ATLAS-100'],
      },
    })
  })

  /**
   * T-BD04-RT-4. 백로그의 스위처에는 「새 보드」가 **없다** (`showCreate={false}`).
   *
   * CREATE 권한은 이 파일의 목이 true 로 주고 있으므로 「권한이 없어서 사라진 것」과
   * 구별된다 — 축이 다르다는 것이 요점이다 (`BoardSelectorDropdown` T-BD04-SEL-4 와 같은 결).
   */
  it('T-BD04-RT-4: 백로그 스위처에는 「새 보드」 항목이 없다 (보드 목록은 그대로 나온다)', async () => {
    const user = userEvent.setup()
    mockUseSearch.mockReturnValue({ board: BOARD_A.boardId })

    renderAdapter()
    await user.click(boardSwitcherTrigger())

    expect(await screen.findByRole('menuitemradio', { name: BOARD_A.name })).toBeInTheDocument()
    expect(screen.getByRole('menuitemradio', { name: BOARD_B.name })).toBeInTheDocument()
    expect(
      screen.queryByRole('menuitem', { name: boardLabels.switcher.createItem }),
    ).not.toBeInTheDocument()
  })

  /** T-BD04-RT-5. 같은 보드를 다시 골라도 navigate 하지 않는다 — 무의미한 히스토리·재조회 방지 */
  it('T-BD04-RT-5: 같은 보드를 다시 고르면 navigate 하지 않는다', async () => {
    const user = userEvent.setup()
    mockUseSearch.mockReturnValue({ board: BOARD_A.boardId })

    renderAdapter()
    await user.click(boardSwitcherTrigger())
    await user.click(await screen.findByRole('menuitemradio', { name: BOARD_A.name }))

    expect(mockNavigate).not.toHaveBeenCalled()
  })

  /** T-BD04-RT-6. 보드가 0개면 스위처를 아예 그리지 않는다 — 고를 것이 없는 빈 드롭다운 금지 */
  it('T-BD04-RT-6: 보드가 0개면 스위처가 없다', () => {
    mockBoards = []

    renderAdapter()

    expect(screen.queryByRole('button', { name: /보드 선택/ })).not.toBeInTheDocument()
    // 화면 자체는 그대로다 — 스위처가 없다고 백로그가 사라지지는 않는다
    expect(screen.getByTestId('backlog-board')).toBeInTheDocument()
  })

  /**
   * ★T-BD04-RT-7 (X4 · E3). 백로그 스위처는 **스크럼 보드만** 고르게 한다.
   *
   * 칸반을 고를 수 있으면 `?board=<칸반>` 으로 백로그가 열리고, 거기서 만든 스프린트는
   * `boardId=<칸반>` 으로 붙는다. 서버 `getBoard` 는 SCRUM 일 때만 활성 스프린트를 조회하므로
   * 그 스프린트는 **어느 보드 화면에도 안 나타난다** — 편차 X4(칸반은 백로그 탭 없이 간다)와
   * 엣지 E3(칸반 완전 무변경)를 동시에 깬다.
   *
   * ★「칸반이 없다」가 이 테스트의 핵심이다. 「스크럼이 있다」만 재면 필터를 통째로 지워도 초록이다.
   */
  it('★T-BD04-RT-7(X4·E3): 칸반 보드는 스위처 목록에 없고 스크럼 보드만 남는다', async () => {
    const user = userEvent.setup()
    mockBoards = [BOARD_A, BOARD_KANBAN]
    mockUseSearch.mockReturnValue({ board: BOARD_A.boardId })

    renderAdapter()
    await user.click(boardSwitcherTrigger())

    // ② 스크럼 보드는 있다
    expect(await screen.findByRole('menuitemradio', { name: BOARD_A.name })).toBeInTheDocument()
    // ★① 칸반 보드는 없다
    expect(
      screen.queryByRole('menuitemradio', { name: BOARD_KANBAN.name }),
    ).not.toBeInTheDocument()
  })

  /**
   * T-BD04-RT-8 (X4). 스크럼 보드가 0개면 스위처를 그리지 않는다 — 칸반만 있는 프로젝트.
   *
   * T-BD04-RT-6(보드 자체가 0개)과 다른 축이다. 보드는 실재하는데 **고를 수 있는 것이 없는**
   * 상태이고, 종류로 좁힌 뒤에도 「빈 드롭다운 금지」 분기가 그대로 받는지를 잰다.
   */
  it('T-BD04-RT-8(X4): 칸반 보드만 있으면 스위처가 없다', () => {
    mockBoards = [BOARD_KANBAN]

    renderAdapter()

    expect(screen.queryByRole('button', { name: /보드 선택/ })).not.toBeInTheDocument()
    // 화면 자체는 그대로다 — 스위처가 없다고 백로그가 사라지지는 않는다
    expect(screen.getByTestId('backlog-board')).toBeInTheDocument()
  })
})
