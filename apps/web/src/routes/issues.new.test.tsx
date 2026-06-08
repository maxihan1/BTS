// 이슈 생성 폼 단위 테스트 — T6-1(클라이언트 검증) T6-2(제출 성공+navigate) T6-3(PROJECT_NOT_FOUND 에러) T6-4(빈 summary 제출 차단) T7-1~T7-3(컴포넌트 선택) T9-1~T9-3(커스텀필드)
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { issueHandlers, createdIssueFixture } from '@/mocks/issue-handlers'
import { componentHandlers, resetComponentStore } from '@/mocks/component-handlers'
import { server } from '@/test/server'
import { http, HttpResponse } from 'msw'
import { IssueCreateForm } from './issues.new'
import type { CustomField } from '@/api/custom-fields.types'

// useNavigate mock — TanStack Router 의존 없이 폼 자체 테스트
const mockNavigate = vi.fn()
vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => mockNavigate,
  useParams: () => ({}),
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

/** 컴포넌트 옵션 fixture — ComponentMultiSelect options 에 공급 */
const componentFixtures = [
  {
    id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
    projectId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
    name: '프론트엔드',
    description: null,
    leadUserId: 'c3d4e5f6-a7b8-4c9d-ae1f-2a3b4c5d6e7f', // alice
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
    server.use(...issueHandlers, ...componentHandlers)
    mockNavigate.mockReset()
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
    await user.type(screen.getByLabelText('프로젝트 키'), 'ATLAS')

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

    await user.type(screen.getByLabelText('프로젝트 키'), 'ATLAS')
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

    await user.type(screen.getByLabelText('프로젝트 키'), 'INVALID')
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

    await user.type(screen.getByLabelText('제목'), '어떤 제목')

    await user.click(screen.getByRole('button', { name: '이슈 생성' }))

    await waitFor(() =>
      expect(screen.getByText('프로젝트 키를 입력하세요.')).toBeInTheDocument(),
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
    await user.type(screen.getByLabelText('프로젝트 키'), 'ATLAS')

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

    await user.type(screen.getByLabelText('프로젝트 키'), 'ATLAS')

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

    await user.type(screen.getByLabelText('프로젝트 키'), 'ATLAS')

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

    await user.type(screen.getByLabelText('프로젝트 키'), 'ATLAS')
    await user.type(screen.getByLabelText('제목'), '컴포넌트 없는 이슈')
    await user.click(screen.getByRole('button', { name: '이슈 생성' }))

    await waitFor(() => expect(mockNavigate).toHaveBeenCalled())

    expect(capturedBody['componentIds']).toEqual([])
  })
})
