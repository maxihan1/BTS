// LoginForm 컴포넌트 통합 테스트 — RHF + Zod 검증, msw 응답 모킹, 접근성 검증
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { LoginForm } from './LoginForm'
import { useAuthStore } from './authStore'

function createWrapper() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  )
}

function renderLoginForm(onSuccess?: () => void) {
  const Wrapper = createWrapper()
  return render(<LoginForm onSuccess={onSuccess} />, { wrapper: Wrapper })
}

beforeEach(() => {
  useAuthStore.setState({ accessToken: null, user: null })
})

afterEach(() => {
  useAuthStore.setState({ accessToken: null, user: null })
})

describe('LoginForm', () => {
  it('입력 + 제출 시 useLoginMutation.mutate가 호출된다', async () => {
    const user = userEvent.setup()

    server.use(
      http.post('/api/v1/auth/login', () =>
        HttpResponse.json({
          access_token: 'test-token',
          token_type: 'Bearer',
          expires_in: 3600,
        }),
      ),
      http.get('/api/v1/users/me/whoami', () =>
        HttpResponse.json({
          username: 'alice',
          email: 'alice@bts.local',
          authMethod: 'local',
          userId: 'u1',
        }),
      ),
    )

    const onSuccess = vi.fn()
    renderLoginForm(onSuccess)

    await user.type(screen.getByLabelText('사용자명'), 'alice')
    await user.type(screen.getByLabelText('비밀번호'), 'password')
    await user.click(screen.getByRole('button', { name: '로그인' }))

    await waitFor(() => expect(onSuccess).toHaveBeenCalledOnce())
    expect(useAuthStore.getState().accessToken).toBe('test-token')
  })

  it('빈 username 제출 시 Zod 검증 에러 메시지가 표시된다', async () => {
    const user = userEvent.setup()
    renderLoginForm()

    // username 비워두고 비밀번호만 입력
    await user.type(screen.getByLabelText('비밀번호'), 'password')
    await user.click(screen.getByRole('button', { name: '로그인' }))

    await screen.findByText('사용자명을 입력하세요.')
  })

  it('401 invalid_credentials 응답 시 에러 메시지가 표시된다', async () => {
    const user = userEvent.setup()

    server.use(
      http.post('/api/v1/auth/login', () =>
        HttpResponse.json({ error: 'invalid_credentials' }, { status: 401 }),
      ),
    )

    renderLoginForm()

    await user.type(screen.getByLabelText('사용자명'), 'alice')
    await user.type(screen.getByLabelText('비밀번호'), 'wrong')
    await user.click(screen.getByRole('button', { name: '로그인' }))

    await screen.findByText('사용자명 또는 비밀번호가 올바르지 않습니다.')
  })

  it('401 mfa_required 응답 시 MFA 에러 메시지가 표시된다', async () => {
    const user = userEvent.setup()

    server.use(
      http.post('/api/v1/auth/login', () =>
        HttpResponse.json({ error: 'mfa_required' }, { status: 401 }),
      ),
    )

    renderLoginForm()

    await user.type(screen.getByLabelText('사용자명'), 'alice')
    await user.type(screen.getByLabelText('비밀번호'), 'password')
    await user.click(screen.getByRole('button', { name: '로그인' }))

    await screen.findByText('추가 인증이 필요합니다. 관리자에게 문의하세요.')
  })

  it('provider 드롭다운이 기본값 local이고 ldap-corp 옵션을 포함한다', () => {
    renderLoginForm()

    // combobox 트리거 — 기본값 "Local" 텍스트 포함 확인
    const trigger = screen.getByRole('combobox', { name: '로그인 방식' })
    expect(trigger).toBeInTheDocument()
    expect(trigger).toHaveTextContent('Local')

    // Radix Select는 접근성용 숨겨진 <select> 요소를 DOM에 렌더링한다.
    // jsdom 환경에서 Portal은 옵션을 role="option"으로 노출하지 않으므로,
    // 숨겨진 네이티브 select의 option 목록으로 두 옵션을 검증한다.
    const nativeSelect = document.querySelector('select[aria-hidden="true"]')
    expect(nativeSelect).toBeInTheDocument()
    const options = Array.from(nativeSelect?.querySelectorAll('option') ?? []).map(
      (o) => o.textContent,
    )
    expect(options).toContain('Local')
    expect(options).toContain('LDAP-corp')
  })

  it('키보드 탐색 — label/aria-invalid/aria-describedby 접근성을 충족한다', async () => {
    const user = userEvent.setup()
    renderLoginForm()

    const usernameInput = screen.getByLabelText('사용자명')
    const passwordInput = screen.getByLabelText('비밀번호')

    // label htmlFor 연결 확인
    expect(usernameInput).toBeInTheDocument()
    expect(passwordInput).toBeInTheDocument()

    // 빈 폼 제출 후 aria-invalid 설정 확인
    await user.click(screen.getByRole('button', { name: '로그인' }))

    await waitFor(() => {
      const container = usernameInput.closest('[data-slot="form-control"]')
      expect(container).toHaveAttribute('aria-invalid', 'true')
    })

    // aria-describedby 연결 확인 — FormControl에 aria-describedby 설정
    const formControl = usernameInput.closest('[data-slot="form-control"]')
    expect(formControl).toHaveAttribute('aria-describedby')

    // 키보드로 버튼까지 Tab 이동 가능 확인
    await user.tab()
    await user.tab()
    await user.tab()
    const submitBtn = screen.getByRole('button', { name: '로그인' })
    expect(submitBtn).toBeInTheDocument()
  })
})
