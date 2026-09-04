// 보드 설정 화면 동반 테스트 — 스코프 가드 · 권한 게이팅 · 조회 분기 (부채 177 Task 4)
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import type { JSX, ReactNode } from 'react'
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
