// 보드 라우트 이슈 생성 진입점 배선 테스트 — 배선 3점만 (FR-UX-09 F3 T7, design 리뷰 DR-7)
//
// ★범위를 좁힌 이유.
// 이 라우트는 600줄 + 훅 5종 + 라우터 의존이라 전개가 비싸다. fail-closed 판정 자체는
// `CreateIssueEntryButton` 단독 시험대가 이미 갖고 있으므로 여기서 다시 펴지 않는다.
// 여기서 보는 것은 **배선**뿐이다 — 진입점이 있는가 · 권한을 그대로 내리는가 · 모달이 열리는가.
import { describe, it, expect, vi, beforeEach } from 'vitest'
import type { ReactNode } from 'react'
import { render, screen } from '@testing-library/react'
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

vi.mock('@/hooks/use-boards', () => ({
  useBoards: () => ({ data: [], isLoading: false, isError: false, error: null }),
  useBoard: () => ({ data: undefined, isLoading: false }),
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

function renderBoardPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={qc}>
      <BoardPage projectKey="ATLAS" selectedBoardId={undefined} filter={null} />
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  mockPermissions.mockReturnValue({ data: { permissions: { CREATE: true } } })
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
