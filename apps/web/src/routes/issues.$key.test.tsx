// 이슈 상세 페이지 단위 테스트 — Task 7 + FR-IS-01 Task-4 (전환 배선)
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { toast } from 'sonner'
import { settlePendingMutations } from '@/test/pending-mutation-guard'
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

// useLabels를 mock — LabelAutocompleteInput 내부 react-query 호출 차단 (IssueMetaPanel.test.tsx와 동일 방식)
// 기본값: 빈 후보 배열 — 라벨 저장 테스트(T6-6)는 free-form 입력을 사용하므로 후보 불필요
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
  }),
}))

// useDebounce를 mock — debounce 없이 즉시 반환해 테스트 단순화 (IssueMetaPanel.test.tsx와 동일 방식)
vi.mock('@/hooks/use-debounce', () => ({
  useDebounce: (value: string) => value,
}))

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
  } as unknown as ReturnType<typeof useIssuePermissions>)
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
   * 상태 전환 드롭다운이 없어야 한다 (D6 제외).
   * 유형 셀렉터(combobox)는 있지만, 상태 전환용 combobox는 없다.
   */
  it('T7-5: 메타패널에 상태 배지가 렌더되고 상태 전환 드롭다운이 없다', async () => {
    setupIssueFoundHandler()

    const { container } = renderPage('ATLAS-1')

    await waitFor(() => {
      // 상태 배지가 렌더되어야 함
      expect(screen.getByTestId('issue-state-badge')).toBeInTheDocument()
      // 유형 셀렉터가 있어야 함 (Task-6에서 추가) — IssueLinksPanel의 "링크 유형" select와
      // 충돌하지 않도록 aside(메타패널) 안으로 범위 좁힘 (playwright-getbyrole-exact-strict-mode)
      const aside = container.querySelector('aside')
      expect(aside).not.toBeNull()
      if (aside === null) return
      expect(within(aside).getByRole('combobox', { name: /유형/ })).toBeInTheDocument()
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
// FR-UX-11 F8 Task 1 — 제목 텍스트 클릭으로 편집 진입
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueDetailPage — 제목 텍스트 클릭 진입 (FR-UX-11 F8 Task 1)', () => {
  /**
   * F8-T1-1. 제목 텍스트 자체를 클릭하면 편집 모드로 진입한다 (FR1).
   * 기존 "✎ 제목 수정" 버튼 경로와 별개의 두 번째 진입면이다.
   */
  it('F8-T1-1: 제목 텍스트 클릭 시 편집 모드로 진입한다', async () => {
    setupIssueFoundHandler()
    const user = userEvent.setup()

    renderPage('ATLAS-1')

    const title = await screen.findByRole('button', { name: issueAtlas1Fixture.summary })
    await user.click(title)

    expect(screen.getByRole('textbox', { name: issueDetailStrings.titleEditLabel })).toBeInTheDocument()
  })

  /**
   * F8-T1-2. 클릭 진입면을 넣어도 heading 의 접근성 이름이 그대로다 (jira-parity-contract §2).
   * heading 의 이름은 자손 텍스트에서 계산되므로 안쪽을 button 으로 감싸도 보존돼야 한다.
   */
  it('F8-T1-2: 제목 heading 의 접근성 이름이 클릭 진입면을 넣어도 보존된다', async () => {
    setupIssueFoundHandler()

    renderPage('ATLAS-1')

    expect(
      await screen.findByRole('heading', { level: 1, name: issueAtlas1Fixture.summary }),
    ).toBeInTheDocument()
  })

  /**
   * F8-T1-3. 수정 권한이 없으면 클릭 진입면 자체가 없다 (FR8, fail-closed).
   * heading 은 그대로 남아 읽기는 가능해야 한다.
   */
  it('F8-T1-3: 수정 권한이 없으면 클릭 진입면이 없고 heading 은 남는다', async () => {
    vi.mocked(useIssuePermissions).mockReturnValue({
      data: {
        issueKey: 'ATLAS-1',
        permissions: { UPDATE: false, SOFT_DELETE: false, TRANSITION: false },
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
    setupIssueFoundHandler()

    renderPage('ATLAS-1')

    expect(
      await screen.findByRole('heading', { level: 1, name: issueAtlas1Fixture.summary }),
    ).toBeInTheDocument()
    expect(
      screen.queryByRole('button', { name: issueAtlas1Fixture.summary }),
    ).not.toBeInTheDocument()
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
        { id: '00000000-0000-4000-8000-000000000001', username: 'alice', displayName: '김앨리스', email: null },
        { id: '00000000-0000-4000-8000-000000000002', username: 'bob', displayName: null, email: null },
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
    // ATLAS-1 전환 목록 핸들러 — useIssueTransitions hook이 GET /api/v1/issues/ATLAS-1/transitions 호출
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
// 상태전환 배선 테스트 (FR-IS-01 Task-4)
// ─────────────────────────────────────────────────────────────────────────────


/** ATLAS-1(open) 가용전환 목록 핸들러 — 전환 테스트 공통 */
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

/** POST /api/v1/issues/ATLAS-1/transition 성공 핸들러 — 전환 테스트 공통 */
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
          { errorCode: 'TRANSITION_NOT_ALLOWED', message: '전환 거부' },
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

describe('IssueDetailPage — 상태전환', () => {
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
   * T4-1 (happy): 전환 셀렉터에서 "Start Work" 선택 → POST 요청 발생 + 상태 배지 갱신.
   * ATLAS-1(open, version=0) → in_progress 전환. stateful override 핸들러 경유.
   */
  it('T4-1: 전환 선택 시 POST 요청이 발생하고 상태 배지가 갱신된다', async () => {
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
    // 전환 셀렉터가 렌더될 때까지 대기
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
    // MOCK_CONFLICT_TRIGGER를 전환 옵션에 추가하기 위해 transitions 핸들러 override
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
   * T4-5 (S6, closed): closed 상태 이슈 로딩 시 전환 셀렉터 미노출 + "더 진행할 전환 없음" 안내.
   * ATLAS-1 key로 closed 상태를 시뮬 — 전환 목록 빈 배열 override.
   */
  it('T4-5: closed 상태(S6) 이슈에서 전환 셀렉터가 없고 "더 진행할 전환 없음" 안내가 보인다', async () => {
    // 전환 목록만 빈 배열로 override — issue 단건은 beforeEach가 처리
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
   * "더 진행할 전환 없음"(종료상태 문구)은 보이지 않아야 한다.
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
   * T4-8 (C1 회귀 가드): 전환 실패(409) 후 셀렉터가 placeholder("")로 리셋되어
   * 같은 옵션을 재선택하면 onChange가 다시 발화되고 두 번째 POST 요청이 발생한다.
   * defaultValue="" 비제어 패턴이면 이 테스트가 실패한다.
   */
  it('T4-8: 전환 실패 후 같은 옵션 재선택 시 POST 요청이 다시 발생한다 (select 리셋 가드)', async () => {
    let postCallCount = 0
    server.use(
      http.post('/api/v1/issues/:key/transition', () => {
        postCallCount += 1
        return HttpResponse.json(
          { errorCode: 'TRANSITION_NOT_ALLOWED', message: '전환 거부' },
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

    // 문서 <main>은 ShellLayout이 단독 소유 — 컴포넌트 단독 렌더 시 0개여야 함(PL-7 landmark 강등)
    expect(container.querySelector('main')).toBeNull()

    // 본문 편집 버튼은 <section aria-label="이슈 상세"> 안에 있으므로 within(section)으로 범위 좁힘
    const section = container.querySelector<HTMLElement>('section[aria-label="이슈 상세"]')
    expect(section).not.toBeNull()
    if (section === null) return

    // 본문 편집 버튼 클릭
    await user.click(within(section).getByRole('button', { name: issueDetailStrings.descriptionEditButton }))

    // textarea에 내용 입력
    const textarea = within(section).getByRole('textbox', { name: issueDetailStrings.descriptionEditButton })
    await user.clear(textarea)
    await user.type(textarea, '새 본문 내용')

    // 저장 — 편집 모드에서의 저장 버튼은 section 안에 있음
    await user.click(within(section).getByRole('button', { name: issueDetailStrings.descriptionSaveButton }))

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

    // LabelAutocompleteInput은 cmdk CommandPrimitive.Input — data-testid로 접근 (IssueMetaPanel.test.tsx와 동일 방식)
    const labelInput = within(labelsSection as HTMLElement).getByTestId('label-autocomplete-input')
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

    // 문서 <main>은 ShellLayout이 단독 소유 — 컴포넌트 단독 렌더 시 0개여야 함(PL-7 landmark 강등)
    expect(container.querySelector('main')).toBeNull()

    // 본문 영역은 <section aria-label="이슈 상세"> 안에 있으므로 within(section)으로 범위 좁힘
    const section = container.querySelector<HTMLElement>('section[aria-label="이슈 상세"]')
    expect(section).not.toBeNull()
    if (section === null) return

    await user.click(within(section).getByRole('button', { name: issueDetailStrings.descriptionEditButton }))
    const textarea = within(section).getByRole('textbox', { name: issueDetailStrings.descriptionEditButton })
    await user.type(textarea, '충돌 테스트')
    await user.click(within(section).getByRole('button', { name: issueDetailStrings.descriptionSaveButton }))

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
  const aliceId = '00000000-0000-4000-8000-000000000001'

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
    // 후보는 검색해야 나온다 (2026-08-09 봉합). 예전에는 이 입력 없이도 목록이 떠 있었다.
    await user.type(
      within(assigneeSection).getByLabelText(issueDetailStrings.assigneeSearchPlaceholder),
      '앨리스',
    )
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

  // ───────────────────────────────────────────────────────────────────────────
  // TODOS 「이슈 상세 담당자 셀렉터가 검색 전에 사용자 전량을 노출한다」 봉합 (2026-08-09)
  //
  // 사내 1,000명 규모에서 담당자 칸을 열자마자 목록이 통째로 펼쳐졌다.
  // 생성 폼은 FR-UX-09 F2 에서 이미 닫혔고(use-assignee-picker.ts:58-61) 상세만 남아 있었다.
  //
  // ★가드가 공허해질 수 있다 — 후보가 애초에 비면 「검색 전 안 나온다」는 조회 실패로도
  //   통과한다(도달 불가 상태를 지키는 가짜 그린). 반드시 「검색하면 나온다」를 짝으로 둔다.
  // ───────────────────────────────────────────────────────────────────────────
  it('T4-A6: ★검색어가 비면 담당자 후보가 렌더되지 않는다', async () => {
    const fixture = { ...issueAtlas1Fixture, assigneeId: null }
    setupAssigneeStatefulHandlers(fixture)
    const { container } = renderPage('ATLAS-1')
    await waitFor(() => expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument())
    const aside = container.querySelector('aside')
    if (aside === null) throw new Error('aside not found')
    const assigneeSection = within(aside).getByTestId('assignee-section')

    // 검색 입력은 있어야 한다 — 「후보가 없다」가 「셀렉터 자체가 없다」와 구분돼야 한다.
    await waitFor(() =>
      expect(
        within(assigneeSection).getByLabelText(issueDetailStrings.assigneeSearchPlaceholder),
      ).toBeInTheDocument(),
    )
    // 아무것도 입력하지 않았으므로 후보 버튼이 하나도 없어야 한다.
    expect(within(assigneeSection).queryByRole('button', { name: '김앨리스' })).toBeNull()
    expect(within(assigneeSection).queryByRole('button', { name: 'bob' })).toBeNull()
  })

  it('T4-A7: 검색하면 후보가 나온다 (비-공허 짝 — 항상 비는 게 아님을 증명)', async () => {
    const fixture = { ...issueAtlas1Fixture, assigneeId: null }
    setupAssigneeStatefulHandlers(fixture)
    const user = userEvent.setup()
    const { container } = renderPage('ATLAS-1')
    await waitFor(() => expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument())
    const aside = container.querySelector('aside')
    if (aside === null) throw new Error('aside not found')
    const assigneeSection = within(aside).getByTestId('assignee-section')

    const search = within(assigneeSection).getByLabelText(
      issueDetailStrings.assigneeSearchPlaceholder,
    )
    await user.type(search, '앨리스')

    await waitFor(() =>
      expect(within(assigneeSection).getByRole('button', { name: '김앨리스' })).toBeInTheDocument(),
    )
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
    // 후보는 검색해야 나온다 (2026-08-09 봉합). 예전에는 이 입력 없이도 목록이 떠 있었다.
    await user.type(
      within(assigneeSection).getByLabelText(issueDetailStrings.assigneeSearchPlaceholder),
      '앨리스',
    )
    await waitFor(() => expect(within(assigneeSection).queryByRole('button', { name: '김앨리스' })).toBeInTheDocument())
    await user.click(within(assigneeSection).getByRole('button', { name: '김앨리스' }))
    await waitFor(() => expect(vi.mocked(toast.error)).toHaveBeenCalled())
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// PDF 다운로드 버튼 테스트 (FR-IS-08 Task 3)
// ─────────────────────────────────────────────────────────────────────────────

// B1 필수 — @/api/issues 부분 mock: fetchIssue/updateIssue/transitionIssue 등 원본 유지,
// downloadIssuePdf만 교체. 통째 mock 시 기존 30+ 테스트 전멸.
vi.mock('@/api/issues', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@/api/issues')>()),
  downloadIssuePdf: vi.fn(),
}))
vi.mock('@/lib/download', () => ({ triggerBlobDownload: vi.fn() }))

import { downloadIssuePdf } from '@/api/issues'
import { triggerBlobDownload } from '@/lib/download'

describe('IssueDetailPage — PDF 다운로드 버튼 (FR-IS-08)', () => {
  beforeEach(() => {
    vi.mocked(toast.error).mockClear()
    vi.mocked(downloadIssuePdf).mockReset()
    vi.mocked(triggerBlobDownload).mockReset()
    server.use(...issueTypeHandlers)
    setupIssueFoundHandler()
    setupTransitionsHandler()
    setupUsersHandler()
  })

  /**
   * T8-1: breadcrumb 행 우측에 PDF 다운로드 버튼이 렌더된다.
   * aria-label로 특정 — nav 컨테이너 범위 안에서 검색해 strict mode 회귀 방지.
   */
  it('T8-1: PDF 다운로드 버튼이 aria-label로 렌더된다', async () => {
    vi.mocked(downloadIssuePdf).mockResolvedValue(new Blob(['%PDF-'], { type: 'application/pdf' }))

    renderPage('ATLAS-1')

    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument(),
    )

    expect(
      screen.getByRole('button', { name: issueDetailStrings.pdfDownloadAriaLabel }),
    ).toBeInTheDocument()
  })

  /**
   * T8-2: PDF 버튼 클릭 시 downloadIssuePdf(key) 호출 후
   * 반환된 Blob으로 triggerBlobDownload(blob, "{key}.pdf") 가 호출된다.
   */
  it('T8-2: 클릭 시 downloadIssuePdf → triggerBlobDownload가 올바른 인수로 호출된다', async () => {
    const fakeBlob = new Blob(['%PDF-'], { type: 'application/pdf' })
    vi.mocked(downloadIssuePdf).mockResolvedValue(fakeBlob)

    const user = userEvent.setup()
    renderPage('ATLAS-1')

    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument(),
    )

    const btn = screen.getByRole('button', { name: issueDetailStrings.pdfDownloadAriaLabel })
    await user.click(btn)

    await waitFor(() => {
      expect(vi.mocked(downloadIssuePdf)).toHaveBeenCalledWith('ATLAS-1')
      expect(vi.mocked(triggerBlobDownload)).toHaveBeenCalledWith(fakeBlob, 'ATLAS-1.pdf')
    })
  })

  /**
   * T8-3: 다운로드 진행 중 버튼이 disabled 상태가 된다.
   * downloadIssuePdf를 resolve하지 않는 Promise로 로딩 상태를 유지.
   */
  it('T8-3: 다운로드 진행 중 버튼이 disabled 상태이다', async () => {
    // resolve하지 않는 Promise로 로딩 상태 유지
    vi.mocked(downloadIssuePdf).mockReturnValue(new Promise(() => undefined))

    const user = userEvent.setup()
    renderPage('ATLAS-1')

    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument(),
    )

    const btn = screen.getByRole('button', { name: issueDetailStrings.pdfDownloadAriaLabel })
    await user.click(btn)

    await waitFor(() => {
      expect(btn).toBeDisabled()
    })
  })

  /**
   * T8-4: downloadIssuePdf reject 시 toast.error가 호출되고 버튼이 재활성된다.
   */
  it('T8-4: downloadIssuePdf 실패 시 toast.error 호출 + 버튼 재활성', async () => {
    vi.mocked(downloadIssuePdf).mockRejectedValue(new Error('network error'))

    const user = userEvent.setup()
    renderPage('ATLAS-1')

    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument(),
    )

    const btn = screen.getByRole('button', { name: issueDetailStrings.pdfDownloadAriaLabel })
    await user.click(btn)

    await waitFor(() => {
      expect(vi.mocked(toast.error)).toHaveBeenCalledWith(issueDetailStrings.pdfDownloadError)
      expect(btn).not.toBeDisabled()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// FR-PM-05 Task-5 — 권한없는 이슈 404 → not-found 화면 (회귀 가드)
// ─────────────────────────────────────────────────────────────────────────────
import { addPermissionDeniedKey, clearPermissionDeniedKeys } from '@/mocks/issue-handlers'
import { changelogHandlers } from '@/mocks/changelog-handlers'

/**
 * addPermissionDeniedKey 로 issue-handlers.ts 기본 핸들러에 권한없음 시나리오를 세팅한 뒤
 * 해당 이슈 키에 404 응답이 반환되고 notFound 화면이 렌더되는 경로를 검증.
 *
 * 계약: 권한없는 이슈(백엔드 403/404 동일 UX) → issueDetailStrings.notFound 화면 렌더.
 * issues.$key.tsx 라인 301-306 catch-all 분기가 이 계약을 영속적으로 충족함을 고정.
 */
describe('FR-PM-05 Task-5 — 권한없는 이슈 404 → not-found 화면', () => {
  beforeEach(() => {
    clearPermissionDeniedKeys()
  })

  afterEach(() => {
    clearPermissionDeniedKeys()
  })

  /**
   * T-PM05-1: addPermissionDeniedKey('ATLAS-1') 세팅 시
   * GET /api/v1/issues/ATLAS-1 이 404를 반환하고 role="alert" + notFound 화면을 렌더한다.
   *
   * 권한없는 이슈 조회(백엔드가 403을 404와 동일한 UX로 처리)는 미존재 이슈와 같은 화면으로
   * 렌더해야 하는 UX 계약(issues.$key.tsx 라인 301-306 catch-all)을 회귀 가드로 고정.
   *
   * RED 조건: issue-handlers.ts 에 addPermissionDeniedKey / permissionDeniedKeys 가
   * 아직 없어 기본 핸들러가 정상 응답 → waitFor alert 단언 실패.
   */
  it('T-PM05-1: 권한없는 이슈 키 세팅 시 notFound 화면을 렌더한다', async () => {
    addPermissionDeniedKey('ATLAS-1')

    renderPage('ATLAS-1')

    await waitFor(() => {
      const alert = screen.getByRole('alert')
      expect(alert).toBeInTheDocument()
      expect(alert).toHaveTextContent(issueDetailStrings.notFound)
    })
  })

  /**
   * T-PM05-2: permissionDeniedKeys 가 비어있으면 기존 정상 렌더가 유지된다(회귀 없음).
   */
  it('T-PM05-2: 권한없음 시나리오 미세팅 시 이슈 상세 화면을 정상 렌더한다', async () => {
    // permissionDeniedKeys 비어있음 — 기본 핸들러가 ATLAS-1 정상 응답
    setupIssueFoundHandler(issueAtlas1Fixture)

    renderPage('ATLAS-1')

    await waitFor(() => {
      expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// Task F5 — 이슈 상세 페이지 하단 변경 이력 섹션 통합
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueDetailPage — Task F5 (변경 이력 섹션 통합)', () => {
  beforeEach(() => {
    // changelog 핸들러 포함 — ATLAS-1 fixture가 기본 등록됨
    server.use(
      ...changelogHandlers,
      ...issueTypeHandlers,
    )
    setupIssueFoundHandler(issueAtlas1Fixture)
  })

  /**
   * TF5-1: 이슈 상세 페이지 로드 시 하단에 "변경 이력" 섹션이 렌더된다.
   * IssueChangelog 컴포넌트가 aria-label="변경 이력" section으로 마운트되어야 한다.
   */
  it('TF5-1: 이슈 상세 페이지 하단에 변경 이력 섹션이 렌더된다', async () => {
    renderPage('ATLAS-1')

    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument(),
    )

    await waitFor(() => {
      expect(
        screen.getByRole('region', { name: issueDetailStrings.changelogSectionTitle }),
      ).toBeInTheDocument()
    })
  })

  /**
   * TF5-2: 변경 이력 섹션이 2단 grid 레이아웃 바깥(전체폭)에 위치해야 한다.
   * IssueChangelog section이 main·aside와 형제 레벨이 아닌 그 부모 아래에 있어야 한다.
   * "변경 이력" h2 헤딩이 섹션 내에 렌더된다.
   */
  it('TF5-2: 변경 이력 섹션에 h2 헤딩이 렌더된다', async () => {
    renderPage('ATLAS-1')

    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument(),
    )

    await waitFor(() => {
      const changelogSection = screen.getByRole('region', {
        name: issueDetailStrings.changelogSectionTitle,
      })
      const heading = within(changelogSection).getByRole('heading', { level: 2 })
      expect(heading).toHaveTextContent(issueDetailStrings.changelogSectionTitle)
    })
  })

  /**
   * TF5-3: 기존 이슈 상세 렌더(breadcrumb/제목/메타패널)에 회귀가 없어야 한다.
   * changelog 섹션 추가 후에도 기존 요소가 정상 렌더된다.
   */
  it('TF5-3: changelog 섹션 추가 후 기존 breadcrumb·제목·메타패널이 회귀 없이 렌더된다', async () => {
    renderPage('ATLAS-1')

    await waitFor(() => {
      const nav = screen.getByRole('navigation', { name: '이동 경로' })
      expect(within(nav).getByText('ATLAS')).toBeInTheDocument()
      expect(within(nav).getByText('ATLAS-1')).toBeInTheDocument()
      expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// Task 5 (FR-LK-02) — LinkGraph 섹션 라우트 통합
// ─────────────────────────────────────────────────────────────────────────────

import { linkGraphStrings } from '@/i18n/ko'

describe('IssueDetailPage — Task 5 FR-LK-02 (LinkGraph 섹션 통합)', () => {
  beforeEach(() => {
    server.use(...issueTypeHandlers)
    setupIssueFoundHandler(issueAtlas1Fixture)
    server.use(
      http.get('/api/v1/issues/ATLAS-1/transitions', () =>
        HttpResponse.json({ data: { transitions: [] } }),
      ),
    )
    setupUsersHandler()
  })

  /**
   * TLK02-1: LinkGraph 섹션(접힘 상태)의 토글 버튼이 이슈 상세 페이지에 렌더된다.
   * LinkGraph는 기본 접힘 — 펼치기 버튼(expandLabel)이 DOM에 존재해야 한다.
   *
   * RED 조건: LinkGraph가 routes 파일에 배선되지 않아 버튼이 없음.
   */
  it('TLK02-1: LinkGraph 섹션 토글 버튼이 이슈 상세 페이지에 렌더된다', async () => {
    const user = userEvent.setup()
    renderPage('ATLAS-1')

    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument(),
    )

    // 탭화(FR-UX-06 PR19 Task 1) — LinkGraph는 "연결" 탭 안에서 lazy mount되므로 탭 활성화 선행
    await user.click(screen.getByRole('tab', { name: issueDetailStrings.activityLinksTabLabel }))

    await waitFor(() => {
      expect(
        screen.getByRole('button', { name: linkGraphStrings.expandLabel }),
      ).toBeInTheDocument()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// Task 5 — IssueLinksPanel 라우트 통합 (FR-LK-01 D6)
// ─────────────────────────────────────────────────────────────────────────────

import { resetIssueLinkStore } from '@/mocks/issue-link-handlers'

describe('IssueDetailPage — Task 5 (IssueLinksPanel 통합)', () => {
  beforeEach(() => {
    resetIssueLinkStore()
    server.use(...issueTypeHandlers)
    setupIssueFoundHandler(issueAtlas1Fixture)
    server.use(
      // 전환 목록 — 패널과 무관하나 useIssueTransitions가 호출됨
      http.get('/api/v1/issues/ATLAS-1/transitions', () =>
        HttpResponse.json({ data: { transitions: [] } }),
      ),
    )
    setupUsersHandler()
  })

  afterEach(() => {
    resetIssueLinkStore()
  })

  /**
   * TLK5-1: IssueLinksPanel이 이슈 상세 페이지에 렌더된다.
   * `data-testid="links-section"` 과 `data-testid="parent-section"` 두 섹션이 모두
   * 변경이력(IssueChangelog) 상단 또는 본문 하단에 마운트되어야 한다.
   *
   * RED 조건: IssueLinksPanel이 routes 파일에 배치되지 않아 두 섹션이 보이지 않음.
   */
  it('TLK5-1: IssueLinksPanel의 links-section과 parent-section이 렌더된다', async () => {
    const user = userEvent.setup()
    renderPage('ATLAS-1')

    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument(),
    )

    // 탭화(FR-UX-06 PR19 Task 1) — IssueLinksPanel은 "연결" 탭 안에서 lazy mount되므로 탭 활성화 선행
    await user.click(screen.getByRole('tab', { name: issueDetailStrings.activityLinksTabLabel }))

    await waitFor(() => {
      // IssueLinksPanel 내부 두 섹션이 DOM에 존재해야 한다
      expect(screen.getByTestId('links-section')).toBeInTheDocument()
      expect(screen.getByTestId('parent-section')).toBeInTheDocument()
    })
  })

  /**
   * TLK5-2: issueKey가 이슈 쿼리 키와 동일하게 패널에 전달된다.
   * 패널 내부의 AddLinkForm이 사용하는 대상 이슈 키 input의 aria-label이 렌더되어야 한다.
   * (IssueLinksPanel이 issueKey props를 받아 내부에서 AddLinkForm을 마운트하는 증거)
   *
   * RED 조건: 패널 미배치 → aria-label 없음.
   */
  it('TLK5-2: 패널의 대상 이슈 키 input(AddLinkForm)이 렌더된다', async () => {
    const user = userEvent.setup()
    renderPage('ATLAS-1')

    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument(),
    )

    // 탭화(FR-UX-06 PR19 Task 1) — IssueLinksPanel은 "연결" 탭 안에서 lazy mount되므로 탭 활성화 선행
    await user.click(screen.getByRole('tab', { name: issueDetailStrings.activityLinksTabLabel }))

    await waitFor(() => {
      // AddLinkForm 내부 Input의 aria-label — issueLinkStrings.targetKeyLabel
      // IssueLinksPanel이 올바른 issueKey='ATLAS-1'을 받아 AddLinkForm을 마운트하면 나타남
      expect(screen.getByRole('textbox', { name: '대상 이슈 키' })).toBeInTheDocument()
    })
  })

  /**
   * TLK5-3: 이슈 쿼리의 parent가 있으면 패널에 부모 키와 요약이 표시된다.
   * issue fixture에 parent 필드를 포함해 MSW를 override하고,
   * ParentSection이 parent.key와 parent.summary를 화면에 표시하는지 검증.
   *
   * RED 조건: IssueLinksPanel이 배치되지 않아 parent가 표시되지 않음.
   */
  it('TLK5-3: 이슈에 parent가 있으면 parent-section에 부모 키와 요약이 표시된다', async () => {
    const fixtureWithParent = {
      ...issueAtlas1Fixture,
      parent: { key: 'ATLAS-0', summary: '부모 이슈 — 에픽' },
    }
    server.use(
      http.get('/api/v1/issues/:key', ({ params }) => {
        if (params['key'] === 'ATLAS-1') {
          return HttpResponse.json({ data: fixtureWithParent })
        }
        return HttpResponse.json({ message: '이슈를 찾을 수 없습니다' }, { status: 404 })
      }),
    )

    const user = userEvent.setup()
    renderPage('ATLAS-1')

    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument(),
    )

    // 탭화(FR-UX-06 PR19 Task 1) — IssueLinksPanel은 "연결" 탭 안에서 lazy mount되므로 탭 활성화 선행
    await user.click(screen.getByRole('tab', { name: issueDetailStrings.activityLinksTabLabel }))

    await waitFor(() => {
      const parentSection = screen.getByTestId('parent-section')
      // ParentSection — hasParent=true 분기에서 parent.key와 parent.summary가 렌더됨
      expect(within(parentSection).getByText('ATLAS-0')).toBeInTheDocument()
      expect(within(parentSection).getByText('부모 이슈 — 에픽')).toBeInTheDocument()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// Task 5 — AttachmentSection 배선 검증 (FR-AC-01 D6)
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueDetailPage — AttachmentSection 배선', () => {
  /**
   * T-AC-1: 이슈 상세 페이지에 AttachmentSection(첨부 파일 섹션)이 렌더된다.
   * MSW attachment-handlers의 목록 핸들러가 등록되어 있어야 성공한다.
   */
  it('T-AC-1: 이슈 상세 화면에 첨부 파일 섹션이 렌더된다', async () => {
    setupIssueFoundHandler()

    renderPage('ATLAS-1')

    // 제목 렌더 대기 (이슈 로드 완료 신호)
    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument(),
    )

    // 첨부 파일 섹션이 존재한다 (aria-label로 식별)
    expect(screen.getByRole('region', { name: '첨부 파일' })).toBeInTheDocument()
  })

  /**
   * T-AC-2: canUpdate 플래그(permissions.UPDATE) 전달 검증.
   * UPDATE=true → 드롭존(파일 업로드 안내) 표시.
   */
  it('T-AC-2: UPDATE 권한이 있으면 드롭존이 표시된다', async () => {
    setupIssueFoundHandler()

    renderPage('ATLAS-1')

    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument(),
    )

    // 드롭존 — 파일 끌어다 놓거나 클릭 안내 텍스트
    await waitFor(() => {
      expect(
        screen.getByText(/파일을 여기에 끌어다 놓거나 클릭해서 선택하세요/),
      ).toBeInTheDocument()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-MV-PAGE: 이슈 이동 진입점 + 308 redirect 처리 (FR-MV-01 Task 5)
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueDetailPage — 이슈 이동 진입점 + 308 redirect', () => {
  /**
   * T-MV-PAGE-1: UPDATE 권한이 있으면 "이동" 버튼이 표시된다.
   */
  it('T-MV-PAGE-1: UPDATE 권한이 있으면 이동 버튼이 표시된다', async () => {
    setupIssueFoundHandler()
    renderPage('ATLAS-1')

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /이슈 이동/i })).toBeInTheDocument()
    })
  })

  /**
   * T-MV-PAGE-2: 이동 버튼 클릭 시 MoveIssueDialog가 열린다.
   */
  it('T-MV-PAGE-2: 이동 버튼 클릭 시 MoveIssueDialog가 열린다', async () => {
    setupIssueFoundHandler()
    renderPage('ATLAS-1')
    const user = userEvent.setup()

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /이슈 이동/i })).toBeInTheDocument()
    })

    await user.click(screen.getByRole('button', { name: /이슈 이동/i }))

    await waitFor(() => {
      expect(screen.getByRole('dialog')).toBeInTheDocument()
    })
  })

})

// ─────────────────────────────────────────────────────────────────────────────
// Task 7 — 추정 카드 + WorklogSection 배선 (FR-TT-01 D6)
// ─────────────────────────────────────────────────────────────────────────────

import { worklogStrings } from '@/i18n/ko'

describe('IssueDetailPage — Task 7 (추정 카드 + WorklogSection 배선)', () => {
  beforeEach(() => {
    setupIssueFoundHandler(issueAtlas1Fixture)
    server.use(
      ...issueTypeHandlers,
      // 전환 목록 — WorklogSection 마운트와 무관하나 useIssueTransitions 호출됨
      http.get('/api/v1/issues/ATLAS-1/transitions', () =>
        HttpResponse.json({ data: { transitions: [] } }),
      ),
      // 워크로그 목록 핸들러 — WorklogSection이 fetchWorklogs 호출
      http.get('/api/v1/issues/:key/worklogs', () =>
        HttpResponse.json({
          data: {
            worklogs: [],
            summary: {
              totalTimeSpentSeconds: 0,
              remainingEstimateSeconds: null,
            },
          },
        }),
      ),
    )
    setupUsersHandler()
  })

  /**
   * TTT7-1: 추정 카드 wrapper가 렌더된다.
   * IssueEstimatePanel을 감싸는 카드 안에 worklogStrings.estimateSectionTitle 라벨이 있어야 한다.
   */
  it('TTT7-1: 추정 카드 wrapper가 estimateSectionTitle 라벨과 함께 렌더된다', async () => {
    renderPage('ATLAS-1')

    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument(),
    )

    await waitFor(() => {
      expect(screen.getByText(worklogStrings.estimateSectionTitle)).toBeInTheDocument()
    })
  })

  /**
   * TTT7-2: IssueEstimatePanel의 입력 필드(data-testid="estimate-fields")가 렌더된다.
   * 추정 카드 안에 IssueEstimatePanel이 마운트됐음을 data-testid로 검증.
   */
  it('TTT7-2: IssueEstimatePanel(estimate-fields)이 추정 카드 안에 렌더된다', async () => {
    renderPage('ATLAS-1')

    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument(),
    )

    await waitFor(() => {
      expect(screen.getByTestId('estimate-fields')).toBeInTheDocument()
    })
  })

  /**
   * TTT7-3: WorklogSection이 이슈 상세 페이지 하단에 렌더된다.
   * section[aria-label="작업 기록"]이 DOM에 존재해야 한다.
   */
  it('TTT7-3: WorklogSection(aria-label="작업 기록")이 페이지 하단에 렌더된다', async () => {
    const user = userEvent.setup()
    renderPage('ATLAS-1')

    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument(),
    )

    // 탭화(FR-UX-06 PR19 Task 1) — WorklogSection은 "작업로그" 탭 안에서 lazy mount되므로 탭 활성화 선행
    await user.click(screen.getByRole('tab', { name: issueDetailStrings.activityWorklogTabLabel }))

    await waitFor(() => {
      expect(
        screen.getByRole('region', { name: worklogStrings.worklogSectionTitle }),
      ).toBeInTheDocument()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// Task 1 — variant='page'|'pane' 분기 (FR-UX-06 PR20 split view)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * variant='pane' 렌더 헬퍼 — pane 전용 prop(onClose/onIssueRedirect/onIssueClosed)을 함께 주입한다.
 * 기존 renderPage 시그니처는 그대로 유지 — variant 기본값('page') 무회귀 증거.
 */
function renderPanePage(
  issueKey: string,
  extraProps: Partial<Omit<Parameters<typeof IssueDetailPage>[0], 'issueKey'>> = {},
) {
  const client = makeClient()
  return {
    client,
    ...render(
      <QueryClientProvider client={client}>
        <IssueDetailPage issueKey={issueKey} variant="pane" {...extraProps} />
      </QueryClientProvider>,
    ),
  }
}

describe('IssueDetailPage — Task 1 (variant page/pane, FR-UX-06 PR20)', () => {
  beforeEach(() => {
    vi.mocked(toast.error).mockClear()
    server.use(...issueTypeHandlers)
    setupIssueFoundHandler()
    setupTransitionsHandler()
    setupUsersHandler()
  })

  /**
   * T1-1: variant 미지정(기본값 'page') — 제목이 h1로 렌더되고 닫기 버튼이 없다.
   * 기존 전체화면 렌더 무회귀 증거.
   */
  it('T1-1: variant 미지정 시 제목이 h1로 렌더되고 닫기 버튼이 없다', async () => {
    renderPage('ATLAS-1')

    await waitFor(() =>
      expect(screen.getByRole('heading', { level: 1 })).toHaveTextContent(issueAtlas1Fixture.summary),
    )
    expect(screen.queryByRole('button', { name: '닫기' })).not.toBeInTheDocument()
  })

  /**
   * T1-2: variant='pane' — 제목이 h2로 렌더되고(문서 h1 단일 계약) h1은 없다.
   */
  it('T1-2: variant="pane" 시 제목이 h2로 렌더되고 h1은 없다', async () => {
    renderPanePage('ATLAS-1')

    await waitFor(() =>
      expect(
        screen.getByRole('heading', { level: 2, name: issueAtlas1Fixture.summary }),
      ).toBeInTheDocument(),
    )
    expect(screen.queryByRole('heading', { level: 1 })).not.toBeInTheDocument()
  })

  /**
   * T1-3: variant='pane' — 닫기 버튼이 렌더되고 클릭 시 onClose가 호출된다.
   */
  it('T1-3: variant="pane" 시 닫기 버튼 클릭으로 onClose가 호출된다', async () => {
    const onClose = vi.fn()
    const user = userEvent.setup()
    renderPanePage('ATLAS-1', { onClose })

    await waitFor(() =>
      expect(
        screen.getByRole('heading', { level: 2, name: issueAtlas1Fixture.summary }),
      ).toBeInTheDocument(),
    )

    await user.click(screen.getByRole('button', { name: '닫기' }))

    expect(onClose).toHaveBeenCalledTimes(1)
  })

  /**
   * T1-4: variant='pane' — Escape 키 입력 시 onClose가 호출된다.
   */
  it('T1-4: variant="pane" 시 Escape 키 입력으로 onClose가 호출된다', async () => {
    const onClose = vi.fn()
    const user = userEvent.setup()
    renderPanePage('ATLAS-1', { onClose })

    await waitFor(() =>
      expect(
        screen.getByRole('heading', { level: 2, name: issueAtlas1Fixture.summary }),
      ).toBeInTheDocument(),
    )

    await user.keyboard('{Escape}')

    expect(onClose).toHaveBeenCalledTimes(1)
  })

  /**
   * T1-8: variant='pane' — 이미 다른 capture 리스너(Radix DismissableLayer 등)가
   * preventDefault()한 Escape는 onClose를 호출하지 않는다 (CONCERNS-2).
   * Radix 다이얼로그/드롭다운이 capture 단계에서 Escape를 dismiss 처리하며
   * preventDefault()만 하고(stopPropagation은 안 함) 하므로, bubble 단계인 페인
   * 리스너가 뒤늦게 도달해도 같이 닫히지 않아야 다이얼로그·페인 이중 발화가 방지된다.
   */
  it('T1-8: variant="pane" 시 defaultPrevented된 Escape는 onClose를 호출하지 않는다', async () => {
    const onClose = vi.fn()
    renderPanePage('ATLAS-1', { onClose })

    await waitFor(() =>
      expect(
        screen.getByRole('heading', { level: 2, name: issueAtlas1Fixture.summary }),
      ).toBeInTheDocument(),
    )

    const event = new KeyboardEvent('keydown', { key: 'Escape', bubbles: true, cancelable: true })
    event.preventDefault()
    act(() => {
      document.dispatchEvent(event)
    })

    expect(onClose).not.toHaveBeenCalled()
  })

  /**
   * T1-5: variant='pane' — 마운트 시 포커스가 상세 영역(제목 또는 닫기 버튼)으로 이동한다.
   */
  it('T1-5: variant="pane" 마운트 시 포커스가 상세 영역으로 이동한다', async () => {
    renderPanePage('ATLAS-1')

    await waitFor(() => {
      const heading = screen.getByRole('heading', { level: 2, name: issueAtlas1Fixture.summary })
      const closeButton = screen.getByRole('button', { name: '닫기' })
      expect([heading, closeButton]).toContain(document.activeElement)
    })
  })

  /**
   * T1-6: variant='pane' + IssueRedirectError(308 옛키→새키) — fullscreen navigate 대신
   * onIssueRedirect(newKey)가 호출된다.
   * MSW ServiceWorker는 opaque 308을 만들 수 없어(api/issues.test.ts T1-2c와 동일 사유) globalThis.fetch를
   * 부분 override — 대상 GET만 redirected:true 응답으로 가로채고 나머지는 원본(MSW 경유)으로 통과시킨다.
   */
  it('T1-6: variant="pane"에서 IssueRedirectError 발생 시 onIssueRedirect가 호출되고 navigate는 호출되지 않는다', async () => {
    const originalFetch = globalThis.fetch
    globalThis.fetch = vi.fn().mockImplementation(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = typeof input === 'string' ? input : input instanceof URL ? input.toString() : input.url
      const method = init?.method ?? 'GET'
      if (url === '/api/v1/issues/ATLAS-1' && method === 'GET') {
        const redirected = new Response(null, { status: 200 })
        Object.defineProperty(redirected, 'redirected', { value: true })
        Object.defineProperty(redirected, 'url', { value: 'http://localhost/api/v1/issues/ATLAS-2' })
        return redirected
      }
      return originalFetch(input, init)
    })

    const onIssueRedirect = vi.fn()
    mockNavigate.mockClear()

    try {
      renderPanePage('ATLAS-1', { onIssueRedirect })

      await waitFor(() => {
        expect(onIssueRedirect).toHaveBeenCalledWith('ATLAS-2')
      })
      expect(mockNavigate).not.toHaveBeenCalled()
    } finally {
      globalThis.fetch = originalFetch
    }
  })

  /**
   * T1-7: variant='pane' — 삭제 성공 시 fullscreen navigate('/issues') 대신 onIssueClosed()가 호출된다.
   */
  it('T1-7: variant="pane"에서 삭제 성공 시 onIssueClosed가 호출되고 navigate는 호출되지 않는다', async () => {
    let deleteCalled = false
    server.use(
      http.delete('/api/v1/issues/:key', () => {
        deleteCalled = true
        return new HttpResponse(null, { status: 204 })
      }),
    )

    mockNavigate.mockClear()
    const onIssueClosed = vi.fn()
    const user = userEvent.setup()
    renderPanePage('ATLAS-1', { onIssueClosed })

    await waitFor(() =>
      expect(
        screen.getByRole('heading', { level: 2, name: issueAtlas1Fixture.summary }),
      ).toBeInTheDocument(),
    )

    await user.click(screen.getByRole('button', { name: /이슈 삭제/ }))
    await user.click(screen.getByRole('button', { name: /확인/ }))

    await waitFor(() => {
      expect(deleteCalled).toBe(true)
      expect(onIssueClosed).toHaveBeenCalledTimes(1)
    })
    expect(mockNavigate).not.toHaveBeenCalled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// FR-UX-11 F8 Task 2 — 제목 편집 Enter 저장 / Esc 취소
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 제목 편집 모드로 들어가 입력창을 돌려준다.
 * 진입은 기존 `✎ 제목 수정` 버튼을 쓴다 — Task 1 의 클릭 진입면과 무관하게
 * 키 처리 계약만 검증하기 위해서다.
 */
async function enterTitleEdit(user: ReturnType<typeof userEvent.setup>): Promise<HTMLElement> {
  await user.click(await screen.findByRole('button', { name: /제목 수정/ }))
  return screen.getByRole('textbox', { name: issueDetailStrings.titleEditLabel })
}

describe('IssueDetailPage — 제목 편집 Enter/Esc (FR-UX-11 F8 Task 2)', () => {
  beforeEach(() => {
    server.use(...issueTypeHandlers)
    setupIssueFoundHandler()
    setupTransitionsHandler()
    setupUsersHandler()
  })

  /**
   * F8-T2-1. Enter 로 저장한다 (FR2). 버튼과 같은 handleEditSave 를 타므로
   * PATCH 바디에 새 summary 와 OCC expectedVersion 이 함께 실려야 한다.
   */
  it('F8-T2-1: 제목 편집 중 Enter 를 누르면 새 summary 로 저장한다', async () => {
    let patchBody: unknown = null
    server.use(
      http.patch('/api/v1/issues/:key', async ({ request }) => {
        patchBody = await request.json()
        return HttpResponse.json({ data: { ...issueAtlas1Fixture, summary: 'Enter 로 저장', version: 1 } })
      }),
    )

    const user = userEvent.setup()
    renderPage('ATLAS-1')

    const input = await enterTitleEdit(user)
    await user.clear(input)
    await user.type(input, 'Enter 로 저장')
    fireEvent.keyDown(input, { key: 'Enter' })

    await waitFor(() => {
      expect(patchBody).toMatchObject({
        summary: 'Enter 로 저장',
        expectedVersion: issueAtlas1Fixture.version,
      })
    })
  })

  /**
   * F8-T2-2. E4 — 한글 IME 조합을 확정하는 Enter 는 저장이 아니다.
   * 조합 Enter 로는 호출되지 않고, 이어진 **진짜 Enter** 로는 호출되는 것까지 확인해
   * "아무 일도 안 일어나서 통과"하는 공허한 가드가 되지 않게 한다.
   */
  it('F8-T2-2: IME 조합 확정 Enter 는 저장하지 않는다 (진짜 Enter 는 저장한다)', async () => {
    let patchCount = 0
    server.use(
      http.patch('/api/v1/issues/:key', () => {
        patchCount += 1
        return HttpResponse.json({ data: { ...issueAtlas1Fixture, summary: '한글 제목', version: 1 } })
      }),
    )

    const user = userEvent.setup()
    renderPage('ATLAS-1')

    const input = await enterTitleEdit(user)
    await user.clear(input)
    await user.type(input, '한글 제목')

    // 조합 확정 Enter — 저장되면 안 된다
    fireEvent.keyDown(input, { key: 'Enter', isComposing: true })
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 50))
    })
    expect(patchCount).toBe(0)

    // 같은 입력창에서 진짜 Enter 는 저장된다 — 위 단언이 공허하지 않다는 증인
    fireEvent.keyDown(input, { key: 'Enter' })
    await waitFor(() => {
      expect(patchCount).toBe(1)
    })
  })

  /**
   * F8-T2-3. Esc 로 취소하고 원본 제목을 복원한다 (FR3).
   */
  it('F8-T2-3: 제목 편집 중 Esc 를 누르면 취소하고 원본을 복원한다', async () => {
    const user = userEvent.setup()
    renderPage('ATLAS-1')

    const input = await enterTitleEdit(user)
    await user.clear(input)
    await user.type(input, '버려질 제목')

    fireEvent.keyDown(input, { key: 'Escape' })

    expect(
      screen.queryByRole('textbox', { name: issueDetailStrings.titleEditLabel }),
    ).not.toBeInTheDocument()
    expect(
      screen.getByRole('heading', { level: 1, name: issueAtlas1Fixture.summary }),
    ).toBeInTheDocument()
  })

  /**
   * F8-T2-4. ★ E1 이중 발화 방지 — pane 에서 편집 중 Esc 는 **편집만** 취소하고
   * 패널을 닫지 않는다. handleTitleKeyDown 의 `e.preventDefault()` 가
   * usePaneEscapeClose 의 `if (e.defaultPrevented) return` 가드를 세우는 것이 근거다.
   */
  it('F8-T2-4: pane 에서 편집 중 Esc 는 편집만 취소하고 패널을 닫지 않는다', async () => {
    const onClose = vi.fn()
    const user = userEvent.setup()
    renderPanePage('ATLAS-1', { onClose })

    await waitFor(() =>
      expect(
        screen.getByRole('heading', { level: 2, name: issueAtlas1Fixture.summary }),
      ).toBeInTheDocument(),
    )

    const input = await enterTitleEdit(user)
    fireEvent.keyDown(input, { key: 'Escape' })

    expect(
      screen.queryByRole('textbox', { name: issueDetailStrings.titleEditLabel }),
    ).not.toBeInTheDocument()
    expect(onClose).not.toHaveBeenCalled()
  })

  /**
   * F8-T2-5. E5 — 저장이 진행 중이면 Enter 가 중복 제출하지 않는다.
   * 저장 버튼의 `disabled={updateMutation.isPending || !canEdit}` 와 같은 조건이다.
   */
  it('F8-T2-5: 저장 진행 중에는 Enter 가 중복 제출하지 않는다', async () => {
    let patchCount = 0
    // 고정 지연(10초)으로 붙잡으면 정착을 기다릴 때 그 시간을 그대로 물어야 한다.
    // 수동 게이트로 붙잡고 검증이 끝나면 즉시 푼다.
    let releasePatch: () => void = () => {}
    const patchGate = new Promise<void>((resolve) => {
      releasePatch = resolve
    })
    server.use(
      http.patch('/api/v1/issues/:key', async () => {
        patchCount += 1
        await patchGate
        return HttpResponse.json({ data: issueAtlas1Fixture })
      }),
    )

    const user = userEvent.setup()
    renderPage('ATLAS-1')

    const input = await enterTitleEdit(user)
    await user.clear(input)
    await user.type(input, '느린 저장')

    fireEvent.keyDown(input, { key: 'Enter' })
    await waitFor(() => {
      expect(patchCount).toBe(1)
    })

    // 저장이 끝나기 전 두 번째 Enter — 중복 제출되면 안 된다
    fireEvent.keyDown(input, { key: 'Enter' })
    await act(async () => {
      await new Promise((resolve) => setTimeout(resolve, 50))
    })
    expect(patchCount).toBe(1)

    releasePatch()
    await settlePendingMutations()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// FR-UX-11 F8 Task 5 pane guard — 본문 Esc 의 이중 발화 방지 증인
//
// IssueDescription 의 `Escape` 분기에도 `e.preventDefault()`(E1)가 있지만, 그 가드의
// 상대인 usePaneEscapeClose 는 **이 라우트**가 소유한다(document 전역 리스너, :91).
// 컴포넌트 단독 테스트에는 pane 컨텍스트가 없어 구조적으로 못 잡으므로 증인을 여기 둔다.
// 이 라우트 테스트에서 IssueDescription 은 목이 아니라 실제로 렌더된다(vi.mock 없음 — T6-1).
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueDetailPage — pane 에서 본문 Esc 이중 발화 방지 (FR-UX-11 F8 Task 5 guard)', () => {
  beforeEach(() => {
    server.use(...issueTypeHandlers)
    setupIssueFoundHandler()
    setupTransitionsHandler()
    setupUsersHandler()
  })

  /**
   * F8-T5G-1. pane 에서 본문 편집 중 Esc 는 편집만 취소하고 패널을 닫지 않는다.
   *
   * **변경분을 만들지 않고** Esc 를 누른다 — requestCancel 이 초안≠원본이면 확인 패널을
   * 띄워 편집이 닫히지 않으므로(편차 D-1), 확인 패널을 타지 않는 경로로 고립시켜야
   * "편집 종료 + onClose 미호출" 두 단언을 같이 세울 수 있다.
   */
  it('F8-T5G-1: pane 에서 본문 편집 중 Esc 는 편집만 취소하고 패널을 닫지 않는다', async () => {
    const onClose = vi.fn()
    const user = userEvent.setup()
    const { container } = renderPanePage('ATLAS-1', { onClose })

    await waitFor(() =>
      expect(
        screen.getByRole('heading', { level: 2, name: issueAtlas1Fixture.summary }),
      ).toBeInTheDocument(),
    )

    // 본문 편집 버튼/편집기는 <section aria-label="이슈 상세"> 안에 있다 (T6-2 와 동일 범위 좁힘)
    const section = container.querySelector<HTMLElement>('section[aria-label="이슈 상세"]')
    expect(section).not.toBeNull()
    if (section === null) return

    await user.click(
      within(section).getByRole('button', { name: issueDetailStrings.descriptionEditButton }),
    )
    const textarea = within(section).getByRole('textbox', {
      name: issueDetailStrings.descriptionEditButton,
    })

    // 변경분 없이 Esc — 확인 패널을 거치지 않고 곧장 편집이 닫히는 경로
    fireEvent.keyDown(textarea, { key: 'Escape' })

    // ① 본문이 읽기 모드로 돌아간다
    expect(
      within(section).queryByRole('textbox', { name: issueDetailStrings.descriptionEditButton }),
    ).not.toBeInTheDocument()
    // ② 패널은 열린 채 — IssueDescription 의 preventDefault 가 usePaneEscapeClose 를 막는다
    expect(onClose).not.toHaveBeenCalled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// FR-UX-11 F8 FR1 — 제목 편집 진입 시 포커스 + 커서 텍스트 끝
//
// E2E 로는 잡히지 않는 공백이다 — Playwright `locator.press()` 가 대상에 **자동으로
// 포커스를 주기 때문에** 앱이 포커스를 안 줘도 초록이 된다(가짜 그린). 유닛에서 잰다.
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueDetailPage — 제목 편집 진입 포커스/커서 (FR-UX-11 F8 FR1)', () => {
  beforeEach(() => {
    server.use(...issueTypeHandlers)
    setupIssueFoundHandler()
    setupTransitionsHandler()
    setupUsersHandler()
  })

  /**
   * F8-FR1-1. 제목 텍스트를 클릭해 진입하면 입력창이 포커스를 갖는다.
   * 포커스가 없으면 사용자가 마우스로 한 번 더 클릭해야 타이핑이 시작된다.
   */
  it('F8-FR1-1: 제목 텍스트 클릭 진입 시 입력창이 포커스를 갖는다', async () => {
    const user = userEvent.setup()
    renderPage('ATLAS-1')

    await user.click(await screen.findByRole('button', { name: issueAtlas1Fixture.summary }))

    expect(screen.getByRole('textbox', { name: issueDetailStrings.titleEditLabel })).toHaveFocus()
  })

  /**
   * F8-FR1-2. 진입 시 커서가 접혀 있고(전체 선택 아님) 맨 앞이 아니다.
   *
   * **이 테스트가 못 잡는 것 — 뮤테이션 실측 결과.** 프로덕션의
   * `input.setSelectionRange(end, end)` 를 통째로 지워도 이 단언은 **초록으로 남는다**.
   * jsdom 이 `value` 를 설정할 때 커서를 자동으로 끝에 두기 때문에 기본값이 기대값과
   * 우연히 일치한다. 즉 이 단언은 "select-all 이나 맨 앞 커서로 **바뀌는**" 회귀는 잡지만,
   * `setSelectionRange` 라인의 **실재 증인은 아니다**.
   * 그 라인의 진짜 증인은 브라우저 눈확인(실제 Chromium 에서 `selectionStart` 실측)이다.
   */
  it('F8-FR1-2: 제목 텍스트 클릭 진입 시 커서가 전체 선택도 맨 앞도 아니다', async () => {
    const user = userEvent.setup()
    renderPage('ATLAS-1')

    await user.click(await screen.findByRole('button', { name: issueAtlas1Fixture.summary }))

    const input = screen.getByRole<HTMLInputElement>('textbox', {
      name: issueDetailStrings.titleEditLabel,
    })
    const end = issueAtlas1Fixture.summary.length
    expect(input.value).toBe(issueAtlas1Fixture.summary)
    // 선택 구간이 접혀 있어야 한다 — 전체 선택(0..end)이면 첫 타건에 원문이 통째로 지워진다
    expect(input.selectionStart).toBe(input.selectionEnd)
    expect(input.selectionStart).toBe(end)
  })

  /**
   * F8-FR1-3. 기존 `✎ 제목 수정` 버튼 경로도 **같은 포커스 동작**이다
   * (두 진입로가 갈라지면 안 된다). 커서 단언은 F8-FR1-2 와 같은 jsdom 한계를 가지므로
   * 여기서는 **포커스만** 잰다 — 같은 한계를 두 곳에 복제하지 않는다.
   */
  it('F8-FR1-3: ✎ 제목 수정 버튼 경로도 입력창이 포커스를 갖는다', async () => {
    const user = userEvent.setup()
    renderPage('ATLAS-1')

    await user.click(await screen.findByRole('button', { name: /제목 수정/ }))

    expect(screen.getByRole('textbox', { name: issueDetailStrings.titleEditLabel })).toHaveFocus()
  })

  /**
   * F8-FR1-4. 편집 중 타이핑으로 커서가 끝으로 되돌아가지 않는다.
   * useTitleEditFocus 의 의존성이 `isEditing` 뿐이라는 계약의 증인 —
   * `editSummary` 를 의존성에 넣으면 매 타건마다 커서가 끝으로 튄다.
   */
  it('F8-FR1-4: 편집 중 커서를 앞으로 옮겨 타이핑해도 끝으로 튀지 않는다', async () => {
    const user = userEvent.setup()
    renderPage('ATLAS-1')

    await user.click(await screen.findByRole('button', { name: issueAtlas1Fixture.summary }))
    const input = screen.getByRole<HTMLInputElement>('textbox', {
      name: issueDetailStrings.titleEditLabel,
    })

    // 커서를 맨 앞에 두고 한 글자 입력.
    // `initialSelectionStart/End` 를 반드시 넘긴다 — userEvent.type 은 기본적으로 커서를
    // **끝으로 옮긴 뒤** 타이핑하므로, 안 넘기면 라이브러리 동작을 재게 되어 가짜 신호가 된다.
    await user.type(input, 'X', { initialSelectionStart: 0, initialSelectionEnd: 0 })

    expect(input.value).toBe(`X${issueAtlas1Fixture.summary}`)
    // 판별 지점 — 효과가 매 타건마다 재실행되면 커서가 끝(value.length)으로 튄다.
    expect(input.selectionStart).toBe(1)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// FR-UX-11 F8 코드리뷰 봉합 — R-1 텍스트 선택 가드 · R-2 편집 종료 포커스 복귀
// ─────────────────────────────────────────────────────────────────────────────

describe('IssueDetailPage — 제목 진입 가드/포커스 복귀 (FR-UX-11 F8 R-1·R-2)', () => {
  beforeEach(() => {
    server.use(...issueTypeHandlers)
    setupIssueFoundHandler()
    setupTransitionsHandler()
    setupUsersHandler()
  })

  /**
   * F8-R1-1. 텍스트를 선택 중이면 제목 클릭이 편집을 열지 않는다 (E2 / 편차 D-2).
   *
   * 본문(`IssueDescription`)이 같은 판정식으로 막는 동작이라 제목만 열리면 비대칭이다.
   * 드래그로 제목을 복사하려는 참을 방해하지 않는다 — Jira 미해결 결함 JRA-64389 미복제.
   *
   * spy 를 `finally` 에서 직접 원복한다 — 이 프로젝트는 `restoreMocks` 를 켜지 않아
   * 전역 spy 가 다음 테스트로 샌다(본문 테스트가 같은 이유로 같은 처리를 한다).
   */
  it('F8-R1-1: 제목 텍스트를 선택 중이면 클릭이 편집을 열지 않는다', async () => {
    const user = userEvent.setup()
    renderPage('ATLAS-1')
    const titleButton = await screen.findByRole('button', { name: issueAtlas1Fixture.summary })

    const selectionSpy = vi
      .spyOn(window, 'getSelection')
      .mockReturnValue({ isCollapsed: false } as Selection)
    try {
      await user.click(titleButton)
      expect(
        screen.queryByRole('textbox', { name: issueDetailStrings.titleEditLabel }),
      ).not.toBeInTheDocument()
    } finally {
      selectionSpy.mockRestore()
    }

    // 비-공허 짝 — 선택이 풀리면 같은 클릭이 편집을 연다.
    // 이게 없으면 "클릭 자체가 원래 안 되는 상태" 여도 위 단언이 통과한다.
    await user.click(titleButton)
    expect(
      screen.getByRole('textbox', { name: issueDetailStrings.titleEditLabel }),
    ).toBeInTheDocument()
  })

  /**
   * F8-R2-1. Esc 취소 후 포커스가 제목 진입면으로 돌아온다 (WCAG 2.4.3).
   * 돌려주지 않으면 포커스가 `<body>` 로 떨어져 다음 Tab 이 문서 맨 앞부터 시작한다.
   *
   * 이름이 아니라 **h1 안의 button** 으로 집는다 — 저장 후 제목 문자열이 바뀌어도
   * 같은 단언이 서게 하려는 것이고, F8-R2-2 와 판정 방식을 통일한다.
   */
  it('F8-R2-1: Esc 취소 후 포커스가 제목 진입면으로 돌아온다', async () => {
    const user = userEvent.setup()
    renderPage('ATLAS-1')

    await user.click(await screen.findByRole('button', { name: issueAtlas1Fixture.summary }))
    const input = screen.getByRole('textbox', { name: issueDetailStrings.titleEditLabel })
    fireEvent.keyDown(input, { key: 'Escape' })

    await waitFor(() => {
      const heading = screen.getByRole('heading', { level: 1 })
      expect(within(heading).getByRole('button')).toHaveFocus()
    })
  })

  /**
   * F8-R2-2. Enter 저장 후에도 포커스가 제목 진입면으로 돌아온다.
   * 저장은 `onSuccess` 에서 편집 모드를 닫으므로 취소와 종료 경로가 다르다 — 따로 잰다.
   */
  it('F8-R2-2: Enter 저장 후 포커스가 제목 진입면으로 돌아온다', async () => {
    server.use(
      http.patch('/api/v1/issues/:key', () =>
        HttpResponse.json({ data: { ...issueAtlas1Fixture, summary: '저장된 제목', version: 1 } }),
      ),
    )

    const user = userEvent.setup()
    renderPage('ATLAS-1')

    await user.click(await screen.findByRole('button', { name: issueAtlas1Fixture.summary }))
    const input = screen.getByRole('textbox', { name: issueDetailStrings.titleEditLabel })
    await user.clear(input)
    await user.type(input, '저장된 제목')
    fireEvent.keyDown(input, { key: 'Enter' })

    await waitFor(() => {
      expect(
        screen.queryByRole('textbox', { name: issueDetailStrings.titleEditLabel }),
      ).not.toBeInTheDocument()
    })
    await waitFor(() => {
      const heading = screen.getByRole('heading', { level: 1 })
      expect(within(heading).getByRole('button')).toHaveFocus()
    })
  })

  /**
   * F8-R2-3. 마운트 직후에는 진입면으로 포커스를 훔치지 않는다.
   * `wasEditingRef` 가드의 증인 — 없으면 페이지를 열자마자 제목 버튼이 포커스를 가져가
   * pane 마운트 포커스(usePaneFocusOnLoad)와 싸운다.
   */
  it('F8-R2-3: 마운트 직후에는 제목 진입면이 포커스를 훔치지 않는다', async () => {
    renderPage('ATLAS-1')

    const titleButton = await screen.findByRole('button', { name: issueAtlas1Fixture.summary })
    expect(titleButton).not.toHaveFocus()
    expect(document.body).toHaveFocus()
  })

  /**
   * F8-R1-2. 제목 진입면이 `select-text` 를 유지한다 — **제목 복사 가능성의 대리 지표**.
   *
   * 근거(Chromium 실측). `<button>` 에서 `user-select: auto` 는 CSS UI 규격상 `none` 으로
   * 해석돼, 이 클래스가 없으면 제목을 드래그해도 선택 길이가 **0** 이다. 즉 제목을 버튼으로
   * 감싼 순간 사용자가 제목을 복사할 수 없게 되고, F8-R1-1 의 `isCollapsed` 가드도
   * 선택이 생기지 않아 영원히 발동하지 못한다.
   *
   * **이 단언의 한계.** jsdom 은 Tailwind CSS 를 적용하지 않아 `user-select` **계산값을
   * 검증하지 못한다**. 여기서 재는 것은 클래스 문자열의 잔존뿐이고, 실제 선택 동작의 증인은
   * 브라우저 눈확인(드래그 후 `getSelection().toString()` 길이 11 실측)이다.
   */
  it('F8-R1-2: 제목 진입면이 select-text 클래스를 유지한다 (복사 가능성 대리 지표)', async () => {
    renderPage('ATLAS-1')

    const titleButton = await screen.findByRole('button', { name: issueAtlas1Fixture.summary })
    expect(titleButton.className).toContain('select-text')
  })
})
