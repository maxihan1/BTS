// LoginForm 컴포넌트 통합 테스트 — identifier-first 2단계 흐름, RHF + Zod 검증, msw 응답 모킹, 접근성 검증
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
 * 이메일 입력 후 "계속" 클릭으로 2단계(폼 로그인 화면)로 진입하는 헬퍼.
 * 매칭 없음(unmatched) 경로 — 2단계 UI가 노출될 때까지 대기한다.
 */
async function goToStep2(user: ReturnType<typeof userEvent.setup>, email = 'alice@example.com') {
  // route API가 미매칭 응답을 반환하도록 설정되어야 한다 (호출 측에서 세팅)
  const emailInput = screen.getByLabelText('이메일')
  await user.type(emailInput, email)
  await user.click(screen.getByRole('button', { name: '계속' }))
  // 2단계 폼(provider 드롭다운 또는 로그인 버튼)이 나타날 때까지 대기
  await screen.findByRole('button', { name: '로그인' })
}

/**
 * providers useQuery 로딩 완료를 기다리는 헬퍼.
 * 2단계 진입 후 provider 드롭다운이 enabled 상태가 되면 로딩 완료로 판단한다.
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

/** 테스트 기본 route 핸들러 — 미매칭 응답 */
const defaultRouteHandler = http.get('/api/v1/auth/route', () =>
  HttpResponse.json({ matched: false }),
)

beforeEach(() => {
  useAuthStore.setState({ accessToken: null, user: null })
  vi.spyOn(window, 'location', 'get').mockReturnValue({
    ...window.location,
    assign: vi.fn(),
  } as unknown as Location)
  // providers/route useQuery가 미핸들 MSW 에러로 폼을 깨뜨리지 않도록 기본 핸들러를 등록한다.
  server.use(defaultProvidersHandler, defaultRouteHandler)
})

afterEach(() => {
  useAuthStore.setState({ accessToken: null, user: null })
  vi.restoreAllMocks()
})

// ─────────────────────────────────────────────────────────────────────────────
// 1단계 — identifier-first 이메일 입력 화면
// ─────────────────────────────────────────────────────────────────────────────

describe('LoginForm — 1단계 (identifier-first)', () => {
  it('초기 렌더에 이메일 필드와 "계속" 버튼만 표시된다', async () => {
    renderLoginForm()

    expect(screen.getByLabelText('이메일')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '계속' })).toBeInTheDocument()

    // provider 드롭다운, username, password 필드는 1단계에 없어야 한다
    expect(screen.queryByRole('combobox', { name: '로그인 방식' })).toBeNull()
    expect(screen.queryByLabelText('사용자명')).toBeNull()
    expect(screen.queryByLabelText('비밀번호')).toBeNull()
  })

  it('이메일 입력 후 계속 → route 매칭(SAML) → window.location.assign으로 SAML 경로 이동', async () => {
    const assignMock = vi.fn()
    vi.spyOn(window, 'location', 'get').mockReturnValue({
      ...window.location,
      assign: assignMock,
    } as unknown as Location)

    server.use(
      http.get('/api/v1/auth/route', () =>
        HttpResponse.json({
          matched: true,
          type: 'SAML',
          registrationId: 'okta',
          displayName: 'Okta SSO',
        }),
      ),
    )

    const user = userEvent.setup({ delay: null })
    renderLoginForm()

    await user.type(screen.getByLabelText('이메일'), 'alice@okta.com')
    await user.click(screen.getByRole('button', { name: '계속' }))

    await waitFor(() => {
      expect(assignMock).toHaveBeenCalledWith('/saml2/authenticate/okta')
    })
  })

  it('이메일 입력 후 계속 → route 매칭(OIDC) → window.location.assign으로 OIDC 경로 이동', async () => {
    const assignMock = vi.fn()
    vi.spyOn(window, 'location', 'get').mockReturnValue({
      ...window.location,
      assign: assignMock,
    } as unknown as Location)

    server.use(
      http.get('/api/v1/auth/route', () =>
        HttpResponse.json({
          matched: true,
          type: 'OIDC',
          registrationId: 'google',
          displayName: 'Google',
        }),
      ),
    )

    const user = userEvent.setup({ delay: null })
    renderLoginForm()

    await user.type(screen.getByLabelText('이메일'), 'alice@google.com')
    await user.click(screen.getByRole('button', { name: '계속' }))

    await waitFor(() => {
      expect(assignMock).toHaveBeenCalledWith('/oauth2/authorization/google')
    })
  })

  it('registrationId에 특수문자가 있으면 encodeURIComponent가 적용된 경로로 이동한다', async () => {
    const assignMock = vi.fn()
    vi.spyOn(window, 'location', 'get').mockReturnValue({
      ...window.location,
      assign: assignMock,
    } as unknown as Location)

    server.use(
      http.get('/api/v1/auth/route', () =>
        HttpResponse.json({
          matched: true,
          type: 'SAML',
          registrationId: 'corp/ad',
          displayName: 'Corp AD',
        }),
      ),
    )

    const user = userEvent.setup({ delay: null })
    renderLoginForm()

    await user.type(screen.getByLabelText('이메일'), 'alice@corp.com')
    await user.click(screen.getByRole('button', { name: '계속' }))

    await waitFor(() => {
      // encodeURIComponent('corp/ad') = 'corp%2Fad'
      expect(assignMock).toHaveBeenCalledWith('/saml2/authenticate/corp%2Fad')
    })
  })

  it('route 미매칭 → 2단계 폼(provider 드롭다운+username+password) 노출 + 이메일을 username에 프리필', async () => {
    const user = userEvent.setup({ delay: null })
    renderLoginForm()

    await user.type(screen.getByLabelText('이메일'), 'alice@example.com')
    await user.click(screen.getByRole('button', { name: '계속' }))

    // 2단계 UI 대기
    await screen.findByRole('button', { name: '로그인' })

    expect(screen.getByRole('combobox', { name: '로그인 방식' })).toBeInTheDocument()
    expect(screen.getByLabelText('사용자명')).toBeInTheDocument()
    expect(screen.getByLabelText('비밀번호')).toBeInTheDocument()

    // 입력한 이메일이 username 필드에 프리필되어야 한다 (사용자 수정 가능)
    expect(screen.getByLabelText('사용자명')).toHaveValue('alice@example.com')
  })

  it('@가 없는 입력은 route 조회 없이 바로 2단계로 진행된다', async () => {
    const user = userEvent.setup({ delay: null })
    renderLoginForm()

    // route API가 호출되면 실패하도록 — 실제로 호출되지 않아야 한다
    server.use(
      http.get('/api/v1/auth/route', () => HttpResponse.error()),
    )

    await user.type(screen.getByLabelText('이메일'), 'alice')
    await user.click(screen.getByRole('button', { name: '계속' }))

    // route 조회 없이 즉시 2단계 진입 — 에러 없이 로그인 버튼 노출
    await screen.findByRole('button', { name: '로그인' })
    // 'alice'가 username에 프리필
    expect(screen.getByLabelText('사용자명')).toHaveValue('alice')
  })

  it('route 조회 실패(fetch 에러) 시 fail-safe로 2단계를 노출한다', async () => {
    server.use(
      http.get('/api/v1/auth/route', () => HttpResponse.error()),
    )

    const user = userEvent.setup({ delay: null })
    renderLoginForm()

    await user.type(screen.getByLabelText('이메일'), 'alice@error.com')
    await user.click(screen.getByRole('button', { name: '계속' }))

    // 에러여도 2단계 폼이 나타나야 한다 (사용자 막지 않음)
    await screen.findByRole('button', { name: '로그인' })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 2단계 — 폼 로그인 (기존 케이스를 2단계 진입 프리스텝 포함으로 재조정)
// ─────────────────────────────────────────────────────────────────────────────

describe('LoginForm — 2단계 (폼 로그인)', () => {
  it('입력 + 제출 시 useLoginMutation.mutate가 호출된다', async () => {
    const user = userEvent.setup({ delay: null })

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

    await goToStep2(user, 'alice@example.com')
    await waitForProvidersLoaded()
    // username 필드는 이메일로 프리필 — 그대로 두고 비밀번호만 입력
    await user.clear(screen.getByLabelText('사용자명'))
    await user.type(screen.getByLabelText('사용자명'), 'alice')
    await user.type(screen.getByLabelText('비밀번호'), 'password')
    await user.click(screen.getByRole('button', { name: '로그인' }))

    await waitFor(() => expect(onSuccess).toHaveBeenCalledOnce())
    expect(useAuthStore.getState().accessToken).toBe('test-token')
  })

  it('빈 username 제출 시 Zod 검증 에러 메시지가 표시된다', async () => {
    const user = userEvent.setup({ delay: null })
    renderLoginForm()

    await goToStep2(user)
    await waitForProvidersLoaded()
    // username 필드 비우고 비밀번호만 입력
    await user.clear(screen.getByLabelText('사용자명'))
    await user.type(screen.getByLabelText('비밀번호'), 'password')
    await user.click(screen.getByRole('button', { name: '로그인' }))

    await screen.findByText('사용자명을 입력하세요.')
  })

  it('401 invalid_credentials 응답 시 에러 메시지가 표시된다', async () => {
    const user = userEvent.setup({ delay: null })

    server.use(
      http.post('/api/v1/auth/login', () =>
        HttpResponse.json({ error: 'invalid_credentials' }, { status: 401 }),
      ),
    )

    renderLoginForm()

    await goToStep2(user)
    await waitForProvidersLoaded()
    await user.type(screen.getByLabelText('비밀번호'), 'wrong')
    await user.click(screen.getByRole('button', { name: '로그인' }))

    await screen.findByText('사용자명 또는 비밀번호가 올바르지 않습니다.')
  })

  it('login 200 mfa_required:true 응답 시 MFA 코드 입력 화면으로 전환된다', async () => {
    const user = userEvent.setup({ delay: null })

    server.use(
      http.post('/api/v1/auth/login', () =>
        HttpResponse.json({
          mfa_required: true,
          mfa_challenge_token: 'challenge-token-xyz',
          expires_in: 300,
        }),
      ),
    )

    renderLoginForm()

    await goToStep2(user)
    await waitForProvidersLoaded()
    await user.type(screen.getByLabelText('비밀번호'), 'password')
    await user.click(screen.getByRole('button', { name: '로그인' }))

    // MFA 코드 입력 필드가 나타나야 한다
    await screen.findByLabelText('인증 코드')
    // 로그인 폼 필드는 사라져야 한다
    expect(screen.queryByLabelText('비밀번호')).toBeNull()
  })

  it('providers API 응답 기반으로 드롭다운 항목이 동적 렌더된다', async () => {
    const user = userEvent.setup({ delay: null })
    renderLoginForm()

    await goToStep2(user)

    // providers API 응답 후 드롭다운이 렌더될 때까지 대기
    await waitFor(() => {
      const nativeSelect = document.querySelector('select[aria-hidden="true"]')
      const options = Array.from(nativeSelect?.querySelectorAll('option') ?? []).map(
        (o) => o.textContent,
      )
      expect(options).toContain('LDAP-corp')
      expect(options).toContain('Local')
    })
  })

  it('provider 드롭다운 기본 선택값이 providers 응답 첫 항목(ldap)의 id이다', async () => {
    const user = userEvent.setup({ delay: null })
    renderLoginForm()

    await goToStep2(user)

    await waitFor(() => {
      const trigger = screen.getByRole('combobox', { name: '로그인 방식' })
      expect(trigger).toHaveTextContent('LDAP-corp')
    })
  })

  it('providers 응답 항목의 value가 provider.id이다', async () => {
    const user = userEvent.setup({ delay: null })
    renderLoginForm()

    await goToStep2(user)

    await waitFor(() => {
      const nativeSelect = document.querySelector('select[aria-hidden="true"]')
      const options = Array.from(nativeSelect?.querySelectorAll('option') ?? [])
      const values = options.map((o) => (o as HTMLOptionElement).value)
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

    const user = userEvent.setup({ delay: null })
    renderLoginForm()

    await goToStep2(user)
    await screen.findByRole('button', { name: '로그인' })
  })

  it('providers fetch 실패 시 LOCAL fallback 항목이 드롭다운에 표시되고 로그인 제출이 동작한다', async () => {
    const user = userEvent.setup({ delay: null })

    server.use(
      http.get('/api/v1/auth/providers', () =>
        HttpResponse.json({ error: 'server_error' }, { status: 500 }),
      ),
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
          userId: '00000000-0000-4000-8000-000000000001',
          mustChangePassword: false,
          isSystemAdmin: false,
        }),
      ),
    )

    const onSuccess = vi.fn()
    renderLoginForm(onSuccess)

    await goToStep2(user)

    await waitFor(() => {
      const nativeSelect = document.querySelector('select[aria-hidden="true"]')
      const options = Array.from(nativeSelect?.querySelectorAll('option') ?? []).map(
        (o) => o.textContent,
      )
      expect(options).toContain('Local')
    })

    await user.clear(screen.getByLabelText('사용자명'))
    await user.type(screen.getByLabelText('사용자명'), 'alice')
    await user.type(screen.getByLabelText('비밀번호'), 'password')
    await user.click(screen.getByRole('button', { name: '로그인' }))

    await waitFor(() => expect(onSuccess).toHaveBeenCalledOnce())
  })

  it('providers 빈 배열 응답 시 LOCAL fallback 항목이 드롭다운에 표시되고 로그인 제출이 동작한다', async () => {
    const user = userEvent.setup({ delay: null })

    server.use(
      http.get('/api/v1/auth/providers', () => HttpResponse.json({ providers: [] })),
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
          userId: '00000000-0000-4000-8000-000000000001',
          mustChangePassword: false,
          isSystemAdmin: false,
        }),
      ),
    )

    const onSuccess = vi.fn()
    renderLoginForm(onSuccess)

    await goToStep2(user)

    await waitFor(() => {
      const nativeSelect = document.querySelector('select[aria-hidden="true"]')
      const options = Array.from(nativeSelect?.querySelectorAll('option') ?? []).map(
        (o) => o.textContent,
      )
      expect(options).toContain('Local')
    })

    await user.clear(screen.getByLabelText('사용자명'))
    await user.type(screen.getByLabelText('사용자명'), 'alice')
    await user.type(screen.getByLabelText('비밀번호'), 'password')
    await user.click(screen.getByRole('button', { name: '로그인' }))

    await waitFor(() => expect(onSuccess).toHaveBeenCalledOnce())
  })

  it('login mfa_required 응답 후 MFA step에서 "다시 로그인" 클릭 시 이메일 입력 1단계로 복귀한다', async () => {
    const user = userEvent.setup({ delay: null })

    server.use(
      http.post('/api/v1/auth/login', () =>
        HttpResponse.json({
          mfa_required: true,
          mfa_challenge_token: 'challenge-token-xyz',
          expires_in: 300,
        }),
      ),
    )

    renderLoginForm()

    await goToStep2(user)
    await waitForProvidersLoaded()
    await user.type(screen.getByLabelText('비밀번호'), 'password')
    await user.click(screen.getByRole('button', { name: '로그인' }))

    // MFA step 진입 확인
    await screen.findByLabelText('인증 코드')

    // "다시 로그인" 클릭 → 1단계(이메일 입력) 복귀
    await user.click(screen.getByRole('button', { name: '다시 로그인' }))

    await screen.findByLabelText('이메일')
    expect(screen.queryByLabelText('인증 코드')).toBeNull()
  })

  it('키보드 탐색 — label/aria-invalid/aria-describedby 접근성을 충족한다', async () => {
    const user = userEvent.setup({ delay: null })
    renderLoginForm()

    await goToStep2(user)

    const usernameInput = screen.getByLabelText('사용자명')
    const passwordInput = screen.getByLabelText('비밀번호')

    expect(usernameInput).toBeInTheDocument()
    expect(passwordInput).toBeInTheDocument()

    await waitForProvidersLoaded()
    // username을 비우고 빈 폼 제출 — aria-invalid 설정 확인
    await user.clear(usernameInput)
    await user.click(screen.getByRole('button', { name: '로그인' }))

    await waitFor(() => {
      const container = usernameInput.closest('[data-slot="form-control"]')
      expect(container).toHaveAttribute('aria-invalid', 'true')
    })

    const formControl = usernameInput.closest('[data-slot="form-control"]')
    expect(formControl).toHaveAttribute('aria-describedby')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// MFA step — 코드 입력, verify, 에러 처리
// ─────────────────────────────────────────────────────────────────────────────

/** MFA step까지 진입하는 헬퍼 — login API가 mfa_required:true를 반환해야 한다 */
async function goToMfaStep(user: ReturnType<typeof userEvent.setup>) {
  server.use(
    http.post('/api/v1/auth/login', () =>
      HttpResponse.json({
        mfa_required: true,
        mfa_challenge_token: 'challenge-token-xyz',
        expires_in: 300,
      }),
    ),
  )
  await goToStep2(user)
  await waitForProvidersLoaded()
  await user.type(screen.getByLabelText('비밀번호'), 'password')
  await user.click(screen.getByRole('button', { name: '로그인' }))
  await screen.findByLabelText('인증 코드')
}

describe('LoginForm — MFA step (3단계)', () => {
  it('MFA step에서 올바른 코드 입력 후 verify 성공 시 onSuccess 콜백이 호출되고 세션이 저장된다', async () => {
    const user = userEvent.setup({ delay: null })

    server.use(
      http.post('/api/v1/auth/mfa/verify', () =>
        HttpResponse.json({
          access_token: 'mfa-session-token',
          token_type: 'Bearer',
          expires_in: 3600,
        }),
      ),
      http.get('/api/v1/users/me/whoami', () =>
        HttpResponse.json({
          username: 'alice',
          email: 'alice@bts.local',
          authMethod: 'local',
          userId: '00000000-0000-4000-8000-000000000001',
          mustChangePassword: false,
          isSystemAdmin: false,
        }),
      ),
    )

    const onSuccess = vi.fn()
    renderLoginForm(onSuccess)

    await goToMfaStep(user)

    // 6자리 코드 입력
    await user.type(screen.getByLabelText('인증 코드'), '123456')
    await user.click(screen.getByRole('button', { name: '확인' }))

    await waitFor(() => expect(onSuccess).toHaveBeenCalledOnce())
    // 세션이 저장되어야 한다
    expect(useAuthStore.getState().accessToken).toBe('mfa-session-token')
    expect(useAuthStore.getState().user).not.toBeNull()
  })

  it('MFA step — 코드 입력 필드는 inputMode="numeric"이고 maxLength가 6이다', async () => {
    const user = userEvent.setup({ delay: null })
    renderLoginForm()

    await goToMfaStep(user)

    const codeInput = screen.getByLabelText('인증 코드')
    expect(codeInput).toHaveAttribute('inputmode', 'numeric')
    expect(codeInput).toHaveAttribute('maxlength', '6')
  })

  it('MFA step에서 verify 401 invalid_code 응답 시 인라인 에러가 표시되고 코드 입력 필드가 유지된다', async () => {
    const user = userEvent.setup({ delay: null })

    server.use(
      http.post('/api/v1/auth/mfa/verify', () =>
        HttpResponse.json({ error: 'invalid_code' }, { status: 401 }),
      ),
    )

    renderLoginForm()

    await goToMfaStep(user)

    await user.type(screen.getByLabelText('인증 코드'), '000000')
    await user.click(screen.getByRole('button', { name: '확인' }))

    // 인라인 에러 메시지 확인
    await screen.findByText('코드가 올바르지 않습니다.')
    // 코드 입력 필드가 유지되어야 한다 (MFA step 유지)
    expect(screen.getByLabelText('인증 코드')).toBeInTheDocument()
  })

  it('MFA step에서 "다시 로그인" 클릭 시 이메일 입력 1단계로 복귀한다', async () => {
    const user = userEvent.setup({ delay: null })
    renderLoginForm()

    await goToMfaStep(user)

    await user.click(screen.getByRole('button', { name: '다시 로그인' }))

    await screen.findByLabelText('이메일')
    expect(screen.queryByLabelText('인증 코드')).toBeNull()
  })
})
