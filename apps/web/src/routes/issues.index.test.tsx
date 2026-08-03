// 이슈 목록 페이지 단위 테스트 — 3-상태 + 빈 상태 + 페이지네이션 + CREATE 권한 게이트 + 일괄 선택/액션 + 필터 결선 + 테이블/정렬/컬럼 결선 (Task 5)
import { useState } from 'react'
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { issueHandlers } from '@/mocks/issue-handlers'
import {
  issuePageFixture,
  emptyIssuePageFixture,
  issuePageFirstFixture,
  issuePageLastFixture,
  ISSUE_FILTER_BOB_ID,
  ISSUE_FILTER_COMP_A_ID,
} from '@/mocks/issue-fixtures'
import { nonMemberProjectPermissions } from '@/mocks/project-permission-handlers'
import { workflowHandlers } from '@/mocks/workflow-handlers'
import { userHandlers } from '@/mocks/user-handlers'
import { componentHandlers } from '@/mocks/component-handlers'
import { labelHandlers } from '@/mocks/label-handlers'
import { projectListHandlers, projectListFixtures } from '@/mocks/project-list-handlers'
import { useActiveProject } from '@/hooks/use-active-project'
import { IssueListPage, IssueListRouteAdapter } from './issues.index'
import { useContextShortcutsStore } from '@/components/keyboard-shortcuts/useContextShortcuts'
import type { IssueFilterParams } from '@/api/issues'
import type { IssueTableSortState } from '@/components/issues/IssueTable'

// ─────────────────────────────────────────────────────────────────────────────
// split view 라우트 어댑터 테스트용 mock — useMediaQuery + useNavigate/useSearch
// (FR-UX-06 Phase 5 PR20 Task 5)
// ─────────────────────────────────────────────────────────────────────────────

/** IssueListRouteAdapter가 읽는 /issues search 파라미터 — router.ts validateSearch 미러 */
interface IssuesRouteSearchMock {
  page?: number
  status?: string | string[]
  assignee?: string | string[]
  label?: string | string[]
  component?: string | string[]
  sort?: string
  selected?: string
  /** FR-UX-07 — 활성 프로젝트 명시 지정 (router.ts validateSearch 미러) */
  projectKey?: string
}

/** 좁은폭/와이드 분기 제어용 mock — 기본값 true(와이드) */
const mockUseMediaQuery = vi.fn((): boolean => true)
vi.mock('@/hooks/use-media-query', () => ({
  useMediaQuery: () => mockUseMediaQuery(),
}))

const mockNavigate = vi.fn()
const mockUseSearch = vi.fn((): IssuesRouteSearchMock => ({}))
vi.mock('@tanstack/react-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@tanstack/react-router')>()
  return {
    ...actual,
    useNavigate: () => mockNavigate,
    useSearch: () => mockUseSearch(),
  }
})

/**
 * IssueDetailPage(issues.$key.tsx) mock — split view 어댑터의 결선(prop 전달)만 검증한다.
 * IssueDetailPage 자체 동작(variant 분기·onClose/Escape/redirect/삭제 콜백)은
 * issues.$key.test.tsx Task 1이 이미 단위 테스트하므로 중복하지 않는다(어댑터 경계만 검증).
 *
 * "잔여 상태 토글" 버튼 + useState(dirty) — CONCERNS-3(react-usestate-stale-key-prop) 회귀 검증용.
 * 실제 IssueDetailPage의 confirmDelete/isEditingTitle 등 잔여 useState를 축소 시뮬레이션한다.
 * key={selected}로 인스턴스가 재마운트되면 초기화(marker 소멸)되고, key 없이 재사용되면
 * 다음 이슈로 이월(marker 유지)된다.
 */
vi.mock('./issues.$key', () => ({
  IssueDetailPage: (props: {
    issueKey: string
    variant?: 'page' | 'pane'
    onClose?: () => void
    onIssueRedirect?: (newKey: string) => void
    onIssueClosed?: () => void
  }) => {
    const variant = props.variant ?? 'page'
    const [dirty, setDirty] = useState(false)
    return (
      <div data-testid="mock-issue-detail-pane">
        {variant === 'pane' ? <h2>{props.issueKey}</h2> : <h1>{props.issueKey}</h1>}
        <button type="button" onClick={() => props.onClose?.()}>페인 닫기</button>
        <button type="button" onClick={() => props.onIssueRedirect?.('NEW-1')}>페인 리다이렉트</button>
        <button type="button" onClick={() => props.onIssueClosed?.()}>페인 삭제</button>
        <button type="button" onClick={() => setDirty(true)}>잔여 상태 토글</button>
        {dirty && <span data-testid="pane-dirty-marker">잔여상태</span>}
      </div>
    )
  },
}))

/** bulk-update POST 202 응답 fixture */
const bulkAcceptedFixture = {
  data: {
    bulkOperationId: 'f1e2d3c4-b5a6-4f7e-8d9c-0b1a2c3d4e5f',
    status: 'PENDING' as const,
    totalCount: 2,
  },
}

/** bulk-operation GET 결과 fixture (COMPLETED) */
const bulkOperationCompletedFixture = {
  data: {
    id: 'f1e2d3c4-b5a6-4f7e-8d9c-0b1a2c3d4e5f',
    operationType: 'BULK_EDIT' as const,
    status: 'COMPLETED' as const,
    payload: { priority: 2, impact: null },
    totalCount: 2,
    processedCount: 2,
    succeededCount: 2,
    failedCount: 0,
    items: [
      { issueKey: 'ATLAS-1', status: 'SUCCEEDED' as const, failureReasonCode: null },
      { issueKey: 'ATLAS-2', status: 'SUCCEEDED' as const, failureReasonCode: null },
    ],
  },
}

// ─────────────────────────────────────────────────────────────────────────────
// 공통 권한 핸들러 — 토큰 없이도 CREATE:true를 반환해 기존 테스트 호환 유지
// ─────────────────────────────────────────────────────────────────────────────

/** 모든 요청에 대해 CREATE:true를 반환하는 기본 권한 핸들러 */
const createTruePermissionHandler = http.get(
  '/api/v1/users/me/project-permissions',
  () => HttpResponse.json({ projectKey: 'ATLAS', permissions: { CREATE: true, UPDATE: true, MANAGE_COMPONENTS: false, MANAGE_VERSIONS: false, MANAGE_CUSTOM_FIELDS: false, MANAGE_FIELD_PERMISSIONS: false, MANAGE_TEMPLATES: false } }),
)

/** 모든 요청에 대해 CREATE:false를 반환하는 권한 핸들러 (비멤버 시나리오) */
const createFalsePermissionHandler = http.get(
  '/api/v1/users/me/project-permissions',
  () =>
    HttpResponse.json({
      projectKey: 'ATLAS',
      permissions: nonMemberProjectPermissions,
    }),
)

// IssueListPage는 props 기반 — 라우터 없이 단위 테스트 가능

/** 빈 필터 상수 — 테스트에서 반복 정의 방지 */
const EMPTY_FILTER: IssueFilterParams = {
  statusKeys: [],
  assigneeIds: [],
  includeUnassigned: false,
  labels: [],
  componentIds: [],
}

/**
 * IssueListPage 렌더 헬퍼.
 *
 * C1 기존 테스트 회귀 보호 — IssueFilterBar가 호출하는 네 핸들러
 * (workflow / user / component / label)를 beforeEach와 무관하게 항상 등록한다.
 * 기존 테스트가 새 네트워크 요청으로 깨지지 않도록 server.use()로 추가한다.
 */
function renderPage(
  page = 0,
  onPageChange?: (page: number) => void,
  onNavigate?: (key: string) => void,
  filter: IssueFilterParams = EMPTY_FILTER,
  onFilterChange?: (f: IssueFilterParams) => void,
) {
  // IssueFilterBar 종속 핸들러 — 기존 테스트 회귀 방지
  server.use(...workflowHandlers, ...userHandlers, ...componentHandlers, ...labelHandlers)

  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
  return {
    client,
    ...render(
      <QueryClientProvider client={client}>
        <IssueListPage
          projectKey="ATLAS"
          page={page}
          onPageChange={onPageChange ?? (() => undefined)}
          onNavigate={onNavigate ?? (() => undefined)}
          filter={filter}
          onFilterChange={onFilterChange ?? (() => undefined)}
        />
      </QueryClientProvider>,
    ),
  }
}

describe('IssueListPage', () => {
  // B1: IssueListPage가 렌더될 때 권한 API를 발사하므로 모든 테스트에 공통 등록
  beforeEach(() => {
    server.use(createTruePermissionHandler)
  })

  /**
   * T1. 로딩 상태 — "로딩 중..." 텍스트가 즉시 렌더되어야 한다.
   */
  it('T1: 로딩 중일 때 로딩 텍스트를 렌더한다', () => {
    server.use(...issueHandlers)
    renderPage()
    expect(screen.getByText(/로딩 중/)).toBeInTheDocument()
  })

  /**
   * T2. fetch 실패 시 role="alert" + 에러 메시지 렌더.
   */
  it('T2: fetch 실패 시 에러 메시지를 role=alert으로 렌더한다', async () => {
    server.use(
      http.get('/api/v1/issues', () =>
        HttpResponse.json({ message: '서버 오류' }, { status: 500 }),
      ),
    )
    renderPage()
    await waitFor(() =>
      expect(screen.getByRole('alert')).toBeInTheDocument(),
    )
  })

  /**
   * T3. 성공 시 이슈 목록의 키 + 요약 + 상태 배지가 DOM에 존재해야 한다.
   */
  it('T3: 성공 시 이슈 키, 요약, 상태 배지를 렌더한다', async () => {
    server.use(...issueHandlers)
    renderPage()

    // 첫 번째 이슈 키가 렌더되어야 한다
    await waitFor(() =>
      expect(screen.getByText('ATLAS-1')).toBeInTheDocument(),
    )

    // 모든 픽스처 이슈 키 확인
    for (const issue of issuePageFixture.content) {
      expect(screen.getByText(issue.key)).toBeInTheDocument()
    }

    // 상태 배지가 존재해야 한다 (첫 번째 이슈: open)
    const badges = screen.getAllByRole('status')
    expect(badges.length).toBeGreaterThan(0)
  })

  /**
   * T4. 빈 목록 — 빈 상태 안내 메시지가 렌더되어야 한다.
   */
  it('T4: 빈 목록일 때 빈 상태 안내를 렌더한다', async () => {
    server.use(
      http.get('/api/v1/issues', () =>
        HttpResponse.json(emptyIssuePageFixture),
      ),
    )
    renderPage()
    await waitFor(() =>
      expect(screen.getByText(/이슈가 없습니다/)).toBeInTheDocument(),
    )
  })

  /**
   * T5. "새 이슈" 링크(/issues/new)가 DOM에 존재해야 한다.
   */
  it('T5: "새 이슈" 링크를 렌더한다', async () => {
    server.use(...issueHandlers)
    renderPage()

    await waitFor(() =>
      expect(screen.getByRole('link', { name: /새 이슈/ })).toBeInTheDocument(),
    )
    expect(screen.getByRole('link', { name: /새 이슈/ })).toHaveAttribute('href', '/issues/new')
  })

  /**
   * T5-A. CREATE:true → "새 이슈" 버튼이 활성 링크(role=link, href=/issues/new)로 렌더된다.
   * data-testid="new-issue-button" 부여 확인.
   */
  it('T5-A: CREATE:true 일 때 "새 이슈"는 활성 링크로 렌더된다', async () => {
    server.use(...issueHandlers)
    renderPage()

    const button = await screen.findByTestId('new-issue-button')
    expect(button.tagName).toBe('A')
    expect(button).toHaveAttribute('href', '/issues/new')
    expect(button).not.toBeDisabled()
  })

  /**
   * T5-B. CREATE:false → "새 이슈" 버튼이 비활성 버튼(role=button, disabled)으로 렌더된다.
   * 클릭해도 이동하지 않는다(href 없음).
   */
  it('T5-B: CREATE:false 일 때 "새 이슈"는 disabled 버튼으로 렌더된다', async () => {
    server.use(createFalsePermissionHandler, ...issueHandlers)
    renderPage()

    const button = await screen.findByTestId('new-issue-button')
    expect(button.tagName).toBe('BUTTON')
    expect(button).toBeDisabled()
    expect(button).not.toHaveAttribute('href')
  })

  /**
   * T5-C. 권한 로딩 중 → fail-closed: 비활성 버튼으로 렌더된다.
   */
  it('T5-C: 권한 로딩 중일 때 "새 이슈"는 disabled 버튼으로 렌더된다 (fail-closed)', async () => {
    // 권한 응답을 지연시켜 로딩 상태를 강제 — issueHandlers는 즉시 응답
    server.use(
      http.get('/api/v1/users/me/project-permissions', async () => {
        // 응답을 반환하지 않아 pending 상태 유지
        await new Promise<never>(() => undefined)
        return undefined as never
      }),
      ...issueHandlers,
    )
    renderPage()

    // 이슈 목록이 렌더되어도 권한이 아직 로딩 중이면 비활성 버튼이어야 한다
    await waitFor(() =>
      expect(screen.getByTestId('new-issue-button')).toBeInTheDocument(),
    )
    expect(screen.getByTestId('new-issue-button').tagName).toBe('BUTTON')
    expect(screen.getByTestId('new-issue-button')).toBeDisabled()
  })

  /**
   * T5-D. 권한 API 에러 → fail-closed: 비활성 버튼으로 렌더된다.
   */
  it('T5-D: 권한 API 에러 시 "새 이슈"는 disabled 버튼으로 렌더된다 (fail-closed)', async () => {
    server.use(
      http.get('/api/v1/users/me/project-permissions', () =>
        HttpResponse.json({ error: 'server_error' }, { status: 500 }),
      ),
      ...issueHandlers,
    )
    renderPage()

    await waitFor(() =>
      expect(screen.getByTestId('new-issue-button')).toBeInTheDocument(),
    )
    expect(screen.getByTestId('new-issue-button').tagName).toBe('BUTTON')
    expect(screen.getByTestId('new-issue-button')).toBeDisabled()
  })

  /**
   * T6. 이슈 항목 클릭 시 onNavigate(key) 콜백이 호출되어야 한다.
   */
  it('T6: 이슈 항목 클릭 시 onNavigate를 호출한다', async () => {
    server.use(...issueHandlers)
    const user = userEvent.setup()
    let navigatedKey = ''
    renderPage(0, undefined, (key) => { navigatedKey = key })

    // 이슈 항목이 렌더될 때까지 대기
    await waitFor(() => expect(screen.getByText('ATLAS-1')).toBeInTheDocument())

    // ATLAS-1 항목 링크 클릭
    const issueLink = screen.getByRole('link', { name: /ATLAS-1/ })
    await user.click(issueLink)

    expect(navigatedKey).toBe('ATLAS-1')
  })

  /**
   * T7. 마지막 페이지가 아닐 때 "다음" 버튼이 활성화되어야 한다.
   */
  it('T7: 첫 번째 페이지일 때 "다음" 버튼이 활성화되어 있다', async () => {
    server.use(
      http.get('/api/v1/issues', () =>
        HttpResponse.json(issuePageFirstFixture),
      ),
    )
    renderPage(0)

    await waitFor(() => expect(screen.getByText('ATLAS-1')).toBeInTheDocument())

    const nextButton = screen.getByRole('button', { name: /다음/ })
    expect(nextButton).not.toBeDisabled()
  })

  /**
   * T8. 첫 번째 페이지일 때 "이전" 버튼이 비활성화되어야 한다.
   */
  it('T8: 첫 번째 페이지일 때 "이전" 버튼이 비활성화되어 있다', async () => {
    server.use(...issueHandlers)
    renderPage(0)

    await waitFor(() => expect(screen.getByText('ATLAS-1')).toBeInTheDocument())

    const prevButton = screen.getByRole('button', { name: /이전/ })
    expect(prevButton).toBeDisabled()
  })

  /**
   * T9. 마지막 페이지일 때 "다음" 버튼이 비활성화되어야 한다.
   */
  it('T9: 마지막 페이지일 때 "다음" 버튼이 비활성화되어 있다', async () => {
    server.use(
      http.get('/api/v1/issues', () =>
        HttpResponse.json(issuePageLastFixture),
      ),
    )
    renderPage(1)

    await waitFor(() => expect(screen.getByText('ATLAS-3')).toBeInTheDocument())

    const nextButton = screen.getByRole('button', { name: /다음/ })
    expect(nextButton).toBeDisabled()
  })

  /**
   * T10. 긴 요약 텍스트가 truncate 처리되어야 한다 (CSS 클래스 확인).
   */
  it('T10: 긴 요약 텍스트에 truncate 클래스가 적용된다', async () => {
    server.use(...issueHandlers)
    renderPage()

    await waitFor(() => expect(screen.getByText('ATLAS-2')).toBeInTheDocument())

    // ATLAS-2의 요약이 있는 요소에 truncate 클래스가 있어야 한다
    const summaryEl = screen.getByTestId('issue-summary-ATLAS-2')
    expect(summaryEl).toHaveClass('truncate')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// Task 9 — 일괄 선택 + 액션 바 결선 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueListPage — 일괄 선택 및 액션 바 (Task 9)', () => {
  /**
   * T11. 행 재구조화:
   *   - 체크박스 클릭이 onNavigate를 호출하지 않음.
   *   - getByLabel(issue.key) 가 링크 1개만 매칭 (체크박스는 이슈 키를 라벨에 포함하지 않음).
   */
  it('T11: 체크박스 클릭 시 onNavigate가 호출되지 않으며, getByLabel(key)는 링크 1개만 반환한다', async () => {
    server.use(...issueHandlers)
    const onNavigate = vi.fn()
    const user = userEvent.setup()
    renderPage(0, undefined, onNavigate)

    await waitFor(() => expect(screen.getByText('ATLAS-1')).toBeInTheDocument())

    // getByLabel(key) — 링크 1개만 매칭 (strict mode 통과)
    const link = screen.getByRole('link', { name: 'ATLAS-1' })
    expect(link).toBeInTheDocument()

    // 체크박스가 키를 aria-label에 포함하지 않으므로 getByLabel('ATLAS-1') = 링크 1개
    // 체크박스 자체는 data-testid로 찾는다
    const checkbox = screen.getByTestId('select-ATLAS-1')
    await user.click(checkbox)

    // onNavigate 미호출 확인
    expect(onNavigate).not.toHaveBeenCalled()
  })

  /**
   * T12. "전체 선택" 체크박스 — 현재 페이지 항목만 토글.
   * 선택 후 IssueBulkActionBar가 노출되어야 한다.
   */
  it('T12: "전체 선택" 체크박스 클릭 시 현재 페이지 항목이 모두 선택되고 액션 바가 노출된다', async () => {
    server.use(...issueHandlers)
    const user = userEvent.setup()
    renderPage()

    await waitFor(() => expect(screen.getByText('ATLAS-1')).toBeInTheDocument())

    // 초기 — 액션 바 없음
    expect(screen.queryByTestId('bulk-action-bar')).not.toBeInTheDocument()

    // 전체 선택 클릭
    const selectAll = screen.getByTestId('select-all-page')
    await user.click(selectAll)

    // 액션 바 노출
    await waitFor(() =>
      expect(screen.getByTestId('bulk-action-bar')).toBeInTheDocument(),
    )

    // fixture 4건 모두 선택됨 — "4건 선택됨" (FR-IS-07 E2E용 ATLAS-5 추가로 페이지 4건)
    expect(screen.getByTestId('bulk-action-bar')).toHaveTextContent('4건 선택됨')
  })

  /**
   * T13. 개별 체크박스 선택 → 선택 수 반영 → 해제 시 액션 바 숨김.
   */
  it('T13: 개별 체크박스 선택/해제가 count에 반영되고 0이면 액션 바가 숨겨진다', async () => {
    server.use(...issueHandlers)
    const user = userEvent.setup()
    renderPage()

    await waitFor(() => expect(screen.getByText('ATLAS-1')).toBeInTheDocument())

    const cb1 = screen.getByTestId('select-ATLAS-1')
    await user.click(cb1)

    await waitFor(() =>
      expect(screen.getByTestId('bulk-action-bar')).toBeInTheDocument(),
    )
    expect(screen.getByTestId('bulk-action-bar')).toHaveTextContent('1건 선택됨')

    // 다시 클릭해 해제
    await user.click(cb1)
    await waitFor(() =>
      expect(screen.queryByTestId('bulk-action-bar')).not.toBeInTheDocument(),
    )
  })

  /**
   * T14. "일괄 편집" 버튼 클릭 시 BulkEditDialog가 열린다.
   */
  it('T14: 액션 바 "일괄 편집" 클릭 시 BulkEditDialog가 열린다', async () => {
    server.use(...issueHandlers)
    const user = userEvent.setup()
    renderPage()

    await waitFor(() => expect(screen.getByText('ATLAS-1')).toBeInTheDocument())

    await user.click(screen.getByTestId('select-ATLAS-1'))
    await waitFor(() => expect(screen.getByTestId('bulk-action-bar')).toBeInTheDocument())

    await user.click(screen.getByRole('button', { name: '일괄 편집' }))

    // BulkEditDialog title 노출
    await waitFor(() =>
      expect(screen.getByRole('heading', { name: '일괄 편집' })).toBeInTheDocument(),
    )
  })

  /**
   * T15. "일괄 전이" 버튼 클릭 시 BulkTransitionDialog가 열린다.
   */
  it('T15: 액션 바 "일괄 전이" 클릭 시 BulkTransitionDialog가 열린다', async () => {
    server.use(...issueHandlers)
    const user = userEvent.setup()
    renderPage()

    await waitFor(() => expect(screen.getByText('ATLAS-1')).toBeInTheDocument())

    await user.click(screen.getByTestId('select-ATLAS-1'))
    await waitFor(() => expect(screen.getByTestId('bulk-action-bar')).toBeInTheDocument())

    // BulkTransitionDialog — GET /transitions 핸들러 이미 issueHandlers에 포함
    server.use(
      http.get('/api/v1/issues/:key/transitions', ({ params }) => {
        const key = params['key'] as string
        if (key === 'ATLAS-1') {
          return HttpResponse.json({ data: { transitions: [{ key: 't1', name: 'In Progress로', fromStateKey: 'open', toStateKey: 'in_progress' }] } })
        }
        return HttpResponse.json({ data: { transitions: [] } })
      }),
    )

    await user.click(screen.getByRole('button', { name: '일괄 전이' }))

    await waitFor(() =>
      expect(screen.getByRole('heading', { name: '일괄 상태 전이' })).toBeInTheDocument(),
    )
  })

  /**
   * T17. 결과 Dialog 닫힘 시 bulkOperationId가 null로 리셋된다 (잔상 방지).
   * 결과 Dialog 닫기 후 폴링이 완전히 비활성화(bulkOperationId=null로 인해 enabled=false)됨을
   * GET 호출 횟수로 검증한다. 닫힘 이후에는 추가 GET이 없어야 한다.
   */
  it('T17: 결과 Dialog 닫힘 시 bulkOperationId가 null로 리셋되어 폴링이 비활성화된다', async () => {
    let getCallCount = 0

    server.use(
      ...issueHandlers,
      http.post('/api/v1/issues/bulk-update', () =>
        HttpResponse.json(bulkAcceptedFixture, { status: 202 }),
      ),
      http.get('/api/v1/bulk-operations/:id', () => {
        getCallCount++
        // PENDING을 계속 반환 — 폴링이 계속되는 상태를 유지
        return HttpResponse.json({
          data: {
            ...bulkOperationCompletedFixture.data,
            status: 'PENDING',
            processedCount: 0,
          },
        })
      }),
    )

    const user = userEvent.setup()
    renderPage()

    await waitFor(() => expect(screen.getByText('ATLAS-1')).toBeInTheDocument())

    // 1건 선택 후 일괄 편집 → 적용
    await user.click(screen.getByTestId('select-ATLAS-1'))
    await waitFor(() => expect(screen.getByTestId('bulk-action-bar')).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: '일괄 편집' }))
    await waitFor(() =>
      expect(screen.getByRole('heading', { name: '일괄 편집' })).toBeInTheDocument(),
    )
    const dialog = screen.getByRole('dialog')
    const prioritySelect = within(dialog).getByLabelText('priority')
    await user.selectOptions(prioritySelect, '2')
    await user.click(within(dialog).getByRole('button', { name: '적용' }))

    // 결과 Dialog 열림 + 폴링 시작 → GET 호출 1회 이상 확인
    await waitFor(() =>
      expect(screen.getByRole('heading', { name: '일괄 작업 결과' })).toBeInTheDocument(),
    )
    await waitFor(() => expect(getCallCount).toBeGreaterThanOrEqual(1))

    // 결과 Dialog 닫기
    const closeBtn = screen.getByRole('button', { name: /닫기/i })
    await user.click(closeBtn)
    await waitFor(() =>
      expect(screen.queryByRole('heading', { name: '일괄 작업 결과' })).not.toBeInTheDocument(),
    )

    // 닫힘 이후 폴링 호출 횟수가 고정돼야 한다
    // bulkOperationId=null → enabled=false → POLL_INTERVAL_MS 경과해도 추가 GET 없음
    const callCountAtClose = getCallCount
    await new Promise((resolve) => setTimeout(resolve, 300))
    expect(getCallCount).toBe(callCountAtClose)
  })

  /**
   * T16. Dialog onSubmitted → BulkOperationResultDialog 열림 + 이슈 목록 invalidate(refetch) + 선택 해제.
   * EC8: invalidateQueries({ queryKey: ['issues', projectKey] }) prefix가 filter-aware 4-요소 queryKey를 포함해 무효화함.
   */
  it('T16: BulkEditDialog onSubmitted 시 결과 Dialog 열림, 선택 해제, 목록 refetch가 발생한다', async () => {
    // bulk-update POST + bulk-operation GET 핸들러 추가
    server.use(
      ...issueHandlers,
      http.post('/api/v1/issues/bulk-update', () =>
        HttpResponse.json(bulkAcceptedFixture, { status: 202 }),
      ),
      http.get('/api/v1/bulk-operations/:id', () =>
        HttpResponse.json(bulkOperationCompletedFixture),
      ),
    )

    const user = userEvent.setup()
    renderPage()

    await waitFor(() => expect(screen.getByText('ATLAS-1')).toBeInTheDocument())

    // 2건 선택
    await user.click(screen.getByTestId('select-ATLAS-1'))
    await user.click(screen.getByTestId('select-ATLAS-2'))

    await waitFor(() => expect(screen.getByTestId('bulk-action-bar')).toBeInTheDocument())
    expect(screen.getByTestId('bulk-action-bar')).toHaveTextContent('2건 선택됨')

    // 일괄 편집 Dialog 열기
    await user.click(screen.getByRole('button', { name: '일괄 편집' }))
    await waitFor(() =>
      expect(screen.getByRole('heading', { name: '일괄 편집' })).toBeInTheDocument(),
    )

    // priority 선택 후 적용
    const dialog = screen.getByRole('dialog')
    const prioritySelect = within(dialog).getByLabelText('priority')
    await user.selectOptions(prioritySelect, '2')

    await user.click(within(dialog).getByRole('button', { name: '적용' }))

    // 결과 Dialog 열림
    await waitFor(() =>
      expect(screen.getByRole('heading', { name: '일괄 작업 결과' })).toBeInTheDocument(),
    )

    // 선택 해제 — 액션 바 사라짐
    await waitFor(() =>
      expect(screen.queryByTestId('bulk-action-bar')).not.toBeInTheDocument(),
    )
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// Task 5 — 필터 결선 테스트 (FR-SR-01 D6)
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueListPage — 필터 결선 (Task 5)', () => {
  beforeEach(() => {
    server.use(createTruePermissionHandler)
  })

  /**
   * TF1. filter prop → fetchIssues(filter) 전달 + queryKey filter-aware.
   *
   * status 필터를 전달하면 URL 파라미터에 status=in_progress가 포함되고
   * 해당 상태 이슈만 반환되어야 한다 (B1 queryKey filter-aware).
   */
  it('TF1: filter prop을 전달하면 fetchIssues에 filter가 적용된다 (queryKey filter-aware)', async () => {
    // MSW: status=in_progress 필터 적용 — ATLAS-2만 반환
    let capturedUrl = ''
    server.use(
      http.get('/api/v1/issues', ({ request }) => {
        capturedUrl = request.url
        const params = new URL(request.url).searchParams
        const statusKeys = params.getAll('status')
        const content = issuePageFixture.content.filter(
          (i) => statusKeys.length === 0 || statusKeys.includes(i.currentStateKey),
        )
        return HttpResponse.json({
          ...issuePageFixture,
          content,
          totalElements: content.length,
          empty: content.length === 0,
        })
      }),
    )

    const filter: IssueFilterParams = {
      statusKeys: ['in_progress'],
      assigneeIds: [],
      includeUnassigned: false,
      labels: [],
      componentIds: [],
    }

    renderPage(0, undefined, undefined, filter)

    await waitFor(() =>
      expect(capturedUrl).toContain('status=in_progress'),
    )

    // ATLAS-2(in_progress)만 표시되어야 한다
    await waitFor(() => expect(screen.getByText('ATLAS-2')).toBeInTheDocument())
    expect(screen.queryByText('ATLAS-1')).not.toBeInTheDocument()
  })

  /**
   * TF2. IssueFilterBar onChange → onFilterChange 콜백 호출 + page=0 리셋.
   *
   * IssueFilterBar의 onChange가 호출되면 onFilterChange 콜백이 새 필터와 함께 호출되어야 한다.
   * 필터 초기화 버튼 클릭 시 빈 필터가 전달된다.
   */
  it('TF2: IssueFilterBar onChange 시 onFilterChange 콜백이 호출된다', async () => {
    server.use(...issueHandlers)

    const onFilterChange = vi.fn()
    const onPageChange = vi.fn()

    const filter: IssueFilterParams = {
      statusKeys: ['open'],
      assigneeIds: [],
      includeUnassigned: false,
      labels: [],
      componentIds: [],
    }

    renderPage(0, onPageChange, undefined, filter, onFilterChange)

    // 이슈 목록이 렌더될 때까지 대기
    await waitFor(() => screen.getByText('이슈 목록'))

    // IssueFilterBar의 "초기화" 버튼 클릭 → 빈 필터 + page=0
    const resetBtn = await screen.findByRole('button', { name: /초기화/i })
    const user = userEvent.setup()
    await user.click(resetBtn)

    expect(onFilterChange).toHaveBeenCalledWith(expect.objectContaining({
      statusKeys: [],
      assigneeIds: [],
      includeUnassigned: false,
      labels: [],
      componentIds: [],
    }))
    expect(onPageChange).toHaveBeenCalledWith(0)
  })

  /**
   * TF3. filter 실변경 시 clearAll 호출 (FR10 회귀 단언).
   *
   * - filter가 실제로 바뀌면 useIssueSelection.clearAll이 호출된다.
   * - page만 변경(filter 동일)이면 clearAll이 호출되지 않는다.
   */
  it('TF3: filter 실변경 시 선택이 초기화되고, page만 변경 시 선택이 유지된다', async () => {
    server.use(...issueHandlers)

    const user = userEvent.setup()

    // filter=open으로 첫 렌더
    const { rerender, client } = renderPage(0, undefined, undefined, {
      statusKeys: ['open'],
      assigneeIds: [],
      includeUnassigned: false,
      labels: [],
      componentIds: [],
    })

    await waitFor(() => expect(screen.getByText('ATLAS-1')).toBeInTheDocument())

    // ATLAS-1 체크박스 선택
    const cb = screen.getByTestId('select-ATLAS-1')
    await user.click(cb)
    await waitFor(() => expect(screen.getByTestId('bulk-action-bar')).toBeInTheDocument())

    // 같은 filter + page=1 으로 rerender → 선택 유지
    rerender(
      <QueryClientProvider client={client}>
        <IssueListPage
          projectKey="ATLAS"
          page={1}
          onPageChange={() => undefined}
          onNavigate={() => undefined}
          filter={{ statusKeys: ['open'], assigneeIds: [], includeUnassigned: false, labels: [], componentIds: [] }}
          onFilterChange={() => undefined}
        />
      </QueryClientProvider>,
    )

    await waitFor(() => expect(screen.getByTestId('bulk-action-bar')).toBeInTheDocument())

    // filter 변경으로 rerender → 선택 초기화
    rerender(
      <QueryClientProvider client={client}>
        <IssueListPage
          projectKey="ATLAS"
          page={1}
          onPageChange={() => undefined}
          onNavigate={() => undefined}
          filter={{ statusKeys: ['in_progress'], assigneeIds: [], includeUnassigned: false, labels: [], componentIds: [] }}
          onFilterChange={() => undefined}
        />
      </QueryClientProvider>,
    )

    await waitFor(() =>
      expect(screen.queryByTestId('bulk-action-bar')).not.toBeInTheDocument(),
    )
  })

  /**
   * TF4a. 0건 + 필터 있음 → 필터 초기화 CTA 버튼 렌더 (EC1).
   */
  it('TF4a: 결과가 0건이고 필터가 적용된 경우 "필터 초기화" CTA가 렌더된다 (EC1)', async () => {
    server.use(
      http.get('/api/v1/issues', () =>
        HttpResponse.json(emptyIssuePageFixture),
      ),
    )

    const filter: IssueFilterParams = {
      statusKeys: ['nonexistent'],
      assigneeIds: [],
      includeUnassigned: false,
      labels: [],
      componentIds: [],
    }

    const onFilterChange = vi.fn()
    const onPageChange = vi.fn()
    renderPage(0, onPageChange, undefined, filter, onFilterChange)

    await waitFor(() =>
      expect(screen.getByRole('button', { name: /필터 초기화/i })).toBeInTheDocument(),
    )

    // 기존 빈 상태("새 이슈를 만들어 프로젝트를 시작해 보세요")는 표시하지 않아야 한다
    expect(screen.queryByText(/새 이슈를 만들어 프로젝트를/)).not.toBeInTheDocument()
  })

  /**
   * TF4b. 0건 + 필터 없음 → 기존 IssueEmptyState 유지.
   */
  it('TF4b: 결과가 0건이고 필터가 없으면 기존 빈 상태 안내를 렌더한다', async () => {
    server.use(
      http.get('/api/v1/issues', () =>
        HttpResponse.json(emptyIssuePageFixture),
      ),
    )

    renderPage(0, undefined, undefined, EMPTY_FILTER)

    await waitFor(() =>
      expect(screen.getByText('이슈가 없습니다.')).toBeInTheDocument(),
    )
    // "필터 초기화" CTA는 없어야 한다
    expect(screen.queryByRole('button', { name: /필터 초기화/i })).not.toBeInTheDocument()
  })

  /**
   * TF5. EC1 "필터 초기화" CTA 클릭 → onFilterChange(빈 필터) + onPageChange(0) 호출.
   */
  it('TF5: 필터 초기화 CTA 클릭 시 onFilterChange(빈 필터)와 onPageChange(0)가 호출된다', async () => {
    server.use(
      http.get('/api/v1/issues', () =>
        HttpResponse.json(emptyIssuePageFixture),
      ),
    )

    const onFilterChange = vi.fn()
    const onPageChange = vi.fn()

    const filter: IssueFilterParams = {
      statusKeys: ['nonexistent'],
      assigneeIds: [],
      includeUnassigned: false,
      labels: [],
      componentIds: [],
    }

    renderPage(0, onPageChange, undefined, filter, onFilterChange)

    const user = userEvent.setup()
    const ctaBtn = await screen.findByRole('button', { name: /필터 초기화/i })
    await user.click(ctaBtn)

    expect(onFilterChange).toHaveBeenCalledWith(EMPTY_FILTER)
    expect(onPageChange).toHaveBeenCalledWith(0)
  })

  /**
   * TF6. EC8 — bulk invalidate prefix가 filter-aware queryKey를 포함해 무효화함.
   *
   * queryKey=['issues','ATLAS',0,normalizedFilter] 형태로 캐시에 저장된 데이터가
   * invalidateQueries({ queryKey: ['issues','ATLAS'] }) prefix 매칭으로 무효화되어야 한다.
   */
  it('TF6: bulk invalidate prefix가 filter-aware queryKey를 포함해 무효화한다 (EC8)', async () => {
    let fetchCallCount = 0

    server.use(
      ...issueHandlers,
      http.post('/api/v1/issues/bulk-update', () =>
        HttpResponse.json({
          data: {
            bulkOperationId: 'ffffffff-ffff-4fff-bfff-ffffffffffff',
            status: 'PENDING' as const,
            totalCount: 1,
          },
        }, { status: 202 }),
      ),
      http.get('/api/v1/bulk-operations/:id', () =>
        HttpResponse.json({
          data: {
            id: 'ffffffff-ffff-4fff-bfff-ffffffffffff',
            operationType: 'BULK_EDIT' as const,
            status: 'COMPLETED' as const,
            payload: { priority: 2, impact: null },
            totalCount: 1,
            processedCount: 1,
            succeededCount: 1,
            failedCount: 0,
            items: [{ issueKey: 'ATLAS-1', status: 'SUCCEEDED' as const, failureReasonCode: null }],
          },
        }),
      ),
    )

    // filter가 적용된 상태로 렌더 — queryKey는 4-요소
    const filter: IssueFilterParams = {
      statusKeys: ['open'],
      assigneeIds: [],
      includeUnassigned: false,
      labels: [],
      componentIds: [],
    }

    server.use(
      http.get('/api/v1/issues', ({ request }) => {
        fetchCallCount++
        const params = new URL(request.url).searchParams
        const statusKeys = params.getAll('status')
        const content = issuePageFixture.content.filter(
          (i) => statusKeys.length === 0 || statusKeys.includes(i.currentStateKey),
        )
        return HttpResponse.json({
          ...issuePageFixture,
          content,
          totalElements: content.length,
          empty: content.length === 0,
        })
      }),
    )

    const user = userEvent.setup()
    renderPage(0, undefined, undefined, filter)

    await waitFor(() => expect(screen.getByText('이슈 목록')).toBeInTheDocument())
    const initialFetchCount = fetchCallCount

    // 1건 선택 후 일괄 편집 제출
    await waitFor(() => expect(screen.queryByTestId('select-ATLAS-1')).not.toBeNull())
    await user.click(screen.getByTestId('select-ATLAS-1'))
    await waitFor(() => expect(screen.getByTestId('bulk-action-bar')).toBeInTheDocument())
    await user.click(screen.getByRole('button', { name: '일괄 편집' }))
    await waitFor(() => expect(screen.getByRole('heading', { name: '일괄 편집' })).toBeInTheDocument())

    const dialog = screen.getByRole('dialog')
    const prioritySelect = within(dialog).getByLabelText('priority')
    await user.selectOptions(prioritySelect, '2')
    await user.click(within(dialog).getByRole('button', { name: '적용' }))

    // invalidateQueries 발생 후 fetchIssues가 다시 호출되어야 한다 (EC8 무효화 확인)
    await waitFor(() => expect(fetchCallCount).toBeGreaterThan(initialFetchCount))
  })

  /**
   * TF7. IssueFilterBar가 페이지 헤더 아래, IssueBulkActionBar 위에 렌더된다 (C1 위치).
   */
  it('TF7: IssueFilterBar가 페이지에 렌더된다 (C1 위치 확인)', async () => {
    server.use(...issueHandlers)

    renderPage(0, undefined, undefined, EMPTY_FILTER)

    await waitFor(() => expect(screen.getByText('이슈 목록')).toBeInTheDocument())

    // IssueFilterBar의 초기화 버튼이 페이지에 렌더되어야 한다
    // (IssueFilterBar 내부 렌더 = 컴포넌트 마운트 증거)
    await waitFor(() =>
      expect(screen.getByRole('button', { name: /초기화/i })).toBeInTheDocument(),
    )
  })

  /**
   * TF8. assignee 필터 → fetchIssues에 assignee 파라미터 전달.
   */
  it('TF8: assignee 필터가 적용된 경우 fetchIssues에 assignee 파라미터가 전달된다', async () => {
    let capturedUrl = ''
    server.use(
      http.get('/api/v1/issues', ({ request }) => {
        capturedUrl = request.url
        return HttpResponse.json(issuePageFixture)
      }),
    )

    const filter: IssueFilterParams = {
      statusKeys: [],
      assigneeIds: [ISSUE_FILTER_BOB_ID],
      includeUnassigned: true,
      labels: [],
      componentIds: [],
    }

    renderPage(0, undefined, undefined, filter)

    await waitFor(() =>
      expect(capturedUrl).toContain(`assignee=${ISSUE_FILTER_BOB_ID}`),
    )
    await waitFor(() =>
      expect(capturedUrl).toContain('assignee=unassigned'),
    )
  })

  /**
   * TF9. component 필터 → fetchIssues에 component 파라미터 전달.
   */
  it('TF9: component 필터가 적용된 경우 fetchIssues에 component 파라미터가 전달된다', async () => {
    let capturedUrl = ''
    server.use(
      http.get('/api/v1/issues', ({ request }) => {
        capturedUrl = request.url
        return HttpResponse.json(issuePageFixture)
      }),
    )

    const filter: IssueFilterParams = {
      statusKeys: [],
      assigneeIds: [],
      includeUnassigned: false,
      labels: [],
      componentIds: [ISSUE_FILTER_COMP_A_ID],
    }

    renderPage(0, undefined, undefined, filter)

    await waitFor(() =>
      expect(capturedUrl).toContain(`component=${ISSUE_FILTER_COMP_A_ID}`),
    )
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// Task 5 — 테이블 전환 + 정렬 URL + 컬럼 선택 결선 (FR-UX-06 Phase 5 PR18)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 정렬 상태(page 포함)를 컴포넌트 내부에서 제어형으로 관리하는 테스트 전용 래퍼.
 *
 * IssueListPage는 sort/page 모두 controlled prop이므로, 헤더를 연속 클릭했을 때
 * 3-state가 올바르게 순환하는지 확인하려면 실제 상태를 들고 있는 상위 컴포넌트가 필요하다
 * (IssueListRouteAdapter의 역할을 테스트 안에서 축소 재현).
 */
function ControlledSortHarness({ initialPage = 0 }: { initialPage?: number } = {}) {
  const [sort, setSort] = useState<IssueTableSortState | null>(null)
  const [page, setPage] = useState(initialPage)
  return (
    <IssueListPage
      projectKey="ATLAS"
      page={page}
      onPageChange={setPage}
      onNavigate={() => undefined}
      filter={EMPTY_FILTER}
      onFilterChange={() => undefined}
      sort={sort}
      onSortChange={setSort}
    />
  )
}

function renderControlledSort(initialPage = 0) {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
  return render(
    <QueryClientProvider client={client}>
      <ControlledSortHarness initialPage={initialPage} />
    </QueryClientProvider>,
  )
}

describe('IssueListPage — 테이블 전환 (Task 5)', () => {
  beforeEach(() => {
    server.use(createTruePermissionHandler)
  })

  /**
   * T-TBL-1. IssueListContent가 카드(<ul>/IssueCard) 대신 <table>(ui/table)로 렌더된다.
   */
  it('T-TBL-1: 이슈 목록이 <table>로 렌더된다(카드 아님)', async () => {
    server.use(...issueHandlers)
    renderPage()

    await waitFor(() => expect(screen.getByText('ATLAS-1')).toBeInTheDocument())
    expect(screen.getByRole('table', { name: '이슈 목록' })).toBeInTheDocument()
  })
})

describe('IssueListPage — 정렬 URL 결선 (Task 5)', () => {
  beforeEach(() => {
    server.use(createTruePermissionHandler)
  })

  /**
   * T-SORT-1. 정렬 가능 헤더("우선순위") 클릭 시 asc → desc → 해제(3-state) 순으로 순환하고,
   * 매 단계마다 fetchIssues 요청 URL에 반영된다(F2, S2). 해제 시 sort 파라미터 자체가 제거된다.
   */
  it('T-SORT-1: 정렬 헤더 클릭이 asc → desc → 해제 3-state로 순환하며 sort 쿼리에 반영된다', async () => {
    const capturedSorts: (string | null)[] = []
    server.use(
      http.get('/api/v1/issues', ({ request }) => {
        capturedSorts.push(new URL(request.url).searchParams.get('sort'))
        return HttpResponse.json(issuePageFixture)
      }),
    )

    const user = userEvent.setup()
    renderControlledSort()

    await waitFor(() => expect(screen.getByText('ATLAS-1')).toBeInTheDocument())

    const header = screen.getByRole('button', { name: '우선순위' })

    await user.click(header)
    await waitFor(() => expect(capturedSorts.at(-1)).toBe('priority,asc'))
    expect(screen.getByRole('columnheader', { name: '우선순위' })).toHaveAttribute('aria-sort', 'ascending')

    await user.click(header)
    await waitFor(() => expect(capturedSorts.at(-1)).toBe('priority,desc'))
    expect(screen.getByRole('columnheader', { name: '우선순위' })).toHaveAttribute('aria-sort', 'descending')

    await user.click(header)
    await waitFor(() => expect(capturedSorts.at(-1)).toBeNull())
    expect(screen.getByRole('columnheader', { name: '우선순위' })).toHaveAttribute('aria-sort', 'none')
  })

  /**
   * T-SORT-2. 다른 정렬 필드("요약") 클릭 시 이전 정렬과 무관하게 즉시 asc로 초기화된다.
   */
  it('T-SORT-2: 다른 필드 헤더를 클릭하면 그 필드의 asc로 즉시 초기화된다', async () => {
    const capturedSorts: (string | null)[] = []
    server.use(
      http.get('/api/v1/issues', ({ request }) => {
        capturedSorts.push(new URL(request.url).searchParams.get('sort'))
        return HttpResponse.json(issuePageFixture)
      }),
    )

    const user = userEvent.setup()
    renderControlledSort()

    await waitFor(() => expect(screen.getByText('ATLAS-1')).toBeInTheDocument())

    await user.click(screen.getByRole('button', { name: '우선순위' }))
    await waitFor(() => expect(capturedSorts.at(-1)).toBe('priority,asc'))

    await user.click(screen.getByRole('button', { name: '요약' }))
    await waitFor(() => expect(capturedSorts.at(-1)).toBe('summary,asc'))
  })

  /**
   * T-SORT-3. 정렬 변경 시 현재 page가 0으로 리셋된다(EC3).
   */
  it('T-SORT-3: 정렬 변경 시 page가 0으로 리셋된다 (EC3)', async () => {
    const capturedPages: string[] = []
    server.use(
      http.get('/api/v1/issues', ({ request }) => {
        capturedPages.push(new URL(request.url).searchParams.get('page') ?? '')
        return HttpResponse.json(issuePageFixture)
      }),
    )

    const user = userEvent.setup()
    renderControlledSort(2)

    await waitFor(() => expect(capturedPages.at(-1)).toBe('2'))

    await user.click(screen.getByRole('button', { name: '요약' }))
    await waitFor(() => expect(capturedPages.at(-1)).toBe('0'))
  })
})

describe('IssueListPage — 컬럼 선택 결선 (Task 5)', () => {
  /** useColumnVisibility가 사용하는 localStorage 키 — issues.index.tsx 구현과 동기화 */
  const STORAGE_KEY = 'issue-table-columns'

  beforeEach(() => {
    server.use(createTruePermissionHandler)
    window.localStorage.removeItem(STORAGE_KEY)
  })

  afterEach(() => {
    window.localStorage.removeItem(STORAGE_KEY)
  })

  /**
   * T-COL-1. 컬럼 선택 드롭다운에서 "담당자"를 끄면 해당 컬럼이 사라지고
   * localStorage에 저장된다(F4, S4).
   */
  it('T-COL-1: 컬럼 선택에서 담당자를 끄면 컬럼이 사라지고 localStorage에 저장된다', async () => {
    server.use(...issueHandlers)
    const user = userEvent.setup()
    renderPage()

    await waitFor(() => expect(screen.getByText('ATLAS-1')).toBeInTheDocument())
    expect(screen.getByRole('columnheader', { name: '담당자' })).toBeInTheDocument()

    await user.click(screen.getByRole('button', { name: /컬럼/ }))
    await user.click(await screen.findByRole('menuitemcheckbox', { name: '담당자' }))

    await waitFor(() =>
      expect(screen.queryByRole('columnheader', { name: '담당자' })).not.toBeInTheDocument(),
    )

    const stored = JSON.parse(window.localStorage.getItem(STORAGE_KEY) ?? '[]') as unknown
    expect(Array.isArray(stored) && stored.includes('assignee')).toBe(false)
  })

  /**
   * T-COL-2. 필수 컬럼(키·요약)은 드롭다운에서 비활성 처리되어 숨길 수 없다(F4).
   */
  it('T-COL-2: 필수 컬럼(키·요약)은 드롭다운에서 비활성 상태로 항상 체크돼 있다', async () => {
    server.use(...issueHandlers)
    const user = userEvent.setup()
    renderPage()

    await waitFor(() => expect(screen.getByText('ATLAS-1')).toBeInTheDocument())

    await user.click(screen.getByRole('button', { name: /컬럼/ }))
    const keyItem = await screen.findByRole('menuitemcheckbox', { name: '키' })
    expect(keyItem).toHaveAttribute('aria-disabled', 'true')
    expect(keyItem).toHaveAttribute('aria-checked', 'true')
  })
})

describe('IssueListPage — 재조회 전환 피드백 (Task 5, GAP-5)', () => {
  beforeEach(() => {
    server.use(createTruePermissionHandler)
  })

  /**
   * T-FETCH-1. 페이지 전환 중에는 이전 데이터가 유지된 채(keepPreviousData) 표 영역이
   * dim 처리(opacity-60 pointer-events-none)되고, 응답 도착 후 dim이 해제된다(GAP-5).
   */
  it('T-FETCH-1: 페이지 전환 중 표 영역이 dim 처리되고 이전 데이터가 유지된다', async () => {
    let resolveSecondPage: (() => void) | undefined
    server.use(
      http.get('/api/v1/issues', async ({ request }) => {
        const params = new URL(request.url).searchParams
        if (params.get('page') === '1') {
          await new Promise<void>((resolve) => {
            resolveSecondPage = resolve
          })
          return HttpResponse.json(issuePageLastFixture)
        }
        return HttpResponse.json(issuePageFirstFixture)
      }),
    )

    const { rerender, client: rerenderClient } = renderPage(0)
    await waitFor(() => expect(screen.getByText('ATLAS-1')).toBeInTheDocument())

    rerender(
      <QueryClientProvider client={rerenderClient}>
        <IssueListPage
          projectKey="ATLAS"
          page={1}
          onPageChange={() => undefined}
          onNavigate={() => undefined}
          filter={EMPTY_FILTER}
          onFilterChange={() => undefined}
        />
      </QueryClientProvider>,
    )

    // 응답이 아직 오지 않아도 이전 데이터(ATLAS-1)가 유지되며 dim 처리된다
    await waitFor(() => {
      expect(screen.getByText('ATLAS-1')).toBeInTheDocument()
      expect(screen.getByTestId('issue-table-region')).toHaveClass('opacity-60')
    })

    resolveSecondPage?.()

    await waitFor(() =>
      expect(screen.getByTestId('issue-table-region')).not.toHaveClass('opacity-60'),
    )
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// Task 5 — IssueListRouteAdapter split view 결선 (FR-UX-06 Phase 5 PR20)
// ─────────────────────────────────────────────────────────────────────────────

/** IssueListRouteAdapter가 검색(status/sort/page) 보존형 navigate에 넘기는 인자 형태 */
interface NavigateSearchCall {
  to: string
  search: (prev: Record<string, unknown>) => Record<string, unknown>
}

/** IssueListRouteAdapter가 좁은폭 전체화면 이동에 넘기는 인자 형태 */
interface NavigateParamsCall {
  to: string
  params: { key: string }
}

/**
 * index번째(0-based) navigate 호출을 꺼낸다. 호출이 없으면 테스트를 명확히 실패시킨다(암묵적 undefined 금지).
 *
 * IssueListPage.handleFilterChange는 onFilterChange(어댑터 handleFilterChange) 직후
 * onPageChange(어댑터 handlePageChange)를 연달아 호출해 navigate가 2회 발생한다 — 필터 변경
 * 자체를 검증하려면 마지막(page 리셋) 호출이 아닌 첫 호출을 봐야 한다(SV9).
 */
function getNavigateCallAt(index: number): NavigateSearchCall | NavigateParamsCall {
  const calls = mockNavigate.mock.calls
  const call = calls.at(index)
  if (call === undefined) {
    throw new Error(`navigate가 index=${index}에서 호출되지 않았습니다`)
  }
  const [arg] = call
  return arg as NavigateSearchCall | NavigateParamsCall
}

/** 마지막 navigate 호출을 꺼낸다. 호출이 없으면 테스트를 명확히 실패시킨다(암묵적 undefined 금지). */
function getLastNavigateCall(): NavigateSearchCall | NavigateParamsCall {
  const calls = mockNavigate.mock.calls
  const lastCall = calls.at(-1)
  if (lastCall === undefined) {
    throw new Error('navigate가 호출되지 않았습니다')
  }
  const [arg] = lastCall
  return arg as NavigateSearchCall | NavigateParamsCall
}

/**
 * IssueListRouteAdapter 렌더 헬퍼 — split view 결선 테스트 전용.
 * IssueFilterBar 종속 핸들러(workflow/user/component/label) + 이슈 목록/상세 핸들러를 등록한다.
 */
function renderRouteAdapter() {
  // ★ projectListHandlers 필수 — FR-UX-07 이후 어댑터가 useProjects()를 호출한다.
  // test/setup.ts가 onUnhandledRequest:'error'라, 빠뜨리면 전 SV 테스트가 프로젝트 조회
  // 실패로 원인과 무관한 메시지를 내며 깨진다(plan 리뷰 C3).
  server.use(
    ...workflowHandlers,
    ...userHandlers,
    ...componentHandlers,
    ...labelHandlers,
    ...issueHandlers,
    ...projectListHandlers,
  )
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
  return {
    client,
    ...render(
      <QueryClientProvider client={client}>
        <IssueListRouteAdapter />
      </QueryClientProvider>,
    ),
  }
}

describe('IssueListRouteAdapter — split view 결선 (Task 5)', () => {
  beforeEach(() => {
    server.use(createTruePermissionHandler)
    mockUseMediaQuery.mockReturnValue(true)
    mockUseSearch.mockReturnValue({})
    mockNavigate.mockClear()
  })

  /**
   * SV1. 와이드 + ?selected=ATLAS-3 초기 진입 → 우측 상세 페인(variant='pane')이 렌더되고
   * IssueTable의 해당 행이 aria-current로 강조되며, 문서 전체 h1은 목록 제목 1개뿐이다.
   */
  it('SV1: 와이드 + selected=ATLAS-3 → 상세 페인 렌더 + 행 강조 + h1 1개', async () => {
    mockUseSearch.mockReturnValue({ selected: 'ATLAS-3' })
    renderRouteAdapter()

    await waitFor(() => expect(screen.getByText('ATLAS-1')).toBeInTheDocument())

    // 상세 페인 — mock IssueDetailPage가 variant='pane'이면 h2로 렌더한다
    expect(screen.getByRole('heading', { level: 2, name: 'ATLAS-3' })).toBeInTheDocument()

    // 문서 전체 h1은 목록 제목("이슈 목록") 1개뿐이어야 한다
    expect(screen.getAllByRole('heading', { level: 1 })).toHaveLength(1)
    expect(screen.getByRole('heading', { level: 1, name: '이슈 목록' })).toBeInTheDocument()

    // IssueTable 선택 행 강조
    const selectedRow = screen.getByTestId('select-ATLAS-3').closest('tr')
    expect(selectedRow).not.toBeNull()
    expect(selectedRow).toHaveAttribute('aria-current', 'true')
  })

  /**
   * SV2. 와이드에서 행 클릭 → 기존 status/sort/page 검색 파라미터를 보존한 채 selected로 navigate한다.
   */
  it('SV2: 와이드에서 행 클릭 시 기존 status/sort/page를 보존하며 selected로 navigate한다', async () => {
    renderRouteAdapter()

    await waitFor(() => expect(screen.getByText('ATLAS-3')).toBeInTheDocument())

    const user = userEvent.setup()
    await user.click(screen.getByRole('link', { name: 'ATLAS-3' }))

    expect(mockNavigate).toHaveBeenCalledTimes(1)
    const call = getLastNavigateCall()
    if (!('search' in call)) throw new Error('search 콜백 기반 navigate가 아닙니다')
    expect(call.to).toBe('/issues')
    expect(call.search({ status: 'open', sort: 'priority,asc', page: 2 })).toEqual({
      status: 'open',
      sort: 'priority,asc',
      page: 2,
      selected: 'ATLAS-3',
    })
  })

  /**
   * SV3. 이미 선택된 키를 재클릭하면 selected가 제거된다(toggle off).
   */
  it('SV3: 이미 선택된 키를 재클릭하면 selected가 제거된다(toggle off)', async () => {
    renderRouteAdapter()

    await waitFor(() => expect(screen.getByText('ATLAS-3')).toBeInTheDocument())

    const user = userEvent.setup()
    await user.click(screen.getByRole('link', { name: 'ATLAS-3' }))

    const call = getLastNavigateCall()
    if (!('search' in call)) throw new Error('search 콜백 기반 navigate가 아닙니다')
    expect(call.search({ selected: 'ATLAS-3' })).toEqual({ selected: undefined })
  })

  /**
   * SV4. 페인 닫기(onClose) → selected가 제거되고 다른 검색 파라미터는 보존된다.
   */
  it('SV4: 페인 닫기(onClose) 시 selected가 제거된다', async () => {
    mockUseSearch.mockReturnValue({ selected: 'ATLAS-3' })
    renderRouteAdapter()

    await waitFor(() => expect(screen.getByTestId('mock-issue-detail-pane')).toBeInTheDocument())

    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: '페인 닫기' }))

    const call = getLastNavigateCall()
    if (!('search' in call)) throw new Error('search 콜백 기반 navigate가 아닙니다')
    expect(call.search({ selected: 'ATLAS-3', sort: 'priority,asc' })).toEqual({
      selected: undefined,
      sort: 'priority,asc',
    })
  })

  /**
   * SV5. 삭제(onIssueClosed) → selected가 제거된다.
   */
  it('SV5: 페인 삭제(onIssueClosed) 시 selected가 제거된다', async () => {
    mockUseSearch.mockReturnValue({ selected: 'ATLAS-3' })
    renderRouteAdapter()

    await waitFor(() => expect(screen.getByTestId('mock-issue-detail-pane')).toBeInTheDocument())

    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: '페인 삭제' }))

    const call = getLastNavigateCall()
    if (!('search' in call)) throw new Error('search 콜백 기반 navigate가 아닙니다')
    expect(call.search({ selected: 'ATLAS-3' })).toEqual({ selected: undefined })
  })

  /**
   * SV6. 리다이렉트(onIssueRedirect) → selected가 새 키로 갱신된다.
   */
  it('SV6: 페인 리다이렉트(onIssueRedirect) 시 selected가 새 키로 갱신된다', async () => {
    mockUseSearch.mockReturnValue({ selected: 'ATLAS-3' })
    renderRouteAdapter()

    await waitFor(() => expect(screen.getByTestId('mock-issue-detail-pane')).toBeInTheDocument())

    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: '페인 리다이렉트' }))

    const call = getLastNavigateCall()
    if (!('search' in call)) throw new Error('search 콜백 기반 navigate가 아닙니다')
    expect(call.search({ selected: 'ATLAS-3' })).toEqual({ selected: 'NEW-1' })
  })

  /**
   * SV7. 좁은폭(useMediaQuery=false) → 상세 페인이 렌더되지 않고, 행 클릭 시
   * 전체화면(/issues/$key)으로 이동한다. selected가 URL에 있어도 페인은 무시한다.
   */
  it('SV7: 좁은폭에서는 페인이 렌더되지 않고 행 클릭이 /issues/$key로 전체화면 이동한다', async () => {
    mockUseMediaQuery.mockReturnValue(false)
    mockUseSearch.mockReturnValue({ selected: 'ATLAS-3' })
    renderRouteAdapter()

    await waitFor(() => expect(screen.getByText('ATLAS-1')).toBeInTheDocument())
    expect(screen.queryByTestId('mock-issue-detail-pane')).not.toBeInTheDocument()

    const user = userEvent.setup()
    await user.click(screen.getByRole('link', { name: 'ATLAS-1' }))

    const call = getLastNavigateCall()
    if (!('params' in call)) throw new Error('params 기반 navigate가 아닙니다')
    expect(call.to).toBe('/issues/$key')
    expect(call.params).toEqual({ key: 'ATLAS-1' })
  })

  /**
   * SV8. selected 미지정(와이드) → 페인 없이 목록이 전체폭으로 렌더된다(무회귀).
   */
  it('SV8: selected 미지정 시 페인 없이 목록이 전체폭으로 렌더된다(무회귀)', async () => {
    renderRouteAdapter()

    await waitFor(() => expect(screen.getByText('ATLAS-1')).toBeInTheDocument())
    expect(screen.queryByTestId('mock-issue-detail-pane')).not.toBeInTheDocument()
    expect(screen.getByRole('table', { name: '이슈 목록' })).toBeInTheDocument()
  })

  /**
   * SV9. 필터 변경 시 selected(상세 페인)가 보존된다 (CONCERNS-1, NFR-3 — 상세 페인은
   * selected 키 기준 독립 fetch이며 목록 필터와 무관해야 한다).
   * handleFilterChange가 search를 통째 nextSearch로 교체하면 selected가 유실돼
   * split 상태에서 필터를 바꾸면 페인이 닫히는 회귀가 생긴다.
   */
  it('SV9: 필터 변경 시 selected가 보존된다', async () => {
    mockUseSearch.mockReturnValue({ selected: 'ATLAS-1', status: 'open' })
    renderRouteAdapter()

    await waitFor(() => expect(screen.getByTestId('mock-issue-detail-pane')).toBeInTheDocument())

    const resetBtn = await screen.findByRole('button', { name: /초기화/i })
    const user = userEvent.setup()
    await user.click(resetBtn)

    // 1번째 호출 = 어댑터 handleFilterChange의 navigate (검증 대상).
    // 2번째 호출은 IssueListPage.handleFilterChange가 이어서 호출하는 onPageChange(0)의
    // navigate로, search가 (prev) => ({...prev, page: nextPage})라 selected를 항상 보존한다 —
    // 여기서 확인하면 handleFilterChange 자체의 selected 유실 버그를 놓친다.
    const call = getNavigateCallAt(0)
    if (!('search' in call)) throw new Error('search 콜백 기반 navigate가 아닙니다')
    expect(call.search({ selected: 'ATLAS-1', status: 'open' })).toEqual(
      expect.objectContaining({ selected: 'ATLAS-1', page: 0 }),
    )
  })

  /**
   * SV10. selected가 바뀌면 상세 페인 인스턴스가 재마운트된다(key={selected}) — CONCERNS-3.
   * key 없이 인스턴스가 재사용되면 confirmDelete 등 잔여 state가 다음 이슈로 이월돼
   * 잘못된 이슈가 삭제될 위험이 있다([[react-usestate-stale-key-prop]]).
   * mock IssueDetailPage 내부 useState(dirty)로 잔여 state를 시뮬레이션한다 —
   * 재마운트되면 초기화(marker 소멸), 인스턴스가 재사용되면 유지(marker 잔존)된다.
   */
  it('SV10: selected가 바뀌면 상세 페인이 재마운트되어 잔여 state가 초기화된다', async () => {
    mockUseSearch.mockReturnValue({ selected: 'ATLAS-1' })
    const { rerender, client } = renderRouteAdapter()

    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 2, name: 'ATLAS-1' })).toBeInTheDocument(),
    )

    // mock 페인 내부 상태를 "더럽힌다" (confirmDelete 등 잔여 state 시뮬레이션)
    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: '잔여 상태 토글' }))
    expect(screen.getByTestId('pane-dirty-marker')).toBeInTheDocument()

    // selected가 ATLAS-2로 바뀐 상태를 반영해 재렌더 (URL 변경 시뮬레이션)
    mockUseSearch.mockReturnValue({ selected: 'ATLAS-2' })
    rerender(
      <QueryClientProvider client={client}>
        <IssueListRouteAdapter />
      </QueryClientProvider>,
    )

    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 2, name: 'ATLAS-2' })).toBeInTheDocument(),
    )

    // 재마운트됐다면 잔여 state(marker)가 초기화되어 사라져야 한다
    expect(screen.queryByTestId('pane-dirty-marker')).not.toBeInTheDocument()
  })

  /**
   * SV11. selected가 빈 문자열이면 상세 페인이 렌더되지 않는다 (CONCERNS-4, 스펙 EC-1).
   * `/issues?selected=`처럼 값이 빈 문자열로 남아있으면 가드(`selected !== undefined`)를
   * 통과해 issueKey=''로 페인이 열려 404가 렌더되는 회귀가 생긴다.
   */
  it('SV11: selected가 빈 문자열이면 페인이 렌더되지 않는다 (EC-1)', async () => {
    mockUseSearch.mockReturnValue({ selected: '' })
    renderRouteAdapter()

    await waitFor(() => expect(screen.getByText('ATLAS-1')).toBeInTheDocument())
    expect(screen.queryByTestId('mock-issue-detail-pane')).not.toBeInTheDocument()
    expect(screen.getByRole('table', { name: '이슈 목록' })).toBeInTheDocument()
  })

  /**
   * SV11b. selected가 공백 문자열이면(트림 후 빈 값) 상세 페인이 렌더되지 않는다 (EC-1).
   */
  it('SV11b: selected가 공백 문자열이면 페인이 렌더되지 않는다 (EC-1)', async () => {
    mockUseSearch.mockReturnValue({ selected: '   ' })
    renderRouteAdapter()

    await waitFor(() => expect(screen.getByText('ATLAS-1')).toBeInTheDocument())
    expect(screen.queryByTestId('mock-issue-detail-pane')).not.toBeInTheDocument()
    expect(screen.getByRole('table', { name: '이슈 목록' })).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// FR-UX-07 — 활성 프로젝트 해소 (DEFAULT_PROJECT_KEY 하드코딩 제거)
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueListRouteAdapter — 활성 프로젝트 (FR-UX-07)', () => {
  /** 이슈 목록 조회에 실제로 실린 projectKey 를 기록한다 */
  let requestedProjectKeys: string[] = []

  beforeEach(() => {
    requestedProjectKeys = []
    localStorage.clear()
    useActiveProject.setState({ activeProjectKey: null })
    server.use(createTruePermissionHandler)
    mockUseMediaQuery.mockReturnValue(true)
    mockUseSearch.mockReturnValue({})
    mockNavigate.mockClear()
  })

  /**
   * 어댑터를 렌더하되 이슈 조회의 projectKey 를 가로채 기록한다.
   *
   * ★ 등록 순서가 중요하다 — 한 번의 `server.use(a, b, c)` 안에서는 **인자 순서상 앞선 것이
   * 우선**한다(첫 매칭 핸들러가 이긴다). capture 를 뒤에 두면 `issueHandlers` 의
   * `/api/v1/issues` 가 이겨서 기록이 비고, 테스트가 원인과 무관하게 실패한다.
   * (호출 단위로는 나중 `server.use` 가 앞선 것을 이긴다 — 두 규칙이 다르다.)
   */
  function renderWithCapture(overrides: Parameters<typeof server.use> = []) {
    server.use(
      // overrides 를 맨 앞에 둬야 기본 핸들러를 이긴다. 렌더 **뒤**에 등록하면 최초 조회가
      // 이미 기본 응답으로 캐시돼 override 가 영영 반영되지 않는다.
      ...overrides,
      http.get('/api/v1/issues', ({ request }) => {
        const key = new URL(request.url).searchParams.get('projectKey')
        if (key !== null) requestedProjectKeys.push(key)
        return HttpResponse.json(issuePageFixture)
      }),
      ...workflowHandlers,
      ...userHandlers,
      ...componentHandlers,
      ...labelHandlers,
      ...issueHandlers,
      ...projectListHandlers,
    )
    const client = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    return render(
      <QueryClientProvider client={client}>
        <IssueListRouteAdapter />
      </QueryClientProvider>,
    )
  }

  it('AP1 (S1): ?projectKey=ZETA 면 그 프로젝트로 이슈를 조회한다', async () => {
    mockUseSearch.mockReturnValue({ projectKey: 'ZETA' })
    renderWithCapture()

    await waitFor(() => expect(requestedProjectKeys).toContain('ZETA'))
  })

  it('AP2 (S2): URL 이 없으면 저장값 프로젝트로 조회한다', async () => {
    useActiveProject.setState({ activeProjectKey: 'ZETA' })
    mockUseSearch.mockReturnValue({})
    renderWithCapture()

    await waitFor(() => expect(requestedProjectKeys).toContain('ZETA'))
    expect(requestedProjectKeys).not.toContain('ATLAS')
  })

  it('AP3 (S3): URL·저장값 둘 다 없으면 목록의 첫 프로젝트로 조회하고 저장한다', async () => {
    mockUseSearch.mockReturnValue({})
    renderWithCapture()

    // project-handlers 활성 시드는 name 오름차순 ATLAS·MIDDLE·ZETA — 첫 원소는 ATLAS
    await waitFor(() => expect(requestedProjectKeys).toContain('ATLAS'))
    expect(useActiveProject.getState().activeProjectKey).toBe('ATLAS')
  })

  it('AP4 (FR7/E1): 프로젝트 목록 로딩 중에는 이슈를 조회하지 않는다', () => {
    mockUseSearch.mockReturnValue({})
    renderWithCapture()

    // 프로젝트 응답 전 동기 시점 — 이슈 조회가 나가면 안 된다(빈 projectKey 요청 방지)
    expect(requestedProjectKeys).toHaveLength(0)
  })

  it('AP5 (S5/FR8): 접근 가능한 프로젝트가 0개면 빈 상태를 보여주고 이슈를 조회하지 않는다', async () => {
    mockUseSearch.mockReturnValue({})
    renderWithCapture([http.get('/api/v1/projects', () => HttpResponse.json({ data: [] }))])

    await waitFor(() =>
      expect(screen.getByText(/접근 가능한 프로젝트가 없습니다/)).toBeInTheDocument(),
    )
    expect(requestedProjectKeys).toHaveLength(0)
    expect(screen.getByRole('link', { name: /프로젝트/ })).toHaveAttribute('href', '/projects')
  })

  it('AP6 (E2): 프로젝트 목록 조회에 실패하면 에러를 표시하고 이슈를 조회하지 않는다', async () => {
    mockUseSearch.mockReturnValue({})
    renderWithCapture([
      http.get('/api/v1/projects', () => new HttpResponse(null, { status: 500 })),
    ])

    await waitFor(() => expect(screen.getByRole('alert')).toBeInTheDocument())
    // 재시도 수단이 있어야 사용자에게 탈출구가 생긴다 (스펙 E2). ★ 반드시 클릭 전에 단언한다 —
    // 회복 후에는 이슈 조회가 나가 이 값이 1이 된다.
    expect(requestedProjectKeys).toHaveLength(0)

    // /api/v1/projects 전용 카운터 — requestedProjectKeys는 이슈 조회의 projectKey만 모으므로
    // 재조회 검증에 재사용하면 항상 0인 공허 단언이 된다.
    let projectListCalls = 0
    server.use(
      http.get('/api/v1/projects', () => {
        projectListCalls += 1
        return HttpResponse.json({ data: projectListFixtures })
      }),
    )

    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: '다시 시도' }))

    await waitFor(() => expect(projectListCalls).toBeGreaterThan(0))
  })

  /**
   * ★ AP7 — plan 독립 리뷰 BLOCKER B2.
   * handleFilterChange 만 `...prev` 를 펼치지 않아 필터를 한 번 누르면 projectKey 가 URL 에서
   * 증발한다. TanStack `search` 는 객체형이면 병합이 아니라 치환이고 전 필드가 optional 이라
   * 타입 체크로도 안 잡힌다 — 조용한 회귀다.
   *
   * 통짜 `...prev` 스프레드는 처방이 아니다 — issueFilterToSearch 가 빈 필터 키를 생략하므로
   * prev 를 통째로 펼치면 해제한 필터가 되살아난다. projectKey 만 명시 보존해야 한다.
   */
  it('AP7 (B2): 필터를 바꿔도 projectKey 가 URL 에서 유지된다', async () => {
    mockUseSearch.mockReturnValue({ projectKey: 'ZETA', status: 'open' })
    renderWithCapture()

    const resetBtn = await screen.findByRole('button', { name: /초기화/i })
    const user = userEvent.setup()
    await user.click(resetBtn)

    const call = getNavigateCallAt(0)
    if (!('search' in call)) throw new Error('search 콜백 기반 navigate가 아닙니다')
    const next = call.search({ projectKey: 'ZETA', status: 'open' })
    expect(next).toEqual(expect.objectContaining({ projectKey: 'ZETA', page: 0 }))
    // 해제한 필터는 되살아나면 안 된다 — 통짜 ...prev 스프레드 처방을 반증하는 가드
    expect(next).not.toHaveProperty('status')
  })

  it('AP8 (B2): 정렬·페이지·선택 변경 navigate 도 projectKey 를 보존한다', async () => {
    mockUseSearch.mockReturnValue({ projectKey: 'ZETA' })
    renderWithCapture()

    await waitFor(() => expect(screen.getByRole('table', { name: '이슈 목록' })).toBeInTheDocument())

    const user = userEvent.setup()
    await user.click(screen.getByRole('button', { name: /키/ }))

    const call = getLastNavigateCall()
    if (!('search' in call)) throw new Error('search 콜백 기반 navigate가 아닙니다')
    expect(call.search({ projectKey: 'ZETA' })).toEqual(
      expect.objectContaining({ projectKey: 'ZETA' }),
    )
  })

  /**
   * ★ AP9 — plan 독립 리뷰 CONCERN C5.
   * 조기 반환을 어댑터 최상단에 두면 split view 우측 상세 페인까지 사라진다. 상세는 selected
   * 키 기준 독립 fetch 라 프로젝트 해소와 무관해야 한다(스펙 E9 직교성).
   */
  it('AP9 (C5): 프로젝트 목록이 로딩 중이어도 split view 상세 페인은 렌더된다', async () => {
    // ★ 코드리뷰 CR4 — 이전 버전은 waitFor 가 해소 완료(ready)까지 기다려서, 조기 반환을
    // 어댑터 최상단으로 끌어올려도(=C5 결함을 재주입해도) 그대로 통과하는 공허 가드였다.
    // 프로젝트 응답을 **영원히 pending** 으로 묶어 loading 시점 자체를 고정한다.
    mockUseSearch.mockReturnValue({ selected: 'ATLAS-3' })
    renderWithCapture([http.get('/api/v1/projects', () => new Promise(() => {}))])

    // 목록 영역은 게이트(로딩)지만 우측 상세 페인은 살아 있어야 한다 — selected 키 기준
    // 독립 fetch 라 프로젝트 해소와 직교한다(스펙 E9)
    await waitFor(() => expect(screen.getByTestId('mock-issue-detail-pane')).toBeInTheDocument())
    expect(screen.queryByRole('table', { name: '이슈 목록' })).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// FR-UX-10 F10 — 목록 항법 커서 (j/k/o/t)
//
// 커서는 새 상태가 아니라 기존 split 선택(selectedKey)이다(ADR D-3). 여기서는
// "다음 키 계산 + 콜백 위임"이 맞는지 본다. 실제 키 입력 → 판별은 T3(파이프라인)
// 테스트가 덮으므로, 등록된 핸들러를 직접 불러 배선만 검증한다.
// ─────────────────────────────────────────────────────────────────────────────

/** IssueListPage 를 커서 콜백과 함께 렌더한다 */
function renderCursorPage(
  selectedKey: string | null,
  callbacks: {
    onCursorTo?: (key: string) => void
    onOpenCursor?: (key: string) => void
    onCloseDetailPane?: () => void
  },
) {
  server.use(
    ...workflowHandlers,
    ...userHandlers,
    ...componentHandlers,
    ...labelHandlers,
    ...issueHandlers,
    ...projectListHandlers,
  )
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <IssueListPage
        projectKey="ATLAS"
        page={0}
        onPageChange={() => undefined}
        onNavigate={() => undefined}
        filter={EMPTY_FILTER}
        onFilterChange={() => undefined}
        selectedKey={selectedKey}
        {...callbacks}
      />
    </QueryClientProvider>,
  )
}

/** 등록된 issue-list 컨텍스트 핸들러를 꺼낸다 (전역 리스너 없이 배선만 검증) */
function listHandlers() {
  return useContextShortcutsStore.getState().handlers['issue-list']
}

describe('IssueListPage — 목록 항법 커서 (FR-UX-10 F10)', () => {
  beforeEach(() => {
    server.use(createTruePermissionHandler)
    useContextShortcutsStore.setState({ handlers: {} })
  })

  it('마운트하면 issue-list 컨텍스트를 등록한다', async () => {
    renderCursorPage(null, {})
    await waitFor(() => expect(screen.getByText('ATLAS-1')).toBeInTheDocument())

    expect(listHandlers()).toBeDefined()
  })

  it('커서가 없을 때 j 는 첫 행을 잡는다 (E2)', async () => {
    const onCursorTo = vi.fn()
    renderCursorPage(null, { onCursorTo })
    await waitFor(() => expect(screen.getByText('ATLAS-1')).toBeInTheDocument())

    listHandlers()?.onCursorMove?.(1)

    expect(onCursorTo).toHaveBeenCalledWith('ATLAS-1')
  })

  it('커서가 첫 행일 때 j 는 다음 행으로 간다', async () => {
    const onCursorTo = vi.fn()
    renderCursorPage('ATLAS-1', { onCursorTo })
    await waitFor(() => expect(screen.getByText('ATLAS-2')).toBeInTheDocument())

    listHandlers()?.onCursorMove?.(1)

    expect(onCursorTo).toHaveBeenCalledWith('ATLAS-2')
  })

  it('E3 — 첫 행에서 k 는 무동작이다 (감싸지 않는다)', async () => {
    const onCursorTo = vi.fn()
    renderCursorPage('ATLAS-1', { onCursorTo })
    await waitFor(() => expect(screen.getByText('ATLAS-1')).toBeInTheDocument())

    listHandlers()?.onCursorMove?.(-1)

    expect(onCursorTo).not.toHaveBeenCalled()
  })

  it('o 는 커서 이슈를 전체화면으로 연다', async () => {
    const onOpenCursor = vi.fn()
    renderCursorPage('ATLAS-2', { onOpenCursor })
    await waitFor(() => expect(screen.getByText('ATLAS-2')).toBeInTheDocument())

    listHandlers()?.onOpenCurrent?.()

    expect(onOpenCursor).toHaveBeenCalledWith('ATLAS-2')
  })

  it('커서가 없으면 o 는 무동작이다 (열 대상이 없다)', async () => {
    const onOpenCursor = vi.fn()
    renderCursorPage(null, { onOpenCursor })
    await waitFor(() => expect(screen.getByText('ATLAS-1')).toBeInTheDocument())

    listHandlers()?.onOpenCurrent?.()

    expect(onOpenCursor).not.toHaveBeenCalled()
  })

  it('t 는 열린 페인을 닫는다 (S4)', async () => {
    const onCloseDetailPane = vi.fn()
    renderCursorPage('ATLAS-2', { onCloseDetailPane })
    await waitFor(() => expect(screen.getByText('ATLAS-2')).toBeInTheDocument())

    listHandlers()?.onToggleDetailPane?.()

    expect(onCloseDetailPane).toHaveBeenCalledOnce()
  })

  it('t 는 닫힌 페인을 첫 행으로 연다 (S4)', async () => {
    const onCursorTo = vi.fn()
    const onCloseDetailPane = vi.fn()
    renderCursorPage(null, { onCursorTo, onCloseDetailPane })
    await waitFor(() => expect(screen.getByText('ATLAS-1')).toBeInTheDocument())

    listHandlers()?.onToggleDetailPane?.()

    expect(onCursorTo).toHaveBeenCalledWith('ATLAS-1')
    expect(onCloseDetailPane).not.toHaveBeenCalled()
  })

  it('★FR11 — 커서 위치를 aria-live 로 공지한다 (총 개수·순번·키·제목)', async () => {
    renderCursorPage('ATLAS-1', {})
    await waitFor(() => expect(screen.getByText('ATLAS-1')).toBeInTheDocument())

    const status = screen.getByTestId('issue-cursor-announcement')
    expect(status).toHaveAttribute('aria-live', 'polite')
    expect(status.textContent).toMatch(/^\d+개 중 1번째, ATLAS-1, .+/)
  })

  it('커서가 없으면 공지문은 비어 있다 (빈 공지를 읽지 않는다)', async () => {
    renderCursorPage(null, {})
    await waitFor(() => expect(screen.getByText('ATLAS-1')).toBeInTheDocument())

    expect(screen.getByTestId('issue-cursor-announcement').textContent).toBe('')
  })

  // ───────────────────────────────────────────────────────────────────────────
  // ★FR10 — 커서가 뷰포트를 벗어나면 따라간다.
  //
  // 가드가 없어서 effect(그 안의 `tr[aria-current="true"]` 셀렉터 포함 — 주석이
  // "방어의 핵심"이라 부르는 그 줄)를 통째로 지워도 전부 green 이었다.
  // ───────────────────────────────────────────────────────────────────────────
  it('★FR10 — 커서 행을 block:"nearest" 로 스크롤에 따라오게 한다', async () => {
    const scrollIntoView = vi.fn()
    const original = window.HTMLElement.prototype.scrollIntoView
    // jsdom 은 scrollIntoView 를 구현하지 않는다 (CommandPalette.test.tsx:8-11 선례)
    window.HTMLElement.prototype.scrollIntoView = scrollIntoView

    try {
      renderCursorPage('ATLAS-2', {})
      await waitFor(() => expect(screen.getByText('ATLAS-2')).toBeInTheDocument())

      await waitFor(() => expect(scrollIntoView).toHaveBeenCalledWith({ block: 'nearest' }))
    } finally {
      window.HTMLElement.prototype.scrollIntoView = original
    }
  })

  it('★FR10 셀렉터 — 커서가 없으면 스크롤하지 않는다 (엉뚱한 행을 끌고 오지 않는다)', async () => {
    const scrollIntoView = vi.fn()
    const original = window.HTMLElement.prototype.scrollIntoView
    window.HTMLElement.prototype.scrollIntoView = scrollIntoView

    try {
      renderCursorPage(null, {})
      await waitFor(() => expect(screen.getByText('ATLAS-1')).toBeInTheDocument())

      expect(scrollIntoView).not.toHaveBeenCalled()
    } finally {
      window.HTMLElement.prototype.scrollIntoView = original
    }
  })
})

describe('IssueListRouteAdapter — 커서 URL 갱신 (FR-UX-10 F10)', () => {
  beforeEach(() => {
    mockNavigate.mockReset()
    useContextShortcutsStore.setState({ handlers: {} })
    // ★와이드를 **명시**한다. 이전에는 다른 describe 가 남긴 값에 얹혀 돌아서,
    // 그 블록을 지우거나 순서를 바꾸면 이 테스트가 무엇을 검증하는지 조용히 바뀌었다.
    mockUseMediaQuery.mockReturnValue(true)
    server.use(createTruePermissionHandler)
  })

  it('★replace:true — 커서 이동이 히스토리를 쌓지 않는다 (연타 후 뒤로가기 1회로 이탈)', async () => {
    mockUseSearch.mockReturnValue({})
    renderRouteAdapter()
    await waitFor(() => expect(screen.getByText('ATLAS-1')).toBeInTheDocument())

    listHandlers()?.onCursorMove?.(1)

    expect(mockNavigate).toHaveBeenCalledWith(
      expect.objectContaining({ to: '/issues', replace: true }),
    )
  })

  it('★★다른 검색 파라미터가 살아남는다 — project-switcher.spec.ts 회귀 가드의 유닛 짝', async () => {
    mockUseSearch.mockReturnValue({ projectKey: 'ATLAS', status: 'open', sort: 'key,asc' })
    renderRouteAdapter()
    await waitFor(() => expect(screen.getByText('ATLAS-1')).toBeInTheDocument())

    listHandlers()?.onCursorMove?.(1)

    const call = mockNavigate.mock.calls.at(-1)?.[0] as { search: (p: object) => object }
    const nextSearch = call.search({ projectKey: 'ATLAS', status: 'open', sort: 'key,asc' })

    expect(nextSearch).toMatchObject({ projectKey: 'ATLAS', status: 'open', sort: 'key,asc' })
    // ★키 존재만 보면 `selected: prev.selected`(무동작)나 `selected: undefined`(삭제)
    // 뮤테이션이 통과한다. 실제 이동 대상까지 못박는다.
    expect(nextSearch).toMatchObject({ selected: 'ATLAS-1' })
  })

  // ───────────────────────────────────────────────────────────────────────────
  // ★E13 — 커서 단축키는 와이드 전용이다.
  //
  // 어댑터의 `isWide ? fn : undefined` 세 줄이 유일한 강제 지점인데 가드가 없었다.
  // 실측(뮤테이션 M7)으로 확인 — `onCursorTo={isWide ? moveCursorTo : undefined}` 를
  // `onCursorTo={moveCursorTo}` 로 바꿔도 167건 전부 green 이었다. 구현 중 실측이
  // 스펙을 뒤집어 만든 계약인데 정작 그 계약만 무방비였다.
  // ───────────────────────────────────────────────────────────────────────────
  it('★E13 — 좁은폭에서는 커서 콜백 3종이 전부 끊긴다 (j/k/o/t 무동작)', async () => {
    mockUseMediaQuery.mockReturnValue(false)
    mockUseSearch.mockReturnValue({})
    renderRouteAdapter()
    await waitFor(() => expect(screen.getByText('ATLAS-1')).toBeInTheDocument())

    const handlers = listHandlers()
    expect(handlers).toBeDefined()

    handlers?.onCursorMove?.(1)
    handlers?.onOpenCurrent?.()
    handlers?.onToggleDetailPane?.()

    // 세 줄 중 **하나라도** isWide 가드를 잃으면 이 단언이 깨진다.
    expect(mockNavigate).not.toHaveBeenCalled()
  })

  it('E13 대조군 — 같은 호출이 와이드에서는 실제로 navigate 한다 (비-공허 확인)', async () => {
    mockUseMediaQuery.mockReturnValue(true)
    mockUseSearch.mockReturnValue({})
    renderRouteAdapter()
    await waitFor(() => expect(screen.getByText('ATLAS-1')).toBeInTheDocument())

    listHandlers()?.onCursorMove?.(1)

    expect(mockNavigate).toHaveBeenCalled()
  })
})
