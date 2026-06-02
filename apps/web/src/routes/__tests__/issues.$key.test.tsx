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
  } as ReturnType<typeof useIssuePermissions>)
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
})
