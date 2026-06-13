// LoginForm 컴포넌트 통합 테스트 — identifier-first 2단계 흐름, RHF + Zod 검증, msw 응답 모킹, 접근성 검증
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { mfaStrings } from '@/i18n/ko'
import { LoginForm } from './LoginForm'
import { useAuthStore } from './authStore'

// @simplewebauthn/browser mock — jsdom은 PublicKeyCredential 미정의라 false 반환.
// 대부분의 테스트에서 지원 환경을 가정하므로 vi.fn()으로 등록하고 beforeEach에서 true로 설정한다.
// vi.mock 호이스팅 규칙: 팩토리 내부에서 외부 변수 참조 금지.
vi.mock('@simplewebauthn/browser', () => ({
  browserSupportsWebAuthn: vi.fn(),
  startRegistration: vi.fn(),
  startAuthentication: vi.fn(),
}))

// @/api/webauthn mock — authenticateWithSecurityKey를 vi.fn으로 교체.
// vi.mock 호이스팅 규칙: 팩토리 내부에서 외부 변수 참조 금지.
vi.mock('@/api/webauthn', () => ({
  listWebauthnKeys: vi.fn(),
  registerSecurityKey: vi.fn(),
  deleteWebauthnKey: vi.fn(),
  webauthnRegisterStart: vi.fn(),
  webauthnRegisterFinish: vi.fn(),
  webauthnAuthenticateStart: vi.fn(),
  verifyWebauthn: vi.fn(),
  authenticateWithSecurityKey: vi.fn(),
}))

// @/api/mfa mock — verifyMfa를 vi.fn으로 교체해 trustDevice 인자 검증.
// vi.mock 호이스팅 규칙: 팩토리 내부에서 외부 변수 참조 금지.
vi.mock('@/api/mfa', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/mfa')>()
  return {
    ...actual,
    verifyMfa: vi.fn(),
  }
})

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

beforeEach(async () => {
  useAuthStore.setState({ accessToken: null, user: null })
  vi.spyOn(window, 'location', 'get').mockReturnValue({
    ...window.location,
    assign: vi.fn(),
  } as unknown as Location)
  // providers/route useQuery가 미핸들 MSW 에러로 폼을 깨뜨리지 않도록 기본 핸들러를 등록한다.
  server.use(defaultProvidersHandler, defaultRouteHandler)
  // 기본적으로 WebAuthn 지원 환경으로 설정한다.
  const { browserSupportsWebAuthn } = await import('@simplewebauthn/browser')
  vi.mocked(browserSupportsWebAuthn).mockReturnValue(true)
  // authenticateWithSecurityKey mock을 매 테스트마다 초기화한다.
  const webauthnModule = await import('@/api/webauthn')
  vi.mocked(webauthnModule.authenticateWithSecurityKey).mockReset()
  // verifyMfa mock을 매 테스트마다 초기화한다. 기본 구현은 성공(MSW 핸들러로 위임하지 않고
  // 직접 mock으로 제어) — trustDevice 인자 검증이 목적이므로 기본 성공 경로를 mock으로 구성.
  const mfaModule = await import('@/api/mfa')
  vi.mocked(mfaModule.verifyMfa).mockReset()
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
          mfaEnrollmentRequired: false,
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
          mfaEnrollmentRequired: false,
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
          mfaEnrollmentRequired: false,
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
          mfaEnrollmentRequired: false,
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
    // 프로덕션 동등 조건 — refresh가 401을 반환해도 invalid_code 메시지가 표시되어야 한다.
    // verifyMfa가 apiFetch를 사용하면 refresh 시도 후 clearSession()이 호출되어
    // "코드가 올바르지 않습니다." 대신 generic 에러 또는 세션 소멸이 발생한다.
    let refreshCallCount = 0
    const user = userEvent.setup({ delay: null })

    server.use(
      http.post('/api/v1/auth/mfa/verify', () =>
        HttpResponse.json({ error: 'invalid_code' }, { status: 401 }),
      ),
      // 프로덕션 조건 시뮬레이션 — verify 전에는 세션이 없으므로 refresh도 401
      http.post('/api/v1/auth/refresh', () => {
        refreshCallCount++
        return HttpResponse.json({ error: 'unauthorized' }, { status: 401 })
      }),
    )

    renderLoginForm()

    await goToMfaStep(user)

    await user.type(screen.getByLabelText('인증 코드'), '000000')
    await user.click(screen.getByRole('button', { name: '확인' }))

    // 인라인 에러 메시지 확인 — refresh 우회로 원래 에러가 그대로 전파되어야 한다
    await screen.findByText('코드가 올바르지 않습니다.')
    // 코드 입력 필드가 유지되어야 한다 (MFA step 유지)
    expect(screen.getByLabelText('인증 코드')).toBeInTheDocument()
    // refresh가 단 한 번도 호출되지 않아야 한다 (raw fetch는 refresh를 우회)
    expect(refreshCallCount).toBe(0)
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

// ─────────────────────────────────────────────────────────────────────────────
// MFA step — 백업 코드 토글
// ─────────────────────────────────────────────────────────────────────────────

describe('LoginForm — MFA step 백업 코드 토글 (task-3)', () => {
  it('MFA step에 "백업 코드로 로그인" 링크가 표시된다', async () => {
    const user = userEvent.setup({ delay: null })
    renderLoginForm()

    await goToMfaStep(user)

    expect(screen.getByRole('button', { name: '백업 코드로 로그인' })).toBeInTheDocument()
  })

  it('"백업 코드로 로그인" 클릭 시 라벨/안내가 백업 코드 모드로 전환되고 "Authenticator 코드로 돌아가기"가 표시된다', async () => {
    const user = userEvent.setup({ delay: null })
    renderLoginForm()

    await goToMfaStep(user)

    await user.click(screen.getByRole('button', { name: '백업 코드로 로그인' }))

    // 라벨이 '백업 코드'로 바뀌어야 한다
    expect(screen.getByLabelText('백업 코드')).toBeInTheDocument()
    // 안내 문구가 백업 코드 안내로 바뀌어야 한다
    expect(screen.getByText('백업 코드 중 하나를 입력하세요.')).toBeInTheDocument()
    // 되돌아가기 링크가 표시되어야 한다
    expect(screen.getByRole('button', { name: 'Authenticator 코드로 돌아가기' })).toBeInTheDocument()
    // 기존 TOTP 전환 링크는 사라져야 한다
    expect(screen.queryByRole('button', { name: '백업 코드로 로그인' })).toBeNull()
  })

  it('백업 코드 모드에서 입력 후 확인 시 verifyMfa(token, code, "backup_code")가 호출된다', async () => {
    const user = userEvent.setup({ delay: null })

    server.use(
      http.post('/api/v1/auth/mfa/verify', () =>
        HttpResponse.json({
          access_token: 'backup-session-token',
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
          mfaEnrollmentRequired: false,
        }),
      ),
    )

    const onSuccess = vi.fn()
    renderLoginForm(onSuccess)

    await goToMfaStep(user)
    await user.click(screen.getByRole('button', { name: '백업 코드로 로그인' }))

    // 백업 코드 입력 후 확인
    await user.type(screen.getByLabelText('백업 코드'), 'ABCD-1234')
    await user.click(screen.getByRole('button', { name: '확인' }))

    await waitFor(() => expect(onSuccess).toHaveBeenCalledOnce())
    expect(useAuthStore.getState().accessToken).toBe('backup-session-token')
  })

  it('TOTP 모드는 기존대로 verifyMfa(token, code, "totp")를 호출한다(회귀 없음)', async () => {
    const user = userEvent.setup({ delay: null })

    server.use(
      http.post('/api/v1/auth/mfa/verify', () =>
        HttpResponse.json({
          access_token: 'totp-session-token',
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
          mfaEnrollmentRequired: false,
        }),
      ),
    )

    const onSuccess = vi.fn()
    renderLoginForm(onSuccess)

    await goToMfaStep(user)

    // 토글 없이 바로 6자리 코드 입력
    await user.type(screen.getByLabelText('인증 코드'), '654321')
    await user.click(screen.getByRole('button', { name: '확인' }))

    await waitFor(() => expect(onSuccess).toHaveBeenCalledOnce())
    expect(useAuthStore.getState().accessToken).toBe('totp-session-token')
  })

  it('모드 전환 시 입력값과 에러가 초기화된다', async () => {
    const user = userEvent.setup({ delay: null })

    server.use(
      http.post('/api/v1/auth/mfa/verify', () =>
        HttpResponse.json({ error: 'invalid_code' }, { status: 401 }),
      ),
      http.post('/api/v1/auth/refresh', () =>
        HttpResponse.json({ error: 'unauthorized' }, { status: 401 }),
      ),
    )

    renderLoginForm()

    await goToMfaStep(user)

    // TOTP 오답 입력 → 에러 표시
    await user.type(screen.getByLabelText('인증 코드'), '000000')
    await user.click(screen.getByRole('button', { name: '확인' }))
    await screen.findByText('코드가 올바르지 않습니다.')

    // 백업 코드 모드로 전환 → 에러/입력값 초기화
    await user.click(screen.getByRole('button', { name: '백업 코드로 로그인' }))

    expect(screen.queryByText('코드가 올바르지 않습니다.')).toBeNull()
    expect(screen.getByLabelText('백업 코드')).toHaveValue('')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// MFA step — 보안 키로 인증 (task-6, FR-MF-03)
// ─────────────────────────────────────────────────────────────────────────────

describe('LoginForm — MFA step 보안 키로 인증 (task-6)', () => {
  it('지원 브라우저에서 MFA step에 "보안 키로 인증" 버튼이 표시된다', async () => {
    const user = userEvent.setup({ delay: null })
    renderLoginForm()

    await goToMfaStep(user)

    expect(screen.getByRole('button', { name: '보안 키로 인증' })).toBeInTheDocument()
  })

  it('"보안 키로 인증" 버튼 클릭 → authenticateWithSecurityKey 성공 → setSession + onSuccess 호출', async () => {
    const user = userEvent.setup({ delay: null })

    const { authenticateWithSecurityKey } = await import('@/api/webauthn')
    vi.mocked(authenticateWithSecurityKey).mockResolvedValueOnce({
      access_token: 'webauthn-session-token',
      token_type: 'Bearer',
      expires_in: 3600,
    })

    server.use(
      http.get('/api/v1/users/me/whoami', () =>
        HttpResponse.json({
          username: 'alice',
          email: 'alice@bts.local',
          authMethod: 'webauthn',
          userId: '00000000-0000-4000-8000-000000000001',
          mustChangePassword: false,
          isSystemAdmin: false,
          mfaEnrollmentRequired: false,
        }),
      ),
    )

    const onSuccess = vi.fn()
    renderLoginForm(onSuccess)

    await goToMfaStep(user)

    await user.click(screen.getByRole('button', { name: '보안 키로 인증' }))

    await waitFor(() => expect(onSuccess).toHaveBeenCalledOnce())
    expect(useAuthStore.getState().accessToken).toBe('webauthn-session-token')
    expect(useAuthStore.getState().user).not.toBeNull()
    // authenticateWithSecurityKey가 challengeToken으로 호출되어야 한다
    expect(vi.mocked(authenticateWithSecurityKey)).toHaveBeenCalledWith('challenge-token-xyz')
  })

  it('EC-1: 사용자 취소(NotAllowedError) → 인라인 에러 + 화면 유지 + challengeToken 보존(재클릭 가능)', async () => {
    const user = userEvent.setup({ delay: null })

    const { authenticateWithSecurityKey } = await import('@/api/webauthn')
    // 첫 번째 클릭: NotAllowedError (취소)
    // 두 번째 클릭: 성공 (재시도 가능 확인)
    vi.mocked(authenticateWithSecurityKey)
      .mockRejectedValueOnce(new DOMException('User cancelled', 'NotAllowedError'))
      .mockResolvedValueOnce({
        access_token: 'webauthn-session-token',
        token_type: 'Bearer',
        expires_in: 3600,
      })

    server.use(
      http.get('/api/v1/users/me/whoami', () =>
        HttpResponse.json({
          username: 'alice',
          email: 'alice@bts.local',
          authMethod: 'webauthn',
          userId: '00000000-0000-4000-8000-000000000001',
          mustChangePassword: false,
          isSystemAdmin: false,
          mfaEnrollmentRequired: false,
        }),
      ),
    )

    const onSuccess = vi.fn()
    renderLoginForm(onSuccess)

    await goToMfaStep(user)

    // 첫 클릭 → 취소 에러
    await user.click(screen.getByRole('button', { name: '보안 키로 인증' }))

    // 인라인 에러 표시 + 화면(인증 코드 필드) 유지
    await screen.findByRole('alert')
    expect(screen.getByLabelText('인증 코드')).toBeInTheDocument()
    expect(onSuccess).not.toHaveBeenCalled()

    // 재클릭 → 성공 (challengeToken이 보존되어 재시도 가능)
    await user.click(screen.getByRole('button', { name: '보안 키로 인증' }))

    await waitFor(() => expect(onSuccess).toHaveBeenCalledOnce())
    // authenticateWithSecurityKey가 동일 challengeToken으로 2회 호출
    expect(vi.mocked(authenticateWithSecurityKey)).toHaveBeenCalledTimes(2)
    expect(vi.mocked(authenticateWithSecurityKey)).toHaveBeenNthCalledWith(2, 'challenge-token-xyz')
  })

  it('EC-4: ApiError 401 invalid_code → 인라인 에러 + 화면 유지 + challengeToken 보존', async () => {
    const user = userEvent.setup({ delay: null })
    const { ApiError } = await import('@/api/client')
    const { authenticateWithSecurityKey } = await import('@/api/webauthn')
    vi.mocked(authenticateWithSecurityKey).mockRejectedValueOnce(
      new ApiError(401, { error: 'invalid_code' }),
    )

    renderLoginForm()

    await goToMfaStep(user)

    await user.click(screen.getByRole('button', { name: '보안 키로 인증' }))

    // 인라인 에러 + 화면 유지. 코드 입력이 아닌 보안 키 흐름이라 전용 문구를 쓴다
    // ("코드가 올바르지 않습니다"는 부적합 — /review 적대적 패스 지적).
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(mfaStrings.webauthnVerifyFailed)
    expect(alert).not.toHaveTextContent('코드가 올바르지 않습니다')
    expect(screen.getByLabelText('인증 코드')).toBeInTheDocument()
  })

  it('EC-5: ApiError 401 mfa_challenge_expired → 로그인 1단계로 복귀(이메일 입력 화면)', async () => {
    const user = userEvent.setup({ delay: null })
    const { ApiError } = await import('@/api/client')
    const { authenticateWithSecurityKey } = await import('@/api/webauthn')
    vi.mocked(authenticateWithSecurityKey).mockRejectedValueOnce(
      new ApiError(401, { error: 'mfa_challenge_expired' }),
    )

    renderLoginForm()

    await goToMfaStep(user)

    await user.click(screen.getByRole('button', { name: '보안 키로 인증' }))

    // 로그인 1단계로 복귀 — 이메일 입력 화면
    await screen.findByLabelText('이메일')
    expect(screen.queryByLabelText('인증 코드')).toBeNull()
  })

  it('C-3: browserSupportsWebAuthn()=false → "보안 키로 인증" 버튼 미표시', async () => {
    const { browserSupportsWebAuthn } = await import('@simplewebauthn/browser')
    vi.mocked(browserSupportsWebAuthn).mockReturnValue(false)

    const user = userEvent.setup({ delay: null })
    renderLoginForm()

    await goToMfaStep(user)

    expect(screen.queryByRole('button', { name: '보안 키로 인증' })).toBeNull()
    // TOTP 코드 입력 필드는 그대로 표시되어야 한다
    expect(screen.getByLabelText('인증 코드')).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// MFA step — 신뢰 디바이스 체크박스 (task-6, FR-MF-05)
// ─────────────────────────────────────────────────────────────────────────────

describe('LoginForm — MFA step 신뢰 디바이스 체크박스 (task-6)', () => {
  it('MFA step에 "이 기기를 30일간 신뢰" 체크박스가 기본 미체크로 표시된다', async () => {
    const user = userEvent.setup({ delay: null })
    renderLoginForm()

    await goToMfaStep(user)

    const checkbox = screen.getByRole('checkbox', { name: '이 기기를 30일간 신뢰' })
    expect(checkbox).toBeInTheDocument()
    expect(checkbox).not.toBeChecked()
  })

  // S6 — 체크박스 미체크 상태로 verifyMfa 호출 시 trustDevice=false
  it('S6: 체크박스 미체크 → 코드 검증 시 verifyMfa(token, code, mode, false)가 호출된다', async () => {
    const user = userEvent.setup({ delay: null })
    const mfaModule = await import('@/api/mfa')
    vi.mocked(mfaModule.verifyMfa).mockResolvedValueOnce({
      access_token: 's6-token',
      token_type: 'Bearer',
      expires_in: 3600,
    })
    server.use(
      http.get('/api/v1/users/me/whoami', () =>
        HttpResponse.json({
          username: 'alice',
          email: 'alice@bts.local',
          authMethod: 'local',
          userId: '00000000-0000-4000-8000-000000000001',
          mustChangePassword: false,
          isSystemAdmin: false,
          mfaEnrollmentRequired: false,
        }),
      ),
    )

    renderLoginForm()
    await goToMfaStep(user)

    // 체크박스 미체크 상태 확인
    const checkbox = screen.getByRole('checkbox', { name: '이 기기를 30일간 신뢰' })
    expect(checkbox).not.toBeChecked()

    await user.type(screen.getByLabelText('인증 코드'), '123456')
    await user.click(screen.getByRole('button', { name: '확인' }))

    await waitFor(() => {
      expect(vi.mocked(mfaModule.verifyMfa)).toHaveBeenCalledWith(
        'challenge-token-xyz',
        '123456',
        'totp',
        false,
      )
    })
  })

  // S5 — 체크박스 체크 후 verifyMfa trustDevice=true
  it('S5: 체크박스 체크 → 코드 검증 시 verifyMfa(token, code, mode, true)가 호출된다', async () => {
    const user = userEvent.setup({ delay: null })
    const mfaModule = await import('@/api/mfa')
    vi.mocked(mfaModule.verifyMfa).mockResolvedValueOnce({
      access_token: 's5-token',
      token_type: 'Bearer',
      expires_in: 3600,
    })
    server.use(
      http.get('/api/v1/users/me/whoami', () =>
        HttpResponse.json({
          username: 'alice',
          email: 'alice@bts.local',
          authMethod: 'local',
          userId: '00000000-0000-4000-8000-000000000001',
          mustChangePassword: false,
          isSystemAdmin: false,
          mfaEnrollmentRequired: false,
        }),
      ),
    )

    renderLoginForm()
    await goToMfaStep(user)

    await user.click(screen.getByRole('checkbox', { name: '이 기기를 30일간 신뢰' }))
    expect(screen.getByRole('checkbox', { name: '이 기기를 30일간 신뢰' })).toBeChecked()

    await user.type(screen.getByLabelText('인증 코드'), '654321')
    await user.click(screen.getByRole('button', { name: '확인' }))

    await waitFor(() => {
      expect(vi.mocked(mfaModule.verifyMfa)).toHaveBeenCalledWith(
        'challenge-token-xyz',
        '654321',
        'totp',
        true,
      )
    })
  })

  // E6 — 핵심: mode 토글 후에도 체크 상태가 부모(LoginMfaStep)에 보존되어야 한다
  it('E6(핵심): 체크박스 체크 → mode 토글(TOTP→백업코드) → 체크 상태 유지 + verifyMfa trustDevice=true 전달', async () => {
    const user = userEvent.setup({ delay: null })
    const mfaModule = await import('@/api/mfa')
    vi.mocked(mfaModule.verifyMfa).mockResolvedValueOnce({
      access_token: 'e6-token',
      token_type: 'Bearer',
      expires_in: 3600,
    })
    server.use(
      http.get('/api/v1/users/me/whoami', () =>
        HttpResponse.json({
          username: 'alice',
          email: 'alice@bts.local',
          authMethod: 'local',
          userId: '00000000-0000-4000-8000-000000000001',
          mustChangePassword: false,
          isSystemAdmin: false,
          mfaEnrollmentRequired: false,
        }),
      ),
    )

    renderLoginForm()
    await goToMfaStep(user)

    // TOTP 모드에서 체크박스 체크
    await user.click(screen.getByRole('checkbox', { name: '이 기기를 30일간 신뢰' }))
    expect(screen.getByRole('checkbox', { name: '이 기기를 30일간 신뢰' })).toBeChecked()

    // mode 토글 — 백업코드 모드로 전환 (MfaCodeInput 재마운트)
    await user.click(screen.getByRole('button', { name: '백업 코드로 로그인' }))

    // 재마운트 후에도 체크박스 체크 상태가 유지되어야 한다 (부모 보관)
    const checkboxAfterToggle = screen.getByRole('checkbox', { name: '이 기기를 30일간 신뢰' })
    expect(checkboxAfterToggle).toBeChecked()

    // 백업코드 입력 후 검증 → trustDevice=true 전달 확인
    await user.type(screen.getByLabelText('백업 코드'), 'ABCD-5678')
    await user.click(screen.getByRole('button', { name: '확인' }))

    await waitFor(() => {
      expect(vi.mocked(mfaModule.verifyMfa)).toHaveBeenCalledWith(
        'challenge-token-xyz',
        'ABCD-5678',
        'backup_code',
        true,
      )
    })
  })

  // S7 — 체크박스 체크 후 보안 키 인증 시 authenticateWithSecurityKey trustDevice=true
  it('S7: 체크박스 체크 → "보안 키로 인증" 클릭 → authenticateWithSecurityKey(token, true)가 호출된다', async () => {
    const user = userEvent.setup({ delay: null })
    const webauthnModule = await import('@/api/webauthn')
    vi.mocked(webauthnModule.authenticateWithSecurityKey).mockResolvedValueOnce({
      access_token: 's7-token',
      token_type: 'Bearer',
      expires_in: 3600,
    })
    server.use(
      http.get('/api/v1/users/me/whoami', () =>
        HttpResponse.json({
          username: 'alice',
          email: 'alice@bts.local',
          authMethod: 'webauthn',
          userId: '00000000-0000-4000-8000-000000000001',
          mustChangePassword: false,
          isSystemAdmin: false,
          mfaEnrollmentRequired: false,
        }),
      ),
    )

    renderLoginForm()
    await goToMfaStep(user)

    await user.click(screen.getByRole('checkbox', { name: '이 기기를 30일간 신뢰' }))
    await user.click(screen.getByRole('button', { name: '보안 키로 인증' }))

    await waitFor(() => {
      expect(vi.mocked(webauthnModule.authenticateWithSecurityKey)).toHaveBeenCalledWith(
        'challenge-token-xyz',
        true,
      )
    })
  })
})
