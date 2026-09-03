// LoginForm OIDC provider 버튼 영역 단위 테스트 — 동적 렌더, 0개 미노출, 클릭 네비게이션 검증 (2단계 흐름)
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

function renderLoginForm() {
  const Wrapper = createWrapper()
  return render(<LoginForm />, { wrapper: Wrapper })
}

/**
 * 자격 증명 폼이 준비될 때까지 기다리는 헬퍼(단일 화면 전환 이후 단계 진입이 없다).
 * SSO 버튼은 폼 하단에 상시 렌더되므로 별도 진입 동작이 필요 없다.
 */
async function waitForCredentialsForm() {
  // 로그인 버튼이 나타나면 폼 준비 완료
  await screen.findByRole('button', { name: '로그인' })
}

beforeEach(() => {
  useAuthStore.setState({ accessToken: null, user: null })
  vi.spyOn(window, 'location', 'get').mockReturnValue({
    ...window.location,
    assign: vi.fn(),
  } as unknown as Location)
  // providers/route useQuery가 미핸들 MSW 에러로 폼을 깨뜨리지 않도록 기본 핸들러를 등록한다.
  server.use(
    http.get('/api/v1/auth/providers', () =>
      HttpResponse.json({
        providers: [
          { id: 'ldap', type: 'LDAP', displayName: 'Ldap', priority: 0, available: true },
          { id: 'local', type: 'LOCAL', displayName: 'Local', priority: 1, available: true },
        ],
      }),
    ),
    http.get('/api/v1/auth/route', () => HttpResponse.json({ matched: false })),
  )
})

afterEach(() => {
  useAuthStore.setState({ accessToken: null, user: null })
  vi.restoreAllMocks()
})

describe('LoginForm — OIDC provider 버튼', () => {
  it('provider 2개를 조회하면 각각 버튼으로 렌더된다', async () => {
    server.use(
      http.get('/api/v1/auth/oidc/providers', () =>
        HttpResponse.json({
          providers: [
            { registrationId: 'google', displayName: 'Google' },
            { registrationId: 'github', displayName: 'GitHub' },
          ],
        }),
      ),
    )

    renderLoginForm()
    await waitForCredentialsForm()

    await screen.findByRole('button', { name: 'Google 로 로그인' })
    await screen.findByRole('button', { name: 'GitHub 로 로그인' })
  })

  it('provider가 0개이면 OIDC 버튼 영역이 렌더되지 않는다', async () => {
    server.use(
      http.get('/api/v1/auth/oidc/providers', () =>
        HttpResponse.json({ providers: [] }),
      ),
    )

    renderLoginForm()
    await waitForCredentialsForm()

    // 로딩이 끝날 때까지 기다린 뒤(로그인 버튼이 이미 보임) OIDC 버튼 없음을 확인
    await screen.findByRole('button', { name: '로그인' })
    expect(screen.queryByRole('button', { name: / 로 로그인/ })).toBeNull()
  })

  it('provider 버튼 클릭 시 window.location.assign으로 OIDC 경로로 이동한다', async () => {
    const assignMock = vi.fn()
    vi.spyOn(window, 'location', 'get').mockReturnValue({
      ...window.location,
      assign: assignMock,
    } as unknown as Location)

    server.use(
      http.get('/api/v1/auth/oidc/providers', () =>
        HttpResponse.json({
          providers: [{ registrationId: 'google', displayName: 'Google' }],
        }),
      ),
    )

    const user = userEvent.setup({ delay: null })
    renderLoginForm()
    await waitForCredentialsForm()

    const btn = await screen.findByRole('button', { name: 'Google 로 로그인' })
    await user.click(btn)

    await waitFor(() => {
      expect(assignMock).toHaveBeenCalledWith('/oauth2/authorization/google')
    })
  })
})
