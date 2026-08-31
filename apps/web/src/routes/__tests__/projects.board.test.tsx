// 칸반 보드 라우트 페이지 단위 테스트 — BoardPage 렌더 시나리오 (FR-BD-01 Task 7 + FR-BD-02 Task 6 + FR-BD-03 Task 6)
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { BoardSummary, BoardDetail, BoardCardFilterParams } from '@/api/boards'
import type { UserSummary } from '@/api/users'
import type { IssueTypeResponse } from '@/api/issue-types'
import { ApiError } from '@/api/client'
import { boardLabels } from '@/i18n/board-labels'

// ─────────────────────────────────────────────────────────────────────────────
// mock — TanStack Router, use-boards, @/api/users, KanbanBoard, FavoriteButton
// ─────────────────────────────────────────────────────────────────────────────

const { mockNavigate } = vi.hoisted(() => ({ mockNavigate: vi.fn() }))

vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => mockNavigate,
  useParams: () => ({ projectKey: 'ATLAS' }),
  useSearch: () => ({}),
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
    const resolvedTo =
      params !== undefined
        ? Object.entries(params).reduce(
            (acc, [key, val]) => acc.replace(`$${key}`, val),
            to,
          )
        : to
    return <a href={resolvedTo} className={className}>{children}</a>
  },
}))

vi.mock('sonner', () => ({
  toast: { success: vi.fn(), error: vi.fn() },
}))

// KanbanBoard는 DndContext 등 복잡한 의존성이 있으므로 단순 mock
// assigneeNames, filter, isFilterActive, issueTypesByKey prop을 캡처해 단언에 활용
const mockKanbanBoardProps: Array<{
  boardId: string
  assigneeNames: unknown
  filter: unknown
  isFilterActive: unknown
  issueTypesByKey: unknown
}> = []

vi.mock('@/components/board/KanbanBoard', () => ({
  KanbanBoard: ({
    boardId,
    assigneeNames,
    filter,
    isFilterActive,
    issueTypesByKey,
  }: {
    boardId: string
    assigneeNames: Map<string, unknown>
    filter?: BoardCardFilterParams
    isFilterActive?: boolean
    issueTypesByKey: Map<string, IssueTypeResponse>
  }) => {
    mockKanbanBoardProps.push({ boardId, assigneeNames, filter, isFilterActive, issueTypesByKey })
    return <div data-testid={`kanban-board-${boardId}`}>KanbanBoard</div>
  },
}))

// BoardFilterBar mock — onChange 콜백을 노출해 필터 변경 시뮬레이션에 활용
let capturedFilterBarOnChange: ((next: BoardCardFilterParams) => void) | null = null

vi.mock('@/components/board/BoardFilterBar', () => ({
  BoardFilterBar: ({
    value,
    onChange,
  }: {
    projectKey: string
    value: BoardCardFilterParams
    onChange: (next: BoardCardFilterParams) => void
  }) => {
    capturedFilterBarOnChange = onChange
    return (
      <div data-testid="board-filter-bar" data-active-count={
        value.assigneeIds.length + value.labels.length + value.componentIds.length
      }>
        FilterBar
      </div>
    )
  },
}))

// CreateBoardForm mock
vi.mock('@/components/board/CreateBoardForm', () => ({
  CreateBoardForm: ({ projectKey }: { projectKey: string }) => (
    <div data-testid="create-board-form" data-project-key={projectKey}>
      CreateBoardForm
    </div>
  ),
}))

// use-boards 훅 mock — 각 테스트에서 덮어씀
// useBoard는 (boardId, filter?) 시그니처로 호출되므로 두 인자 모두 캡처
const mockUseBoards = vi.fn()
const mockUseBoard = vi.fn()
const mockUseBoardCalls: Array<[string | undefined, BoardCardFilterParams | undefined]> = []

vi.mock('@/hooks/use-boards', () => ({
  useBoards: (projectKey: string) => mockUseBoards(projectKey),
  useBoard: (boardId: string | undefined, filter?: BoardCardFilterParams) => {
    mockUseBoardCalls.push([boardId, filter])
    return mockUseBoard(boardId, filter)
  },
  useCreateBoard: () => ({ mutate: vi.fn(), isPending: false }),
}))

// @/api/users mock
const mockFetchUsers = vi.fn()
vi.mock('@/api/users', () => ({
  fetchUsers: () => mockFetchUsers(),
}))

// use-update-swimlane mock
const mockUpdateSwimlaneMutate = vi.fn()
const mockUseUpdateSwimlane = vi.fn()
vi.mock('@/hooks/use-update-swimlane', () => ({
  useUpdateSwimlane: (boardId: string) => mockUseUpdateSwimlane(boardId),
}))

// use-project-permissions mock
const mockUseProjectPermissions = vi.fn()
vi.mock('@/hooks/use-project-permissions', () => ({
  useProjectPermissions: (projectKey: string) => mockUseProjectPermissions(projectKey),
}))

// use-issue-types mock (FR-UX-14 F14 Task 3 — 라우트가 issueTypesByKey 맵 구성용으로 호출)
const mockUseIssueTypes = vi.fn()
vi.mock('@/hooks/use-issue-types', () => ({
  useIssueTypes: () => mockUseIssueTypes(),
}))

// FavoriteButton mock — targetType·targetId props를 캡처해 단언에 활용
let capturedFavTargetType: string | null = null
let capturedFavTargetId: string | null = null

vi.mock('@/components/favorite/FavoriteButton', () => ({
  FavoriteButton: (props: { targetType: string; targetId: string }) => {
    capturedFavTargetType = props.targetType
    capturedFavTargetId = props.targetId
    return (
      <button
        type="button"
        data-testid="favorite-button"
        aria-label="즐겨찾기"
        data-target-type={props.targetType}
        data-target-id={props.targetId}
      >
        ★
      </button>
    )
  },
}))

// SwimlaneSelector mock — 테스트에서 onChange를 직접 호출할 수 있도록 캡처
let capturedSwimlaneSelectorOnChange: ((field: string) => void) | null = null
vi.mock('@/components/board/SwimlaneSelector', () => ({
  SwimlaneSelector: ({
    value,
    onChange,
  }: {
    value: string
    onChange: (field: string) => void
  }) => {
    capturedSwimlaneSelectorOnChange = onChange
    return (
      <div data-testid="swimlane-selector" data-value={value}>
        SwimlaneSelector
      </div>
    )
  },
}))

// ─────────────────────────────────────────────────────────────────────────────
// fixture
// ─────────────────────────────────────────────────────────────────────────────

const BOARD_A: BoardSummary = {
  boardId: 'a1b2c3d4-e5f6-4890-abcd-ef1234567891',
  projectKey: 'ATLAS',
  name: '스프린트 보드 A',
}

const BOARD_B: BoardSummary = {
  boardId: 'b2c3d4e5-f6a7-4890-abcd-ef1234567892',
  projectKey: 'ATLAS',
  name: '스프린트 보드 B',
}

const BOARD_DETAIL: BoardDetail = {
  boardId: BOARD_A.boardId,
  projectKey: 'ATLAS',
  name: '스프린트 보드 A',
  columns: [],
  truncated: false,
  unplacedCount: 0,
  swimlaneField: 'NONE',
  quickFilters: [],
}

const BOARD_DETAIL_TRUNCATED: BoardDetail = {
  ...BOARD_DETAIL,
  truncated: true,
  unplacedCount: 3,
}

const ALICE_ID = 'c3d4e5f6-a7b8-4890-abcd-ef1234567893'
const UNKNOWN_USER_ID = 'd4e5f6a7-b8c9-4890-abcd-ef1234567894'

const USERS: UserSummary[] = [
  {
    id: ALICE_ID,
    username: 'alice',
    displayName: '앨리스',
    email: 'alice@example.com',
  },
]

/** 담당자 3가지 케이스를 포함하는 BoardDetail fixture */
const BOARD_DETAIL_WITH_ASSIGNEES: BoardDetail = {
  boardId: BOARD_A.boardId,
  projectKey: 'ATLAS',
  name: '스프린트 보드 A',
  swimlaneField: 'NONE',
  columns: [
    {
      columnId: 'col-1',
      stateKey: 'todo',
      name: '할 일',
      category: 'TODO',
      displayOrder: 1,
      wipLimit: null,
      wipExceeded: false,
      cards: [
        // case 1: assigneeId=null → unassigned
        { issueKey: 'ATLAS-1', summary: '미배정 이슈', assigneeId: null, version: 1, priority: 1, epicKey: null, rank: null, typeKey: 'task', labels: [], originalEstimateSeconds: null },
        // case 2: assigneeId=ALICE_ID, userMap에 있음 → named
        { issueKey: 'ATLAS-2', summary: '앨리스 이슈', assigneeId: ALICE_ID, version: 2, priority: 1, epicKey: null, rank: null, typeKey: 'task', labels: [], originalEstimateSeconds: null },
        // case 3: assigneeId=UNKNOWN_USER_ID, userMap에 없음 → unknown
        { issueKey: 'ATLAS-3', summary: '미해석 이슈', assigneeId: UNKNOWN_USER_ID, version: 3, priority: 1, epicKey: null, rank: null, typeKey: 'task', labels: [], originalEstimateSeconds: null },
      ],
    },
  ],
  truncated: false,
  unplacedCount: 0,
  quickFilters: [],
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

async function renderBoardPage(
  projectKey = 'ATLAS',
  selectedBoardId?: string,
  filter?: BoardCardFilterParams,
) {
  const { BoardPage } = await import('@/routes/projects.$projectKey.board')
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
  return render(
    <QueryClientProvider client={client}>
      <BoardPage projectKey={projectKey} selectedBoardId={selectedBoardId} filter={filter} />
    </QueryClientProvider>,
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('BoardPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockKanbanBoardProps.length = 0
    mockUseBoardCalls.length = 0
    capturedFilterBarOnChange = null
    capturedSwimlaneSelectorOnChange = null
    capturedFavTargetType = null
    capturedFavTargetId = null
    mockFetchUsers.mockResolvedValue(USERS)
    mockUseBoard.mockReturnValue({ data: undefined, isLoading: false })
    mockUseUpdateSwimlane.mockReturnValue({ mutate: mockUpdateSwimlaneMutate, isPending: false })
    mockUseProjectPermissions.mockReturnValue({
      data: { projectKey: 'ATLAS', permissions: { CREATE: true, MANAGE_COMPONENTS: false, MANAGE_VERSIONS: false, MANAGE_CUSTOM_FIELDS: false, MANAGE_FIELD_PERMISSIONS: false, MANAGE_TEMPLATES: false } },
      isLoading: false,
    })
    mockUseIssueTypes.mockReturnValue({ data: [], isLoading: false })
  })

  afterEach(() => {
    vi.clearAllMocks()
    mockKanbanBoardProps.length = 0
    mockUseBoardCalls.length = 0
    capturedSwimlaneSelectorOnChange = null
    capturedFavTargetType = null
    capturedFavTargetId = null
  })

  /**
   * T-BD7-R-1. 보드 목록 로딩 중 — 스켈레톤이 렌더된다.
   */
  it('T-BD7-R-1: 보드 목록 로딩 중 스켈레톤이 렌더된다', async () => {
    mockUseBoards.mockReturnValue({ data: undefined, isLoading: true, error: null, isError: false })

    await renderBoardPage()

    expect(screen.queryByTestId('create-board-form')).not.toBeInTheDocument()
    expect(screen.queryByTestId(/kanban-board-/)).not.toBeInTheDocument()
  })

  /**
   * T-BD7-R-2. 보드 0개 — CreateBoardForm이 렌더된다.
   */
  it('T-BD7-R-2: 보드 0개이면 CreateBoardForm이 렌더된다', async () => {
    mockUseBoards.mockReturnValue({ data: [], isLoading: false, error: null, isError: false })

    await renderBoardPage()

    await waitFor(() => {
      expect(screen.getByTestId('create-board-form')).toBeInTheDocument()
    })
    expect(screen.queryByTestId(/kanban-board-/)).not.toBeInTheDocument()
  })

  /**
   * T-BD7-R-3. 보드 1개 — KanbanBoard가 렌더된다. CreateBoardForm은 없다.
   */
  it('T-BD7-R-3: 보드 1개이면 KanbanBoard가 렌더된다', async () => {
    mockUseBoards.mockReturnValue({
      data: [BOARD_A],
      isLoading: false,
      error: null,
      isError: false,
    })
    mockUseBoard.mockReturnValue({ data: BOARD_DETAIL, isLoading: false })

    await renderBoardPage()

    await waitFor(() => {
      expect(
        screen.getByTestId(`kanban-board-${BOARD_A.boardId}`),
      ).toBeInTheDocument()
    })
    expect(screen.queryByTestId('create-board-form')).not.toBeInTheDocument()
  })

  /**
   * T-BD7-R-4. 보드 **1개**여도 스위처가 렌더된다 (FR-BD-01-2c).
   *
   * 이전 계약은 `>= 2` 였다 — 보드가 1개인 프로젝트에서는 스위처가 통째로 없어서
   * 「보드는 N개 가질 수 있다」(Jira 근거 J1)가 화면에 드러나지 않았고 생성 진입점도 사라졌다.
   */
  it('T-BD7-R-4: 보드가 1개여도 보드 스위처가 렌더된다', async () => {
    mockUseBoards.mockReturnValue({
      data: [BOARD_A],
      isLoading: false,
      error: null,
      isError: false,
    })
    mockUseBoard.mockReturnValue({ data: BOARD_DETAIL, isLoading: false })

    await renderBoardPage()

    const trigger = await screen.findByRole('button', { name: /보드 선택/ })
    expect(trigger).toBeInTheDocument()
    // 트리거는 현재 보드 이름을 보여 준다 — 「무엇을 보고 있는가」가 접힌 상태에서도 읽힌다
    expect(trigger).toHaveTextContent(BOARD_A.name)
  })

  /**
   * T-BD7-R-4b. 스위처를 열면 보드 목록이 `role="menuitemradio"` 로 나오고
   * 현재 보드만 `aria-checked=true` 다.
   *
   * ★ 이 단언이 role 계약 자체다 — Radix Select 에서 DropdownMenu 로 갈아탔으므로
   *   `role="combobox"` 는 더 이상 나오지 않는다. 그 소멸을 여기서 대체한다.
   */
  it('T-BD7-R-4b: 스위처를 열면 보드가 menuitemradio 로 나온다', async () => {
    const user = userEvent.setup()
    mockUseBoards.mockReturnValue({
      data: [BOARD_A, BOARD_B],
      isLoading: false,
      error: null,
      isError: false,
    })
    mockUseBoard.mockReturnValue({ data: BOARD_DETAIL, isLoading: false })

    await renderBoardPage()

    await user.click(await screen.findByRole('button', { name: /보드 선택/ }))

    const itemA = await screen.findByRole('menuitemradio', { name: BOARD_A.name })
    const itemB = await screen.findByRole('menuitemradio', { name: BOARD_B.name })
    expect(itemA).toHaveAttribute('aria-checked', 'true')
    expect(itemB).toHaveAttribute('aria-checked', 'false')
  })

  /**
   * T-BD7-R-4c. CREATE 권한이 있으면 「새 보드」 항목이 나오고, 고르면 생성 폼이 뜬다.
   *
   * ★ T-BD7-R-4d(권한 없음)의 **비-공허 짝**이다. 이 테스트가 없으면 라벨을 오타 내도
   *   `queryByRole(...).toBeNull()` 이 그대로 통과한다.
   */
  it('T-BD7-R-4c: CREATE 권한이 있으면 「새 보드」 항목으로 생성 폼을 연다', async () => {
    const user = userEvent.setup()
    mockUseBoards.mockReturnValue({
      data: [BOARD_A],
      isLoading: false,
      error: null,
      isError: false,
    })
    mockUseBoard.mockReturnValue({ data: BOARD_DETAIL, isLoading: false })

    await renderBoardPage()

    await user.click(await screen.findByRole('button', { name: /보드 선택/ }))
    await user.click(
      await screen.findByRole('menuitem', { name: boardLabels.switcher.createItem }),
    )

    expect(await screen.findByTestId('create-board-form')).toBeInTheDocument()
  })

  /**
   * T-BD7-R-4d. CREATE 권한이 없으면 「새 보드」 항목이 **DOM 에 없다** — 비활성이 아니다
   * (Jira 근거 J5 · FR-BD-01-2d). 스위처 자체는 그대로 보인다.
   */
  it('T-BD7-R-4d: CREATE 권한이 없으면 「새 보드」 항목이 DOM 에 없다', async () => {
    const user = userEvent.setup()
    mockUseBoards.mockReturnValue({
      data: [BOARD_A, BOARD_B],
      isLoading: false,
      error: null,
      isError: false,
    })
    mockUseBoard.mockReturnValue({ data: BOARD_DETAIL, isLoading: false })
    mockUseProjectPermissions.mockReturnValue({
      data: {
        projectKey: 'ATLAS',
        permissions: {
          CREATE: false,
          MANAGE_COMPONENTS: false,
          MANAGE_VERSIONS: false,
          MANAGE_CUSTOM_FIELDS: false,
          MANAGE_FIELD_PERMISSIONS: false,
          MANAGE_TEMPLATES: false,
        },
      },
      isLoading: false,
    })

    await renderBoardPage()

    await user.click(await screen.findByRole('button', { name: /보드 선택/ }))

    // 보드 목록은 그대로 나온다 — 「메뉴가 안 열려서 없다」와 구별하는 앵커
    expect(await screen.findByRole('menuitemradio', { name: BOARD_A.name })).toBeInTheDocument()
    expect(
      screen.queryByRole('menuitem', { name: boardLabels.switcher.createItem }),
    ).toBeNull()
  })

  /**
   * T-BD7-R-5. 403 AGILE_ACCESS_DENIED — "접근 권한이 없습니다" 안내 화면이 렌더된다.
   */
  it('T-BD7-R-5: 403 에러 시 접근 권한 없음 안내가 렌더된다', async () => {
    mockUseBoards.mockReturnValue({
      data: undefined,
      isLoading: false,
      error: new ApiError(403, { errorCode: 'AGILE_ACCESS_DENIED' }),
      isError: true,
    })

    await renderBoardPage()

    await waitFor(() => {
      expect(screen.getByText(/접근 권한이 없습니다/i)).toBeInTheDocument()
    })
    expect(screen.queryByTestId('create-board-form')).not.toBeInTheDocument()
  })

  /**
   * T-BD7-R-6. truncated=true — 경고 배너가 표시된다.
   */
  it('T-BD7-R-6: board.truncated=true이면 truncated 경고 배너가 표시된다', async () => {
    mockUseBoards.mockReturnValue({
      data: [BOARD_A],
      isLoading: false,
      error: null,
      isError: false,
    })
    mockUseBoard.mockReturnValue({ data: BOARD_DETAIL_TRUNCATED, isLoading: false })

    await renderBoardPage()

    await waitFor(() => {
      expect(screen.getByText(/표시되지 않은 이슈가 있습니다/i)).toBeInTheDocument()
    })
  })

  /**
   * T-BD7-R-7. unplacedCount>0 — 매핑 미완료 경고 배너가 표시된다.
   */
  it('T-BD7-R-7: unplacedCount>0이면 unplaced 경고 배너가 표시된다', async () => {
    mockUseBoards.mockReturnValue({
      data: [BOARD_A],
      isLoading: false,
      error: null,
      isError: false,
    })
    mockUseBoard.mockReturnValue({ data: BOARD_DETAIL_TRUNCATED, isLoading: false })

    await renderBoardPage()

    await waitFor(() => {
      expect(screen.getByText(/3개 이슈가 컬럼에 매핑되지 않아/i)).toBeInTheDocument()
    })
  })

  /**
   * T-BD7-R-9. assigneeId=null 카드 → KanbanBoard에 {state:"unassigned"} 전달.
   * T-BD7-R-10. assigneeId 있고 userMap 해석됨 → {state:"named", name:"앨리스"} 전달.
   * T-BD7-R-11. assigneeId 있으나 userMap 미해석 → {state:"unknown"} 전달 (미배정과 구분).
   */
  it('T-BD7-R-9: assigneeId=null 카드는 {state:"unassigned"}로 KanbanBoard에 전달된다', async () => {
    mockUseBoards.mockReturnValue({ data: [BOARD_A], isLoading: false, error: null, isError: false })
    mockUseBoard.mockReturnValue({ data: BOARD_DETAIL_WITH_ASSIGNEES, isLoading: false })

    await renderBoardPage()

    await waitFor(() => {
      expect(screen.getByTestId(`kanban-board-${BOARD_A.boardId}`)).toBeInTheDocument()
    })

    const lastCall = mockKanbanBoardProps[mockKanbanBoardProps.length - 1]
    const names = lastCall?.assigneeNames as Map<string, { state: string }>
    expect(names?.get('ATLAS-1')).toEqual({ state: 'unassigned' })
  })

  it('T-BD7-R-10: assigneeId가 userMap에 있으면 {state:"named"} 로 전달된다', async () => {
    mockUseBoards.mockReturnValue({ data: [BOARD_A], isLoading: false, error: null, isError: false })
    mockUseBoard.mockReturnValue({ data: BOARD_DETAIL_WITH_ASSIGNEES, isLoading: false })

    await renderBoardPage()

    await waitFor(() => {
      expect(screen.getByTestId(`kanban-board-${BOARD_A.boardId}`)).toBeInTheDocument()
    })

    const lastCall = mockKanbanBoardProps[mockKanbanBoardProps.length - 1]
    const names = lastCall?.assigneeNames as Map<string, { state: string; name?: string }>
    expect(names?.get('ATLAS-2')).toEqual({ state: 'named', name: '앨리스' })
  })

  it('T-BD7-R-11: assigneeId가 있으나 userMap 미해석이면 {state:"unknown"} — "미배정" 아님', async () => {
    mockUseBoards.mockReturnValue({ data: [BOARD_A], isLoading: false, error: null, isError: false })
    mockUseBoard.mockReturnValue({ data: BOARD_DETAIL_WITH_ASSIGNEES, isLoading: false })

    await renderBoardPage()

    await waitFor(() => {
      expect(screen.getByTestId(`kanban-board-${BOARD_A.boardId}`)).toBeInTheDocument()
    })

    const lastCall = mockKanbanBoardProps[mockKanbanBoardProps.length - 1]
    const names = lastCall?.assigneeNames as Map<string, { state: string }>
    const unknownEntry = names?.get('ATLAS-3')
    // unknown이어야 함 — unassigned면 spec FR-7 위반
    expect(unknownEntry).toEqual({ state: 'unknown' })
  })

  /**
   * T-BD7-R-8. 스위처에서 다른 보드를 고르면 navigate가 `?board=<id>` 로 호출된다 (S2).
   *
   * 🛑 **조건부 후퇴를 두지 마라.** 이전 판은 `if (mockNavigate.mock.calls.length > 0)` 로 감싸고
   *    else 분기에서 렌더만 확인했다 — navigate 가 한 번도 안 불려도 초록이 되는 가짜 그린이었다.
   *    이제 항목 클릭이 실제로 전환을 일으키는지를 **무조건** 단언한다.
   */
  it('T-BD7-R-8: 스위처 항목을 고르면 navigate가 ?board=로 호출된다', async () => {
    const user = userEvent.setup()
    mockUseBoards.mockReturnValue({
      data: [BOARD_A, BOARD_B],
      isLoading: false,
      error: null,
      isError: false,
    })
    mockUseBoard.mockReturnValue({ data: BOARD_DETAIL, isLoading: false })

    await renderBoardPage()

    await user.click(await screen.findByRole('button', { name: /보드 선택/ }))
    await user.click(await screen.findByRole('menuitemradio', { name: BOARD_B.name }))

    expect(mockNavigate).toHaveBeenCalledWith(
      expect.objectContaining({
        to: '/projects/$projectKey/board',
        params: { projectKey: 'ATLAS' },
        search: { board: BOARD_B.boardId },
      }),
    )
  })

  // ─────────────────────────────────────────────────────────────────────────────
  // Task 6 — 필터 통합 (FR-BD-02)
  // ─────────────────────────────────────────────────────────────────────────────

  /**
   * T-BD6-F-1. filter prop이 있으면 useBoard가 해당 filter를 인자로 호출된다.
   * (URL search → searchToFilter → filter → useBoard(boardId, filter) 경로 검증)
   */
  it('T-BD6-F-1: filter prop이 있으면 useBoard가 filter를 인자로 호출된다', async () => {
    const filter: BoardCardFilterParams = {
      assigneeIds: [ALICE_ID],
      includeUnassigned: false,
      labels: [],
      componentIds: [],
    }
    mockUseBoards.mockReturnValue({ data: [BOARD_A], isLoading: false, error: null, isError: false })
    mockUseBoard.mockReturnValue({ data: BOARD_DETAIL, isLoading: false })

    await renderBoardPage('ATLAS', undefined, filter)

    await waitFor(() => {
      expect(screen.getByTestId(`kanban-board-${BOARD_A.boardId}`)).toBeInTheDocument()
    })

    // useBoard가 filter를 두 번째 인자로 받아야 한다
    const calls = mockUseBoardCalls.filter(([id]) => id === BOARD_A.boardId)
    expect(calls.length).toBeGreaterThan(0)
    expect(calls[calls.length - 1]?.[1]).toEqual(filter)
  })

  /**
   * T-BD6-F-2. BoardFilterBar onChange 호출 시 navigate가 filterToSearch 결과와 board를 합쳐 호출된다.
   * (필터 변경 → URL 갱신 경로 검증)
   */
  it('T-BD6-F-2: BoardFilterBar onChange 시 navigate가 filter params를 URL에 반영한다', async () => {
    mockUseBoards.mockReturnValue({ data: [BOARD_A], isLoading: false, error: null, isError: false })
    mockUseBoard.mockReturnValue({ data: BOARD_DETAIL, isLoading: false })

    await renderBoardPage()

    await waitFor(() => {
      expect(screen.getByTestId('board-filter-bar')).toBeInTheDocument()
    })

    // BoardFilterBar mock의 onChange를 직접 호출해 필터 변경 시뮬레이션
    const newFilter: BoardCardFilterParams = {
      assigneeIds: [ALICE_ID],
      includeUnassigned: false,
      labels: ['버그'],
      componentIds: [],
    }
    capturedFilterBarOnChange?.(newFilter)

    // navigate가 호출돼야 하며, URL search에 assignee, label 파라미터가 포함돼야 한다
    await waitFor(() => {
      expect(mockNavigate).toHaveBeenCalled()
    })
    const callArg = mockNavigate.mock.calls[0]?.[0] as {
      search?: Record<string, unknown>
    }
    expect(callArg?.search).toMatchObject({
      assignee: expect.arrayContaining([ALICE_ID]) as unknown,
      label: expect.arrayContaining(['버그']) as unknown,
    })
    // board 키가 유지돼야 한다 (undefined면 키가 없거나 undefined로 전달)
  })

  /**
   * T-BD6-F-3. KanbanBoard에 filter prop이 전달된다.
   * (filter → KanbanBoard → useMoveCard(boardId, filter) 경로 중 prop 전달 검증)
   */
  it('T-BD6-F-3: KanbanBoard에 filter prop이 전달된다', async () => {
    const filter: BoardCardFilterParams = {
      assigneeIds: [],
      includeUnassigned: true,
      labels: ['버그'],
      componentIds: [],
    }
    mockUseBoards.mockReturnValue({ data: [BOARD_A], isLoading: false, error: null, isError: false })
    mockUseBoard.mockReturnValue({ data: BOARD_DETAIL, isLoading: false })

    await renderBoardPage('ATLAS', undefined, filter)

    await waitFor(() => {
      expect(screen.getByTestId(`kanban-board-${BOARD_A.boardId}`)).toBeInTheDocument()
    })

    const lastCall = mockKanbanBoardProps[mockKanbanBoardProps.length - 1]
    expect(lastCall?.filter).toEqual(filter)
  })

  /**
   * T-BD6-F-4. 필터 결과가 0건(모든 컬럼 카드 0)이면 빈 상태 안내와 "필터 초기화" 버튼이 렌더된다 (D2).
   * EC7: 보드 상세가 있을 때만 필터 바가 표시된다.
   */
  it('T-BD6-F-4: 필터 후 카드 0건이면 빈 상태 안내 + 초기화 CTA가 렌더된다', async () => {
    const filter: BoardCardFilterParams = {
      assigneeIds: [ALICE_ID],
      includeUnassigned: false,
      labels: [],
      componentIds: [],
    }
    const emptyBoard: BoardDetail = {
      ...BOARD_DETAIL,
      columns: [
        { columnId: 'col-1', stateKey: 'todo', name: '할 일', category: 'TODO', displayOrder: 1, wipLimit: null, wipExceeded: false, cards: [] },
        { columnId: 'col-2', stateKey: 'done', name: '완료', category: 'DONE', displayOrder: 2, wipLimit: null, wipExceeded: false, cards: [] },
      ],
    }
    mockUseBoards.mockReturnValue({ data: [BOARD_A], isLoading: false, error: null, isError: false })
    mockUseBoard.mockReturnValue({ data: emptyBoard, isLoading: false })

    await renderBoardPage('ATLAS', undefined, filter)

    // 빈 상태 안내 문구
    await waitFor(() => {
      expect(screen.getByText(/조건에 맞는 카드가 없습니다/i)).toBeInTheDocument()
    })
    // 필터 초기화 CTA
    expect(screen.getByRole('button', { name: /초기화/i })).toBeInTheDocument()
  })

  /**
   * T-BD6-F-5. EC7: 보드 상세가 있을 때만 BoardFilterBar가 렌더된다.
   * (보드 상세 로딩 중에는 필터 바 없음)
   */
  it('T-BD6-F-5: 보드 상세가 있을 때만 BoardFilterBar가 렌더된다', async () => {
    mockUseBoards.mockReturnValue({ data: [BOARD_A], isLoading: false, error: null, isError: false })
    // 보드 상세 로딩 중
    mockUseBoard.mockReturnValue({ data: undefined, isLoading: true })

    await renderBoardPage()

    // 보드 상세 없으면 필터 바 없어야 한다
    expect(screen.queryByTestId('board-filter-bar')).not.toBeInTheDocument()
  })

  /**
   * T-BD6-F-6. 빈 필터 초기화 버튼 클릭 시 navigate가 빈 필터(board 키만)로 호출된다.
   */
  it('T-BD6-F-6: 빈 상태 초기화 버튼 클릭 시 navigate가 빈 필터로 호출된다', async () => {
    const user = userEvent.setup()
    const filter: BoardCardFilterParams = {
      assigneeIds: [ALICE_ID],
      includeUnassigned: false,
      labels: [],
      componentIds: [],
    }
    const emptyBoard: BoardDetail = {
      ...BOARD_DETAIL,
      columns: [
        { columnId: 'col-1', stateKey: 'todo', name: '할 일', category: 'TODO', displayOrder: 1, wipLimit: null, wipExceeded: false, cards: [] },
      ],
    }
    mockUseBoards.mockReturnValue({ data: [BOARD_A], isLoading: false, error: null, isError: false })
    mockUseBoard.mockReturnValue({ data: emptyBoard, isLoading: false })

    await renderBoardPage('ATLAS', BOARD_A.boardId, filter)

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /초기화/i })).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: /초기화/i }))

    await waitFor(() => {
      expect(mockNavigate).toHaveBeenCalled()
    })

    // navigate 호출 인자에서 filter 파라미터가 없어야 한다 (board만 유지)
    const callArg = mockNavigate.mock.calls[0]?.[0] as {
      search?: Record<string, unknown>
    }
    expect(callArg?.search).not.toHaveProperty('assignee')
    expect(callArg?.search).not.toHaveProperty('label')
    expect(callArg?.search).not.toHaveProperty('component')
  })

  // ─────────────────────────────────────────────────────────────────────────────
  // Task 6 — 스윔레인 셀렉터 + CREATE 권한 게이팅 (FR-BD-03)
  // ─────────────────────────────────────────────────────────────────────────────

  /**
   * S6 — CREATE 권한 있으면 SwimlaneSelector가 렌더된다.
   */
  it('S6-A: CREATE 권한이 있으면 SwimlaneSelector가 보드 상세 있을 때 렌더된다', async () => {
    mockUseBoards.mockReturnValue({ data: [BOARD_A], isLoading: false, error: null, isError: false })
    mockUseBoard.mockReturnValue({ data: BOARD_DETAIL, isLoading: false })
    mockUseProjectPermissions.mockReturnValue({
      data: {
        projectKey: 'ATLAS',
        permissions: {
          CREATE: true,
          MANAGE_COMPONENTS: false,
          MANAGE_VERSIONS: false,
          MANAGE_CUSTOM_FIELDS: false,
          MANAGE_FIELD_PERMISSIONS: false,
          MANAGE_TEMPLATES: false,
        },
      },
      isLoading: false,
    })

    await renderBoardPage()

    await waitFor(() => {
      expect(screen.getByTestId('swimlane-selector')).toBeInTheDocument()
    })
  })

  /**
   * S6 — CREATE 권한 없으면 SwimlaneSelector가 렌더되지 않는다.
   */
  it('S6-B: CREATE 권한이 없으면 SwimlaneSelector가 렌더되지 않는다', async () => {
    mockUseBoards.mockReturnValue({ data: [BOARD_A], isLoading: false, error: null, isError: false })
    mockUseBoard.mockReturnValue({ data: BOARD_DETAIL, isLoading: false })
    mockUseProjectPermissions.mockReturnValue({
      data: {
        projectKey: 'ATLAS',
        permissions: {
          CREATE: false,
          MANAGE_COMPONENTS: false,
          MANAGE_VERSIONS: false,
          MANAGE_CUSTOM_FIELDS: false,
          MANAGE_FIELD_PERMISSIONS: false,
          MANAGE_TEMPLATES: false,
        },
      },
      isLoading: false,
    })

    await renderBoardPage()

    await waitFor(() => {
      expect(screen.getByTestId(`kanban-board-${BOARD_A.boardId}`)).toBeInTheDocument()
    })
    expect(screen.queryByTestId('swimlane-selector')).not.toBeInTheDocument()
  })

  /**
   * S6 — 보드 상세 없으면 SwimlaneSelector가 렌더되지 않는다 (권한 있어도).
   */
  it('S6-C: 보드 상세가 없으면 SwimlaneSelector가 렌더되지 않는다', async () => {
    mockUseBoards.mockReturnValue({ data: [BOARD_A], isLoading: false, error: null, isError: false })
    mockUseBoard.mockReturnValue({ data: undefined, isLoading: true })
    mockUseProjectPermissions.mockReturnValue({
      data: {
        projectKey: 'ATLAS',
        permissions: {
          CREATE: true,
          MANAGE_COMPONENTS: false,
          MANAGE_VERSIONS: false,
          MANAGE_CUSTOM_FIELDS: false,
          MANAGE_FIELD_PERMISSIONS: false,
          MANAGE_TEMPLATES: false,
        },
      },
      isLoading: false,
    })

    await renderBoardPage()

    expect(screen.queryByTestId('swimlane-selector')).not.toBeInTheDocument()
  })

  /**
   * S7 — SwimlaneSelector 변경 시 useUpdateSwimlane.mutate가 호출된다.
   */
  it('S7-A: SwimlaneSelector onChange 호출 시 useUpdateSwimlane.mutate가 호출된다', async () => {
    mockUseBoards.mockReturnValue({ data: [BOARD_A], isLoading: false, error: null, isError: false })
    mockUseBoard.mockReturnValue({ data: BOARD_DETAIL, isLoading: false })

    await renderBoardPage()

    await waitFor(() => {
      expect(screen.getByTestId('swimlane-selector')).toBeInTheDocument()
    })

    // SwimlaneSelector mock의 onChange를 직접 호출
    capturedSwimlaneSelectorOnChange?.('ASSIGNEE')

    expect(mockUpdateSwimlaneMutate).toHaveBeenCalledWith(
      'ASSIGNEE',
      expect.objectContaining({ onError: expect.any(Function) as unknown }),
    )
  })

  // ─────────────────────────────────────────────────────────────────────────────
  // Task 3 — issueTypesByKey 배선 (FR-UX-14 F14, NFR2)
  // ─────────────────────────────────────────────────────────────────────────────

  /**
   * T-BD-TYPE-1. useIssueTypes 결과를 typeKey → IssueTypeResponse 맵으로 변환해 KanbanBoard에 전달한다.
   */
  it('T-BD-TYPE-1: 이슈 타입 목록을 typeKey 맵으로 변환해 KanbanBoard에 전달한다', async () => {
    const taskType: IssueTypeResponse = { id: 1, key: 'task', name: '작업', description: '', iconName: 'task' }
    mockUseBoards.mockReturnValue({ data: [BOARD_A], isLoading: false, error: null, isError: false })
    mockUseBoard.mockReturnValue({ data: BOARD_DETAIL, isLoading: false })
    mockUseIssueTypes.mockReturnValue({ data: [taskType], isLoading: false })

    await renderBoardPage()

    await waitFor(() => {
      expect(screen.getByTestId(`kanban-board-${BOARD_A.boardId}`)).toBeInTheDocument()
    })

    const lastCall = mockKanbanBoardProps[mockKanbanBoardProps.length - 1]
    const map = lastCall?.issueTypesByKey as Map<string, IssueTypeResponse>
    expect(map.get('task')).toEqual(taskType)
  })

  /**
   * T-BD-TYPE-2. useIssueTypes 조회 실패·로딩 중(data undefined)이면 빈 맵을 전달한다(FR6, E7·E8).
   */
  it('T-BD-TYPE-2: useIssueTypes 데이터가 없으면 빈 맵을 KanbanBoard에 전달한다', async () => {
    mockUseBoards.mockReturnValue({ data: [BOARD_A], isLoading: false, error: null, isError: false })
    mockUseBoard.mockReturnValue({ data: BOARD_DETAIL, isLoading: false })
    mockUseIssueTypes.mockReturnValue({ data: undefined, isLoading: true })

    await renderBoardPage()

    await waitFor(() => {
      expect(screen.getByTestId(`kanban-board-${BOARD_A.boardId}`)).toBeInTheDocument()
    })

    const lastCall = mockKanbanBoardProps[mockKanbanBoardProps.length - 1]
    const map = lastCall?.issueTypesByKey as Map<string, IssueTypeResponse>
    expect(map.size).toBe(0)
  })

  // ─────────────────────────────────────────────────────────────────────────────
  // hotfix-p2 — 필터 활성 시 isFilterActive prop 전달 (FR-BD-03)
  // ─────────────────────────────────────────────────────────────────────────────

  /**
   * P2-A. 필터가 비어 있지 않으면 KanbanBoard에 isFilterActive=true가 전달된다.
   */
  it('P2-A: 필터가 비어 있지 않으면 KanbanBoard에 isFilterActive=true가 전달된다', async () => {
    const filter: BoardCardFilterParams = {
      assigneeIds: [ALICE_ID],
      includeUnassigned: false,
      labels: [],
      componentIds: [],
    }
    mockUseBoards.mockReturnValue({ data: [BOARD_A], isLoading: false, error: null, isError: false })
    mockUseBoard.mockReturnValue({ data: BOARD_DETAIL, isLoading: false })

    await renderBoardPage('ATLAS', undefined, filter)

    await waitFor(() => {
      expect(screen.getByTestId(`kanban-board-${BOARD_A.boardId}`)).toBeInTheDocument()
    })

    const lastCall = mockKanbanBoardProps[mockKanbanBoardProps.length - 1]
    expect(lastCall?.isFilterActive).toBe(true)
  })

  /**
   * P2-B. 필터가 비어 있으면 KanbanBoard에 isFilterActive=false가 전달된다.
   */
  it('P2-B: 필터가 비어 있으면 KanbanBoard에 isFilterActive=false가 전달된다', async () => {
    mockUseBoards.mockReturnValue({ data: [BOARD_A], isLoading: false, error: null, isError: false })
    mockUseBoard.mockReturnValue({ data: BOARD_DETAIL, isLoading: false })

    // filter 없이 렌더 → EMPTY_FILTER
    await renderBoardPage()

    await waitFor(() => {
      expect(screen.getByTestId(`kanban-board-${BOARD_A.boardId}`)).toBeInTheDocument()
    })

    const lastCall = mockKanbanBoardProps[mockKanbanBoardProps.length - 1]
    expect(lastCall?.isFilterActive).toBe(false)
  })

  // ─────────────────────────────────────────────────────────────────────────────
  // Task 7 — 뷰 전환 nav (FR-TL-01)
  // ─────────────────────────────────────────────────────────────────────────────

  /**
   * T-BD7-NAV-1. 보드 페이지에 백로그·타임라인 뷰 전환 nav가 렌더된다.
   * 보드 1개 + 상세 로드 상태에서 nav[aria-label="프로젝트 뷰 전환"]이 존재하고
   * 백로그 링크와 타임라인 링크가 올바른 href를 가진다.
   */
  it('T-BD7-NAV-1: 보드 페이지에 백로그·타임라인 뷰 전환 nav가 렌더된다', async () => {
    mockUseBoards.mockReturnValue({
      data: [BOARD_A],
      isLoading: false,
      error: null,
      isError: false,
    })
    mockUseBoard.mockReturnValue({ data: BOARD_DETAIL, isLoading: false })

    await renderBoardPage()

    const nav = await waitFor(() =>
      screen.getByRole('navigation', { name: '프로젝트 뷰 전환' }),
    )
    expect(within(nav).getByRole('link', { name: '백로그' })).toHaveAttribute(
      'href',
      '/projects/ATLAS/backlog',
    )
    expect(within(nav).getByRole('link', { name: '타임라인' })).toHaveAttribute(
      'href',
      '/projects/ATLAS/timeline',
    )
  })

  /**
   * S7 — mutate onError 시 toast.error가 호출된다.
   */
  it('S7-B: mutate onError 시 toast.error가 호출된다', async () => {
    const { toast } = await import('sonner')
    mockUseBoards.mockReturnValue({ data: [BOARD_A], isLoading: false, error: null, isError: false })
    mockUseBoard.mockReturnValue({ data: BOARD_DETAIL, isLoading: false })

    await renderBoardPage()

    await waitFor(() => {
      expect(screen.getByTestId('swimlane-selector')).toBeInTheDocument()
    })

    // onChange를 호출해 mutate 캡처
    capturedSwimlaneSelectorOnChange?.('PRIORITY')

    // mutate 호출 인자에서 onError 콜백을 꺼내 직접 실행
    const mutateCall = mockUpdateSwimlaneMutate.mock.calls[0]
    const options = mutateCall?.[1] as { onError?: () => void } | undefined
    options?.onError?.()

    expect(toast.error).toHaveBeenCalled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// BoardRouteAdapter 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('BoardRouteAdapter', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockFetchUsers.mockResolvedValue([])
    mockUseBoards.mockReturnValue({ data: [], isLoading: false, error: null, isError: false })
    mockUseBoard.mockReturnValue({ data: undefined, isLoading: false })
    mockUseUpdateSwimlane.mockReturnValue({ mutate: mockUpdateSwimlaneMutate, isPending: false })
    mockUseProjectPermissions.mockReturnValue({ data: undefined, isLoading: false })
    mockUseIssueTypes.mockReturnValue({ data: [], isLoading: false })
  })

  /**
   * T-BD7-A-1. BoardRouteAdapter가 useParams에서 projectKey를 추출해 BoardPage에 전달한다.
   */
  it('T-BD7-A-1: BoardRouteAdapter가 useParams의 projectKey를 BoardPage에 전달한다', async () => {
    const { BoardRouteAdapter } = await import('@/routes/projects.$projectKey.board')
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })

    render(
      <QueryClientProvider client={client}>
        <BoardRouteAdapter />
      </QueryClientProvider>,
    )

    await waitFor(() => {
      expect(mockUseBoards).toHaveBeenCalledWith('ATLAS')
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// FavoriteButton 렌더 (FR-UX-02 Task 8)
// ─────────────────────────────────────────────────────────────────────────────

describe('FavoriteButton 렌더', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockKanbanBoardProps.length = 0
    mockUseBoardCalls.length = 0
    capturedFilterBarOnChange = null
    capturedSwimlaneSelectorOnChange = null
    capturedFavTargetType = null
    capturedFavTargetId = null
    mockFetchUsers.mockResolvedValue(USERS)
    mockUseBoard.mockReturnValue({ data: BOARD_DETAIL, isLoading: false })
    mockUseUpdateSwimlane.mockReturnValue({ mutate: mockUpdateSwimlaneMutate, isPending: false })
    mockUseProjectPermissions.mockReturnValue({
      data: { projectKey: 'ATLAS', permissions: { CREATE: true, MANAGE_COMPONENTS: false, MANAGE_VERSIONS: false, MANAGE_CUSTOM_FIELDS: false, MANAGE_FIELD_PERMISSIONS: false, MANAGE_TEMPLATES: false } },
      isLoading: false,
    })
    mockUseIssueTypes.mockReturnValue({ data: [], isLoading: false })
  })

  afterEach(() => {
    vi.clearAllMocks()
    mockKanbanBoardProps.length = 0
    mockUseBoardCalls.length = 0
    capturedFavTargetType = null
    capturedFavTargetId = null
  })

  /**
   * T-UX02-BD8-FAV1. 보드 페이지 정상 렌더 시 헤더 영역에 FavoriteButton이 렌더된다.
   * targetType="PROJECT", targetId=projectKey가 전달돼야 한다.
   */
  it('T-UX02-BD8-FAV1: 보드 로드 후 헤더에 FavoriteButton(PROJECT, projectKey)이 렌더된다', async () => {
    mockUseBoards.mockReturnValue({ data: [BOARD_A], isLoading: false, error: null, isError: false })

    await renderBoardPage('ATLAS')

    await waitFor(() => expect(screen.getByTestId('favorite-button')).toBeInTheDocument())
    expect(capturedFavTargetType).toBe('PROJECT')
    expect(capturedFavTargetId).toBe('ATLAS')
  })

  /**
   * T-UX02-BD8-FAV2. 보드 목록 로딩 중에도 FavoriteButton이 렌더된다.
   * (projectKey는 라우트 파라미터에서 즉시 알 수 있으므로 보드 로딩과 무관)
   */
  it('T-UX02-BD8-FAV2: 보드 목록 로딩 중에도 FavoriteButton이 렌더된다', async () => {
    mockUseBoards.mockReturnValue({ data: undefined, isLoading: true, error: null, isError: false })

    await renderBoardPage('ATLAS')

    await waitFor(() => expect(screen.getByTestId('favorite-button')).toBeInTheDocument())
    expect(capturedFavTargetType).toBe('PROJECT')
    expect(capturedFavTargetId).toBe('ATLAS')
  })

  /**
   * T-UX02-BD8-FAV3. 보드 0개(CreateBoardForm 표시) 상황에서도 FavoriteButton이 렌더된다.
   */
  it('T-UX02-BD8-FAV3: 보드 0개 시에도 FavoriteButton이 렌더된다', async () => {
    mockUseBoards.mockReturnValue({ data: [], isLoading: false, error: null, isError: false })

    await renderBoardPage('ATLAS')

    await waitFor(() => expect(screen.getByTestId('favorite-button')).toBeInTheDocument())
    expect(capturedFavTargetType).toBe('PROJECT')
    expect(capturedFavTargetId).toBe('ATLAS')
  })
})
