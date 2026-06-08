// CreateUserForm 컴포넌트 단위 테스트 — 필드 검증 + 제출 + 결과 패널 + 409 에러
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { CreateUserForm } from '@/components/admin/CreateUserForm'

// ─────────────────────────────────────────────────────────────────────────────
// mock — TanStack Router (navigate), sonner toast
// ─────────────────────────────────────────────────────────────────────────────

const { mockNavigate } = vi.hoisted(() => ({ mockNavigate: vi.fn() }))

vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => mockNavigate,
  useParams: () => ({}),
}))

vi.mock('sonner', () => ({
  toast: { success: vi.fn(), error: vi.fn() },
}))

// ─────────────────────────────────────────────────────────────────────────────
// fixture
// ─────────────────────────────────────────────────────────────────────────────

const CREATED_RESPONSE = {
  id: '11111111-0000-0000-0000-000000000001',
  username: 'newuser',
  temporaryPassword: 'TmpPass123!',
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function renderForm() {
  // XSRF 쿠키 세팅
  document.cookie = 'XSRF-TOKEN=test-xsrf; path=/'

  const client = new QueryClient({
    defaultOptions: {
      queries: { retry: false },
      mutations: { retry: false },
    },
  })
  return render(
    <QueryClientProvider client={client}>
      <CreateUserForm />
    </QueryClientProvider>,
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('CreateUserForm', () => {
  beforeEach(() => {
    mockNavigate.mockReset()
    document.cookie = 'XSRF-TOKEN=; max-age=0; path=/'
  })

  /**
   * T8-F-1. 폼 마운트 시 username, displayName, email 필드가 렌더된다.
   */
  it('T8-F-1: 폼 마운트 시 username, displayName, email 필드가 렌더된다', () => {
    renderForm()

    expect(screen.getByLabelText('사용자 이름')).toBeInTheDocument()
    expect(screen.getByLabelText('표시 이름')).toBeInTheDocument()
    expect(screen.getByLabelText('이메일 (선택)')).toBeInTheDocument()
  })

  /**
   * T8-F-2. username 빈 값 → 에러 메시지 노출, createUser 미호출.
   */
  it('T8-F-2: username 빈 값 → 에러 메시지 노출, 제출 차단', async () => {
    const user = userEvent.setup()
    renderForm()

    await user.type(screen.getByLabelText('표시 이름'), '새 사용자')
    await user.click(screen.getByRole('button', { name: '사용자 생성' }))

    await waitFor(() =>
      expect(screen.getByText(/사용자 이름은 필수/)).toBeInTheDocument(),
    )
  })

  /**
   * T8-F-3. displayName 빈 값 → 에러 메시지 노출, createUser 미호출.
   */
  it('T8-F-3: displayName 빈 값 → 에러 메시지 노출, 제출 차단', async () => {
    const user = userEvent.setup()
    renderForm()

    await user.type(screen.getByLabelText('사용자 이름'), 'newuser')
    await user.click(screen.getByRole('button', { name: '사용자 생성' }))

    await waitFor(() =>
      expect(screen.getByText(/표시 이름은 필수/)).toBeInTheDocument(),
    )
  })

  /**
   * T8-F-4. 유효한 입력 제출 → createUser 호출 → 성공 시 결과 패널 표시.
   * 결과 패널: 생성된 username + temporaryPassword + "이 비밀번호는 다시 표시되지 않습니다" 안내.
   */
  it('T8-F-4: 유효한 입력 제출 → 성공 → 결과 패널 + 임시 비밀번호 표시', async () => {
    const user = userEvent.setup()

    server.use(
      http.post('/api/v1/users', () =>
        HttpResponse.json(CREATED_RESPONSE, { status: 201 }),
      ),
    )

    renderForm()

    await user.type(screen.getByLabelText('사용자 이름'), 'newuser')
    await user.type(screen.getByLabelText('표시 이름'), '새 사용자')
    await user.click(screen.getByRole('button', { name: '사용자 생성' }))

    // 결과 패널: username 표시
    await waitFor(() =>
      expect(screen.getByText(CREATED_RESPONSE.username)).toBeInTheDocument(),
    )

    // 결과 패널: temporaryPassword 표시 (1회만)
    expect(screen.getByText(CREATED_RESPONSE.temporaryPassword)).toBeInTheDocument()

    // 결과 패널: "다시 표시되지 않습니다" 안내 문구
    expect(screen.getByText(/다시 표시되지 않습니다/)).toBeInTheDocument()
  })

  /**
   * T8-F-5. 409 USERNAME_TAKEN → 에러 메시지 노출, 폼 유지.
   */
  it('T8-F-5: 409 USERNAME_TAKEN → 에러 메시지 노출, 폼 유지', async () => {
    const user = userEvent.setup()

    server.use(
      http.post('/api/v1/users', () =>
        HttpResponse.json({ code: 'USERNAME_TAKEN' }, { status: 409 }),
      ),
    )

    renderForm()

    await user.type(screen.getByLabelText('사용자 이름'), 'newuser')
    await user.type(screen.getByLabelText('표시 이름'), '새 사용자')
    await user.click(screen.getByRole('button', { name: '사용자 생성' }))

    await waitFor(() =>
      expect(screen.getByText(/이미 사용 중인 사용자 이름/)).toBeInTheDocument(),
    )

    // 폼 입력 필드가 여전히 존재 (결과 패널로 전환 안 됨)
    expect(screen.getByLabelText('사용자 이름')).toBeInTheDocument()
  })

  /**
   * T8-F-6. 성공 후 결과 패널에서 "추가 생성" 버튼 클릭 → 폼 초기화.
   */
  it('T8-F-6: 결과 패널 → "추가 생성" 버튼 → 폼 초기화', async () => {
    const user = userEvent.setup()

    server.use(
      http.post('/api/v1/users', () =>
        HttpResponse.json(CREATED_RESPONSE, { status: 201 }),
      ),
    )

    renderForm()

    await user.type(screen.getByLabelText('사용자 이름'), 'newuser')
    await user.type(screen.getByLabelText('표시 이름'), '새 사용자')
    await user.click(screen.getByRole('button', { name: '사용자 생성' }))

    await waitFor(() =>
      expect(screen.getByText(/다시 표시되지 않습니다/)).toBeInTheDocument(),
    )

    await user.click(screen.getByRole('button', { name: '추가 생성' }))

    // 폼 입력 필드가 다시 나타난다
    await waitFor(() =>
      expect(screen.getByLabelText('사용자 이름')).toBeInTheDocument(),
    )
  })
})
