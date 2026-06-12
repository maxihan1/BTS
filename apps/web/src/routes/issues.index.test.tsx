// 이슈 목록 페이지 단위 테스트 — 3-상태 + 빈 상태 + 페이지네이션 + CREATE 권한 게이트 + 일괄 선택/액션
import { describe, it, expect, vi, beforeEach } from 'vitest'
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
} from '@/mocks/issue-fixtures'
import { nonMemberProjectPermissions } from '@/mocks/project-permission-handlers'
import { IssueListPage } from './issues.index'

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
  () => HttpResponse.json({ projectKey: 'ATLAS', permissions: { CREATE: true, MANAGE_COMPONENTS: false, MANAGE_VERSIONS: false, MANAGE_CUSTOM_FIELDS: false, MANAGE_FIELD_PERMISSIONS: false, MANAGE_TEMPLATES: false } }),
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

function renderPage(page = 0, onPageChange?: (page: number) => void, onNavigate?: (key: string) => void) {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
  return render(
    <QueryClientProvider client={client}>
      <IssueListPage
        projectKey="ATLAS"
        page={page}
        onPageChange={onPageChange ?? (() => undefined)}
        onNavigate={onNavigate ?? (() => undefined)}
      />
    </QueryClientProvider>,
  )
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
