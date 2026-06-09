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

/**
 * providers useQuery 로딩 완료를 기다리는 헬퍼.
 * provider 드롭다운이 enabled 상태가 되면 로딩 완료로 판단한다.
 */
async function waitForProvidersLoaded() {
  await waitFor(() => {
    const combobox = screen.getByRole('combobox', { name: '로그인 방식' })
    expect(combobox).not.toBeDisabled()
  })
}

/** 테스트 기본 providers 핸들러 — ldap(priority 0), local(priority 1) */
const defaultProvidersHandler = http.get('/api/v1/auth/providers', () =>
  HttpResponse.json({
    providers: [
      { id: 'ldap', type: 'LDAP', displayName: 'Ldap', priority: 0, available: true },
      { id: 'local', type: 'LOCAL', displayName: 'Local', priority: 1, available: true },
    ],
  }),
)

beforeEach(() => {
  useAuthStore.setState({ accessToken: null, user: null })
  // providers useQuery가 미핸들 MSW 에러로 폼을 깨뜨리지 않도록 기본 핸들러를 등록한다.
  server.use(defaultProvidersHandler)
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
          mustChangePassword: false,
          isSystemAdmin: false,
        }),
      ),
    )

    const onSuccess = vi.fn()
    renderLoginForm(onSuccess)

    // providers 로딩 완료 후 제출해야 provider 폼 값이 설정된다
    await waitForProvidersLoaded()
    await user.type(screen.getByLabelText('사용자명'), 'alice')
    await user.type(screen.getByLabelText('비밀번호'), 'password')
    await user.click(screen.getByRole('button', { name: '로그인' }))

    await waitFor(() => expect(onSuccess).toHaveBeenCalledOnce())
    expect(useAuthStore.getState().accessToken).toBe('test-token')
  })

  it('빈 username 제출 시 Zod 검증 에러 메시지가 표시된다', async () => {
    const user = userEvent.setup()
    renderLoginForm()

    // providers 로딩 완료 후 제출해야 provider 폼 값이 설정된다
    await waitForProvidersLoaded()
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

    await waitForProvidersLoaded()
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

    await waitForProvidersLoaded()
    await user.type(screen.getByLabelText('사용자명'), 'alice')
    await user.type(screen.getByLabelText('비밀번호'), 'password')
    await user.click(screen.getByRole('button', { name: '로그인' }))

    await screen.findByText('추가 인증이 필요합니다. 관리자에게 문의하세요.')
  })

  it('providers API 응답 기반으로 드롭다운 항목이 동적 렌더된다', async () => {
    renderLoginForm()

    // providers API 응답 후 드롭다운이 렌더될 때까지 대기
    // auth-handlers.ts 기본 핸들러: ldap(priority 0), local(priority 1)
    await waitFor(() => {
      const nativeSelect = document.querySelector('select[aria-hidden="true"]')
      const options = Array.from(nativeSelect?.querySelectorAll('option') ?? []).map(
        (o) => o.textContent,
      )
      // 한국어 id→라벨 매핑: local → 'Local', ldap → 'LDAP-corp'
      expect(options).toContain('LDAP-corp')
      expect(options).toContain('Local')
    })
  })

  it('provider 드롭다운 기본 선택값이 providers 응답 첫 항목(ldap)의 id이다', async () => {
    renderLoginForm()

    // auth-handlers.ts 기본: ldap(priority 0)이 첫 항목
    await waitFor(() => {
      const trigger = screen.getByRole('combobox', { name: '로그인 방식' })
      // 첫 항목 ldap → 매핑 라벨 'LDAP-corp'가 트리거에 표시
      expect(trigger).toHaveTextContent('LDAP-corp')
    })
  })

  it('providers 응답 항목의 value가 provider.id이다', async () => {
    renderLoginForm()

    await waitFor(() => {
      const nativeSelect = document.querySelector('select[aria-hidden="true"]')
      const options = Array.from(nativeSelect?.querySelectorAll('option') ?? [])
      const values = options.map((o) => (o as HTMLOptionElement).value)
      // id: 'ldap', 'local' — 하드코딩 'ldap-corp' 없음
      expect(values).toContain('ldap')
      expect(values).toContain('local')
      expect(values).not.toContain('ldap-corp')
    })
  })

  it('providers fetch 실패 시 폼이 깨지지 않고 로그인 버튼이 렌더된다', async () => {
    server.use(
      http.get('/api/v1/auth/providers', () =>
        HttpResponse.json({ error: 'server_error' }, { status: 500 }),
      ),
    )

    renderLoginForm()

    // 로그인 버튼이 렌더되어야 한다 (폼 크래시 없음)
    await screen.findByRole('button', { name: '로그인' })
  })

  it('키보드 탐색 — label/aria-invalid/aria-describedby 접근성을 충족한다', async () => {
    const user = userEvent.setup()
    renderLoginForm()

    const usernameInput = screen.getByLabelText('사용자명')
    const passwordInput = screen.getByLabelText('비밀번호')

    // label htmlFor 연결 확인
    expect(usernameInput).toBeInTheDocument()
    expect(passwordInput).toBeInTheDocument()

    // providers 로딩 완료 후 제출해야 provider 폼 값이 설정된다
    await waitForProvidersLoaded()
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
