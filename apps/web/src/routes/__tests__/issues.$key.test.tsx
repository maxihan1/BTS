// 이슈 상세 페이지 권한별 제목/본문 편집 버튼 disabled 단위 테스트 (FR-PM-02 Task 5)
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { server } from '@/test/server'
import { issueHandlers } from '@/mocks/issue-handlers'
import { issuePermissionHandlers } from '@/mocks/issue-permission-handlers'
import { issueTypeHandlers } from '@/mocks/issue-type-handlers'
import { workflowHandlers } from '@/mocks/workflow-handlers'
import { userHandlers } from '@/mocks/user-handlers'
import { IssueDetailPage } from '@/routes/issues.$key'
import { useRecentIssues, RECENT_ISSUES_STORAGE_KEY } from '@/hooks/use-recent-issues'

// TanStack Router useNavigate mock
vi.mock('@tanstack/react-router', () => ({
  useParams: vi.fn(),
  useNavigate: () => vi.fn(),
}))

// useIssuePermissions 훅을 vi.mock으로 모킹 — 권한 시나리오를 자유롭게 제어한다
vi.mock('@/hooks/use-issue-permissions', () => ({
  useIssuePermissions: vi.fn(),
}))

import { useIssuePermissions } from '@/hooks/use-issue-permissions'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function makeClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
}

function mockPermissions(overrides: {
  canEdit?: boolean
  canDelete?: boolean
  isLoading?: boolean
  isError?: boolean
} = {}) {
  const { canEdit = true, canDelete = true, isLoading = false, isError = false } = overrides

  vi.mocked(useIssuePermissions).mockReturnValue({
    data: isLoading || isError ? undefined : {
      issueKey: 'ATLAS-1',
      permissions: {
        UPDATE: canEdit,
        SOFT_DELETE: canDelete,
        TRANSITION: true,
      },
    },
    isLoading,
    isError,
    isPending: isLoading,
    isSuccess: !isLoading && !isError,
    error: null,
    status: isLoading ? 'pending' : isError ? 'error' : 'success',
    fetchStatus: isLoading ? 'fetching' : 'idle',
    dataUpdatedAt: 0,
    errorUpdatedAt: 0,
    failureCount: 0,
    failureReason: null,
    isFetched: !isLoading,
    isFetchedAfterMount: !isLoading,
    isFetching: isLoading,
    isInitialLoading: isLoading,
    isLoadingError: false,
    isPlaceholderData: false,
    isRefetchError: false,
    isRefetching: false,
    isStale: false,
    refetch: vi.fn(),
  } as unknown as ReturnType<typeof useIssuePermissions>)
}

function renderPage(issueKey = 'ATLAS-1') {
  return render(
    <QueryClientProvider client={makeClient()}>
      <IssueDetailPage issueKey={issueKey} />
    </QueryClientProvider>,
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueDetailPage — 권한별 제목/편집 버튼 제어 (FR-PM-02 Task 5)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    server.use(
      ...issueHandlers,
      ...issuePermissionHandlers,
      ...issueTypeHandlers,
      ...workflowHandlers,
      ...userHandlers,
    )
  })

  it('T5-D1: UPDATE=true → 이슈 로드 후 제목 수정 버튼이 활성이어야 한다', async () => {
    mockPermissions({ canEdit: true })
    renderPage()

    await waitFor(() => {
      expect(screen.getByLabelText('✎ 제목 수정')).not.toBeDisabled()
    })
  })

  it('T5-D2: UPDATE=false → 제목 수정 버튼이 disabled이어야 한다', async () => {
    mockPermissions({ canEdit: false })
    renderPage()

    await waitFor(() => {
      const editButton = screen.getByLabelText('✎ 제목 수정')
      expect(editButton).toBeDisabled()
    })
  })

  it('T5-D3: UPDATE=true → 제목 편집 진입 후 저장 버튼이 활성이어야 한다', async () => {
    mockPermissions({ canEdit: true })
    const user = userEvent.setup()
    renderPage()

    // 제목 수정 버튼 클릭 → 편집 모드 진입
    await waitFor(() => screen.getByLabelText('✎ 제목 수정'))
    await user.click(screen.getByLabelText('✎ 제목 수정'))

    const saveButton = screen.getByTestId('issue-title-save')
    expect(saveButton).not.toBeDisabled()
  })

  it('T5-D4: UPDATE=false 로딩 중(isLoading=true) → 제목 수정 버튼이 disabled이어야 한다 (fail-closed)', async () => {
    mockPermissions({ isLoading: true })
    renderPage()

    await waitFor(() => {
      const editButton = screen.getByLabelText('✎ 제목 수정')
      expect(editButton).toBeDisabled()
    })
  })

  it('T5-D5: UPDATE=false 에러(isError=true) → 제목 수정 버튼이 disabled이어야 한다 (fail-closed)', async () => {
    mockPermissions({ isError: true })
    renderPage()

    await waitFor(() => {
      const editButton = screen.getByLabelText('✎ 제목 수정')
      expect(editButton).toBeDisabled()
    })
  })

  // ───────────────────────────────────────────────────────────────────────────
  // FR-UX-08 PR-B — 최근 본 이슈 기록 (FR4 · S8/S9 · E5)
  //
  // 새 파일(`issues.$key.recent.test.tsx`)이 아니라 이 파일에 넣는다 — 같은 대상을 두
  // 파일이 나눠 보면 한쪽을 고치는 사람이 다른 쪽을 못 본다(「두 목록이 서로를 안 본다」).
  // 같은 PR의 FR15-b가 `nav-labels` 테스트 2벌을 통합한 것과 같은 판단이다.
  // ───────────────────────────────────────────────────────────────────────────
  describe('최근 본 이슈 기록 (FR-UX-08 PR-B FR4)', () => {
    beforeEach(() => {
      localStorage.clear()
      useRecentIssues.setState({ recentIssueKeys: [] })
      mockPermissions()
    })

    it('T-RV-1 (S8): 이슈 조회에 성공하면 그 키가 최근 목록 맨 앞에 기록된다', async () => {
      renderPage('ATLAS-1')

      await waitFor(() => {
        expect(useRecentIssues.getState().recentIssueKeys).toEqual(['ATLAS-1'])
      })
    })

    it('T-RV-2 (FR4): 조회가 404로 실패하면 기록하지 않는다', async () => {
      // 조회 전에 기록하면 죽은 키가 목록을 오염시키고, 그 키는 다음 마운트에서 또 실패한다.
      renderPage('ATLAS-NOSUCH')

      // 조회가 끝날 시간을 준 뒤에도 목록이 비어 있어야 한다.
      await waitFor(() => {
        expect(screen.queryByText('로딩 중...')).not.toBeInTheDocument()
      })
      expect(useRecentIssues.getState().recentIssueKeys).toEqual([])
    })

    it('T-RV-3 (E5): 같은 이슈를 다시 열어도 목록이 늘어나지 않는다', async () => {
      const first = renderPage('ATLAS-1')
      await waitFor(() => {
        expect(useRecentIssues.getState().recentIssueKeys).toEqual(['ATLAS-1'])
      })
      first.unmount()

      renderPage('ATLAS-1')
      await waitFor(() => {
        expect(useRecentIssues.getState().recentIssueKeys).toEqual(['ATLAS-1'])
      })
    })

    it('T-RV-4 (NFR1): 기록되는 값은 이슈 키뿐이다 — 제목이 저장되지 않는다', async () => {
      renderPage('ATLAS-1')

      await waitFor(() => {
        expect(useRecentIssues.getState().recentIssueKeys).toEqual(['ATLAS-1'])
      })

      const raw = localStorage.getItem(RECENT_ISSUES_STORAGE_KEY) ?? '[]'
      const parsed: unknown = JSON.parse(raw)
      for (const entry of parsed as unknown[]) {
        expect(entry as string).toMatch(/^[A-Z][A-Z0-9]*-\d+$/)
      }
    })
  })
})
