// FR-PM-07 PR-B Task 6 — 필드 권한(restrictedFields/noneditableFields) 숨김·비활성 단위 테스트
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { server } from '@/test/server'
import { http, HttpResponse } from 'msw'
import { IssueMetaPanel } from '../IssueMetaPanel'
import type { IssueResponse } from '@/api/issues'
import type { IssueTypeResponse } from '@/api/issue-types'
import type { CustomField } from '@/api/custom-fields.types'

// useIssuePermissions — 네트워크 없이 권한 제어
vi.mock('@/hooks/use-issue-permissions', () => ({
  useIssuePermissions: vi.fn(),
}))

// LabelAutocompleteInput 내부 훅 모킹
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

// useCustomFields 모킹 — 커스텀 필드 정의 제어
vi.mock('@/hooks/use-custom-fields', () => ({
  useCustomFields: vi.fn(),
  CUSTOM_FIELD_KEYS: { list: (k: string) => ['custom-fields', k] },
}))

import { useCustomFields } from '@/hooks/use-custom-fields'

// ─────────────────────────────────────────────────────────────────────────────
// 공통 설정 (각 테스트 실행 전 초기화)
// ─────────────────────────────────────────────────────────────────────────────

beforeEach(() => {
  vi.clearAllMocks()
  // 보안등급 API — 빈 배열로 응답해 UI 간섭 없이 동작
  server.use(
    http.get('/api/v1/projects/:projectKey/issue-security-scheme/levels', () =>
      HttpResponse.json({ levels: [] }),
    ),
  )
})

// ─────────────────────────────────────────────────────────────────────────────
// 공통 fixture
// ─────────────────────────────────────────────────────────────────────────────

/** 커스텀 필드 정의 fixture — key: 'cf_region' */
const customFieldFixture: CustomField = {
  id: 'fd000001-0000-4000-8000-000000000099',
  projectId: 'pd000001-0000-4000-8000-000000000099',
  key: 'cf_region',
  name: '지역',
  description: null,
  fieldType: 'SHORT_TEXT',
  required: false,
  displayOrder: 0,
  options: [],
}

/** 기본 이슈 fixture — restrictedFields/noneditableFields 비어 있음 */
const baseIssue: IssueResponse = {
  key: 'ATLAS-99',
  id: 'a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c99',
  projectKey: 'ATLAS',
  summary: '필드권한 테스트 이슈',
  currentStateKey: 'open',
  reporterId: 'b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d99',
  assigneeId: null,
  componentIds: [],
  affectsVersionIds: [],
  fixVersionIds: [],
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
  customFields: {},
  restrictedFields: [],
  noneditableFields: [],
}

const typeFixture: IssueTypeResponse = {
  id: 1,
  key: 'bug',
  name: '버그',
  description: '버그',
  iconName: null,
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — 권한/이슈 override 조합 렌더
// ─────────────────────────────────────────────────────────────────────────────

/**
 * IssueMetaPanel을 필요한 mocking 세팅과 함께 렌더하는 헬퍼.
 *
 * @param issueOverride - 기본 baseIssue 위에 덮어쓸 이슈 필드
 * @param canEdit - UPDATE 권한 여부 (기본값 true)
 * @param fieldDefs - 커스텀 필드 정의 목록 (기본값 빈 배열)
 */
function renderPanel(
  issueOverride: Partial<IssueResponse> = {},
  canEdit = true,
  fieldDefs: CustomField[] = [],
): ReturnType<typeof render> {
  vi.mocked(useIssuePermissions).mockReturnValue({
    data: {
      issueKey: 'ATLAS-99',
      permissions: {
        UPDATE: canEdit,
        SOFT_DELETE: true,
        TRANSITION: true,
      },
    },
    isLoading: false,
    isError: false,
    isPending: false,
    isSuccess: true,
    error: null,
    status: 'success',
    fetchStatus: 'idle',
    dataUpdatedAt: 0,
    errorUpdatedAt: 0,
    failureCount: 0,
    failureReason: null,
    isFetched: true,
    isFetchedAfterMount: true,
    isFetching: false,
    isInitialLoading: false,
    isLoadingError: false,
    isPlaceholderData: false,
    isRefetchError: false,
    isRefetching: false,
    isStale: false,
    refetch: vi.fn(),
  } as unknown as ReturnType<typeof useIssuePermissions>)

  vi.mocked(useCustomFields).mockReturnValue({
    data: fieldDefs,
    isLoading: false,
    isError: false,
    isPending: false,
    isSuccess: true,
    error: null,
    status: 'success',
    fetchStatus: 'idle',
    dataUpdatedAt: 0,
    errorUpdatedAt: 0,
    failureCount: 0,
    failureReason: null,
    isFetched: true,
    isFetchedAfterMount: true,
    isFetching: false,
    isInitialLoading: false,
    isLoadingError: false,
    isPlaceholderData: false,
    isRefetchError: false,
    isRefetching: false,
    isStale: false,
    refetch: vi.fn(),
  } as unknown as ReturnType<typeof useCustomFields>)

  const issue: IssueResponse = { ...baseIssue, ...issueOverride }
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })

  return render(
    <QueryClientProvider client={client}>
      <IssueMetaPanel
        issue={issue}
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
        reporter={null}
      />
    </QueryClientProvider>,
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// T6-A: restrictedFields — 해당 필드 섹션 숨김
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueMetaPanel — restrictedFields 숨김 (FR-PM-07 Task 6)', () => {
  it('T6-A1: restrictedFields에 "environment"가 포함되면 환경 섹션이 렌더되지 않는다', () => {
    renderPanel({ restrictedFields: ['environment'] })

    expect(screen.queryByTestId('environment-section')).not.toBeInTheDocument()
  })

  it('T6-A2: restrictedFields에 "labels"가 포함되면 라벨 섹션이 렌더되지 않는다', () => {
    renderPanel({ restrictedFields: ['labels'] })

    expect(screen.queryByTestId('labels-section')).not.toBeInTheDocument()
  })

  it('T6-A3: restrictedFields에 "assigneeId"가 포함되면 담당자 섹션이 렌더되지 않는다', () => {
    renderPanel({ restrictedFields: ['assigneeId'] })

    expect(screen.queryByTestId('assignee-section')).not.toBeInTheDocument()
  })

  it('T6-A4: restrictedFields에 커스텀 필드 키 "cf_region"이 포함되면 해당 커스텀 필드 입력이 렌더되지 않는다', () => {
    renderPanel(
      { restrictedFields: ['cf_region'] },
      true,
      [customFieldFixture],
    )

    // 커스텀 필드 섹션 자체는 다른 필드가 없어도 렌더될 수 있으나,
    // cf_region 입력 요소는 존재하면 안 된다.
    expect(screen.queryByTestId('custom-field-cf_region')).not.toBeInTheDocument()
  })

  it('T6-A5: restrictedFields가 빈 배열이면 environment/labels/assignee 섹션이 모두 정상 렌더된다', () => {
    renderPanel({ restrictedFields: [] })

    expect(screen.getByTestId('environment-section')).toBeInTheDocument()
    expect(screen.getByTestId('labels-section')).toBeInTheDocument()
    expect(screen.getByTestId('assignee-section')).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T6-B: noneditableFields — 해당 필드 편집 컨트롤 disabled
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueMetaPanel — noneditableFields 비활성 (FR-PM-07 Task 6)', () => {
  it('T6-B1: noneditableFields에 "environment"가 포함되면 환경 저장 버튼이 disabled이어야 한다', () => {
    renderPanel({ noneditableFields: ['environment'] }, true)

    expect(screen.getByTestId('environment-save')).toBeDisabled()
  })

  it('T6-B2: noneditableFields에 "labels"가 포함되면 라벨 저장 버튼이 disabled이어야 한다', () => {
    renderPanel({ noneditableFields: ['labels'] }, true)

    expect(screen.getByTestId('labels-save')).toBeDisabled()
  })

  it('T6-B3: noneditableFields에 커스텀 필드 키 "cf_region"이 포함되면 해당 CustomFieldInput이 disabled이어야 한다', () => {
    renderPanel(
      { noneditableFields: ['cf_region'] },
      true,
      [customFieldFixture],
    )

    const input = screen.getByTestId('custom-field-cf_region')
    expect(input).toBeDisabled()
  })

  it('T6-B4: noneditableFields가 빈 배열이고 canEdit=true이면 환경/라벨 저장 버튼이 활성이어야 한다', () => {
    renderPanel({ noneditableFields: [] }, true)

    expect(screen.getByTestId('environment-save')).not.toBeDisabled()
    expect(screen.getByTestId('labels-save')).not.toBeDisabled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T6-C: canEdit(useIssuePermissions UPDATE)과 noneditableFields의 AND 조합
//        — 둘 중 하나라도 막으면 disabled
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueMetaPanel — canEdit AND noneditableFields 조합 (FR-PM-07 Task 6)', () => {
  it('T6-C1: canEdit=false + noneditableFields=[] → 환경 저장 버튼이 disabled이어야 한다 (canEdit이 막음)', () => {
    renderPanel({ noneditableFields: [] }, false)

    expect(screen.getByTestId('environment-save')).toBeDisabled()
  })

  it('T6-C2: canEdit=true + noneditableFields=["environment"] → 환경 저장 버튼이 disabled이어야 한다 (noneditableFields가 막음)', () => {
    renderPanel({ noneditableFields: ['environment'] }, true)

    expect(screen.getByTestId('environment-save')).toBeDisabled()
  })

  it('T6-C3: canEdit=false + noneditableFields=["environment"] → 환경 저장 버튼이 disabled이어야 한다 (둘 다 막음)', () => {
    renderPanel({ noneditableFields: ['environment'] }, false)

    expect(screen.getByTestId('environment-save')).toBeDisabled()
  })

  it('T6-C4: canEdit=true + noneditableFields=[] → 환경 저장 버튼이 활성이어야 한다 (둘 다 허용)', () => {
    renderPanel({ noneditableFields: [] }, true)

    expect(screen.getByTestId('environment-save')).not.toBeDisabled()
  })

  it('T6-C5: canEdit=false + noneditableFields=["cf_region"] → CustomFieldInput이 disabled이어야 한다 (둘 다 막음)', () => {
    renderPanel(
      { noneditableFields: ['cf_region'] },
      false,
      [customFieldFixture],
    )

    const input = screen.getByTestId('custom-field-cf_region')
    expect(input).toBeDisabled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T6-D: summary/priority는 restrictedFields에 포함되지 않음 (백엔드 계약 확인)
//        — 이 필드들은 렌더 차단 경로가 없어야 한다
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueMetaPanel — summary/priority는 restrictedFields 대상이 아님 (FR-PM-07 Task 6)', () => {
  it('T6-D1: restrictedFields=["summary"] 여도 priority 셀렉터는 렌더된다 (non-null 필드 — 백엔드가 안 보냄)', () => {
    // summary는 메타패널이 아닌 상위 레이아웃에서 렌더하므로 여기서는
    // priority 셀렉터가 정상 노출됨을 확인한다
    renderPanel({ restrictedFields: ['summary'] })

    // priority 셀렉터 — aria-label로 찾기
    const prioritySelect = screen.getByRole('combobox', { name: /우선순위/i })
    expect(prioritySelect).toBeInTheDocument()
  })

  it('T6-D2: restrictedFields=["priority"] 여도 priority 셀렉터가 여전히 렌더된다 (non-null 필드는 UI에서 숨기지 않음)', () => {
    // 백엔드 스펙상 priority는 non-null이라 restrictedFields에 안 들어오지만,
    // 혹시 포함되어도 UI가 graceful하게 동작(숨기지 않음)해야 한다.
    // 이 케이스에서는 priority 섹션을 숨기는 로직이 없어야 하므로 셀렉터가 보여야 한다.
    renderPanel({ restrictedFields: ['priority'] })

    const prioritySelect = screen.getByRole('combobox', { name: /우선순위/i })
    expect(prioritySelect).toBeInTheDocument()
  })
})
