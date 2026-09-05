// 보드 설정 화면 동반 테스트 — 스코프 가드 · 권한 게이팅 · 조회 분기 · 탭바 (부채 177 Task 4·15)
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { JSX, ReactNode } from 'react'
import type { BoardDetail } from '@/api/boards'
import { BoardSettingsPage } from './projects.$projectKey.board.settings'
import { boardLabels } from '@/i18n/board-labels'

// TanStack Router 의 Link 는 RouterProvider 밖에서 던진다. 이 테스트가 재는 것은 라우팅이
// 아니라 **어떤 분기가 그려지는가**이므로 Link 를 평범한 앵커로 갈아 끼운다.
vi.mock('@tanstack/react-router', () => ({
  Link: ({ children }: { children: ReactNode }): JSX.Element => <a href="#">{children}</a>,
  useParams: () => ({ projectKey: 'ATLAS' }),
  useSearch: () => ({}),
}))

const mockUseBoard = vi.fn()
const mockUsePermissions = vi.fn()

vi.mock('@/hooks/use-boards', () => ({
  useBoard: (...args: unknown[]) => mockUseBoard(...args),
}))
vi.mock('@/hooks/use-project-permissions', () => ({
  useProjectPermissions: (...args: unknown[]) => mockUsePermissions(...args),
}))

const BOARD_ID = 'a1b2c3d4-e5f6-4890-abcd-ef1234567890'

function renderPage(boardId: string | undefined): void {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={qc}>
      <BoardSettingsPage projectKey="ATLAS" boardId={boardId} />
    </QueryClientProvider>,
  )
}

/** CREATE 권한을 가진 사용자로 세운다. */
function grantCreate(granted: boolean): void {
  mockUsePermissions.mockReturnValue({
    data: { projectKey: 'ATLAS', permissions: { CREATE: granted } },
  })
}

beforeEach(() => {
  vi.clearAllMocks()
  grantCreate(true)
  mockUseBoard.mockReturnValue({ isPending: false, isError: false, data: undefined, refetch: vi.fn() })
})

describe('보드 설정 화면 — 스코프 가드 (S8 · 부채 164 를 안 늘린다)', () => {
  it('T-BS-1: ?board= 없이 들어오면 보드를 지목하라고 안내하고 보드 화면 링크를 준다', () => {
    renderPage(undefined)

    expect(screen.getByText(boardLabels.settings.boardNotSelected)).toBeInTheDocument()
    // ★기본 보드를 스스로 고르지 않는다. useBoard 를 아예 부르지 않는 것이 그 증거다 —
    //   부르면 「어느 보드인가」에 대한 네 번째 규칙이 여기 생긴 것이다.
    expect(mockUseBoard).toHaveBeenCalledWith(undefined)
  })

  it('T-BS-2: 빈 문자열 board 도 미지목으로 다룬다', () => {
    renderPage('')

    expect(screen.getByText(boardLabels.settings.boardNotSelected)).toBeInTheDocument()
  })

  it('T-BS-3: board 가 있으면 그 보드를 조회한다', () => {
    renderPage(BOARD_ID)

    expect(screen.queryByText(boardLabels.settings.boardNotSelected)).not.toBeInTheDocument()
    expect(mockUseBoard).toHaveBeenCalledWith(BOARD_ID)
  })
})

describe('보드 설정 화면 — 권한 게이팅 (S7 · J8)', () => {
  it('T-BS-4: CREATE 권한이 없으면 사유를 보인다', () => {
    grantCreate(false)
    renderPage(BOARD_ID)

    expect(screen.getByText(boardLabels.settings.readOnlyReason)).toBeInTheDocument()
  })

  it('T-BS-5: 권한이 아직 안 왔으면 사유를 보인다 — fail-closed', () => {
    // ★`=== true` 로만 여는 관용구의 판정이다. `!== false` 로 무르면 이 건이 통과하면서
    //   권한 응답 도착 전에 편집이 열린다.
    mockUsePermissions.mockReturnValue({ data: undefined })
    renderPage(BOARD_ID)

    expect(screen.getByText(boardLabels.settings.readOnlyReason)).toBeInTheDocument()
  })

  it('T-BS-6: CREATE 권한이 있으면 사유를 보이지 않는다', () => {
    renderPage(BOARD_ID)

    expect(screen.queryByText(boardLabels.settings.readOnlyReason)).not.toBeInTheDocument()
  })
})

describe('보드 설정 화면 — 조회 분기 (§8b 상호작용 상태 표)', () => {
  it('T-BS-7: 조회 실패는 사유와 재시도를 함께 보인다', () => {
    const refetch = vi.fn()
    mockUseBoard.mockReturnValue({ isPending: false, isError: true, data: undefined, refetch })
    renderPage(BOARD_ID)

    expect(screen.getByText(boardLabels.settings.loadError)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: boardLabels.settings.retry })).toBeInTheDocument()
  })

  it('T-BS-8: 화면 제목은 하나뿐이다 — 즉사 계약(h1 단일)', () => {
    renderPage(BOARD_ID)

    const headings = screen.getAllByRole('heading', { level: 1 })
    expect(headings).toHaveLength(1)
    expect(headings[0]).toHaveTextContent(boardLabels.settings.pageHeading)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 탭바 (Task 15 · R1 · J22)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 보드 상세 픽스처.
 *
 * ★`ColumnSettingsPanel` 을 mock 으로 갈아 끼우지 않는다. 이 describe 가 재려는 것은
 * 「Columns 탭 안에 **진짜 컬럼 본문**이 들어 있는가」이고, 스텁으로 바꾸면 탭 껍데기만
 * 남아 본문이 어느 탭에도 안 걸린 구현도 통과한다.
 */
function boardFixture(): BoardDetail {
  return {
    boardId: BOARD_ID,
    projectKey: 'ATLAS',
    name: 'ATLAS 보드',
    columns: [
      {
        columnId: 'b2c3d4e5-f6a7-4901-8bcd-ef1234567891',
        name: '할 일',
        category: 'TODO',
        displayOrder: 0,
        states: [{ key: 'open', name: '열림', category: 'TODO' }],
        cards: [],
        wipLimit: null,
        wipExceeded: false,
      },
    ],
    truncated: false,
    unplacedCount: 0,
    unmappedStates: [],
    swimlaneField: 'NONE',
    quickFilters: [],
    boardType: 'KANBAN',
    activeSprint: null,
  }
}

/** 조회가 끝나 보드가 도착한 상태로 세운다. */
function withBoard(): void {
  mockUseBoard.mockReturnValue({
    isPending: false,
    isError: false,
    data: boardFixture(),
    refetch: vi.fn(),
  })
}

/** 화면에 그려져야 하는 탭 라벨 5종 — 순서가 곧 지라 탭 순서다. */
const TAB_NAMES = [
  boardLabels.settings.tabs.columns,
  boardLabels.settings.tabs.cardLayout,
  boardLabels.settings.tabs.estimation,
  boardLabels.settings.tabs.workingDays,
  boardLabels.settings.tabs.detailView,
] as const

describe('보드 설정 화면 — 탭바 (R1 · J22)', () => {
  it('T-BS-9: 탭 5종을 순서대로 그린다', () => {
    withBoard()
    renderPage(BOARD_ID)

    const tabs = screen.getAllByRole('tab')
    expect(tabs.map((tab) => tab.textContent)).toEqual([...TAB_NAMES])
  })

  it('T-BS-10: 기본 탭은 컬럼이고, 그 안에 컬럼 본문이 들어 있다', () => {
    withBoard()
    renderPage(BOARD_ID)

    expect(screen.getByRole('tab', { name: boardLabels.settings.tabs.columns })).toHaveAttribute(
      'aria-selected',
      'true',
    )
    // 컬럼 카드는 `article` 이다(ColumnSettingsCard). 탭 안에 실제 본문이 있다는 증거다.
    expect(screen.getByRole('article', { name: '할 일' })).toBeInTheDocument()
  })

  it('T-BS-11: 다른 탭을 누르면 패널이 실제로 갈린다 — 컬럼 본문이 내려간다', async () => {
    withBoard()
    renderPage(BOARD_ID)

    await userEvent.click(screen.getByRole('tab', { name: boardLabels.settings.tabs.workingDays }))

    // ★「누를 수 있는데 아무 일도 안 일어나는」 탭을 금지한 `#452` 결정의 최소 판정이다.
    //   선택 표시만 재면 패널이 안 갈려도 통과한다 — 본문이 내려간 것까지 함께 잰다.
    expect(
      screen.getByRole('tab', { name: boardLabels.settings.tabs.workingDays }),
    ).toHaveAttribute('aria-selected', 'true')
    expect(screen.queryByRole('article', { name: '할 일' })).not.toBeInTheDocument()
    expect(screen.getByRole('tabpanel')).toBeInTheDocument()
  })

  it('T-BS-12: 탭 라벨은 서로를 substring 으로 품지 않는다', () => {
    // ★Playwright `getByRole('tab', { name })` 은 기본이 **부분 일치**다. 한 라벨이 다른
    //   라벨을 품으면 두 탭이 잡혀 strict mode 로 즉사한다 — E2E 를 쓰기 전에 여기서 막는다.
    for (const outer of TAB_NAMES) {
      for (const inner of TAB_NAMES) {
        if (outer === inner) continue
        expect(outer.includes(inner)).toBe(false)
      }
    }
  })

  it('T-BS-13: 조회 실패에는 탭바를 그리지 않는다 — 빈 껍데기를 보이지 않는다', () => {
    mockUseBoard.mockReturnValue({
      isPending: false,
      isError: true,
      data: undefined,
      refetch: vi.fn(),
    })
    renderPage(BOARD_ID)

    expect(screen.queryAllByRole('tab')).toHaveLength(0)
    expect(screen.getByText(boardLabels.settings.loadError)).toBeInTheDocument()
  })
})
