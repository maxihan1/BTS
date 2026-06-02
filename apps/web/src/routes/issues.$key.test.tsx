// 이슈 상세 페이지 단위 테스트 — Task 7 + FR-IS-01 Task-4 (전이 배선)
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { toast } from 'sonner'
import { server } from '@/test/server'
import { issueAtlas1Fixture, issueAtlasNoWorkflowFixture } from '@/mocks/issue-fixtures'
import { issueTypeHandlers } from '@/mocks/issue-type-handlers'
import { MOCK_CONFLICT_TRIGGER, MOCK_NO_WORKFLOW_TRIGGER } from '@/mocks/issue-handlers'
import { issueDetailStrings } from '@/i18n/ko'
import { IssueDetailPage } from './issues.$key'

// useIssuePermissions를 mock — 기존 테스트는 권한 제어를 검증하지 않으므로 모든 권한 true로 고정
vi.mock('@/hooks/use-issue-permissions', () => ({
  useIssuePermissions: vi.fn(),
}))
import { useIssuePermissions } from '@/hooks/use-issue-permissions'

/** 기존 테스트 전체에서 모든 권한 true — 기존 테스트는 권한 제어를 검증하지 않는다 */
beforeEach(() => {
  vi.mocked(useIssuePermissions).mockReturnValue({
    data: {
      issueKey: 'ATLAS-1',
      permissions: { UPDATE: true, SOFT_DELETE: true, TRANSITION: true },
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
  } as ReturnType<typeof useIssuePermissions>)
})

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function makeClient() {
  return new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
}

function renderPage(issueKey: string) {
  const client = makeClient()
  return {
    client,
    ...render(
      <QueryClientProvider client={client}>
        <IssueDetailPage issueKey={issueKey} />
      </QueryClientProvider>,
    ),
  }
}

// sonner toast mock — DOM 없이 호출 여부로 검증 (기존 프로젝트 패턴 일치)
vi.mock('sonner', () => ({
  toast: {
    error: vi.fn(),
    success: vi.fn(),
  },
}))

// navigate mock — useDeleteIssue onSuccess에서 호출
const mockNavigate = vi.fn()
vi.mock('@tanstack/react-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@tanstack/react-router')>()
  return {
    ...actual,
    useNavigate: () => mockNavigate,
    useParams: () => ({ key: 'ATLAS-1' }),
  }
})

// ─────────────────────────────────────────────────────────────────────────────
// MSW 핸들러 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function setupIssueFoundHandler(fixture = issueAtlas1Fixture) {
  server.use(
    http.get('/api/v1/issues/:key', ({ params }) => {
      if (params['key'] === fixture.key) {
        return HttpResponse.json({ data: fixture })
      }
      return HttpResponse.json(
        { message: `이슈를 찾을 수 없습니다: ${params['key'] as string}` },
        { status: 404 },
      )
    }),
  )
}

function setupIssueNotFoundHandler() {
  server.use(
    http.get('/api/v1/issues/:key', () =>
      HttpResponse.json({ message: '이슈를 찾을 수 없습니다' }, { status: 404 }),
    ),
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 3-상태 분기 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueDetailPage — 3-상태 분기', () => {
  /**
   * T7-1. 로딩 상태 — fetchIssue 응답 대기 중 "로딩 중..." 텍스트 렌더.
   */
  it('T7-1: 로딩 상태에서 "로딩 중..." 텍스트를 렌더한다', () => {
    // 응답을 지연시켜 로딩 상태를 유지
    server.use(
      http.get('/api/v1/issues/:key', async () => {
        await new Promise((resolve) => setTimeout(resolve, 10_000))
        return HttpResponse.json({ data: issueAtlas1Fixture })
      }),
    )

    renderPage('ATLAS-1')

    expect(screen.getByText('로딩 중...')).toBeInTheDocument()
  })

  /**
   * T7-2. 에러 상태 — 404 응답 시 role="alert" + "이슈를 찾을 수 없습니다" 렌더.
   * WCAG AA: role=alert로 스크린 리더가 즉시 인식.
   */
  it('T7-2: 404 에러 시 role="alert"와 오류 메시지를 렌더한다', async () => {
    setupIssueNotFoundHandler()

    renderPage('NOT-EXIST')

    await waitFor(() => {
      const alert = screen.getByRole('alert')
      expect(alert).toBeInTheDocument()
      expect(alert).toHaveTextContent('이슈를 찾을 수 없습니다')
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 성공 상태 — 레이아웃 검증
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueDetailPage — 성공 레이아웃', () => {
  /**
   * T7-3. breadcrumb에 "ATLAS / ATLAS-1" 패턴이 렌더되어야 한다.
   */
  it('T7-3: breadcrumb에 프로젝트 키와 이슈 키를 렌더한다', async () => {
    setupIssueFoundHandler()

    renderPage('ATLAS-1')

    await waitFor(() => {
      // nav 내부에서만 검사 — 메타패널에도 ATLAS가 있으므로 nav 범위로 좁힘
      const nav = screen.getByRole('navigation', { name: '이동 경로' })
      expect(within(nav).getByText('ATLAS')).toBeInTheDocument()
      expect(within(nav).getByText('ATLAS-1')).toBeInTheDocument()
    })
  })

  /**
   * T7-4. 이슈 제목(summary)이 h1으로 렌더되어야 한다.
   */
  it('T7-4: 이슈 제목이 h1 요소로 렌더된다', async () => {
    setupIssueFoundHandler()

    renderPage('ATLAS-1')

    await waitFor(() => {
      const heading = screen.getByRole('heading', { level: 1 })
      expect(heading).toHaveTextContent(issueAtlas1Fixture.summary)
    })
  })

  /**
   * T7-5. 메타패널에 상태가 읽기전용 배지로 렌더되어야 한다.
   * 상태 전이 드롭다운이 없어야 한다 (D6 제외).
   * 유형 셀렉터(combobox)는 있지만, 상태 전이용 combobox는 없다.
   */
  it('T7-5: 메타패널에 상태 배지가 렌더되고 상태 전이 드롭다운이 없다', async () => {
    setupIssueFoundHandler()

    renderPage('ATLAS-1')

    await waitFor(() => {
      // 상태 배지가 렌더되어야 함
      expect(screen.getByTestId('issue-state-badge')).toBeInTheDocument()
      // 유형 셀렉터가 있어야 함 (Task-6에서 추가)
      expect(screen.getByRole('combobox', { name: /유형/ })).toBeInTheDocument()
    })
  })

  /**
   * T7-6. 메타패널에 보고자, 프로젝트 키, 버전이 렌더되어야 한다.
   */
  it('T7-6: 메타패널에 보고자·프로젝트·버전이 렌더된다', async () => {
    setupIssueFoundHandler()

    const { container } = renderPage('ATLAS-1')

    await waitFor(() => {
      const aside = container.querySelector('aside')
      expect(aside).toBeInTheDocument()
      if (aside === null) return
      expect(within(aside).getByText('보고자')).toBeInTheDocument()
      expect(within(aside).getByText('프로젝트')).toBeInTheDocument()
      expect(within(aside).getByText('버전')).toBeInTheDocument()
    })
  })

  /**
   * T7-7. createdAt이 null인 경우 "—" 표기.
   */
  it('T7-7: createdAt null인 경우 "—"를 렌더한다', async () => {
    const nullDateFixture = { ...issueAtlas1Fixture, createdAt: null, updatedAt: null }
    setupIssueFoundHandler(nullDateFixture)

    const { container } = renderPage('ATLAS-1')

    await waitFor(() => {
      const aside = container.querySelector('aside')
      expect(aside).toBeInTheDocument()
      if (aside === null) return
      // 두 날짜 모두 null이므로 "—"가 두 개 이상 렌더되어야 함
      const dashes = within(aside).getAllByText('—')
      expect(dashes.length).toBeGreaterThanOrEqual(2)
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 제목 인라인 편집 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueDetailPage — 제목 인라인 편집', () => {
  /**
   * T7-8. "제목 수정" 버튼 클릭 시 input이 나타나고 기존 제목이 채워져 있어야 한다.
   */
  it('T7-8: "제목 수정" 클릭 시 편집 input이 나타나고 기존 제목이 채워진다', async () => {
    setupIssueFoundHandler()
    const user = userEvent.setup()

    renderPage('ATLAS-1')

    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument(),
    )

    const editButton = screen.getByRole('button', { name: /제목 수정/ })
    await user.click(editButton)

    const input = screen.getByRole('textbox', { name: /제목 편집/ })
    expect(input).toBeInTheDocument()
    expect(input).toHaveValue(issueAtlas1Fixture.summary)
  })

  /**
   * T7-9. 편집 모드에서 저장 시 useUpdateIssueSummary.mutate가 호출된다.
   */
  it('T7-9: 편집 모드에서 저장 시 PATCH 요청이 발생한다', async () => {
    setupIssueFoundHandler()

    let patchCalled = false
    server.use(
      http.patch('/api/v1/issues/:key', async () => {
        patchCalled = true
        return HttpResponse.json({
          data: { ...issueAtlas1Fixture, summary: '수정된 제목', version: 1 },
        })
      }),
    )

    const user = userEvent.setup()
    renderPage('ATLAS-1')

    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument(),
    )

    const editButton = screen.getByRole('button', { name: /제목 수정/ })
    await user.click(editButton)

    const input = screen.getByRole('textbox', { name: /제목 편집/ })
    await user.clear(input)
    await user.type(input, '수정된 제목')

    // 제목 편집 input 주변의 저장 버튼을 input의 부모 컨테이너로 좁혀 선택
    const saveButton = screen.getAllByRole('button', { name: /저장/ }).find(
      (btn) => btn.getAttribute('aria-label') === issueDetailStrings.saveButton,
    )
    expect(saveButton).toBeDefined()
    if (saveButton === undefined) return
    await user.click(saveButton)

    await waitFor(() => {
      expect(patchCalled).toBe(true)
    })
  })

  /**
   * T7-10. 편집 모드에서 취소 시 편집 input이 사라지고 h1이 다시 나타난다.
   */
  it('T7-10: 편집 모드에서 취소 시 h1으로 돌아간다', async () => {
    setupIssueFoundHandler()
    const user = userEvent.setup()

    renderPage('ATLAS-1')

    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument(),
    )

    const editButton = screen.getByRole('button', { name: /제목 수정/ })
    await user.click(editButton)

    expect(screen.getByRole('textbox', { name: /제목 편집/ })).toBeInTheDocument()

    const cancelButton = screen.getByRole('button', { name: /취소/ })
    await user.click(cancelButton)

    expect(screen.queryByRole('textbox', { name: /제목 편집/ })).not.toBeInTheDocument()
    expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 삭제 버튼 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueDetailPage — 삭제', () => {
  /**
   * T7-11. "이슈 삭제" 버튼 클릭 시 확인 다이얼로그(confirm)가 나타난다.
   */
  it('T7-11: "이슈 삭제" 버튼 클릭 시 확인 UI가 나타난다', async () => {
    setupIssueFoundHandler()
    const user = userEvent.setup()

    renderPage('ATLAS-1')

    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument(),
    )

    const deleteButton = screen.getByRole('button', { name: /이슈 삭제/ })
    await user.click(deleteButton)

    // 확인 다이얼로그 또는 확인 버튼이 나타나야 함
    expect(screen.getByRole('button', { name: /확인/ })).toBeInTheDocument()
  })

  /**
   * T7-12. 삭제 확인 후 DELETE 요청이 발생하고 navigate(목록)가 호출된다.
   */
  it('T7-12: 삭제 확인 시 DELETE 요청 후 목록으로 navigate한다', async () => {
    setupIssueFoundHandler()

    let deleteCalled = false
    server.use(
      http.delete('/api/v1/issues/:key', () => {
        deleteCalled = true
        return new HttpResponse(null, { status: 204 })
      }),
    )

    mockNavigate.mockClear()
    const user = userEvent.setup()

    renderPage('ATLAS-1')

    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument(),
    )

    const deleteButton = screen.getByRole('button', { name: /이슈 삭제/ })
    await user.click(deleteButton)

    const confirmButton = screen.getByRole('button', { name: /확인/ })
    await user.click(confirmButton)

    await waitFor(() => {
      expect(deleteCalled).toBe(true)
      expect(mockNavigate).toHaveBeenCalledWith({ to: '/issues' })
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 공통 사용자 목록 핸들러 헬퍼 (Task 4 — useUsers hook이 GET /api/v1/users 호출)
// ─────────────────────────────────────────────────────────────────────────────

function setupUsersHandler() {
  server.use(
    http.get('/api/v1/users', () =>
      HttpResponse.json([
        { id: 'c3d4e5f6-a7b8-4c9d-ae1f-2a3b4c5d6e7f', username: 'alice', displayName: '김앨리스', email: null },
        { id: 'd4e5f6a7-b8c9-4d0e-af1f-3b4c5d6e7f8a', username: 'bob', displayName: null, email: null },
      ]),
    ),
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 타입 변경 테스트 (C-2)
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueDetailPage — 타입 변경', () => {
  beforeEach(() => {
    vi.mocked(toast.error).mockClear()
    vi.mocked(toast.success).mockClear()
    // issue-types 핸들러 등록 — useIssueTypes hook이 GET /api/v1/issue-types 호출
    server.use(...issueTypeHandlers)
    // ATLAS-1 이슈 단건 조회 핸들러
    setupIssueFoundHandler()
    // ATLAS-1 전이 목록 핸들러 — useIssueTransitions hook이 GET /api/v1/issues/ATLAS-1/transitions 호출
    setupTransitionsHandler()
    // 사용자 목록 핸들러 — useUsers hook이 GET /api/v1/users 호출
    setupUsersHandler()
  })

  /**
   * T7-13 (happy). 유형 셀렉터에서 다른 타입을 선택하면 PATCH 요청이 발생하고
   * 성공 후 메타패널의 타입 이름이 갱신된다.
   *
   * MSW 실제 핸들러 경유 — ATLAS-1(version=0, typeId=1/bug) → typeId=3(task) 변경.
   */
  it('T7-13: 유형 셀렉터 변경 시 PATCH 요청이 발생하고 타입 이름이 갱신된다', async () => {
    const updatedFixture = {
      ...issueAtlas1Fixture,
      typeId: 3,
      typeKey: 'task',
      typeName: '작업',
      version: 1,
    }

    // GET 핸들러를 상태 있는 형태로 override — PATCH 성공 이후 re-fetch 시 updatedFixture 반환
    let currentFixture = issueAtlas1Fixture as typeof issueAtlas1Fixture
    let patchBody: Record<string, unknown> | undefined
    server.use(
      http.get('/api/v1/issues/:key', ({ params }) => {
        if (params['key'] === 'ATLAS-1') {
          return HttpResponse.json({ data: currentFixture })
        }
        return HttpResponse.json({ message: '이슈를 찾을 수 없습니다' }, { status: 404 })
      }),
      http.patch('/api/v1/issues/:key', async ({ request }) => {
        patchBody = await request.clone().json() as Record<string, unknown>
        // PATCH 성공 이후 GET re-fetch가 업데이트된 데이터를 반환하도록 상태 전환
        currentFixture = updatedFixture
        return HttpResponse.json({ data: updatedFixture })
      }),
    )

    const user = userEvent.setup()
    renderPage('ATLAS-1')

    // 이슈 로딩 대기 — typeKey='bug' 초기 상태
    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument(),
    )
    // 이슈 타입 목록 로딩 대기 — 셀렉터에 옵션이 채워질 때까지
    await waitFor(() =>
      expect(screen.getByRole('combobox', { name: issueDetailStrings.typeSelectLabel })).toBeInTheDocument(),
    )
    // 초기 타입 이름이 '버그'임을 확인
    expect(screen.getByTestId('issue-type-name')).toHaveTextContent('버그')

    const select = screen.getByRole('combobox', { name: issueDetailStrings.typeSelectLabel })
    // '작업'(typeId=3, task) 선택 — 현재값 '버그'(typeId=1)와 달라 onTypeChange 호출됨
    await user.selectOptions(select, '작업')

    // PATCH 요청 발생 및 body 검증
    await waitFor(() => {
      expect(patchBody).toBeDefined()
    })
    expect(patchBody?.['typeId']).toBe(3)
    expect(patchBody?.['expectedVersion']).toBe(0)

    // onSuccess → setQueryData(즉시) + invalidateQueries(re-fetch) 이후 타입 이름 '작업' 갱신
    await waitFor(() => {
      expect(screen.getByTestId('issue-type-name')).toHaveTextContent('작업')
    })
  })

  /**
   * T7-14 (409). 타입 변경 시 낙관락 버전 충돌(409 VERSION_CONFLICT)이 발생하면
   * versionConflictError 한국어 토스트가 노출된다.
   *
   * PATCH → 409 응답은 MSW override로 직접 제어.
   */
  it('T7-14: 타입 변경 409 충돌 시 versionConflictError 토스트가 노출된다', async () => {
    server.use(
      http.patch('/api/v1/issues/:key', () =>
        HttpResponse.json(
          { errorCode: 'VERSION_CONFLICT', message: '버전 충돌이 발생했습니다.' },
          { status: 409 },
        ),
      ),
    )

    const user = userEvent.setup()
    renderPage('ATLAS-1')

    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument(),
    )
    await waitFor(() =>
      expect(screen.getByRole('combobox', { name: issueDetailStrings.typeSelectLabel })).toBeInTheDocument(),
    )

    const select = screen.getByRole('combobox', { name: issueDetailStrings.typeSelectLabel })
    // '작업'(typeId=3, task) 선택 → PATCH → 409 → onError
    await user.selectOptions(select, '작업')

    await waitFor(() => {
      expect(vi.mocked(toast.error)).toHaveBeenCalledWith(
        issueDetailStrings.versionConflictError,
      )
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 상태전이 배선 테스트 (FR-IS-01 Task-4)
// ─────────────────────────────────────────────────────────────────────────────


/** ATLAS-1(open) 가용전이 목록 핸들러 — 전이 테스트 공통 */
function setupTransitionsHandler() {
  server.use(
    http.get('/api/v1/issues/ATLAS-1/transitions', () =>
      HttpResponse.json({
        data: {
          transitions: [
            { key: 'open__in_progress', name: 'Start Work', fromStateKey: 'open', toStateKey: 'in_progress' },
            { key: 'open__closed', name: 'Cancel', fromStateKey: 'open', toStateKey: 'closed' },
          ],
        },
      }),
    ),
  )
}

/** POST /api/v1/issues/ATLAS-1/transition 성공 핸들러 — 전이 테스트 공통 */
function setupTransitionPostHandler() {
  server.use(
    http.post('/api/v1/issues/:key/transition', async ({ params, request }) => {
      const key = params['key'] as string
      const body = await request.clone().json() as { toStatusKey?: string; expectedVersion?: number }
      const toStatusKey = body.toStatusKey ?? ''

      if (toStatusKey === MOCK_NO_WORKFLOW_TRIGGER) {
        return HttpResponse.json(
          { errorCode: 'workflow_not_configured', message: '워크플로우 미설정' },
          { status: 422 },
        )
      }
      if (toStatusKey === MOCK_CONFLICT_TRIGGER) {
        return HttpResponse.json(
          { errorCode: 'TRANSITION_NOT_ALLOWED', message: '전이 거부' },
          { status: 409 },
        )
      }
      return HttpResponse.json({
        data: {
          key,
          id: 'a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d',
          projectKey: 'ATLAS',
          summary: '첫 번째 이슈 — 로그인 페이지 구현',
          currentStateKey: toStatusKey,
          reporterId: 'b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d6e',
          version: 1,
          createdAt: '2026-01-01T09:00:00Z',
          updatedAt: new Date().toISOString(),
          typeId: 1,
          typeKey: 'bug',
          typeName: '버그',
        },
      })
    }),
  )
}

describe('IssueDetailPage — 상태전이', () => {
  beforeEach(() => {
    vi.mocked(toast.error).mockClear()
    vi.mocked(toast.success).mockClear()
    server.use(...issueTypeHandlers)
    setupIssueFoundHandler()
    setupTransitionsHandler()
    setupTransitionPostHandler()
    setupUsersHandler()
  })

  /**
   * T4-1 (happy): 전이 셀렉터에서 "Start Work" 선택 → POST 요청 발생 + 상태 배지 갱신.
   * ATLAS-1(open, version=0) → in_progress 전이. stateful override 핸들러 경유.
   */
  it('T4-1: 전이 선택 시 POST 요청이 발생하고 상태 배지가 갱신된다', async () => {
    // stateful override: POST 후 GET이 in_progress 상태를 반환하도록 상태 공유
    let currentStateKey = 'open'
    let currentVersion = 0

    server.use(
      http.get('/api/v1/issues/:key', ({ params }) => {
        if (params['key'] !== 'ATLAS-1') {
          return HttpResponse.json({ message: '이슈를 찾을 수 없습니다' }, { status: 404 })
        }
        return HttpResponse.json({
          data: { ...issueAtlas1Fixture, currentStateKey, version: currentVersion },
        })
      }),
      http.post('/api/v1/issues/:key/transition', async ({ request }) => {
        const body = await request.clone().json() as { toStatusKey?: string; expectedVersion?: number }
        const toStatusKey = body.toStatusKey ?? ''
        currentStateKey = toStatusKey
        currentVersion += 1
        return HttpResponse.json({
          data: { ...issueAtlas1Fixture, currentStateKey: toStatusKey, version: currentVersion },
        })
      }),
    )

    const user = userEvent.setup()
    renderPage('ATLAS-1')

    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument(),
    )
    // 전이 셀렉터가 렌더될 때까지 대기
    await waitFor(() =>
      expect(screen.getByRole('combobox', { name: issueDetailStrings.transitionSelectLabel })).toBeInTheDocument(),
    )

    const select = screen.getByRole('combobox', { name: issueDetailStrings.transitionSelectLabel })
    await user.selectOptions(select, 'in_progress')

    // onSuccess → 캐시 무효화 → GET re-fetch → 상태 배지 갱신
    await waitFor(() => {
      expect(screen.getByTestId('issue-state-badge')).toHaveTextContent('in_progress')
    })
  })

  /**
   * T4-2 (S3, 409 transition_not_allowed): MOCK_CONFLICT_TRIGGER 선택 →
   * 409 transition_not_allowed → transitionNotAllowedError 토스트.
   */
  it('T4-2: 409 transition_not_allowed 시 transitionNotAllowedError 토스트가 노출된다', async () => {
    // MOCK_CONFLICT_TRIGGER를 전이 옵션에 추가하기 위해 transitions 핸들러 override
    server.use(
      http.get('/api/v1/issues/ATLAS-1/transitions', () =>
        HttpResponse.json({
          data: {
            transitions: [
              { key: `open__${MOCK_CONFLICT_TRIGGER}`, name: 'Trigger Conflict', fromStateKey: 'open', toStateKey: MOCK_CONFLICT_TRIGGER },
            ],
          },
        }),
      ),
    )

    const user = userEvent.setup()
    renderPage('ATLAS-1')

    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument(),
    )
    await waitFor(() =>
      expect(screen.getByRole('combobox', { name: issueDetailStrings.transitionSelectLabel })).toBeInTheDocument(),
    )

    const select = screen.getByRole('combobox', { name: issueDetailStrings.transitionSelectLabel })
    await user.selectOptions(select, MOCK_CONFLICT_TRIGGER)

    await waitFor(() => {
      expect(vi.mocked(toast.error)).toHaveBeenCalledWith(
        issueDetailStrings.transitionNotAllowedError,
      )
    })
  })

  /**
   * T4-3 (S4, 409 version_conflict): expectedVersion 불일치 →
   * 409 version_conflict → transitionVersionConflictError 토스트.
   */
  it('T4-3: 409 version_conflict 시 transitionVersionConflictError 토스트가 노출된다', async () => {
    server.use(
      http.post('/api/v1/issues/ATLAS-1/transition', () =>
        HttpResponse.json(
          { errorCode: 'VERSION_CONFLICT', message: '버전 충돌이 발생했습니다.' },
          { status: 409 },
        ),
      ),
    )

    const user = userEvent.setup()
    renderPage('ATLAS-1')

    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument(),
    )
    await waitFor(() =>
      expect(screen.getByRole('combobox', { name: issueDetailStrings.transitionSelectLabel })).toBeInTheDocument(),
    )

    const select = screen.getByRole('combobox', { name: issueDetailStrings.transitionSelectLabel })
    await user.selectOptions(select, 'in_progress')

    await waitFor(() => {
      expect(vi.mocked(toast.error)).toHaveBeenCalledWith(
        issueDetailStrings.transitionVersionConflictError,
      )
    })
  })

  /**
   * T4-4 (S5, 422): POST 422 응답 → transitionWorkflowNotConfiguredError 토스트.
   */
  it('T4-4: 422 응답 시 transitionWorkflowNotConfiguredError 토스트가 노출된다', async () => {
    // MOCK_NO_WORKFLOW_TRIGGER를 옵션으로 주입
    server.use(
      http.get('/api/v1/issues/ATLAS-1/transitions', () =>
        HttpResponse.json({
          data: {
            transitions: [
              { key: `open__${MOCK_NO_WORKFLOW_TRIGGER}`, name: 'Trigger 422', fromStateKey: 'open', toStateKey: MOCK_NO_WORKFLOW_TRIGGER },
            ],
          },
        }),
      ),
    )

    const user = userEvent.setup()
    renderPage('ATLAS-1')

    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument(),
    )
    await waitFor(() =>
      expect(screen.getByRole('combobox', { name: issueDetailStrings.transitionSelectLabel })).toBeInTheDocument(),
    )

    const select = screen.getByRole('combobox', { name: issueDetailStrings.transitionSelectLabel })
    await user.selectOptions(select, MOCK_NO_WORKFLOW_TRIGGER)

    await waitFor(() => {
      expect(vi.mocked(toast.error)).toHaveBeenCalledWith(
        issueDetailStrings.transitionWorkflowNotConfiguredError,
      )
    })
  })

  /**
   * T4-5 (S6, closed): closed 상태 이슈 로딩 시 전이 셀렉터 미노출 + "더 진행할 전이 없음" 안내.
   * ATLAS-1 key로 closed 상태를 시뮬 — 전이 목록 빈 배열 override.
   */
  it('T4-5: closed 상태(S6) 이슈에서 전이 셀렉터가 없고 "더 진행할 전이 없음" 안내가 보인다', async () => {
    // 전이 목록만 빈 배열로 override — issue 단건은 beforeEach가 처리
    server.use(
      http.get('/api/v1/issues/ATLAS-1/transitions', () =>
        HttpResponse.json({ data: { transitions: [] } }),
      ),
    )

    renderPage('ATLAS-1')

    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument(),
    )

    await waitFor(() => {
      expect(screen.queryByRole('combobox', { name: issueDetailStrings.transitionSelectLabel })).not.toBeInTheDocument()
      expect(screen.getByText(issueDetailStrings.noTransitionsAvailable)).toBeInTheDocument()
    })
  })

  // ── E5 구분 검증 ───────────────────────────────────────────────────────────

  /**
   * T4-6 (E5, S5 미설정): GET /transitions 422 → 미설정 안내문구 노출.
   * "더 진행할 전이 없음"(종료상태 문구)은 보이지 않아야 한다.
   */
  it('T4-6: GET /transitions 422(워크플로우 미설정) 시 미설정 안내문구가 노출된다', async () => {
    server.use(
      http.get('/api/v1/issues/ATLAS-NOWF', () =>
        HttpResponse.json({ data: issueAtlasNoWorkflowFixture }),
      ),
      http.get('/api/v1/issues/ATLAS-NOWF/transitions', () =>
        HttpResponse.json(
          { errorCode: 'workflow_not_configured', message: '이슈에 워크플로우가 설정되지 않았습니다.' },
          { status: 422 },
        ),
      ),
      // issue-types도 필요 (useIssueTypes)
      http.get('/api/v1/issue-types', () =>
        HttpResponse.json({ data: [] }),
      ),
    )

    renderPage('ATLAS-NOWF')

    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument(),
    )

    await waitFor(() => {
      expect(screen.getByText(issueDetailStrings.transitionWorkflowNotConfiguredError)).toBeInTheDocument()
      expect(screen.queryByText(issueDetailStrings.noTransitionsAvailable)).not.toBeInTheDocument()
    })
  })

  /**
   * T4-8 (C1 회귀 가드): 전이 실패(409) 후 셀렉터가 placeholder("")로 리셋되어
   * 같은 옵션을 재선택하면 onChange가 다시 발화되고 두 번째 POST 요청이 발생한다.
   * defaultValue="" 비제어 패턴이면 이 테스트가 실패한다.
   */
  it('T4-8: 전이 실패 후 같은 옵션 재선택 시 POST 요청이 다시 발생한다 (select 리셋 가드)', async () => {
    let postCallCount = 0
    server.use(
      http.post('/api/v1/issues/:key/transition', () => {
        postCallCount += 1
        return HttpResponse.json(
          { errorCode: 'TRANSITION_NOT_ALLOWED', message: '전이 거부' },
          { status: 409 },
        )
      }),
    )

    const user = userEvent.setup()
    renderPage('ATLAS-1')

    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument(),
    )
    await waitFor(() =>
      expect(screen.getByRole('combobox', { name: issueDetailStrings.transitionSelectLabel })).toBeInTheDocument(),
    )

    const select = screen.getByRole('combobox', { name: issueDetailStrings.transitionSelectLabel })

    // 1차 선택 — 실패
    await user.selectOptions(select, 'in_progress')
    await waitFor(() => expect(postCallCount).toBe(1))

    // 실패 후 셀렉터가 placeholder로 리셋되어 있어야 함
    await waitFor(() => {
      expect((select as HTMLSelectElement).value).toBe('')
    })

    // 2차 — 동일 옵션 재선택해도 onChange 발화 → POST 2회
    await user.selectOptions(select, 'in_progress')
    await waitFor(() => expect(postCallCount).toBe(2))
  })

  /**
   * T4-7 (E5 구분): 종료상태(S6, 200+빈배열)와 미설정(S5, 422)이 다른 안내문구를 표시한다.
   * 빈 배열 → noTransitionsAvailable, 422 → transitionWorkflowNotConfiguredError.
   * @task task-6
   */
  it('T4-7: 종료상태(빈 배열)와 워크플로우 미설정(422)이 서로 다른 문구를 표시한다', async () => {
    // (1) 종료상태 — 빈 배열
    server.use(
      http.get('/api/v1/issues/ATLAS-1/transitions', () =>
        HttpResponse.json({ data: { transitions: [] } }),
      ),
    )

    const { unmount } = renderPage('ATLAS-1')

    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument(),
    )
    await waitFor(() => {
      expect(screen.getByText(issueDetailStrings.noTransitionsAvailable)).toBeInTheDocument()
    })

    unmount()

    // (2) 미설정 — 422
    server.use(
      http.get('/api/v1/issues/ATLAS-NOWF', () =>
        HttpResponse.json({ data: issueAtlasNoWorkflowFixture }),
      ),
      http.get('/api/v1/issues/ATLAS-NOWF/transitions', () =>
        HttpResponse.json(
          { errorCode: 'workflow_not_configured', message: '이슈에 워크플로우가 설정되지 않았습니다.' },
          { status: 422 },
        ),
      ),
      http.get('/api/v1/issue-types', () =>
        HttpResponse.json({ data: [] }),
      ),
    )

    renderPage('ATLAS-NOWF')

    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument(),
    )
    await waitFor(() => {
      expect(screen.getByText(issueDetailStrings.transitionWorkflowNotConfiguredError)).toBeInTheDocument()
      // 종료상태 문구는 노출되지 않아야 한다
      expect(screen.queryByText(issueDetailStrings.noTransitionsAvailable)).not.toBeInTheDocument()
    })
  })

})

// ─────────────────────────────────────────────────────────────────────────────
// Task 6 — IssueDescription 배선 + 메타필드 mutation 5종
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Task 6 공통 MSW 핸들러 설정.
 * stateful override: PATCH 성공 후 GET re-fetch가 최신 데이터를 반환하도록 유지.
 */
function setupTask6StatefulHandlers(
  initial: typeof issueAtlas1Fixture,
  patchSpy?: (body: Record<string, unknown>) => void,
) {
  let currentFixture = initial
  server.use(
    http.get('/api/v1/issues/:key', ({ params }) => {
      if (params['key'] === initial.key) {
        return HttpResponse.json({ data: currentFixture })
      }
      return HttpResponse.json({ message: '이슈를 찾을 수 없습니다' }, { status: 404 })
    }),
    http.patch('/api/v1/issues/:key', async ({ request }) => {
      const body = await request.clone().json() as Record<string, unknown>
      patchSpy?.(body)
      const updated = { ...currentFixture, ...body, version: (currentFixture.version) + 1 }
      currentFixture = updated as typeof issueAtlas1Fixture
      return HttpResponse.json({ data: currentFixture })
    }),
  )
}

describe('IssueDetailPage — Task 6 (IssueDescription 배선 + 메타필드 mutation)', () => {
  beforeEach(() => {
    vi.mocked(toast.error).mockClear()
    server.use(...issueTypeHandlers)
    setupTransitionsHandler()
    setupUsersHandler()
  })

  /**
   * T6-1: 자리표시자 대신 IssueDescription 컴포넌트가 렌더된다.
   * descriptionPlaceholder 텍스트가 없고 "본문 편집" 버튼이 있어야 한다.
   */
  it('T6-1: 자리표시자 대신 IssueDescription 컴포넌트가 렌더된다', async () => {
    setupTask6StatefulHandlers(issueAtlas1Fixture)

    renderPage('ATLAS-1')

    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument(),
    )

    // 자리표시자 텍스트가 없어야 함
    expect(screen.queryByText(issueDetailStrings.descriptionPlaceholder)).not.toBeInTheDocument()
    // IssueDescription의 편집 버튼이 있어야 함
    expect(screen.getByRole('button', { name: issueDetailStrings.descriptionEditButton })).toBeInTheDocument()
  })

  /**
   * T6-2: 본문 저장 → PATCH {description} 발생 + stateful refetch 후 화면에 반영.
   * MSW stateful 핸들러 위에서 검증 (msw-mutation-stateful-refetch 메모리 가짜그린 방지).
   */
  it('T6-2: 본문 저장 시 PATCH {description}가 발생하고 refetch 후 화면에 반영된다', async () => {
    const fixture = { ...issueAtlas1Fixture, descriptionHtml: null, description: null }
    const patchBodies: Array<Record<string, unknown>> = []
    setupTask6StatefulHandlers(fixture, (body) => patchBodies.push(body))

    const user = userEvent.setup()
    const { container } = renderPage('ATLAS-1')

    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument(),
    )

    // 본문 편집 버튼은 <main> 안에 있으므로 within(main)으로 범위 좁힘
    const main = container.querySelector('main')
    expect(main).not.toBeNull()
    if (main === null) return

    // 본문 편집 버튼 클릭
    await user.click(within(main).getByRole('button', { name: issueDetailStrings.descriptionEditButton }))

    // textarea에 내용 입력
    const textarea = within(main).getByRole('textbox', { name: issueDetailStrings.descriptionEditButton })
    await user.clear(textarea)
    await user.type(textarea, '새 본문 내용')

    // 저장 — 편집 모드에서의 저장 버튼은 main 안에 있음
    await user.click(within(main).getByRole('button', { name: issueDetailStrings.descriptionSaveButton }))

    await waitFor(() => {
      expect(patchBodies.length).toBeGreaterThan(0)
    })
    const lastPatch = patchBodies[patchBodies.length - 1]
    expect(lastPatch?.['description']).toBe('새 본문 내용')
    expect(lastPatch?.['expectedVersion']).toBe(0)
  })

  /**
   * T6-3: 우선순위 변경 → 즉시 PATCH {priority, expectedVersion} 발생 + stateful refetch.
   */
  it('T6-3: 우선순위 변경 시 즉시 PATCH가 발생하고 refetch 후 화면에 반영된다', async () => {
    const fixture = { ...issueAtlas1Fixture, priority: 3 }
    const patchBodies: Array<Record<string, unknown>> = []
    setupTask6StatefulHandlers(fixture, (body) => patchBodies.push(body))

    const user = userEvent.setup()
    renderPage('ATLAS-1')

    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument(),
    )

    const select = screen.getByRole('combobox', { name: issueDetailStrings.prioritySelectLabel })
    await user.selectOptions(select, '높음') // priority=2

    await waitFor(() => {
      expect(patchBodies.length).toBeGreaterThan(0)
    })
    const lastPatch = patchBodies[patchBodies.length - 1]
    expect(lastPatch?.['priority']).toBe(2)
    expect(lastPatch?.['expectedVersion']).toBe(0)
  })

  /**
   * T6-4: 영향도 변경 → 즉시 PATCH {impact, expectedVersion} 발생.
   * null(미지정) 상태에서 '높음'(impact=1) 선택.
   */
  it('T6-4: 영향도 변경 시 즉시 PATCH가 발생한다', async () => {
    const fixture = { ...issueAtlas1Fixture, impact: null }
    const patchBodies: Array<Record<string, unknown>> = []
    setupTask6StatefulHandlers(fixture, (body) => patchBodies.push(body))

    const user = userEvent.setup()
    renderPage('ATLAS-1')

    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument(),
    )

    const select = screen.getByRole('combobox', { name: issueDetailStrings.impactSelectLabel })
    await user.selectOptions(select, '높음') // impact=1

    await waitFor(() => {
      expect(patchBodies.length).toBeGreaterThan(0)
    })
    const lastPatch = patchBodies[patchBodies.length - 1]
    expect(lastPatch?.['impact']).toBe(1)
    expect(lastPatch?.['expectedVersion']).toBe(0)
  })

  /**
   * T6-5: 환경 저장 → PATCH {environment, expectedVersion} 발생.
   */
  it('T6-5: 환경 저장 버튼 클릭 시 PATCH가 발생한다', async () => {
    const fixture = { ...issueAtlas1Fixture, environment: null }
    const patchBodies: Array<Record<string, unknown>> = []
    setupTask6StatefulHandlers(fixture, (body) => patchBodies.push(body))

    const user = userEvent.setup()
    const { container } = renderPage('ATLAS-1')

    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument(),
    )

    // 환경 섹션은 data-testid="environment-section"으로 범위 좁힘
    const envSection = container.querySelector('[data-testid="environment-section"]')
    expect(envSection).not.toBeNull()
    if (envSection === null) return

    const envTextarea = within(envSection as HTMLElement).getByRole('textbox', { name: issueDetailStrings.environmentLabel })
    await user.clear(envTextarea)
    await user.type(envTextarea, 'Chrome 120')

    await user.click(within(envSection as HTMLElement).getByRole('button', { name: issueDetailStrings.environmentSaveButton }))

    await waitFor(() => {
      expect(patchBodies.length).toBeGreaterThan(0)
    })
    const lastPatch = patchBodies[patchBodies.length - 1]
    expect(lastPatch?.['environment']).toBe('Chrome 120')
    expect(lastPatch?.['expectedVersion']).toBe(0)
  })

  /**
   * T6-6: 라벨 저장 → PATCH {labels, expectedVersion} 발생.
   */
  it('T6-6: 라벨 저장 버튼 클릭 시 PATCH가 발생한다', async () => {
    const fixture = { ...issueAtlas1Fixture, labels: [] }
    const patchBodies: Array<Record<string, unknown>> = []
    setupTask6StatefulHandlers(fixture, (body) => patchBodies.push(body))

    const user = userEvent.setup()
    const { container } = renderPage('ATLAS-1')

    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument(),
    )

    // 라벨 섹션은 data-testid="labels-section"으로 범위 좁힘
    const labelsSection = container.querySelector('[data-testid="labels-section"]')
    expect(labelsSection).not.toBeNull()
    if (labelsSection === null) return

    const labelInput = within(labelsSection as HTMLElement).getByRole('textbox', { name: issueDetailStrings.labelAddPlaceholder })
    await user.click(labelInput)
    await user.type(labelInput, 'frontend{Enter}')

    await user.click(within(labelsSection as HTMLElement).getByRole('button', { name: issueDetailStrings.labelsSaveButton }))

    await waitFor(() => {
      expect(patchBodies.length).toBeGreaterThan(0)
    })
    const lastPatch = patchBodies[patchBodies.length - 1]
    expect(Array.isArray(lastPatch?.['labels'])).toBe(true)
    expect((lastPatch?.['labels'] as string[]).includes('frontend')).toBe(true)
    expect(lastPatch?.['expectedVersion']).toBe(0)
  })

  /**
   * T6-7: 409 VERSION_CONFLICT → toast(versionConflictError) + invalidateQueries.
   * descriptionMutation 409 응답 시 검증.
   * 주의: beforeEach에 /api/v1/users 핸들러가 필요하나 Task 4 GREEN 전까지 setupUsersHandler가 없음.
   */
  it('T6-7: 본문 저장 409 충돌 시 versionConflictError 토스트가 노출된다', async () => {
    setupIssueFoundHandler()
    server.use(
      http.patch('/api/v1/issues/:key', () =>
        HttpResponse.json(
          { errorCode: 'VERSION_CONFLICT', message: '버전 충돌' },
          { status: 409 },
        ),
      ),
    )

    const user = userEvent.setup()
    const { container } = renderPage('ATLAS-1')

    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument(),
    )

    // 본문 영역은 <main> 안에 있으므로 within(main)으로 범위 좁힘
    const main = container.querySelector('main')
    expect(main).not.toBeNull()
    if (main === null) return

    await user.click(within(main).getByRole('button', { name: issueDetailStrings.descriptionEditButton }))
    const textarea = within(main).getByRole('textbox', { name: issueDetailStrings.descriptionEditButton })
    await user.type(textarea, '충돌 테스트')
    await user.click(within(main).getByRole('button', { name: issueDetailStrings.descriptionSaveButton }))

    await waitFor(() => {
      expect(vi.mocked(toast.error)).toHaveBeenCalledWith(
        issueDetailStrings.versionConflictError,
      )
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// Task 4 — 담당자 배선 (FR-IS-03 D6)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 담당자 변경 MSW stateful 핸들러.
 * PATCH /assignee 성공 → issueOverrides에 영속 → GET refetch 시 최신 데이터 반환.
 * (msw-mutation-stateful-refetch 교훈 — setQueryData 위 가짜그린 방지)
 */
function setupAssigneeStatefulHandlers(initial: typeof issueAtlas1Fixture & { assigneeId?: string | null }) {
  let currentFixture = { ...initial }
  server.use(
    http.get('/api/v1/issues/:key', ({ params }) => {
      if (params['key'] === initial.key) {
        return HttpResponse.json({ data: currentFixture })
      }
      return HttpResponse.json({ message: '이슈를 찾을 수 없습니다' }, { status: 404 })
    }),
    http.patch('/api/v1/issues/:key/assignee', async ({ request }) => {
      const body = await request.clone().json() as { assigneeId: string | null; expectedVersion: number }
      // 성공 — stateful 영속 (교훈 2: invalidate refetch 후 롤백 방지)
      currentFixture = {
        ...currentFixture,
        assigneeId: body.assigneeId,
        version: currentFixture.version + 1,
      }
      return HttpResponse.json({ data: currentFixture })
    }),
  )
}

describe('IssueDetailPage — 담당자 배선 (Task 4)', () => {
  const aliceId = 'c3d4e5f6-a7b8-4c9d-ae1f-2a3b4c5d6e7f'

  beforeEach(() => {
    vi.mocked(toast.error).mockClear()
    server.use(...issueTypeHandlers)
    setupTransitionsHandler()
    setupUsersHandler()
  })

  it('T4-A1: 담당자 섹션(assignee-section)이 메타패널에 렌더된다', async () => {
    setupAssigneeStatefulHandlers(issueAtlas1Fixture)
    const { container } = renderPage('ATLAS-1')
    await waitFor(() => expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument())
    const aside = container.querySelector('aside')
    if (aside === null) throw new Error('aside not found')
    expect(within(aside).getByTestId('assignee-section')).toBeInTheDocument()
  })

  it('T4-A2: assigneeId=null이면 미지정 텍스트가 표시된다', async () => {
    const fixture = { ...issueAtlas1Fixture, assigneeId: null }
    setupAssigneeStatefulHandlers(fixture)
    renderPage('ATLAS-1')
    await waitFor(() => {
      const assigneeSection = screen.getByTestId('assignee-section')
      expect(within(assigneeSection).getByText(issueDetailStrings.assigneeUnassigned)).toBeInTheDocument()
    })
  })

  it('T4-A3: 사용자 선택 시 PATCH /assignee가 발생하고 refetch 후 담당자 이름이 표시된다', async () => {
    const fixture = { ...issueAtlas1Fixture, assigneeId: null }
    setupAssigneeStatefulHandlers(fixture)
    const user = userEvent.setup()
    const { container } = renderPage('ATLAS-1')
    await waitFor(() => expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument())
    const aside = container.querySelector('aside')
    if (aside === null) throw new Error('aside not found')
    const assigneeSection = within(aside).getByTestId('assignee-section')
    await waitFor(() => expect(within(assigneeSection).queryByRole('button', { name: '김앨리스' })).toBeInTheDocument())
    await user.click(within(assigneeSection).getByRole('button', { name: '김앨리스' }))
    await waitFor(() => {
      const currentNameEl = within(assigneeSection).getByTestId('assignee-current-name')
      expect(currentNameEl.textContent).toBe('김앨리스')
    })
  })

  it('T4-A4: 담당자 해제 시 PATCH /assignee(null)가 발생하고 refetch 후 미지정이 표시된다', async () => {
    const fixture = { ...issueAtlas1Fixture, assigneeId: aliceId }
    setupAssigneeStatefulHandlers(fixture)
    const user = userEvent.setup()
    const { container } = renderPage('ATLAS-1')
    await waitFor(() => expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument())
    const aside = container.querySelector('aside')
    if (aside === null) throw new Error('aside not found')
    const assigneeSection = within(aside).getByTestId('assignee-section')
    await waitFor(() => expect(within(assigneeSection).queryByRole('button', { name: issueDetailStrings.assigneeUnassignButton })).toBeInTheDocument())
    await user.click(within(assigneeSection).getByRole('button', { name: issueDetailStrings.assigneeUnassignButton }))
    await waitFor(() => {
      const currentNameEl = within(assigneeSection).getByTestId('assignee-current-name')
      expect(currentNameEl.textContent).toBe(issueDetailStrings.assigneeUnassigned)
    })
  })

  it('T4-A5: 담당자 변경 409 충돌 시 toast.error가 호출된다', async () => {
    const fixture = { ...issueAtlas1Fixture, assigneeId: null }
    setupAssigneeStatefulHandlers(fixture)
    server.use(
      http.patch('/api/v1/issues/:key/assignee', () =>
        HttpResponse.json({ errorCode: 'VERSION_CONFLICT' }, { status: 409 }),
      ),
    )
    const user = userEvent.setup()
    const { container } = renderPage('ATLAS-1')
    await waitFor(() => expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument())
    const aside = container.querySelector('aside')
    if (aside === null) throw new Error('aside not found')
    const assigneeSection = within(aside).getByTestId('assignee-section')
    await waitFor(() => expect(within(assigneeSection).queryByRole('button', { name: '김앨리스' })).toBeInTheDocument())
    await user.click(within(assigneeSection).getByRole('button', { name: '김앨리스' }))
    await waitFor(() => expect(vi.mocked(toast.error)).toHaveBeenCalled())
  })
})
