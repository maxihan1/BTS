// LoginForm SAML IdP 버튼 영역 단위 테스트 — 동적 렌더, 0개 미노출, 클릭 네비게이션 검증
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

beforeEach(() => {
  useAuthStore.setState({ accessToken: null, user: null })
  vi.spyOn(window, 'location', 'get').mockReturnValue({
    ...window.location,
    assign: vi.fn(),
  } as unknown as Location)
})

afterEach(() => {
  useAuthStore.setState({ accessToken: null, user: null })
  vi.restoreAllMocks()
})

describe('LoginForm — SAML IdP 버튼', () => {
  it('IdP 2개를 조회하면 각각 버튼으로 렌더된다', async () => {
    server.use(
      http.get('/api/v1/auth/saml/idps', () =>
        HttpResponse.json({
          idps: [
            { registrationId: 'okta', displayName: 'Okta SSO' },
            { registrationId: 'azure-ad', displayName: 'Azure AD' },
          ],
        }),
      ),
    )

    renderLoginForm()

    await screen.findByRole('button', { name: 'Okta SSO 로 로그인' })
    await screen.findByRole('button', { name: 'Azure AD 로 로그인' })
  })

  it('IdP가 0개이면 SAML 버튼 영역이 렌더되지 않는다', async () => {
    server.use(
      http.get('/api/v1/auth/saml/idps', () =>
        HttpResponse.json({ idps: [] }),
      ),
    )

    renderLoginForm()

    // 로딩이 끝날 때까지 기다린 뒤(로그인 버튼이 나오면 로딩 완료) SAML 버튼 없음을 확인
    await screen.findByRole('button', { name: '로그인' })
    expect(screen.queryByRole('button', { name: / 로 로그인/ })).toBeNull()
  })

  it('IdP 버튼 클릭 시 window.location.assign으로 SAML 경로로 이동한다', async () => {
    const assignMock = vi.fn()
    vi.spyOn(window, 'location', 'get').mockReturnValue({
      ...window.location,
      assign: assignMock,
    } as unknown as Location)

    server.use(
      http.get('/api/v1/auth/saml/idps', () =>
        HttpResponse.json({
          idps: [{ registrationId: 'okta', displayName: 'Okta SSO' }],
        }),
      ),
    )

    const user = userEvent.setup()
    renderLoginForm()

    const btn = await screen.findByRole('button', { name: 'Okta SSO 로 로그인' })
    await user.click(btn)

    await waitFor(() => {
      expect(assignMock).toHaveBeenCalledWith('/saml2/authenticate/okta')
    })
  })
})
