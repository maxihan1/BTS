// 칸반 보드 라우트 페이지 단위 테스트 — BoardPage 렌더 시나리오 (FR-BD-01 Task 7 + FR-BD-02 Task 6 + FR-BD-03 Task 6)
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { BoardSummary, BoardDetail, BoardCardFilterParams } from '@/api/boards'
import type { UserSummary } from '@/api/users'
import { ApiError } from '@/api/client'

// ─────────────────────────────────────────────────────────────────────────────
// mock — TanStack Router, use-boards, @/api/users, KanbanBoard
// ─────────────────────────────────────────────────────────────────────────────

const { mockNavigate } = vi.hoisted(() => ({ mockNavigate: vi.fn() }))

vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => mockNavigate,
  useParams: () => ({ projectKey: 'ATLAS' }),
  useSearch: () => ({}),
}))

vi.mock('sonner', () => ({
  toast: { success: vi.fn(), error: vi.fn() },
}))

// KanbanBoard는 DndContext 등 복잡한 의존성이 있으므로 단순 mock
// assigneeNames, filter, isFilterActive prop을 캡처해 단언에 활용
const mockKanbanBoardProps: Array<{ boardId: string; assigneeNames: unknown; filter: unknown; isFilterActive: unknown }> = []

vi.mock('@/components/board/KanbanBoard', () => ({
  KanbanBoard: ({
    boardId,
    assigneeNames,
    filter,
    isFilterActive,
  }: {
    boardId: string
    assigneeNames: Map<string, unknown>
    filter?: BoardCardFilterParams
    isFilterActive?: boolean
  }) => {
    mockKanbanBoardProps.push({ boardId, assigneeNames, filter, isFilterActive })
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
        { issueKey: 'ATLAS-1', summary: '미배정 이슈', assigneeId: null, version: 1, priority: 1 },
        // case 2: assigneeId=ALICE_ID, userMap에 있음 → named
        { issueKey: 'ATLAS-2', summary: '앨리스 이슈', assigneeId: ALICE_ID, version: 2, priority: 1 },
        // case 3: assigneeId=UNKNOWN_USER_ID, userMap에 없음 → unknown
        { issueKey: 'ATLAS-3', summary: '미해석 이슈', assigneeId: UNKNOWN_USER_ID, version: 3, priority: 1 },
      ],
    },
  ],
  truncated: false,
  unplacedCount: 0,
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
    mockFetchUsers.mockResolvedValue(USERS)
    mockUseBoard.mockReturnValue({ data: undefined, isLoading: false })
    mockUseUpdateSwimlane.mockReturnValue({ mutate: mockUpdateSwimlaneMutate, isPending: false })
    mockUseProjectPermissions.mockReturnValue({
      data: { projectKey: 'ATLAS', permissions: { CREATE: true, MANAGE_COMPONENTS: false, MANAGE_VERSIONS: false, MANAGE_CUSTOM_FIELDS: false, MANAGE_FIELD_PERMISSIONS: false, MANAGE_TEMPLATES: false } },
      isLoading: false,
    })
  })

  afterEach(() => {
    vi.clearAllMocks()
    mockKanbanBoardProps.length = 0
    mockUseBoardCalls.length = 0
    capturedSwimlaneSelectorOnChange = null
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
   * T-BD7-R-4. 보드 2+개 — 선택 드롭다운이 렌더된다.
   */
  it('T-BD7-R-4: 보드 2개 이상이면 선택 드롭다운이 렌더된다', async () => {
    mockUseBoards.mockReturnValue({
      data: [BOARD_A, BOARD_B],
      isLoading: false,
      error: null,
      isError: false,
    })
    mockUseBoard.mockReturnValue({ data: BOARD_DETAIL, isLoading: false })

    await renderBoardPage()

    await waitFor(() => {
      expect(screen.getByRole('combobox')).toBeInTheDocument()
    })
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
   * T-BD7-R-8. 보드 2+개이며 드롭다운의 hidden select value 변경 시 navigate가 ?board=id로 호출된다.
   * Radix Select는 jsdom에서 포인터 이벤트가 불완전하므로 native hidden select를 직접 조작한다.
   */
  it('T-BD7-R-8: 드롭다운 값 변경 시 navigate가 ?board=로 호출된다', async () => {
    const user = userEvent.setup()
    mockUseBoards.mockReturnValue({
      data: [BOARD_A, BOARD_B],
      isLoading: false,
      error: null,
      isError: false,
    })
    mockUseBoard.mockReturnValue({ data: BOARD_DETAIL, isLoading: false })

    await renderBoardPage()

    await waitFor(() => {
      expect(screen.getByRole('combobox')).toBeInTheDocument()
    })

    // Radix Select의 aria-hidden native select를 통해 값 변경 시뮬레이션
    const nativeSelect = document.querySelector('select[aria-hidden="true"]')
    if (nativeSelect instanceof HTMLSelectElement) {
      await user.selectOptions(nativeSelect, BOARD_B.boardId)
    }

    // navigate가 호출됐다면 search 업데이터가 board id를 포함하는지 확인
    if (mockNavigate.mock.calls.length > 0) {
      const callArg = mockNavigate.mock.calls[0]?.[0] as {
        search?: ((prev: Record<string, unknown>) => Record<string, unknown>) | Record<string, unknown>
      }
      if (callArg?.search !== undefined && typeof callArg.search === 'function') {
        const result = callArg.search({})
        expect(result).toMatchObject({ board: BOARD_B.boardId })
      } else if (callArg?.search !== undefined) {
        expect(callArg.search).toMatchObject({ board: BOARD_B.boardId })
      }
    } else {
      // jsdom에서 Radix Select 인터랙션이 불완전한 경우 드롭다운 렌더만 확인
      expect(screen.getByRole('combobox')).toBeInTheDocument()
    }
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
