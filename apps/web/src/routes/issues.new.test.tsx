// 이슈 생성 폼 단위 테스트 — T6-1(클라이언트 검증) T6-2(제출 성공+navigate) T6-3(PROJECT_NOT_FOUND 에러) T6-4(빈 summary 제출 차단)
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { issueHandlers, createdIssueFixture } from '@/mocks/issue-handlers'
import { server } from '@/test/server'
import { IssueCreateForm } from './issues.new'

// useNavigate mock — TanStack Router 의존 없이 폼 자체 테스트
const mockNavigate = vi.fn()
vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => mockNavigate,
  useParams: () => ({}),
}))

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

describe('IssueCreateForm', () => {
  beforeEach(() => {
    server.use(...issueHandlers)
    mockNavigate.mockReset()
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
})
