// LoginForm 컴포넌트 통합 테스트 — 단일 화면 자격 증명 + MFA, RHF + Zod 검증, msw 응답 모킹, 접근성 검증
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { mfaStrings } from '@/i18n/ko'
import { LoginForm } from './LoginForm'
import { LOGIN_PROVIDER_STORAGE_KEY } from './login-provider-preference'
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
 * 자격 증명 폼이 준비되기를 기다리고 식별자를 채우는 헬퍼.
 *
 * 이메일 선입력 1단계가 폐기되면서 username 프리필도 사라졌다. 예전에는 1단계 입력이
 * username 에 프리필돼 후속 테스트가 그것에 의존했으므로, 그 전제를 이 헬퍼가 대신 채운다.
 */
async function fillIdentifier(
  user: ReturnType<typeof userEvent.setup>,
  identifier = 'alice@example.com',
) {
  await screen.findByRole('button', { name: '로그인' })
  await user.clear(screen.getByLabelText('사용자명'))
  await user.type(screen.getByLabelText('사용자명'), identifier)
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

/** 테스트 기본 route 핸들러 — 미매칭 응답 */
const defaultRouteHandler = http.get('/api/v1/auth/route', () =>
  HttpResponse.json({ matched: false }),
)

beforeEach(async () => {
  useAuthStore.setState({ accessToken: null, user: null })
  // 🛑 로그인 방식 기억이 테스트 사이로 새면 기본값 판별식이 앞 테스트의 저장값을 본다.
  window.localStorage.clear()
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
})

afterEach(() => {
  useAuthStore.setState({ accessToken: null, user: null })
  vi.restoreAllMocks()
})

// ─────────────────────────────────────────────────────────────────────────────
// 폼 로그인 — provider + 식별자 + 비밀번호
// ─────────────────────────────────────────────────────────────────────────────

describe('LoginForm — 폼 로그인', () => {
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

    await fillIdentifier(user, 'alice@example.com')
    await waitForProvidersLoaded()
    // username 필드는 이메일로 프리필 — 그대로 두고 비밀번호만 입력
    await user.clear(screen.getByLabelText('사용자명'))
    await user.type(screen.getByLabelText('사용자명'), 'alice')
    await user.type(screen.getByLabelText('비밀번호'), 'password')
    await user.click(screen.getByRole('button', { name: '로그인' }))

    await waitFor(() => expect(onSuccess).toHaveBeenCalledOnce())
    expect(useAuthStore.getState().accessToken).toBe('test-token')
  })

  /**
   * 저장 시점 계약 (Maxi 확정 2026-09-07) — **성공했을 때만** 쓴다.
   *
   * 🛑 「선택을 바꾸면 저장」이 아니다. 그러면 드롭다운을 열어 훑어본 것만으로 기본값이 바뀌고
   *    다음 방문에 성공한 적 없는 방식을 받는다. 아래 세 판별식이 그 둘을 갈라 얼린다.
   *
   * 🛑 **드롭다운을 조작해서 재지 않는다.** Radix Select 는 jsdom 에서 열리지 않는다
   *    (`target.hasPointerCapture is not a function`). 그래서 「제출된 값이 저장된다」는
   *    **providers 목록을 갈아** 기본값 자체를 바꾸는 방식으로 잰다 — 상수 `'local'` 을
   *    쓰는 구현과 실제로 갈린다.
   */
  it('로그인에 성공하면 그 방식을 저장한다 — 다음 방문의 기본값이 된다', async () => {
    const user = userEvent.setup({ delay: null })
    server.use(
      http.post('/api/v1/auth/login', () =>
        HttpResponse.json({ access_token: 'test-token', token_type: 'Bearer', expires_in: 3600 }),
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

    renderLoginForm()
    await fillIdentifier(user, 'alice@example.com')
    await waitForProvidersLoaded()
    await user.clear(screen.getByLabelText('사용자명'))
    await user.type(screen.getByLabelText('사용자명'), 'alice')
    await user.type(screen.getByLabelText('비밀번호'), 'password')
    await user.click(screen.getByRole('button', { name: '로그인' }))

    await waitFor(() =>
      expect(window.localStorage.getItem(LOGIN_PROVIDER_STORAGE_KEY)).toBe('local'),
    )
  })

  it('저장되는 값은 상수가 아니라 **제출한 방식**이다 (local 이 없는 조직)', async () => {
    const user = userEvent.setup({ delay: null })
    server.use(
      http.get('/api/v1/auth/providers', () =>
        HttpResponse.json({
          providers: [{ id: 'ldap', type: 'LDAP', displayName: 'Ldap', priority: 0, available: true }],
        }),
      ),
      http.post('/api/v1/auth/login', () =>
        HttpResponse.json({ access_token: 'test-token', token_type: 'Bearer', expires_in: 3600 }),
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

    renderLoginForm()
    await fillIdentifier(user, 'alice@example.com')
    await waitForProvidersLoaded()
    await user.clear(screen.getByLabelText('사용자명'))
    await user.type(screen.getByLabelText('사용자명'), 'alice')
    await user.type(screen.getByLabelText('비밀번호'), 'password')
    await user.click(screen.getByRole('button', { name: '로그인' }))

    await waitFor(() =>
      expect(window.localStorage.getItem(LOGIN_PROVIDER_STORAGE_KEY)).toBe('ldap'),
    )
  })

  it('제출 전에는 저장하지 않는다 (대조군)', async () => {
    // 마운트·목록 도착만으로 쓰면 「성공한 방식을 기억한다」가 「마지막으로 화면을 열었을 때의
    // 기본값을 기억한다」로 바뀐다. 두 문장은 다르고, 이 대조군이 그 차이를 지킨다.
    const user = userEvent.setup({ delay: null })
    renderLoginForm()
    await fillIdentifier(user, 'alice@example.com')
    await waitForProvidersLoaded()

    // 앵커 — 폼이 실제로 떠 있다(부재 단언만으로는 공허하다)
    expect(screen.getByRole('combobox', { name: '로그인 방식' })).toHaveTextContent('Local')
    expect(window.localStorage.getItem(LOGIN_PROVIDER_STORAGE_KEY)).toBeNull()
  })

  it('빈 username 제출 시 Zod 검증 에러 메시지가 표시된다', async () => {
    const user = userEvent.setup({ delay: null })
    renderLoginForm()

    await fillIdentifier(user)
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

    await fillIdentifier(user)
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

    await fillIdentifier(user)
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

    await fillIdentifier(user)

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

  /**
   * 기본 선택값 계약 (Maxi 확정 2026-09-07).
   *
   * 종전 계약은 「응답 첫 항목(ldap)」이었다. 서버가 `priority` 순으로 주므로 LDAP 이 0 인
   * 이 픽스처에서는 Local 계정 사용자가 **매번** 드롭다운을 바꿔야 했다.
   *
   * 🛑 픽스처가 `local` 을 **첫 항목이 아닌 자리**에 두는 것이 이 판별식의 전제다.
   *    `local` 이 첫 항목이면 새 규칙과 옛 규칙이 같은 답을 내 red 가 되지 않는다.
   */
  it('provider 드롭다운 기본 선택값이 첫 항목이 아니라 `local` 이다 (저장값 없음)', async () => {
    const user = userEvent.setup({ delay: null })
    renderLoginForm()

    await fillIdentifier(user)

    await waitFor(() => {
      const trigger = screen.getByRole('combobox', { name: '로그인 방식' })
      expect(trigger).toHaveTextContent('Local')
    })
    // 대조군 — 옛 기본값이 더는 선택돼 있지 않다
    expect(screen.getByRole('combobox', { name: '로그인 방식' })).not.toHaveTextContent('LDAP-corp')
  })

  it('저장된 방식이 있으면 그것이 기본값이다 — 다음 로그인에 불러온다', async () => {
    window.localStorage.setItem(LOGIN_PROVIDER_STORAGE_KEY, 'ldap')
    const user = userEvent.setup({ delay: null })
    renderLoginForm()

    await fillIdentifier(user)

    await waitFor(() => {
      expect(screen.getByRole('combobox', { name: '로그인 방식' })).toHaveTextContent('LDAP-corp')
    })
  })

  it('저장값이 목록에 없으면 버리고 `local` 로 돌아간다 (자가 치유 · 보안 렌즈 S2)', async () => {
    // localStorage 는 같은 오리진 스크립트가 쓸 수 있고 provider 는 조직 설정에서 사라진다.
    // 대조 없이 채우면 드롭다운이 빈 값으로 뜨고 폼이 서버가 모르는 값을 제출한다.
    window.localStorage.setItem(LOGIN_PROVIDER_STORAGE_KEY, 'no-such-provider')
    const user = userEvent.setup({ delay: null })
    renderLoginForm()

    await fillIdentifier(user)

    await waitFor(() => {
      expect(screen.getByRole('combobox', { name: '로그인 방식' })).toHaveTextContent('Local')
    })
  })

  it('providers 응답 항목의 value가 provider.id이다', async () => {
    const user = userEvent.setup({ delay: null })
    renderLoginForm()

    await fillIdentifier(user)

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

    await fillIdentifier(user)
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

    await fillIdentifier(user)

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

    await fillIdentifier(user)

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

  it('login mfa_required 응답 후 MFA step에서 "다시 로그인" 클릭 시 자격 증명 폼으로 복귀한다', async () => {
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

    await fillIdentifier(user)
    await waitForProvidersLoaded()
    await user.type(screen.getByLabelText('비밀번호'), 'password')
    await user.click(screen.getByRole('button', { name: '로그인' }))

    // MFA step 진입 확인
    await screen.findByLabelText('인증 코드')

    // "다시 로그인" 클릭 → 자격 증명 폼 복귀 (챌린지 토큰 폐기)
    await user.click(screen.getByRole('button', { name: '다시 로그인' }))

    await screen.findByLabelText('비밀번호')
    expect(screen.queryByLabelText('인증 코드')).toBeNull()
  })

  it('키보드 탐색 — label/aria-invalid/aria-describedby 접근성을 충족한다', async () => {
    const user = userEvent.setup({ delay: null })
    renderLoginForm()

    await fillIdentifier(user)

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
  await fillIdentifier(user)
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

  it('MFA step에서 "다시 로그인" 클릭 시 자격 증명 폼으로 복귀한다', async () => {
    const user = userEvent.setup({ delay: null })
    renderLoginForm()

    await goToMfaStep(user)

    await user.click(screen.getByRole('button', { name: '다시 로그인' }))

    await screen.findByLabelText('비밀번호')
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
    // authenticateWithSecurityKey가 challengeToken, trustDevice=false(기본값)으로 호출되어야 한다
    expect(vi.mocked(authenticateWithSecurityKey)).toHaveBeenCalledWith('challenge-token-xyz', false)
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
    // authenticateWithSecurityKey가 동일 challengeToken으로 2회 호출 (trustDevice=false 기본값)
    expect(vi.mocked(authenticateWithSecurityKey)).toHaveBeenCalledTimes(2)
    expect(vi.mocked(authenticateWithSecurityKey)).toHaveBeenNthCalledWith(2, 'challenge-token-xyz', false)
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

  it('EC-5: ApiError 401 mfa_challenge_expired → 자격 증명 폼으로 복귀', async () => {
    const user = userEvent.setup({ delay: null })
    const { ApiError } = await import('@/api/client')
    const { authenticateWithSecurityKey } = await import('@/api/webauthn')
    vi.mocked(authenticateWithSecurityKey).mockRejectedValueOnce(
      new ApiError(401, { error: 'mfa_challenge_expired' }),
    )

    renderLoginForm()

    await goToMfaStep(user)

    await user.click(screen.getByRole('button', { name: '보안 키로 인증' }))

    // 자격 증명 폼으로 복귀 — 챌린지 토큰이 만료됐으므로 처음부터 다시 한다
    await screen.findByLabelText('비밀번호')
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
    const verifySpy = vi.spyOn(mfaModule, 'verifyMfa')
    server.use(
      http.post('/api/v1/auth/mfa/verify', () =>
        HttpResponse.json({
          access_token: 's6-token',
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

    renderLoginForm()
    await goToMfaStep(user)

    // 체크박스 미체크 상태 확인
    const checkbox = screen.getByRole('checkbox', { name: '이 기기를 30일간 신뢰' })
    expect(checkbox).not.toBeChecked()

    await user.type(screen.getByLabelText('인증 코드'), '123456')
    await user.click(screen.getByRole('button', { name: '확인' }))

    await waitFor(() => {
      expect(verifySpy).toHaveBeenCalledWith(
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
    const verifySpy = vi.spyOn(mfaModule, 'verifyMfa')
    server.use(
      http.post('/api/v1/auth/mfa/verify', () =>
        HttpResponse.json({
          access_token: 's5-token',
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

    renderLoginForm()
    await goToMfaStep(user)

    await user.click(screen.getByRole('checkbox', { name: '이 기기를 30일간 신뢰' }))
    expect(screen.getByRole('checkbox', { name: '이 기기를 30일간 신뢰' })).toBeChecked()

    await user.type(screen.getByLabelText('인증 코드'), '654321')
    await user.click(screen.getByRole('button', { name: '확인' }))

    await waitFor(() => {
      expect(verifySpy).toHaveBeenCalledWith(
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
    const verifySpy = vi.spyOn(mfaModule, 'verifyMfa')
    server.use(
      http.post('/api/v1/auth/mfa/verify', () =>
        HttpResponse.json({
          access_token: 'e6-token',
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
      expect(verifySpy).toHaveBeenCalledWith(
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

// ─────────────────────────────────────────────────────────────────────────────
// 단일 화면 폼 — 이메일 선입력 1단계 폐기 (FR-AU-07 deviation)
// ─────────────────────────────────────────────────────────────────────────────

describe('LoginForm — 단일 화면 (이메일 선입력 단계 폐기)', () => {
  it('초기 렌더에 식별자·비밀번호·로그인 버튼이 동시에 보이고 "계속" 버튼은 없다', async () => {
    renderLoginForm()

    // 식별자 필드는 username 하나로 유지한다 — LDAP 식별자는 이메일이 아니라 'alice' 라서
    // 라벨을 '이메일'로 바꾸면 사실이 틀린다(D6).
    expect(screen.getByLabelText('사용자명')).toBeInTheDocument()
    expect(screen.getByLabelText('비밀번호')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: '로그인' })).toBeInTheDocument()

    // 1단계가 사라졌으므로 "계속" 버튼도 이메일 전용 필드도 없어야 한다
    expect(screen.queryByRole('button', { name: '계속' })).toBeNull()
  })

  it('식별자 blur 시 도메인 route 를 조회해 SSO 버튼을 노출한다 — 자동 이동하지 않는다', async () => {
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

    await user.type(screen.getByLabelText('사용자명'), 'alice@okta.com')
    await user.tab()

    expect(await screen.findByRole('button', { name: 'Okta SSO 로 로그인' })).toBeInTheDocument()
    // 트리거가 blur 라는 수동적 이벤트이므로 풀 네비게이션을 걸면 안 된다(C7).
    expect(assignMock).not.toHaveBeenCalled()
  })

  it('SSO 매칭 후에도 로컬 로그인 버튼이 살아 있다 (FR-07 S4 fail-safe)', async () => {
    server.use(
      http.get('/api/v1/auth/route', () =>
        HttpResponse.json({
          matched: true,
          type: 'OIDC',
          registrationId: 'keycloak',
          displayName: 'Keycloak',
        }),
      ),
    )

    const user = userEvent.setup({ delay: null })
    renderLoginForm()

    await user.type(screen.getByLabelText('사용자명'), 'alice@corp.com')
    await user.tab()

    await screen.findByRole('button', { name: 'Keycloak 로 로그인' })
    // 매칭 도메인에 LOCAL/LDAP 계정이 공존할 수 있다 — 끊긴 라우트가 사용자를 막지 않는다.
    expect(screen.getByRole('button', { name: '로그인' })).toBeEnabled()
  })

  it('blur 시점에 이미 입력된 비밀번호가 보존된다', async () => {
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

    await user.type(screen.getByLabelText('비밀번호'), 'secret1234')
    await user.type(screen.getByLabelText('사용자명'), 'alice@okta.com')
    await user.tab()

    await screen.findByRole('button', { name: 'Okta SSO 로 로그인' })
    expect(screen.getByLabelText('비밀번호')).toHaveValue('secret1234')
  })

  it('@ 가 없는 식별자는 route 조회를 하지 않는다 (LDAP 사용자명)', async () => {
    let routeCalls = 0
    server.use(
      http.get('/api/v1/auth/route', () => {
        routeCalls++
        return HttpResponse.json({ matched: false })
      }),
    )

    const user = userEvent.setup({ delay: null })
    renderLoginForm()

    await user.type(screen.getByLabelText('사용자명'), 'alice')
    await user.tab()

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '로그인' })).toBeEnabled()
    })
    expect(routeCalls).toBe(0)
  })

  it('route 조회가 실패해도 로컬 로그인이 막히지 않는다 (fail-safe)', async () => {
    server.use(http.get('/api/v1/auth/route', () => HttpResponse.error()))

    const user = userEvent.setup({ delay: null })
    renderLoginForm()

    await user.type(screen.getByLabelText('사용자명'), 'alice@unreachable.com')
    await user.tab()

    await waitFor(() => {
      expect(screen.getByRole('button', { name: '로그인' })).toBeEnabled()
    })
  })

  it('조회가 한 번 실패해도 같은 도메인을 다시 조회할 수 있다 (dedupe 오염 금지)', async () => {
    // dedupe 키를 조회 **시작 시점**에 세우면, 실패한 도메인이 키에 남아 재조회가
    // 영구 차단된다. 일시적 네트워크 장애 뒤 SSO 버튼이 영영 안 뜨는 회귀다.
    let calls = 0
    server.use(
      http.get('/api/v1/auth/route', () => {
        calls += 1
        if (calls === 1) return HttpResponse.error()
        return HttpResponse.json({
          matched: true,
          type: 'SAML',
          registrationId: 'okta',
          displayName: 'Okta SSO',
        })
      }),
    )

    const user = userEvent.setup({ delay: null })
    renderLoginForm()

    const identifier = screen.getByLabelText('사용자명')
    await user.type(identifier, 'alice@okta.com')
    await user.tab()
    await waitFor(() => {
      expect(calls).toBe(1)
    })

    // 같은 도메인으로 다시 blur — 재조회가 일어나고 이번엔 성공한다
    await user.click(identifier)
    await user.tab()

    expect(await screen.findByRole('button', { name: 'Okta SSO 로 로그인' })).toBeInTheDocument()
  })

  it('식별자에서 @ 를 지우면 SSO 버튼이 사라진다 (stale 매칭 금지)', async () => {
    // `@` 가 없으면 조회를 건너뛰는데, 그때 이전 매칭 결과를 지우지 않으면
    // LDAP 사용자명으로 바꿨는데 이전 도메인의 SSO 버튼이 남는다.
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

    const identifier = screen.getByLabelText('사용자명')
    await user.type(identifier, 'alice@okta.com')
    await user.tab()
    await screen.findByRole('button', { name: 'Okta SSO 로 로그인' })

    await user.clear(identifier)
    await user.type(identifier, 'alice')
    await user.tab()

    await waitFor(() => {
      expect(screen.queryByRole('button', { name: 'Okta SSO 로 로그인' })).toBeNull()
    })
  })
})
