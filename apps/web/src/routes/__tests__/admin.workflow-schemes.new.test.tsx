// 워크플로우 스킴 생성 폼 단위 테스트 — Zod 검증 + mutate 호출 + navigate + 409 토스트
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import type { SchemeResponse } from '@/api/workflow-schemes'
import { WorkflowSchemeNewForm } from '../admin.workflow-schemes.new'

// vi.hoisted로 mock 함수 선언 — vi.mock 호이스팅보다 먼저 초기화 보장
const { mockNavigate, mockToastError } = vi.hoisted(() => ({
  mockNavigate: vi.fn(),
  mockToastError: vi.fn(),
}))

// useNavigate mock — TanStack Router 의존 없이 폼 자체 테스트
vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => mockNavigate,
  useParams: () => ({}),
}))

// sonner toast mock — 토스트 호출 검증
vi.mock('sonner', () => ({
  toast: {
    success: vi.fn(),
    error: mockToastError,
  },
}))

/** 성공 fixture — createWorkflowScheme 201 응답 */
const createdSchemeFixture: SchemeResponse = {
  schemeKey: 'my-new-scheme',
  name: '새 스킴',
  description: '설명',
  usedByProjectsCount: 0,
  mappingsCount: 0,
}

/** QueryClientProvider 래퍼 */
function renderForm() {
  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
  return render(
    <QueryClientProvider client={client}>
      <WorkflowSchemeNewForm onSuccess={(key) => mockNavigate({ to: '/admin/workflow-schemes/$key', params: { key } })} />
    </QueryClientProvider>,
  )
}

describe('WorkflowSchemeNewForm', () => {
  beforeEach(() => {
    mockNavigate.mockReset()
    mockToastError.mockReset()
  })

  /**
   * T8-1. 폼이 마운트되면 schemeKey, name, description 3개 필드가 렌더된다.
   */
  it('T8-1: 폼 마운트 시 3개 입력 필드가 렌더된다', () => {
    server.use(
      http.post('/api/v1/workflow-schemes', () =>
        HttpResponse.json({ data: createdSchemeFixture }, { status: 201 }),
      ),
    )

    renderForm()

    expect(screen.getByLabelText('스킴 키')).toBeInTheDocument()
    expect(screen.getByLabelText('이름')).toBeInTheDocument()
    expect(screen.getByLabelText('설명 (선택)')).toBeInTheDocument()
  })

  /**
   * T8-2. schemeKey가 REGEX를 위반하면 한국어 Zod 에러 메시지가 노출된다.
   */
  it('T8-2: schemeKey REGEX 위반("Abc!") → 한국어 에러 메시지 렌더', async () => {
    const user = userEvent.setup()
    renderForm()

    await user.type(screen.getByLabelText('스킴 키'), 'Abc!')
    await user.type(screen.getByLabelText('이름'), '유효한 이름')
    await user.click(screen.getByRole('button', { name: '스킴 생성' }))

    await waitFor(() =>
      expect(
        screen.getByText('스킴 키는 소문자/숫자/하이픈 1~30자로 시작은 소문자'),
      ).toBeInTheDocument(),
    )

    expect(mockNavigate).not.toHaveBeenCalled()
  })

  /**
   * T8-3. name이 빈 값이면 "이름은 필수입니다" 메시지가 노출된다.
   */
  it('T8-3: name 빈 값 → "이름은 필수입니다" 메시지 노출', async () => {
    const user = userEvent.setup()
    renderForm()

    await user.type(screen.getByLabelText('스킴 키'), 'valid-key')
    // name 은 비워둠
    await user.click(screen.getByRole('button', { name: '스킴 생성' }))

    await waitFor(() =>
      expect(screen.getByText('이름은 필수입니다')).toBeInTheDocument(),
    )

    expect(mockNavigate).not.toHaveBeenCalled()
  })

  /**
   * T8-4. name이 255자 초과이면 길이 제한 에러 메시지가 노출된다.
   */
  it('T8-4: name 255자 초과 → 길이 제한 메시지 노출', async () => {
    const user = userEvent.setup()
    renderForm()

    await user.type(screen.getByLabelText('스킴 키'), 'valid-key')
    await user.type(screen.getByLabelText('이름'), 'a'.repeat(256))
    await user.click(screen.getByRole('button', { name: '스킴 생성' }))

    await waitFor(() =>
      expect(screen.getByText('이름은 255자 이내')).toBeInTheDocument(),
    )

    expect(mockNavigate).not.toHaveBeenCalled()
  })

  /**
   * T8-5. 유효한 입력 → submit → mutate 호출 → 201 mock → navigate 호출.
   */
  it('T8-5: 유효한 입력 제출 → 201 → navigate 호출', async () => {
    const user = userEvent.setup()

    server.use(
      http.post('/api/v1/workflow-schemes', () =>
        HttpResponse.json({ data: createdSchemeFixture }, { status: 201 }),
      ),
    )

    renderForm()

    await user.type(screen.getByLabelText('스킴 키'), 'my-new-scheme')
    await user.type(screen.getByLabelText('이름'), '새 스킴')
    await user.click(screen.getByRole('button', { name: '스킴 생성' }))

    await waitFor(() =>
      expect(mockNavigate).toHaveBeenCalledWith({
        to: '/admin/workflow-schemes/$key',
        params: { key: createdSchemeFixture.schemeKey },
      }),
    )
  })

  /**
   * T8-6. 409 errorCode(SCHEME_KEY_DUPLICATE) → toast.error 호출 + 폼 유지.
   */
  it('T8-6: 409 SCHEME_KEY_DUPLICATE → toast.error 호출 + 폼 유지', async () => {
    const user = userEvent.setup()

    server.use(
      http.post('/api/v1/workflow-schemes', () =>
        HttpResponse.json(
          { code: 'SCHEME_KEY_DUPLICATE', detail: 'already exists' },
          { status: 409 },
        ),
      ),
    )

    renderForm()

    await user.type(screen.getByLabelText('스킴 키'), 'my-new-scheme')
    await user.type(screen.getByLabelText('이름'), '새 스킴')
    await user.click(screen.getByRole('button', { name: '스킴 생성' }))

    await waitFor(() => expect(mockToastError).toHaveBeenCalled())

    // 폼 필드가 그대로 존재해야 함 (navigate 미호출 = 폼 유지)
    expect(mockNavigate).not.toHaveBeenCalled()
    expect(screen.getByLabelText('스킴 키')).toBeInTheDocument()
  })
})
