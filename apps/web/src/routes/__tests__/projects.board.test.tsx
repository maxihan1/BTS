// 칸반 보드 라우트 페이지 단위 테스트 — BoardPage 렌더 시나리오 (FR-BD-01 Task 7 + FR-BD-02 Task 6 + FR-BD-03 Task 6)
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type {
  ActiveSprint,
  BoardColumn,
  BoardSummary,
  BoardDetail,
  BoardCardFilterParams,
} from '@/api/boards'
import type { UserSummary } from '@/api/users'
import type { IssueTypeResponse } from '@/api/issue-types'
import { ApiError } from '@/api/client'
import { boardLabels, boardManageErrorMessage } from '@/i18n/board-labels'
import { scrumEmptyStateLabels } from '@/components/board/ScrumSprintEmptyState'

// ─────────────────────────────────────────────────────────────────────────────
// mock — TanStack Router, use-boards, @/api/users, KanbanBoard, FavoriteButton
// ─────────────────────────────────────────────────────────────────────────────

const { mockNavigate } = vi.hoisted(() => ({ mockNavigate: vi.fn() }))

vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => mockNavigate,
  useParams: () => ({ projectKey: 'ATLAS' }),
  useSearch: () => ({}),
  // 🛑 `search` 를 반드시 직렬화한다. 이 대역이 그것을 삼키면 컴포넌트가 보드 스코프를
  //    버려도 유닛은 전부 초록이고 E2E 에서만 red 가 선다 — 이 저장소가 이미 밟은 양식
  //    (memory `mock-swallowed-prop-is-invisible-to-unit-tests`).
  //    실제로 밟았다. 이 대역이 삼키는 동안 T-BD04-1 이 「CTA href 에 ?board= 가 **없다**」를
  //    계약으로 굳히고 있었고, 그것은 FR-BD-04 PR ⑥ 이 결함이라 판정한 바로 그 상태다.
  Link: ({
    to,
    params,
    search,
    children,
    className,
  }: {
    to: string
    params?: Record<string, string>
    search?: Record<string, string>
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
    const query =
      search === undefined ? '' : `?${new URLSearchParams(Object.entries(search)).toString()}`
    return <a href={`${resolvedTo}${query}`} className={className}>{children}</a>
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
// showEmptyStateIntro 를 DOM 에 노출한다 — 「보드가 없습니다」 인트로가 다이얼로그에서만 꺼지는지
// (C1) 를 여기서 잰다. mock 이 prop 을 삼키면 유닛은 초록인데 화면에는 그대로 뜬다.
vi.mock('@/components/board/CreateBoardForm', () => ({
  CreateBoardForm: ({
    projectKey,
    showEmptyStateIntro,
    onCreated,
  }: {
    projectKey: string
    showEmptyStateIntro?: boolean
    onCreated?: () => void
  }) => (
    <div
      data-testid="create-board-form"
      data-project-key={projectKey}
      data-empty-intro={String(showEmptyStateIntro ?? true)}
    >
      CreateBoardForm
      {/* 실제 폼의 mutation onSuccess 자리 — 성공 신호를 테스트가 직접 쏜다.
          onCreated 를 안 넘기면 눌러도 아무 일도 없어 C5-2 가 red 가 된다. */}
      <button
        type="button"
        data-testid="create-board-form-fire-created"
        onClick={() => {
          onCreated?.()
        }}
      >
        생성 성공 신호
      </button>
    </div>
  ),
}))

// use-boards 훅 mock — 각 테스트에서 덮어씀
// useBoard는 (boardId, filter?) 시그니처로 호출되므로 두 인자 모두 캡처
const mockUseBoards = vi.fn()
const mockUseBoard = vi.fn()
const mockUseBoardCalls: Array<[string | undefined, BoardCardFilterParams | undefined]> = []

const mockUseUpdateBoardName = vi.fn()
const mockUseDeleteBoard = vi.fn()

// ★factory 를 통째로 쓰지 않고 실제 모듈을 펼쳐 덮는다 — boardKeys 같은 비-훅 export 를
//   빠뜨리면 「mock 에 그 export 가 없다」로 죽고, 그 실패는 배선이 아니라 mock 의 결함이다.
vi.mock('@/hooks/use-boards', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/hooks/use-boards')>()
  return {
    ...actual,
    useBoards: (projectKey: string) => mockUseBoards(projectKey),
    useBoard: (boardId: string | undefined, filter?: BoardCardFilterParams) => {
      mockUseBoardCalls.push([boardId, filter])
      return mockUseBoard(boardId, filter)
    },
    useCreateBoard: () => ({ mutate: vi.fn(), isPending: false }),
    useUpdateBoardName: (projectKey: string, boardId: string) =>
      mockUseUpdateBoardName(projectKey, boardId),
    useDeleteBoard: (projectKey: string) => mockUseDeleteBoard(projectKey),
  }
})

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

/** 이름 변경 mutation 스파이 — 각 테스트가 mockImplementation 으로 콜백 흐름을 정한다 */
const mockRenameMutate = vi.fn()
/** 삭제 mutation 스파이 — 성공/실패 콜백을 테스트가 직접 태운다 */
const mockDeleteMutate = vi.fn()

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

// ★ 기존 픽스처는 전부 **칸반**이다 — 이 파일의 기존 시나리오가 재는 것은 칸반 동작이고,
//   FR-BD-04 이후에도 그 동작이 한 줄도 안 바뀐다는 것이 E3 의 내용이다.
const BOARD_A: BoardSummary = {
  boardId: 'a1b2c3d4-e5f6-4890-abcd-ef1234567891',
  projectKey: 'ATLAS',
  name: '스프린트 보드 A',
  boardType: 'KANBAN',
}

const BOARD_B: BoardSummary = {
  boardId: 'b2c3d4e5-f6a7-4890-abcd-ef1234567892',
  projectKey: 'ATLAS',
  name: '스프린트 보드 B',
  boardType: 'KANBAN',
}

const BOARD_DETAIL: BoardDetail = {
  boardId: BOARD_A.boardId,
  projectKey: 'ATLAS',
  name: '스프린트 보드 A',
  columns: [],
  truncated: false,
  unplacedCount: 0,
  unmappedStates: [],
  swimlaneField: 'NONE',
  quickFilters: [],
  boardType: 'KANBAN',
  // 칸반은 스프린트라는 개념이 없어 서버가 항상 null 을 준다 (`activeSprintSchema` KDoc)
  activeSprint: null,
}

/**
 * 삭제 가능한 보드 상세 — `canDelete: true` 를 **명시**한다.
 *
 * `BOARD_DETAIL` 은 이 필드를 담지 않는다(= `undefined`). Zod 가 `.optional()` 이라
 * 백엔드가 안 실어 줄 수 있고, 그 경우는 fail-closed 다 — 두 픽스처가 그 갈림을 잰다.
 */
const BOARD_DETAIL_DELETABLE: BoardDetail = {
  ...BOARD_DETAIL,
  canDelete: true,
}

/** 삭제 권한이 없는 보드 상세 — `canDelete: false` 를 명시한다 */
const BOARD_DETAIL_NOT_DELETABLE: BoardDetail = {
  ...BOARD_DETAIL,
  canDelete: false,
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
  boardType: 'KANBAN',
  activeSprint: null,
  columns: [
    {
      columnId: 'col-1',
      states: [{ key: 'todo', name: '할 일', category: 'TODO' }],
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
  unmappedStates: [],
  quickFilters: [],
}

// ── FR-BD-04 — 스크럼 보드 픽스처 ────────────────────────────────────────────

/** 서버 `ActiveSprintResponse` 4필드 그대로 (`goal`·`status` 없음) */
const ACTIVE_SPRINT: ActiveSprint = {
  sprintId: 'e5f6a7b8-c9d0-4890-abcd-ef1234567895',
  name: 'Sprint 3',
  startDate: '2026-09-01',
  endDate: '2026-09-15',
}

/** 카드가 0건인 컬럼 — 「컬럼은 있는데 이슈가 없다」를 만드는 최소 조각 */
const EMPTY_TODO_COLUMN: BoardColumn = {
  columnId: 'col-1',
  states: [{ key: 'todo', name: '할 일', category: 'TODO' }],
  name: '할 일',
  category: 'TODO',
  displayOrder: 1,
  wipLimit: null,
  wipExceeded: false,
  cards: [],
}

/**
 * 스크럼 · 활성 스프린트 없음 (E1).
 *
 * `canDelete: true` 를 명시한다 — 빈 상태에서도 `⋯` 관리 메뉴가 **남아 있는지**가
 * 이 PR 의 핵심 회귀 가드이고, 삭제 항목이 그 메뉴가 살아 있다는 가장 강한 신호다.
 */
const SCRUM_BOARD_NO_SPRINT: BoardDetail = {
  ...BOARD_DETAIL,
  boardType: 'SCRUM',
  activeSprint: null,
  canDelete: true,
  columns: [EMPTY_TODO_COLUMN],
}

/** 스크럼 · 활성 스프린트 있고 카드도 있음 (FR-2) */
const SCRUM_BOARD_WITH_SPRINT: BoardDetail = {
  ...BOARD_DETAIL_WITH_ASSIGNEES,
  boardType: 'SCRUM',
  activeSprint: ACTIVE_SPRINT,
}

/** 스크럼 · 활성 스프린트는 있는데 그 스프린트에 이슈가 0건 (E2) */
const SCRUM_BOARD_EMPTY_SPRINT: BoardDetail = {
  ...BOARD_DETAIL,
  boardType: 'SCRUM',
  activeSprint: ACTIVE_SPRINT,
  columns: [EMPTY_TODO_COLUMN],
}

/** 칸반 · 카드 0건 (E3) — 같은 「카드 0건」이어도 칸반은 빈 상태로 갈리지 않는다 */
const KANBAN_BOARD_NO_CARDS: BoardDetail = {
  ...BOARD_DETAIL,
  columns: [EMPTY_TODO_COLUMN],
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
    mockRenameMutate.mockReset()
    mockDeleteMutate.mockReset()
    mockUseUpdateBoardName.mockReturnValue({ mutate: mockRenameMutate, isPending: false })
    mockUseDeleteBoard.mockReturnValue({ mutate: mockDeleteMutate, isPending: false })
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
        { columnId: 'col-1', states: [{ key: 'todo', name: '할 일', category: 'TODO' }], name: '할 일', category: 'TODO', displayOrder: 1, wipLimit: null, wipExceeded: false, cards: [] },
        { columnId: 'col-2', states: [{ key: 'done', name: '완료', category: 'DONE' }], name: '완료', category: 'DONE', displayOrder: 2, wipLimit: null, wipExceeded: false, cards: [] },
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
        { columnId: 'col-1', states: [{ key: 'todo', name: '할 일', category: 'TODO' }], name: '할 일', category: 'TODO', displayOrder: 1, wipLimit: null, wipExceeded: false, cards: [] },
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
   * T-BD7-NAV-2 (★ 회귀 가드 · FR-BD-04 PR ⑥). 스크럼 보드에서는 nav 백로그 링크가
   * 보드 스코프를 싣고, **보드 상세가 아직 로딩 중이어도** 싣는다.
   *
   * 🛑 두 가지를 한 번에 잠근다.
   *
   * ① 보드→백로그 방향에 판별식이 없었다. 형제 T-BD7-NAV-1 은 픽스처가 칸반이라
   *    `boardType === 'SCRUM'` 분기를 한 번도 밟지 않고, E2E 에도 보드 화면의 nav
   *    백로그 링크를 누르는 스텝이 없다. 즉 이 방향은 어느 계층에서도 무보증이었다.
   *
   * ② 종류를 `boardDetail` 에서 읽으면 nav 가 상세를 기다리지 않고 렌더되므로
   *    `useBoard` 가 in-flight 인 창에서 `boardType` 이 undefined 라 `search` 가 빠진다.
   *    클릭하면 서버가 `findScrumBoardIdByProject`(`created_at ASC LIMIT 1`)로 **첫 번째**
   *    스크럼 보드에 폴백해, **이 PR 이 없애려는 바로 그 증상**이 로딩 중에 재현된다.
   *    그래서 `useBoardViewNavLinks` 는 `boards` 요약에서 읽는다 — `isLoading: true` 로
   *    그 창을 고정한다. 상세에서 읽는 구현으로 되돌리면 이 테스트가 red 다.
   */
  it('T-BD7-NAV-2: 스크럼이면 상세 로딩 중에도 nav 백로그 링크가 보드 스코프를 싣는다', async () => {
    const scrumSummary: BoardSummary = { ...BOARD_A, boardType: 'SCRUM' }
    mockUseBoards.mockReturnValue({
      data: [scrumSummary],
      isLoading: false,
      error: null,
      isError: false,
    })
    // 보드 상세는 아직 안 왔다 — 느린 네트워크·캐시 미스의 그 창.
    mockUseBoard.mockReturnValue({ data: undefined, isLoading: true })

    await renderBoardPage()

    const nav = await waitFor(() =>
      screen.getByRole('navigation', { name: '프로젝트 뷰 전환' }),
    )
    expect(within(nav).getByRole('link', { name: '백로그' })).toHaveAttribute(
      'href',
      `/projects/ATLAS/backlog?board=${scrumSummary.boardId}`,
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

  // ───────────────────────────────────────────────────────────────────────────
  // 보드 `⋯` 관리 메뉴 — 이름 변경 · 삭제 (FR-BD-01-2a/2b/2d · Jira 근거 J3·J4·J5)
  //
  // 권한 판정은 전부 **DOM 부재**로 잰다. `toBeDisabled()` 로 재면 「비활성으로 보이지만
  // 마크업에는 있는」 상태를 통과시키게 되고, 그건 J5 가 말하는 Jira 동작이 아니다.
  // ───────────────────────────────────────────────────────────────────────────

  /** 보드 관리 `⋯` 트리거를 눌러 메뉴를 편다 */
  async function openBoardActionsMenu(
    user: ReturnType<typeof userEvent.setup>,
  ): Promise<void> {
    await user.click(await screen.findByRole('button', { name: /보드 관리/ }))
  }

  /**
   * 삭제 확인 다이얼로그까지 연다 — `⋯` → 「보드 삭제」.
   * 다이얼로그가 열린 것까지 확인하고 그 element 를 돌려준다.
   */
  async function openDeleteDialog(
    user: ReturnType<typeof userEvent.setup>,
  ): Promise<HTMLElement> {
    await openBoardActionsMenu(user)
    await user.click(
      await screen.findByRole('menuitem', { name: boardLabels.actions.deleteItem }),
    )
    return await screen.findByRole('dialog', { name: boardLabels.actions.deleteDialogTitle })
  }

  /**
   * T-BD6M-1. 두 권한이 다 있으면 항목 2개가 나온다.
   *
   * ★아래 부재 단언 3종의 **비-공허 짝**이다. 이게 없으면 라벨을 오타 내거나 메뉴를 통째로
   *   지워도 `queryBy...toBeNull()` 이 전부 그대로 통과한다.
   */
  it('T-BD6M-1: 권한이 둘 다 있으면 「이름 변경」·「보드 삭제」가 나온다', async () => {
    const user = userEvent.setup()
    mockUseBoards.mockReturnValue({ data: [BOARD_A], isLoading: false, error: null, isError: false })
    mockUseBoard.mockReturnValue({ data: BOARD_DETAIL_DELETABLE, isLoading: false })

    await renderBoardPage()
    await openBoardActionsMenu(user)

    expect(
      await screen.findByRole('menuitem', { name: boardLabels.actions.renameItem }),
    ).toBeInTheDocument()
    expect(
      await screen.findByRole('menuitem', { name: boardLabels.actions.deleteItem }),
    ).toBeInTheDocument()
  })

  /**
   * T-BD6M-2. CREATE 권한이 없으면 「이름 변경」이 **DOM 에 없다** (FR-BD-01-2d).
   * 삭제 항목은 남아 있으므로 「메뉴가 안 열려서 없다」와 구별된다.
   */
  it('T-BD6M-2: canCreate=false 면 「이름 변경」이 DOM 에 없다', async () => {
    const user = userEvent.setup()
    mockUseBoards.mockReturnValue({ data: [BOARD_A], isLoading: false, error: null, isError: false })
    mockUseBoard.mockReturnValue({ data: BOARD_DETAIL_DELETABLE, isLoading: false })
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
    await openBoardActionsMenu(user)

    // 메뉴가 열려 있다는 앵커 — 삭제 항목은 그대로 나온다
    expect(
      await screen.findByRole('menuitem', { name: boardLabels.actions.deleteItem }),
    ).toBeInTheDocument()
    expect(
      screen.queryByRole('menuitem', { name: boardLabels.actions.renameItem }),
    ).toBeNull()
  })

  /**
   * T-BD6M-3. `canDelete === false` 면 「보드 삭제」가 **DOM 에 없다**.
   */
  it('T-BD6M-3: canDelete 가 false 면 「보드 삭제」가 DOM 에 없다', async () => {
    const user = userEvent.setup()
    mockUseBoards.mockReturnValue({ data: [BOARD_A], isLoading: false, error: null, isError: false })
    mockUseBoard.mockReturnValue({ data: BOARD_DETAIL_NOT_DELETABLE, isLoading: false })

    await renderBoardPage()
    await openBoardActionsMenu(user)

    expect(
      await screen.findByRole('menuitem', { name: boardLabels.actions.renameItem }),
    ).toBeInTheDocument()
    expect(
      screen.queryByRole('menuitem', { name: boardLabels.actions.deleteItem }),
    ).toBeNull()
  })

  /**
   * T-BD6M-4. ★fail-closed 앵커. `canDelete` 가 **undefined** 여도 「보드 삭제」는 없다.
   *
   * 응답 스키마가 `.optional()` 이라 백엔드가 그 필드를 안 실어 주는 경로가 실재한다
   * (구버전 서버 · 부분 응답). `!== false` 같은 느슨한 판정을 쓰면 그때 삭제가 열린다.
   */
  it('T-BD6M-4: canDelete 가 undefined 면 「보드 삭제」가 DOM 에 없다', async () => {
    const user = userEvent.setup()
    mockUseBoards.mockReturnValue({ data: [BOARD_A], isLoading: false, error: null, isError: false })
    // BOARD_DETAIL 은 canDelete 를 아예 담지 않는다
    mockUseBoard.mockReturnValue({ data: BOARD_DETAIL, isLoading: false })

    await renderBoardPage()
    await openBoardActionsMenu(user)

    expect(
      await screen.findByRole('menuitem', { name: boardLabels.actions.renameItem }),
    ).toBeInTheDocument()
    expect(
      screen.queryByRole('menuitem', { name: boardLabels.actions.deleteItem }),
    ).toBeNull()
  })

  /**
   * T-BD6M-5. 삭제를 확인하면 mutation 이 그 보드 id 로 불리고, 성공하면 **남은 보드로**
   * 이동한다 (E2). 남은 보드가 없으면 board 없는 URL 로 가서 빈 상태(E1)로 떨어진다.
   */
  it('T-BD6M-5: 삭제 확인 후 deleteBoard 를 부르고 남은 보드로 navigate 한다', async () => {
    const user = userEvent.setup()
    mockUseBoards.mockReturnValue({
      data: [BOARD_A, BOARD_B],
      isLoading: false,
      error: null,
      isError: false,
    })
    mockUseBoard.mockReturnValue({ data: BOARD_DETAIL_DELETABLE, isLoading: false })
    mockDeleteMutate.mockImplementation(
      (_vars: { boardId: string }, opts?: { onSuccess?: () => void }) => {
        opts?.onSuccess?.()
      },
    )

    await renderBoardPage()
    const dialog = await openDeleteDialog(user)
    await user.click(
      within(dialog).getByRole('button', { name: boardLabels.actions.deleteConfirm }),
    )

    expect(mockDeleteMutate).toHaveBeenCalledWith(
      { boardId: BOARD_A.boardId },
      expect.anything(),
    )
    expect(mockNavigate).toHaveBeenCalledWith(
      expect.objectContaining({ search: { board: BOARD_B.boardId } }),
    )
  })

  /**
   * T-BD6M-6 (S7). 삭제가 실패하면 다이얼로그는 **열린 채**로 그 안에 사유가 뜬다.
   *
   * 화면 배너나 toast 로만 알리면 모달 오버레이가 그것을 가리거나, 창이 닫힌 뒤 도착한 실패가
   * 갈 곳을 잃는다 — `confirm-dialog.tsx` 가 error prop 을 둔 이유가 그것이다.
   */
  it('T-BD6M-6: 삭제가 실패하면 다이얼로그가 열린 채 사유가 창 안에 뜬다', async () => {
    const user = userEvent.setup()
    mockUseBoards.mockReturnValue({ data: [BOARD_A], isLoading: false, error: null, isError: false })
    mockUseBoard.mockReturnValue({ data: BOARD_DETAIL_DELETABLE, isLoading: false })
    mockDeleteMutate.mockImplementation(
      (_vars: { boardId: string }, opts?: { onError?: (err: unknown) => void }) => {
        opts?.onError?.(new ApiError(403, { errorCode: 'AGILE_ACCESS_DENIED' }))
      },
    )

    await renderBoardPage()
    const dialog = await openDeleteDialog(user)
    await user.click(
      within(dialog).getByRole('button', { name: boardLabels.actions.deleteConfirm }),
    )

    // 창이 그대로 열려 있다
    expect(
      screen.getByRole('dialog', { name: boardLabels.actions.deleteDialogTitle }),
    ).toBeInTheDocument()

    const alert = within(dialog).getByRole('alert')
    expect(alert).toHaveTextContent(
      boardManageErrorMessage('AGILE_ACCESS_DENIED', boardLabels.actions.deleteFailed),
    )
    // raw 코드가 그대로 새면 매핑이 없는 것이다 (PR #106 의 가짜 그린)
    expect(alert).not.toHaveTextContent('AGILE_ACCESS_DENIED')
  })

  /**
   * T-BD6M-7 (E7). `confirming` 중에는 취소·Esc 로 창이 닫히지 않는다.
   *
   * Radix 는 Esc · 오버레이 클릭 · 우상단 X 를 **한 콜백**(`onOpenChange`)으로 보내므로,
   * Esc 가 막히면 나머지 둘도 같은 자리에서 막힌다. 취소 버튼만 별도로 `disabled` 다.
   */
  it('T-BD6M-7: confirming 중에는 취소·Esc 로 창이 닫히지 않는다', async () => {
    const user = userEvent.setup()
    mockUseBoards.mockReturnValue({ data: [BOARD_A], isLoading: false, error: null, isError: false })
    mockUseBoard.mockReturnValue({ data: BOARD_DETAIL_DELETABLE, isLoading: false })
    // 삭제 요청이 아직 진행 중인 상태
    mockUseDeleteBoard.mockReturnValue({ mutate: mockDeleteMutate, isPending: true })

    await renderBoardPage()
    const dialog = await openDeleteDialog(user)

    const cancel = within(dialog).getByRole('button', { name: boardLabels.actions.deleteCancel })
    expect(cancel).toBeDisabled()
    await user.click(cancel)
    expect(
      screen.getByRole('dialog', { name: boardLabels.actions.deleteDialogTitle }),
    ).toBeInTheDocument()

    await user.keyboard('{Escape}')
    expect(
      screen.getByRole('dialog', { name: boardLabels.actions.deleteDialogTitle }),
    ).toBeInTheDocument()
  })

  /**
   * T-BD6M-8. 이름 변경 — 새 이름을 제출하면 mutation 이 그 이름으로 불리고, 성공하면 닫힌다.
   */
  it('T-BD6M-8: 이름 변경 제출 시 새 이름으로 mutate 하고 성공하면 창이 닫힌다', async () => {
    const user = userEvent.setup()
    mockUseBoards.mockReturnValue({ data: [BOARD_A], isLoading: false, error: null, isError: false })
    mockUseBoard.mockReturnValue({ data: BOARD_DETAIL_DELETABLE, isLoading: false })
    mockRenameMutate.mockImplementation(
      (_vars: { name: string }, opts?: { onSuccess?: () => void }) => {
        opts?.onSuccess?.()
      },
    )

    await renderBoardPage()
    await openBoardActionsMenu(user)
    await user.click(
      await screen.findByRole('menuitem', { name: boardLabels.actions.renameItem }),
    )

    const dialog = await screen.findByRole('dialog', {
      name: boardLabels.actions.renameDialogTitle,
    })
    const input = within(dialog).getByLabelText(boardLabels.actions.renameNameLabel)
    await user.clear(input)
    await user.type(input, '새 이름')
    await user.click(
      within(dialog).getByRole('button', { name: boardLabels.actions.renameSubmit }),
    )

    expect(mockRenameMutate).toHaveBeenCalledWith({ name: '새 이름' }, expect.anything())
    await waitFor(() => {
      expect(
        screen.queryByRole('dialog', { name: boardLabels.actions.renameDialogTitle }),
      ).toBeNull()
    })
  })

  /**
   * T-BD6M-9 (C1). 「새 보드」 다이얼로그의 생성 폼은 빈 상태 인트로를 **끄고** 쓴다.
   *
   * 그 인트로는 「보드가 없습니다」로 시작한다 — 보드가 있는데 스위처에서 폼을 열면 그 문장이
   * 사실이 아니게 된다(라이트·다크 양쪽 재현).
   */
  it('T-BD6M-9: 「새 보드」 다이얼로그의 생성 폼은 빈 상태 인트로를 끈다', async () => {
    const user = userEvent.setup()
    mockUseBoards.mockReturnValue({ data: [BOARD_A], isLoading: false, error: null, isError: false })
    mockUseBoard.mockReturnValue({ data: BOARD_DETAIL_DELETABLE, isLoading: false })

    await renderBoardPage()
    await user.click(await screen.findByRole('button', { name: /보드 선택/ }))
    await user.click(
      await screen.findByRole('menuitem', { name: boardLabels.switcher.createItem }),
    )

    expect(await screen.findByTestId('create-board-form')).toHaveAttribute(
      'data-empty-intro',
      'false',
    )
  })

  /**
   * T-BD6M-10 (C1 비-공허 짝). 보드가 0개인 빈 상태에서는 인트로가 **그대로 켜져** 있다.
   */
  it('T-BD6M-10: 보드 0개 빈 상태의 생성 폼은 인트로를 켠 채 쓴다', async () => {
    mockUseBoards.mockReturnValue({ data: [], isLoading: false, error: null, isError: false })

    await renderBoardPage()

    expect(await screen.findByTestId('create-board-form')).toHaveAttribute(
      'data-empty-intro',
      'true',
    )
  })

  /**
   * T-BD6M-11 (회귀 앵커). 보드 목록이 **밖에서** 늘어나도 열려 있는 「새 보드」 창은 닫히지 않는다.
   *
   * 닫힘을 「보드 개수가 늘었다」로 추론하면, 창을 열고 이름을 입력하던 중 탭을 떠났다 돌아올 때
   * (`refetchOnWindowFocus` 기본값) 다른 사람이 만든 보드가 목록에 반영되며 창이 닫힌다.
   * 사용자에게는 「만들어졌다」로 읽히지만 실제로는 아무것도 만들어지지 않았다.
   *
   * 헬퍼를 안 쓰고 직접 render 하는 이유는 rerender 가 필요해서다 — 목록이 바뀌는 순간을
   * 재현하려면 부모를 다시 그려야 한다. 매번 **새 엘리먼트**를 넘긴다(같은 참조는 React 가
   * props 동일로 보고 재렌더를 건너뛴다).
   */
  it('T-BD6M-11: 보드 목록이 밖에서 늘어나도 열려 있는 「새 보드」 창이 닫히지 않는다', async () => {
    const user = userEvent.setup()
    mockUseBoards.mockReturnValue({ data: [BOARD_A], isLoading: false, error: null, isError: false })
    mockUseBoard.mockReturnValue({ data: BOARD_DETAIL, isLoading: false })

    const { BoardPage } = await import('@/routes/projects.$projectKey.board')
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    const { rerender } = render(
      <QueryClientProvider client={client}>
        <BoardPage projectKey="ATLAS" selectedBoardId={undefined} filter={undefined} />
      </QueryClientProvider>,
    )

    await user.click(await screen.findByRole('button', { name: /보드 선택/ }))
    await user.click(
      await screen.findByRole('menuitem', { name: boardLabels.switcher.createItem }),
    )
    expect(await screen.findByTestId('create-board-form')).toBeInTheDocument()

    // When. 이 창의 제출과 무관하게 목록만 갱신된다 — 남이 만든 보드가 refetch 로 들어온 상황
    const callsBeforeRefetch = mockUseBoards.mock.calls.length
    mockUseBoards.mockReturnValue({
      data: [BOARD_A, BOARD_B],
      isLoading: false,
      error: null,
      isError: false,
    })
    rerender(
      <QueryClientProvider client={client}>
        <BoardPage projectKey="ATLAS" selectedBoardId={undefined} filter={undefined} />
      </QueryClientProvider>,
    )

    // 앵커. 재렌더가 실제로 일어나 새 목록을 읽었다 — 없으면 「아무 일도 안 해서 통과」다
    expect(mockUseBoards.mock.calls.length).toBeGreaterThan(callsBeforeRefetch)

    // Then. 입력 중이던 창은 그대로 열려 있다
    expect(screen.getByTestId('create-board-form')).toBeInTheDocument()
  })

  /**
   * T-BD6M-12 (T-BD6M-11 의 비-공허 짝). 생성 **성공 신호**가 오면 그때 창이 닫힌다.
   *
   * 이것이 없으면 T-BD6M-11 은 「창이 영원히 안 닫힌다」로도 통과한다.
   */
  it('T-BD6M-12: 생성에 성공하면 「새 보드」 창이 닫힌다', async () => {
    const user = userEvent.setup()
    mockUseBoards.mockReturnValue({ data: [BOARD_A], isLoading: false, error: null, isError: false })
    mockUseBoard.mockReturnValue({ data: BOARD_DETAIL, isLoading: false })

    await renderBoardPage()

    await user.click(await screen.findByRole('button', { name: /보드 선택/ }))
    await user.click(
      await screen.findByRole('menuitem', { name: boardLabels.switcher.createItem }),
    )
    expect(await screen.findByTestId('create-board-form')).toBeInTheDocument()

    await user.click(screen.getByTestId('create-board-form-fire-created'))

    await waitFor(() => {
      expect(screen.queryByTestId('create-board-form')).toBeNull()
    })
  })

  // ───────────────────────────────────────────────────────────────────────────
  // FR-BD-04 — 스크럼 보드 화면 (FR-1 · FR-2 · E1·E2·E3)
  // ───────────────────────────────────────────────────────────────────────────

  /**
   * T-BD04-1 (E1). 스크럼 보드에 활성 스프린트가 없으면 빈 상태 + 백로그 링크가 뜨고
   * KanbanBoard 는 그 자리에서 물러난다 (FR-1).
   *
   * 🛑 CTA 는 **보고 있던 보드를 실어 나른다** (FR-BD-04 PR ⑥). 스코프가 빠지면 서버가
   * `findScrumBoardIdByProject`(`created_at ASC LIMIT 1`)로 **첫 번째** 스크럼 보드에
   * 폴백해, 두 번째 보드에서 안내를 따르면 **다른 보드의 백로그**가 열린다.
   *
   * 이 단언은 PR ⑥ 이전까지 `?board=` **없는** 값을 계약으로 굳히고 있었다 — 위 라우터
   * 대역이 `search` 를 삼켜 그 상태가 초록이었기 때문이다. 대역을 고치자 이 테스트 하나가
   * 정확히 red 였다(실측). 형제 T-BD7-NAV-1 은 칸반 픽스처라 `?board=` 미부착이 정답이고
   * 그대로 초록이다 — 즉 이 쌍이 편차 X4(칸반 id 는 싣지 않는다)를 함께 잠근다.
   */
  it('T-BD04-1: 스크럼 · 활성 스프린트 없음이면 빈 상태와 백로그 링크가 뜬다', async () => {
    mockUseBoards.mockReturnValue({ data: [BOARD_A], isLoading: false, error: null, isError: false })
    mockUseBoard.mockReturnValue({ data: SCRUM_BOARD_NO_SPRINT, isLoading: false })

    await renderBoardPage()

    expect(
      await screen.findByText(scrumEmptyStateLabels.noActiveSprint.description),
    ).toBeInTheDocument()
    expect(
      screen.getByRole('link', { name: scrumEmptyStateLabels.backlogLink }),
    ).toHaveAttribute('href', `/projects/ATLAS/backlog?board=${SCRUM_BOARD_NO_SPRINT.boardId}`)
    expect(screen.queryByTestId(`kanban-board-${BOARD_A.boardId}`)).not.toBeInTheDocument()
  })

  /**
   * T-BD04-2 (★ 회귀 가드). 빈 상태여도 **헤더·보드 스위처·`⋯` 관리 메뉴·필터바가 남아 있다**.
   *
   * 🛑 이 단언이 이 task 의 핵심이다 — 빈 상태를 early-return 으로 만들면 전부 사라지고,
   *    `e2e/board-manage.spec.ts` S4·S5(스크럼 보드로 전환 → `⋯` 로 삭제)가 죽는다.
   *    「빈 상태가 뜬다」만 재면 그 회귀가 초록으로 통과한다.
   */
  it('T-BD04-2: 스크럼 빈 상태에서도 헤더·스위처·⋯ 관리 메뉴·필터바가 남는다', async () => {
    const user = userEvent.setup()
    mockUseBoards.mockReturnValue({ data: [BOARD_A], isLoading: false, error: null, isError: false })
    mockUseBoard.mockReturnValue({ data: SCRUM_BOARD_NO_SPRINT, isLoading: false })

    await renderBoardPage()

    // 빈 상태가 실제로 떠 있는 상태에서 재야 한다 — 조건을 못 밟으면 공허한 통과다
    expect(
      await screen.findByText(scrumEmptyStateLabels.noActiveSprint.title),
    ).toBeInTheDocument()

    expect(screen.getByRole('heading', { name: boardLabels.page.title })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /보드 선택/ })).toBeInTheDocument()
    expect(screen.getByTestId('board-filter-bar')).toBeInTheDocument()

    const actionsTrigger = screen.getByRole('button', {
      name: boardLabels.actions.triggerAriaLabel(SCRUM_BOARD_NO_SPRINT.name),
    })
    expect(actionsTrigger).toBeInTheDocument()

    // 메뉴가 트리거만 남고 속이 빈 것이 아니라 실제로 열려 삭제까지 갈 수 있다
    await user.click(actionsTrigger)
    expect(
      await screen.findByRole('menuitem', { name: boardLabels.actions.deleteItem }),
    ).toBeInTheDocument()
  })

  /**
   * T-BD04-3 (FR-2). 활성 스프린트가 있으면 헤더에 이름과 기간이 뜨고 카드는 그대로 렌더된다.
   */
  it('T-BD04-3: 활성 스프린트가 있으면 헤더에 이름과 기간이 뜬다', async () => {
    mockUseBoards.mockReturnValue({ data: [BOARD_A], isLoading: false, error: null, isError: false })
    mockUseBoard.mockReturnValue({ data: SCRUM_BOARD_WITH_SPRINT, isLoading: false })

    await renderBoardPage()

    const summary = await screen.findByTestId('active-sprint-summary')
    expect(summary).toHaveTextContent(ACTIVE_SPRINT.name)
    expect(summary).toHaveTextContent('2026-09-01 ~ 2026-09-15')

    // 카드가 있으므로 빈 상태가 아니다 — 보드는 평소대로 그려진다
    expect(screen.getByTestId(`kanban-board-${BOARD_A.boardId}`)).toBeInTheDocument()
    expect(
      screen.queryByText(scrumEmptyStateLabels.noActiveSprint.title),
    ).not.toBeInTheDocument()
  })

  /**
   * T-BD04-4 (E2). 활성 스프린트는 있는데 이슈가 0건이면 **①과 다른 문장**이 뜬다.
   * 사용자가 할 일이 다르다 — 전자는 백로그로 가서 시작해야 하고 후자는 이슈를 넣어야 한다.
   */
  it('T-BD04-4: 활성 스프린트가 있는데 카드가 0건이면 ①과 다른 문장이 뜬다', async () => {
    mockUseBoards.mockReturnValue({ data: [BOARD_A], isLoading: false, error: null, isError: false })
    mockUseBoard.mockReturnValue({ data: SCRUM_BOARD_EMPTY_SPRINT, isLoading: false })

    await renderBoardPage()

    expect(await screen.findByText(scrumEmptyStateLabels.emptySprint.title)).toBeInTheDocument()
    expect(
      screen.queryByText(scrumEmptyStateLabels.noActiveSprint.title),
    ).not.toBeInTheDocument()
    // 스프린트 헤더는 그대로 있다 — 「어느 스프린트가 비었는가」를 알아야 한다
    expect(await screen.findByTestId('active-sprint-summary')).toHaveTextContent(
      ACTIVE_SPRINT.name,
    )
  })

  /**
   * T-BD04-5 (E3). 칸반 보드는 **완전 무변경**이다.
   * 같은 「카드 0건」이어도 칸반은 빈 상태로 갈리지 않고 스프린트 헤더도 없다.
   */
  it('T-BD04-5: 칸반 보드는 카드가 0건이어도 스크럼 빈 상태로 갈리지 않는다', async () => {
    mockUseBoards.mockReturnValue({ data: [BOARD_A], isLoading: false, error: null, isError: false })
    mockUseBoard.mockReturnValue({ data: KANBAN_BOARD_NO_CARDS, isLoading: false })

    await renderBoardPage()

    expect(
      await screen.findByTestId(`kanban-board-${BOARD_A.boardId}`),
    ).toBeInTheDocument()
    expect(
      screen.queryByText(scrumEmptyStateLabels.noActiveSprint.title),
    ).not.toBeInTheDocument()
    expect(screen.queryByText(scrumEmptyStateLabels.emptySprint.title)).not.toBeInTheDocument()
    expect(screen.queryByTestId('active-sprint-summary')).not.toBeInTheDocument()
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
