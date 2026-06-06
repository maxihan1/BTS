// IssueMetaPanel 권한별 삭제/저장 버튼 disabled 단위 테스트 (FR-PM-02 Task 5)
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { server } from '@/test/server'
import { http, HttpResponse } from 'msw'
import { IssueMetaPanel } from '../IssueMetaPanel'
import type { IssueResponse } from '@/api/issues'
import type { IssueTypeResponse } from '@/api/issue-types'

// useIssuePermissions 훅을 vi.mock으로 모킹 — 네트워크 없이 제어 가능하게 한다
vi.mock('@/hooks/use-issue-permissions', () => ({
  useIssuePermissions: vi.fn(),
}))

// LabelAutocompleteInput 내부 useLabels/useDebounce mock — QueryClient 없이 렌더 가능하게 한다
vi.mock('@/hooks/use-labels', () => ({
  useLabels: vi.fn().mockReturnValue({
    data: [],
    isLoading: false,
    isError: false,
    isPending: false,
    isSuccess: true,
    error: null,
    status: 'success',
    fetchStatus: 'idle',
  }),
}))
vi.mock('@/hooks/use-debounce', () => ({
  useDebounce: (value: string) => value,
}))

import { useIssuePermissions } from '@/hooks/use-issue-permissions'

// IssueSecurityLevelSelect 내부 useQuery가 호출하는 security-levels API 핸들러 등록
// 이 테스트는 권한 제어만 검증하므로 빈 배열로 응답해 UI에 영향 없이 동작하게 한다.
beforeEach(() => {
  server.use(
    http.get('/api/v1/projects/:projectKey/issue-security-scheme/levels', () =>
      HttpResponse.json({ levels: [] }),
    ),
  )
})

// ─────────────────────────────────────────────────────────────────────────────
// 공통 fixture
// ─────────────────────────────────────────────────────────────────────────────

const issueFixture: IssueResponse = {
  key: 'ATLAS-1',
  id: 'a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d',
  projectKey: 'ATLAS',
  summary: '테스트 이슈',
  currentStateKey: 'open',
  reporterId: 'b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d6e',
  assigneeId: null,
  componentIds: [],
  version: 0,
  createdAt: '2026-01-01T09:00:00Z',
  updatedAt: null,
  typeId: 1,
  typeKey: 'bug',
  typeName: '버그',
  description: null,
  descriptionHtml: null,
  priority: 3,
  priorityName: 'Medium',
  labels: [],
  environment: null,
  impact: null,
  impactName: null,
}

const typeFixture: IssueTypeResponse = {
  id: 1,
  key: 'bug',
  name: '버그',
  description: '버그',
  iconName: null,
}

function renderPanel(overrides: {
  canDelete?: boolean
  canEdit?: boolean
  isLoading?: boolean
  isError?: boolean
} = {}) {
  const { canDelete = true, canEdit = true, isLoading = false, isError = false } = overrides

  // useIssuePermissions 반환값을 원하는 대로 제어
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

  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <IssueMetaPanel
        issue={issueFixture}
        availableTypes={[typeFixture]}
        onTypeChange={vi.fn()}
        onDeleteClick={vi.fn()}
        onCloneClick={vi.fn()}
        transitions={[]}
        onTransition={vi.fn()}
        isTransitioning={false}
        onPriorityChange={vi.fn()}
        onImpactChange={vi.fn()}
        onEnvironmentSave={vi.fn()}
        onLabelsSave={vi.fn()}
        users={[]}
        onAssigneeSearch={vi.fn()}
        onAssigneeChange={vi.fn()}
        currentAssignee={null}
      />
    </QueryClientProvider>,
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueMetaPanel — 삭제 버튼 권한 제어 (FR-PM-02 Task 5)', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('T5-M1: SOFT_DELETE=true → 이슈 삭제 버튼이 활성(disabled=false)이어야 한다', () => {
    renderPanel({ canDelete: true })

    const deleteButton = screen.getByTestId('issue-delete')
    expect(deleteButton).not.toBeDisabled()
  })

  it('T5-M2: SOFT_DELETE=false → 이슈 삭제 버튼이 disabled이어야 한다', () => {
    renderPanel({ canDelete: false })

    const deleteButton = screen.getByTestId('issue-delete')
    expect(deleteButton).toBeDisabled()
  })

  it('T5-M3: SOFT_DELETE=false → 삭제 버튼에 권한 없음 사유(title/aria-label)가 표시되어야 한다', () => {
    renderPanel({ canDelete: false })

    const deleteButton = screen.getByTestId('issue-delete')
    // title 또는 aria-label 중 하나라도 사유를 포함해야 한다
    const hasReason =
      deleteButton.getAttribute('title')?.includes('삭제 권한') ||
      deleteButton.getAttribute('aria-label')?.includes('삭제 권한')
    expect(hasReason).toBe(true)
  })

  it('T5-M4: 권한 로딩 중(isLoading=true) → 이슈 삭제 버튼이 disabled이어야 한다 (fail-closed)', () => {
    renderPanel({ isLoading: true })

    const deleteButton = screen.getByTestId('issue-delete')
    expect(deleteButton).toBeDisabled()
  })

  it('T5-M5: 권한 에러(isError=true) → 이슈 삭제 버튼이 disabled이어야 한다 (fail-closed)', () => {
    renderPanel({ isError: true })

    const deleteButton = screen.getByTestId('issue-delete')
    expect(deleteButton).toBeDisabled()
  })

  it('T5-M6: UPDATE=false → 환경 저장 버튼이 disabled이어야 한다', () => {
    renderPanel({ canEdit: false })

    const envSaveButton = screen.getByTestId('environment-save')
    expect(envSaveButton).toBeDisabled()
  })

  it('T5-M7: UPDATE=false → 라벨 저장 버튼이 disabled이어야 한다', () => {
    renderPanel({ canEdit: false })

    const labelsSaveButton = screen.getByTestId('labels-save')
    expect(labelsSaveButton).toBeDisabled()
  })

  it('T5-M8: UPDATE=true → 환경/라벨 저장 버튼이 활성이어야 한다', () => {
    renderPanel({ canEdit: true })

    expect(screen.getByTestId('environment-save')).not.toBeDisabled()
    expect(screen.getByTestId('labels-save')).not.toBeDisabled()
  })

  it('T5-M9: 권한 로딩 중 → 환경/라벨 저장 버튼도 disabled이어야 한다 (fail-closed)', () => {
    renderPanel({ isLoading: true })

    expect(screen.getByTestId('environment-save')).toBeDisabled()
    expect(screen.getByTestId('labels-save')).toBeDisabled()
  })
})
