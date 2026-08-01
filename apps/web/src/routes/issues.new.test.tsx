// 이슈 생성 폼 단위 테스트 — T6-1(클라이언트 검증) T6-2(제출 성공+navigate) T6-3(PROJECT_NOT_FOUND 에러) T6-4(빈 summary 제출 차단) T7-1~T7-3(컴포넌트 선택) T9-1~T9-3(커스텀필드)
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { issueHandlers, createdIssueFixture } from '@/mocks/issue-handlers'
import { componentHandlers, resetComponentStore } from '@/mocks/component-handlers'
// FR-UX-09 F2 — 프로젝트가 셀렉터가 되고 유형 셀렉터가 생겨 두 목록 조회가 필요해졌다
import { projectHandlers } from '@/mocks/project-handlers'
import { issueTypeHandlers } from '@/mocks/issue-type-handlers'
import { server } from '@/test/server'
import { http, HttpResponse } from 'msw'
// IssueCreateForm 은 components/issue/ 로 이동했다 (FR-UX-09 F2 T4).
// CreateIssueDialog 가 폼을 감싸고 이 라우트가 모달을 감싸므로, 폼이 라우트 파일에 남아 있으면
// routes/issues.new → CreateIssueDialog → routes/issues.new 순환 import 가 된다.
import { IssueCreateForm } from '@/components/issue/IssueCreateForm'
import { IssueCreateRouteAdapter } from './issues.new'
import { issueCreateStrings } from '@/i18n/ko'
import type { CustomField } from '@/api/custom-fields.types'

// useNavigate/useSearch mock — TanStack Router 의존 없이 폼/어댑터 테스트
// mockUseSearch: FR-UX-04 FR7 — issues.new의 summary URL 프리필(useSearch) 테스트용
const mockNavigate = vi.fn()
const mockUseSearch = vi.fn((): { summary?: string } => ({}))
vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => mockNavigate,
  useParams: () => ({}),
  useSearch: () => mockUseSearch(),
}))

// useCustomFields mock — 네트워크 없이 커스텀필드 정의 제어 (타이핑 중간 경로 요청 제거)
vi.mock('@/hooks/use-custom-fields', () => ({
  useCustomFields: vi.fn(),
  CUSTOM_FIELD_KEYS: { list: (k: string) => ['custom-fields', k] },
}))

import { useCustomFields } from '@/hooks/use-custom-fields'

const EMPTY_CUSTOM_FIELDS_RESULT = {
  data: [] as CustomField[],
  isLoading: false,
  isError: false,
  isPending: false,
  isSuccess: true,
  error: null,
  status: 'success' as const,
  fetchStatus: 'idle' as const,
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
}

/** onSuccess prop 없이 폼만 렌더하는 헬퍼 */
function renderForm(onSuccess?: (key: string) => void) {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
  return render(
    <QueryClientProvider client={client}>
      <IssueCreateForm onSuccess={onSuccess} />
    </QueryClientProvider>,
  )
}

/**
 * 프로젝트 셀렉터에서 프로젝트를 고른다 (FR-UX-09 F2 — 자유 텍스트 입력이 셀렉터로 바뀌었다).
 * 옵션은 MSW `GET /api/v1/projects` 가 채우므로 렌더 직후엔 비어 있을 수 있어 기다린다.
 */
async function selectProject(user: ReturnType<typeof userEvent.setup>, key: string): Promise<void> {
  const select = await waitFor(() => {
    const el = screen.getByLabelText(issueCreateStrings.projectKeyLabel) as HTMLSelectElement
    expect(el.querySelectorAll('option').length).toBeGreaterThan(1)
    return el
  })
  await user.selectOptions(select, key)
}

/** 컴포넌트 옵션 fixture — ComponentMultiSelect options 에 공급 */
const componentFixtures = [
  {
    id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
    projectId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
    name: '프론트엔드',
    description: null,
    leadUserId: '00000000-0000-4000-8000-000000000001', // alice
  },
  {
    id: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
    projectId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
    name: '백엔드',
    description: null,
    leadUserId: null,
  },
]

describe('IssueCreateForm', () => {
  beforeEach(() => {
    resetComponentStore()
    server.use(...issueHandlers, ...componentHandlers, ...projectHandlers, ...issueTypeHandlers)
    mockNavigate.mockReset()
    mockUseSearch.mockReturnValue({})
    // 기본값: 커스텀 필드 없음 — T9-* 테스트에서 개별 오버라이드
    vi.mocked(useCustomFields).mockReturnValue(
      EMPTY_CUSTOM_FIELDS_RESULT as unknown as ReturnType<typeof useCustomFields>,
    )
  })

  /**
   * T6-1. summary 필드가 비어 있으면 제출이 차단되고 검증 메시지가 노출된다.
   * 서버에 요청이 도달하지 않아야 한다 (클라이언트 검증 선행).
   */
  it('T6-1: 빈 summary로 제출하면 검증 메시지가 표시되고 서버에 도달하지 않는다', async () => {
    const user = userEvent.setup()
    renderForm()

    // projectKey 입력, summary 는 비워둠
    await selectProject(user, 'ATLAS')

    // 제출 버튼 클릭
    await user.click(screen.getByRole('button', { name: '이슈 생성' }))

    // 검증 메시지가 노출되어야 한다
    await waitFor(() =>
      expect(screen.getByText('제목을 입력하세요.')).toBeInTheDocument(),
    )

    // navigate 가 호출되지 않아야 한다 (서버 미도달)
    expect(mockNavigate).not.toHaveBeenCalled()
  })

  /**
   * T6-2. 유효한 입력 후 제출하면 createIssue 가 호출되고
   * 201 응답의 key 로 onSuccess(key) 가 호출된다.
   * IssueCreateRouteAdapter 는 onSuccess 에서 navigate 를 호출하는 역할이므로,
   * 여기서는 onSuccess 콜백에 mockNavigate 를 직접 연결해 검증한다.
   */
  it('T6-2: 유효한 입력 제출 후 201 응답 key로 onSuccess가 호출된다', async () => {
    const user = userEvent.setup()
    const onSuccess = (key: string) => {
      mockNavigate({ to: '/issues/$key', params: { key } })
    }
    renderForm(onSuccess)

    await selectProject(user, 'ATLAS')
    await user.type(screen.getByLabelText('제목'), '새 이슈 제목')

    await user.click(screen.getByRole('button', { name: '이슈 생성' }))

    await waitFor(() =>
      expect(mockNavigate).toHaveBeenCalledWith({
        to: '/issues/$key',
        params: { key: createdIssueFixture.key },
      }),
    )
  })

  /**
   * T6-3. projectKey 가 'INVALID' 이면 서버에서 PROJECT_NOT_FOUND(404)를 반환하고
   * role="alert" 에러 메시지가 폼에 노출된다.
   */
  it('T6-3: PROJECT_NOT_FOUND(404) 응답 시 role=alert 에러 메시지가 표시된다', async () => {
    const user = userEvent.setup()
    renderForm()

    // 프로젝트가 셀렉터가 된 뒤로 'INVALID' 를 타이핑할 수 없다. 서버가 404 를 주는 쪽으로 바꾼다
    // — 검증 대상은 「404 응답 시 role=alert 노출」이지 「잘못된 키를 칠 수 있는가」가 아니다.
    server.use(
      http.post('/api/v1/issues', () =>
        HttpResponse.json({ errorCode: 'PROJECT_NOT_FOUND' }, { status: 404 }),
      ),
    )
    await selectProject(user, 'ATLAS')
    await user.type(screen.getByLabelText('제목'), '어떤 제목')

    await user.click(screen.getByRole('button', { name: '이슈 생성' }))

    await waitFor(() =>
      expect(screen.getByRole('alert')).toBeInTheDocument(),
    )
    expect(screen.getByRole('alert')).toHaveTextContent('존재하지 않는 프로젝트입니다.')
  })

  /**
   * T6-4. projectKey 가 비어 있으면 제출이 차단되고 검증 메시지가 노출된다.
   */
  it('T6-4: 빈 projectKey로 제출하면 검증 메시지가 표시된다', async () => {
    const user = userEvent.setup()
    renderForm()

    // FR-UX-09 F2 — 셀렉터는 활성 프로젝트를 기본 선택하므로, 「비어 있음」을 만들려면
    // placeholder 옵션('')을 명시적으로 고른다.
    await selectProject(user, '')
    await user.type(screen.getByLabelText('제목'), '어떤 제목')

    await user.click(screen.getByRole('button', { name: '이슈 생성' }))

    await waitFor(() =>
      expect(screen.getByText(issueCreateStrings.projectKeyRequired)).toBeInTheDocument(),
    )

    expect(mockNavigate).not.toHaveBeenCalled()
  })

  /**
   * T7-1. projectKey가 비어 있으면 ComponentMultiSelect가 disabled 상태다.
   */
  it('T7-1: projectKey 미입력 시 컴포넌트 셀렉터가 disabled 상태다', async () => {
    renderForm()

    // projectKey 가 빈 상태 — 컴포넌트 검색 input이 disabled여야 한다
    const searchInput = screen.getByLabelText('컴포넌트 검색')
    expect(searchInput).toBeDisabled()
  })

  /**
   * T7-2. 유효한 projectKey 입력 후 컴포넌트 옵션이 표시되고 선택 후 제출하면
   * createIssue 에 componentIds 가 전달된다.
   */
  it('T7-2: 유효한 projectKey 입력 후 컴포넌트 선택 → 제출 시 componentIds 전달', async () => {
    // MSW 핸들러로 ATLAS 프로젝트 컴포넌트 목록 시드
    server.use(
      http.get('/api/v1/projects/ATLAS/components', () =>
        HttpResponse.json({ data: componentFixtures }),
      ),
    )

    // POST 요청 body를 캡처하기 위한 핸들러 재등록
    let capturedBody: Record<string, unknown> = {}
    server.use(
      http.post('/api/v1/issues', async ({ request }) => {
        capturedBody = await request.clone().json() as Record<string, unknown>
        return HttpResponse.json({ data: createdIssueFixture }, { status: 201 })
      }),
    )

    const user = userEvent.setup()
    const onSuccess = (key: string) => {
      mockNavigate({ to: '/issues/$key', params: { key } })
    }
    renderForm(onSuccess)

    // projectKey 입력 → 컴포넌트 목록 로드
    await selectProject(user, 'ATLAS')

    // 컴포넌트 옵션이 로드될 때까지 대기
    await waitFor(() =>
      expect(screen.getByRole('checkbox', { name: '프론트엔드' })).toBeInTheDocument(),
    )

    // 컴포넌트 선택
    await user.click(screen.getByRole('checkbox', { name: '프론트엔드' }))

    await user.type(screen.getByLabelText('제목'), '컴포넌트 있는 이슈')
    await user.click(screen.getByRole('button', { name: '이슈 생성' }))

    await waitFor(() => expect(mockNavigate).toHaveBeenCalled())

    expect(capturedBody['componentIds']).toEqual(['aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'])
  })

  /**
   * T9-1. projectKey 미입력 시 커스텀 필드 섹션이 렌더되지 않는다 (또는 disabled).
   * useCustomFields가 enabled=false라서 로드가 되지 않으므로 섹션이 비어있어야 한다.
   */
  it('T9-1: projectKey 미입력 시 커스텀 필드 입력이 렌더되지 않는다', () => {
    renderForm()

    // projectKey 미입력 → useCustomFields enabled=false → 섹션 렌더 안 됨
    expect(screen.queryByTestId('custom-fields-section')).not.toBeInTheDocument()
  })

  /**
   * T9-2. projectKey 입력 후 커스텀 필드 정의가 로드되면 CustomFieldInput이 렌더된다.
   */
  it('T9-2: projectKey 입력 후 커스텀 필드 정의가 로드되면 입력 위젯이 렌더된다', async () => {
    const customFieldFixture: CustomField = {
      id: 'fd000001-0000-4000-8000-000000000001',
      projectId: 'pd000001-0000-4000-8000-000000000001',
      key: 'affected_version',
      name: '영향 버전',
      description: null,
      fieldType: 'SHORT_TEXT',
      required: false,
      displayOrder: 0,
      options: [],
    }
    vi.mocked(useCustomFields).mockReturnValue({
      ...EMPTY_CUSTOM_FIELDS_RESULT,
      data: [customFieldFixture],
    } as unknown as ReturnType<typeof useCustomFields>)

    const user = userEvent.setup()
    renderForm()

    await selectProject(user, 'ATLAS')

    await waitFor(() =>
      expect(screen.getByTestId('custom-field-affected_version')).toBeInTheDocument(),
    )
  })

  /**
   * T9-3. 커스텀 필드 값을 입력하고 제출하면 customFields가 body에 포함된다.
   */
  it('T9-3: 커스텀 필드 값 입력 후 제출 시 customFields가 body에 포함된다', async () => {
    const customFieldFixture: CustomField = {
      id: 'fd000001-0000-4000-8000-000000000001',
      projectId: 'pd000001-0000-4000-8000-000000000001',
      key: 'affected_version',
      name: '영향 버전',
      description: null,
      fieldType: 'SHORT_TEXT',
      required: false,
      displayOrder: 0,
      options: [],
    }
    vi.mocked(useCustomFields).mockReturnValue({
      ...EMPTY_CUSTOM_FIELDS_RESULT,
      data: [customFieldFixture],
    } as unknown as ReturnType<typeof useCustomFields>)

    let capturedBody: Record<string, unknown> = {}
    server.use(
      http.post('/api/v1/issues', async ({ request }) => {
        capturedBody = await request.clone().json() as Record<string, unknown>
        return HttpResponse.json({ data: createdIssueFixture }, { status: 201 })
      }),
    )

    const user = userEvent.setup()
    const onSuccess = (key: string) => {
      mockNavigate({ to: '/issues/$key', params: { key } })
    }
    renderForm(onSuccess)

    await selectProject(user, 'ATLAS')

    await waitFor(() =>
      expect(screen.getByTestId('custom-field-affected_version')).toBeInTheDocument(),
    )

    await user.type(screen.getByTestId('custom-field-affected_version'), 'v2.1.0')
    await user.type(screen.getByLabelText('제목'), '커스텀필드 이슈')
    await user.click(screen.getByRole('button', { name: '이슈 생성' }))

    await waitFor(() => expect(mockNavigate).toHaveBeenCalled())

    expect(capturedBody['customFields']).toEqual({ affected_version: 'v2.1.0' })
  })

  /**
   * T9-C4-1. required 커스텀 필드가 비어 있으면 생성 버튼이 disabled 처리되거나
   * 경고 메시지가 노출되고 mutation이 호출되지 않는다 (스펙 E-3).
   */
  it('T9-C4-1: required 커스텀 필드를 비운 채 제출 시도 → mutation 미호출 + 경고 표시', async () => {
    const requiredFieldFixture: CustomField = {
      id: 'fd000001-0000-4000-8000-000000000010',
      projectId: 'pd000001-0000-4000-8000-000000000001',
      key: 'req_field',
      name: '필수 항목',
      description: null,
      fieldType: 'SHORT_TEXT',
      required: true,
      displayOrder: 0,
      options: [],
    }
    vi.mocked(useCustomFields).mockReturnValue({
      ...EMPTY_CUSTOM_FIELDS_RESULT,
      data: [requiredFieldFixture],
    } as unknown as ReturnType<typeof useCustomFields>)

    let mutationCalled = false
    server.use(
      http.post('/api/v1/issues', async () => {
        mutationCalled = true
        return HttpResponse.json({ data: createdIssueFixture }, { status: 201 })
      }),
    )

    const user = userEvent.setup()
    renderForm()

    // projectKey + summary 입력, required 커스텀 필드는 비워둠
    await selectProject(user, 'ATLAS')
    await user.type(screen.getByLabelText('제목'), '이슈 제목')

    // 커스텀 필드 섹션이 렌더될 때까지 대기
    await waitFor(() =>
      expect(screen.getByTestId('custom-field-req_field')).toBeInTheDocument(),
    )

    // 생성 버튼 클릭 (req_field 비어 있음)
    const submitButton = screen.getByRole('button', { name: '이슈 생성' })
    await user.click(submitButton)

    // mutation이 호출되지 않아야 한다
    expect(mutationCalled).toBe(false)
    expect(mockNavigate).not.toHaveBeenCalled()
  })

  /**
   * T9-C4-2. required 커스텀 필드를 채우면 정상 제출된다 (E-3 정상 경로).
   */
  it('T9-C4-2: required 커스텀 필드를 채운 후 제출 → 정상 생성', async () => {
    const requiredFieldFixture: CustomField = {
      id: 'fd000001-0000-4000-8000-000000000010',
      projectId: 'pd000001-0000-4000-8000-000000000001',
      key: 'req_field',
      name: '필수 항목',
      description: null,
      fieldType: 'SHORT_TEXT',
      required: true,
      displayOrder: 0,
      options: [],
    }
    vi.mocked(useCustomFields).mockReturnValue({
      ...EMPTY_CUSTOM_FIELDS_RESULT,
      data: [requiredFieldFixture],
    } as unknown as ReturnType<typeof useCustomFields>)

    server.use(
      http.post('/api/v1/issues', async () =>
        HttpResponse.json({ data: createdIssueFixture }, { status: 201 }),
      ),
    )

    const user = userEvent.setup()
    const onSuccess = (key: string) => {
      mockNavigate({ to: '/issues/$key', params: { key } })
    }
    renderForm(onSuccess)

    await selectProject(user, 'ATLAS')
    await user.type(screen.getByLabelText('제목'), '이슈 제목')

    await waitFor(() =>
      expect(screen.getByTestId('custom-field-req_field')).toBeInTheDocument(),
    )

    // required 필드에 값 입력 후 제출
    await user.type(screen.getByTestId('custom-field-req_field'), '유효한 값')
    await user.click(screen.getByRole('button', { name: '이슈 생성' }))

    await waitFor(() => expect(mockNavigate).toHaveBeenCalled())
  })

  /**
   * T7-3. 컴포넌트를 선택하지 않고 제출하면 componentIds가 빈 배열로 전달된다.
   */
  it('T7-3: 컴포넌트 미선택 제출 시 componentIds 빈 배열 전달', async () => {
    server.use(
      http.get('/api/v1/projects/ATLAS/components', () =>
        HttpResponse.json({ data: componentFixtures }),
      ),
    )

    let capturedBody: Record<string, unknown> = {}
    server.use(
      http.post('/api/v1/issues', async ({ request }) => {
        capturedBody = await request.clone().json() as Record<string, unknown>
        return HttpResponse.json({ data: createdIssueFixture }, { status: 201 })
      }),
    )

    const user = userEvent.setup()
    const onSuccess = (key: string) => {
      mockNavigate({ to: '/issues/$key', params: { key } })
    }
    renderForm(onSuccess)

    await selectProject(user, 'ATLAS')
    await user.type(screen.getByLabelText('제목'), '컴포넌트 없는 이슈')
    await user.click(screen.getByRole('button', { name: '이슈 생성' }))

    await waitFor(() => expect(mockNavigate).toHaveBeenCalled())

    expect(capturedBody['componentIds']).toEqual([])
  })

  /**
   * FR7 — IssueCreateRouteAdapter의 summary URL search param 프리필.
   * 명령 팔레트 `/issue <제목>` 실행(FR-UX-04) 시 `/issues/new?summary=...`로 이동한 뒤
   * 제목 필드가 프리필되는지 검증한다. useSearch를 목킹해 URL 파싱은 라우터에 위임하고
   * 어댑터의 defaultValues 반영 로직만 단위 테스트한다.
   */
  describe('IssueCreateRouteAdapter — summary URL 프리필 (FR7)', () => {
    /** QueryClientProvider로 감싸 useComponents/useCustomFields/useMutation 훅 의존성을 충족한다 */
    function renderRouteAdapter() {
      const client = new QueryClient({
        defaultOptions: {
          queries: { retry: false },
          mutations: { retry: false },
        },
      })
      const result = render(
        <QueryClientProvider client={client}>
          <IssueCreateRouteAdapter />
        </QueryClientProvider>,
      )
      return {
        ...result,
        rerenderAdapter: () =>
          result.rerender(
            <QueryClientProvider client={client}>
              <IssueCreateRouteAdapter />
            </QueryClientProvider>,
          ),
      }
    }

    it('FR7-1: URL summary가 있으면 제목 필드 기본값에 반영된다', () => {
      mockUseSearch.mockReturnValue({ summary: '결제 실패' })

      renderRouteAdapter()

      expect(screen.getByLabelText('제목')).toHaveValue('결제 실패')
    })

    it('FR7-2 (회귀): URL summary가 없으면 제목 필드가 기존처럼 빈 값이다', () => {
      mockUseSearch.mockReturnValue({})

      renderRouteAdapter()

      expect(screen.getByLabelText('제목')).toHaveValue('')
    })

    it('FR7-3: URL summary 앞뒤 공백은 trim되어 반영된다', () => {
      mockUseSearch.mockReturnValue({ summary: '  공백 포함 제목  ' })

      renderRouteAdapter()

      expect(screen.getByLabelText('제목')).toHaveValue('공백 포함 제목')
    })

    it('FR7-4: URL summary가 200자를 넘으면 zod max(200)에 맞춰 잘린다', () => {
      // FR-UX-09 F2 — 백엔드 @Size(max = 200) 와 정렬하면서 상한이 500 → 200 이 됐다.
      mockUseSearch.mockReturnValue({ summary: 'a'.repeat(600) })

      renderRouteAdapter()

      expect(screen.getByLabelText('제목')).toHaveValue('a'.repeat(200))
    })

    /**
     * CONCERN-1 (plan 리뷰): react-hook-form defaultValues는 mount 시 1회만 적용된다.
     * 이미 /issues/new에 머문 상태에서 URL summary만 바뀌면(같은 라우트라 컴포넌트가
     * 자연 리마운트되지 않음) 프리필이 갱신되지 않을 수 있다. key={summary} 강제 리마운트로
     * 해소했는지 검증한다.
     */
    it('FR7-5 (CONCERN-1): 같은 라우트에서 summary가 바뀌면 제목 필드가 새 값으로 갱신된다', async () => {
      mockUseSearch.mockReturnValue({ summary: '첫 번째 제목' })
      const { rerenderAdapter } = renderRouteAdapter()

      expect(screen.getByLabelText('제목')).toHaveValue('첫 번째 제목')

      mockUseSearch.mockReturnValue({ summary: '두 번째 제목' })
      rerenderAdapter()

      await waitFor(() =>
        expect(screen.getByLabelText('제목')).toHaveValue('두 번째 제목'),
      )
    })
  })
})
