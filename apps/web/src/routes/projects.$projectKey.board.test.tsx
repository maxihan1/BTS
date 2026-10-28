// 보드 라우트 이슈 생성 진입점 배선 테스트 — 배선 3점만 (FR-UX-09 F3 T7, design 리뷰 DR-7)
//
// ★범위를 좁힌 이유.
// 이 라우트는 600줄 + 훅 5종 + 라우터 의존이라 전개가 비싸다. fail-closed 판정 자체는
// `CreateIssueEntryButton` 단독 시험대가 이미 갖고 있으므로 여기서 다시 펴지 않는다.
// 여기서 보는 것은 **배선**뿐이다 — 진입점이 있는가 · 권한을 그대로 내리는가 · 모달이 열리는가.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import type { ReactNode } from 'react'
import { render, screen, within } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'

vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => vi.fn(),
  useParams: () => ({ projectKey: 'ATLAS' }),
  useSearch: () => ({}),
  Link: ({ to, children }: { to: string; children: ReactNode }) => <a href={to}>{children}</a>,
}))

// 생성 모달은 스텁 — 이 파일의 관심사는 배선이다 (BacklogBoard.test 와 같은 이유).
// 진짜 모달의 열림·제출은 E2E(T8)가 본다.
vi.mock('@/components/issue/CreateIssueDialog', () => ({
  CreateIssueDialog: ({ open }: { open: boolean }) =>
    open ? <div role="dialog" aria-label="이슈 생성 모달 스텁" /> : null,
}))

vi.mock('@/components/favorite/FavoriteButton', () => ({
  FavoriteButton: () => <button type="button">즐겨찾기 스텁</button>,
}))

const mockPermissions = vi.fn()
const mockBoards = vi.fn()
const mockBoard = vi.fn()

vi.mock('@/hooks/use-boards', async (importOriginal) => ({
  // 툴바 배선 테스트는 보드 `⋯` 메뉴(이름 변경 · 삭제 훅)까지 그린다 — 나머지 export 는 진짜를 쓴다
  ...(await importOriginal<typeof import('@/hooks/use-boards')>()),
  useBoards: () => mockBoards() as unknown,
  useBoard: () => mockBoard() as unknown,
  useCreateBoard: () => ({ mutate: vi.fn(), isPending: false }),
}))

vi.mock('@/hooks/use-project-permissions', () => ({
  useProjectPermissions: () => mockPermissions() as unknown,
}))

vi.mock('@/hooks/use-update-swimlane', () => ({
  useUpdateSwimlane: () => ({ mutate: vi.fn() }),
}))

import { BoardPage } from './projects.$projectKey.board'
import { boardLabels } from '@/i18n/board-labels'
import { backlogLabels } from '@/i18n/backlog-labels'

function renderBoardPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <BoardPage projectKey="ATLAS" selectedBoardId={undefined} />
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  mockPermissions.mockReturnValue({ data: { permissions: { CREATE: true } } })
  mockBoards.mockReturnValue({ data: [], isLoading: false, isError: false, error: null })
  mockBoard.mockReturnValue({ data: undefined, isLoading: false })
})

describe('보드 라우트 — 이슈 생성 진입점 배선 (F3 FR-3)', () => {
  it('보드 헤더에 진입점이 있다 (컬럼별이 아니다)', () => {
    renderBoardPage()

    expect(
      screen.getByRole('button', { name: boardLabels.page.createIssue }),
    ).toBeInTheDocument()
  })

  it('★권한 조회 결과를 그대로 내린다 — CREATE 가 없으면 비활성 (fail-closed)', () => {
    mockPermissions.mockReturnValue({ data: { permissions: { CREATE: false } } })
    renderBoardPage()

    expect(screen.getByRole('button', { name: boardLabels.page.createIssue })).toBeDisabled()
  })

  it('★권한 조회가 아직 안 끝났으면 비활성이다 (로딩도 fail-closed)', () => {
    mockPermissions.mockReturnValue({ data: undefined })
    renderBoardPage()

    expect(screen.getByRole('button', { name: boardLabels.page.createIssue })).toBeDisabled()
  })

  it('진입점을 누르면 모달이 열린다', async () => {
    const user = userEvent.setup()
    renderBoardPage()

    expect(screen.queryByRole('dialog')).toBeNull()
    await user.click(screen.getByRole('button', { name: boardLabels.page.createIssue }))

    expect(await screen.findByRole('dialog')).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 보드 툴바 — 텍스트 검색 · 스프린트 완료 · 남은 일수 배선 (R3 · JB-B-3 · JB-B-4)
// ─────────────────────────────────────────────────────────────────────────────

describe('보드 라우트 — 툴바 배선 (JB-B-3 · JB-B-4)', () => {
  const BOARD_ID = '00000000-0000-4000-8000-0000000000b1'
  const card = (issueKey: string, summary: string) => ({
    issueKey, summary, assigneeId: null, version: 1, priority: 3, epicKey: null, rank: null,
    typeKey: 'task', labels: [], originalEstimateSeconds: null,
  })

  beforeEach(() => {
    server.use(
      http.get('/api/v1/users', () => HttpResponse.json({ data: [] })),
      http.get('/api/v1/issue-types', () => HttpResponse.json({ data: [] })),
      http.get('/api/v1/projects/ATLAS/components', () => HttpResponse.json({ data: [] })),
    )
    mockBoards.mockReturnValue({
      data: [{ boardId: BOARD_ID, projectKey: 'ATLAS', name: 'ATLAS 보드', boardType: 'SCRUM' }],
      isLoading: false, isError: false, error: null,
    })
    mockBoard.mockReturnValue({
      isLoading: false,
      data: {
        boardId: BOARD_ID, projectKey: 'ATLAS', name: 'ATLAS 보드', boardType: 'SCRUM',
        activeSprint: { sprintId: '00000000-0000-4000-8000-0000000000a1', name: '스프린트 1', startDate: '2026-10-20', endDate: '2099-01-01' },
        truncated: false, unplacedCount: 0, unmappedStates: [], swimlaneField: 'NONE', quickFilters: [],
        columns: [{
          columnId: '00000000-0000-4000-8000-0000000000c1', name: '할 일', category: 'TODO', displayOrder: 1,
          states: [{ key: 'todo', name: '할 일', category: 'TODO' }], wipLimit: null, wipExceeded: false,
          cards: [card('ATLAS-1', '로그인 버그'), card('ATLAS-2', '결제 화면')],
        }],
      },
    })
  })

  it('보드 검색에 입력하면 요약이 맞는 카드만 남는다', async () => {
    const user = userEvent.setup()
    renderBoardPage()
    expect(screen.getByText('결제 화면')).toBeInTheDocument()

    await user.type(screen.getByRole('searchbox', { name: boardLabels.page.search }), '로그인')

    expect(screen.getByText('로그인 버그')).toBeInTheDocument()
    expect(screen.queryByText('결제 화면')).toBeNull()
  })

  it('활성 스프린트가 있으면 남은 일수와 「스프린트 완료」가 보드 헤더에 있다', () => {
    renderBoardPage()
    const header = screen.getByTestId('board-header')
    expect(within(header).getByText(/일 남음$/)).toBeInTheDocument()
    expect(within(header).getByRole('button', { name: backlogLabels.completeSprint })).toBeInTheDocument()
  })
})
