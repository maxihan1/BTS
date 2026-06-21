// 칸반 보드 라우트 페이지 단위 테스트 — BoardPage 렌더 시나리오 (FR-BD-01 Task 7)
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { BoardSummary, BoardDetail } from '@/api/boards'
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
vi.mock('@/components/board/KanbanBoard', () => ({
  KanbanBoard: ({ boardId }: { boardId: string }) => (
    <div data-testid={`kanban-board-${boardId}`}>KanbanBoard</div>
  ),
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
const mockUseBoards = vi.fn()
const mockUseBoard = vi.fn()

vi.mock('@/hooks/use-boards', () => ({
  useBoards: (projectKey: string) => mockUseBoards(projectKey),
  useBoard: (boardId: string | undefined) => mockUseBoard(boardId),
  useCreateBoard: () => ({ mutate: vi.fn(), isPending: false }),
}))

// @/api/users mock
const mockFetchUsers = vi.fn()
vi.mock('@/api/users', () => ({
  fetchUsers: () => mockFetchUsers(),
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
}

const BOARD_DETAIL_TRUNCATED: BoardDetail = {
  ...BOARD_DETAIL,
  truncated: true,
  unplacedCount: 3,
}

const USERS: UserSummary[] = [
  {
    id: 'c3d4e5f6-a7b8-4890-abcd-ef1234567893',
    username: 'alice',
    displayName: '앨리스',
    email: 'alice@example.com',
  },
]

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

async function renderBoardPage(projectKey = 'ATLAS', selectedBoardId?: string) {
  const { BoardPage } = await import('@/routes/projects.$projectKey.board')
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
  return render(
    <QueryClientProvider client={client}>
      <BoardPage projectKey={projectKey} selectedBoardId={selectedBoardId} />
    </QueryClientProvider>,
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('BoardPage', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    mockFetchUsers.mockResolvedValue(USERS)
    mockUseBoard.mockReturnValue({ data: undefined, isLoading: false })
  })

  afterEach(() => {
    vi.clearAllMocks()
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
   * T-BD7-R-8. 보드 2+개이며 드롭다운 변경 시 navigate가 ?board=id로 호출된다.
   */
  it('T-BD7-R-8: 드롭다운 변경 시 navigate가 ?board=로 호출된다', async () => {
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

    await user.click(screen.getByRole('combobox'))

    const option = await screen.findByText('스프린트 보드 B')
    await user.click(option)

    expect(mockNavigate).toHaveBeenCalledWith(
      expect.objectContaining({ search: expect.objectContaining({ board: BOARD_B.boardId }) }),
    )
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
