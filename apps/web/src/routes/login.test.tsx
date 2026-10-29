// LoginPage 테스트 — main 랜드마크 · SSO 하나뿐인 설치의 바로 넘기기와 남는 카드(S21 · S22 계정 카드). 폼과 라우팅 우선순위는 LoginDialog.test.tsx 가 소유한다
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { useSsoOnlyEntry } from '@/auth/useSsoOnlyEntry'
import { AUTO_SSO_AT_KEY, isLoggedOut, markLoggedOut, readLastAccount, rememberLogin } from '@/auth/loginMemory'
import { LoginPage } from './login'

const replaceMock = vi.fn()

/** 주소창 쿼리를 정하고 `location.replace` 를 관측 가능한 스텁으로 바꾼다 */
function setLocation(search: string) {
  vi.spyOn(window, 'location', 'get').mockReturnValue({
    ...window.location,
    pathname: '/login',
    search,
    replace: replaceMock,
  } as unknown as Location)
}

/** 같은 판정을 화면에 적는 탐침 — 「판정이 끝났는데도 안 넘겼다」를 보려면 끝난 시점이 필요하다 */
const Probe = () => {
  const entry = useSsoOnlyEntry()
  return <p data-testid="probe">{entry === undefined ? '판정 중' : entry === null ? 'dialog' : 'sso'}</p>
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={client}>
      <LoginPage />
      <Probe />
    </QueryClientProvider>,
  )
}

/** 판정이 끝나 화면이 그 결과로 다시 그려질 때까지 기다린다 */
async function waitForVerdict(expected: 'dialog' | 'sso') {
  await vi.waitFor(() => expect(screen.getByTestId('probe')).toHaveTextContent(expected))
}

/** 비밀번호 방식 0 + OIDC(auth) 하나 — 운영 BTS 의 설치 모양 */
function useSsoOnlyInstall() {
  server.use(
    http.get('/api/v1/auth/providers', () => HttpResponse.json({ providers: [] })),
    http.get('/api/v1/auth/saml/idps', () => HttpResponse.json({ idps: [] })),
    http.get('/api/v1/auth/oidc/providers', () =>
      HttpResponse.json({ providers: [{ registrationId: 'auth', displayName: 'QA Hub 통합계정' }] }),
    ),
  )
}

beforeEach(() => {
  replaceMock.mockClear()
  window.sessionStorage.clear()
  window.localStorage.clear()
  setLocation('')
  // 테스트 서버에는 로그인 수단 기본 핸들러가 없다 — 비밀번호(Local)만 있는 설치를 기본으로 둔다
  server.use(
    http.get('/api/v1/auth/providers', () =>
      HttpResponse.json({
        providers: [{ id: 'local', type: 'LOCAL', displayName: 'Local', priority: 0, available: true }],
      }),
    ),
    http.get('/api/v1/auth/saml/idps', () => HttpResponse.json({ idps: [] })),
    http.get('/api/v1/auth/oidc/providers', () => HttpResponse.json({ providers: [] })),
  )
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('LoginPage — main 랜드마크 (C3, _shell 밖이라 자체 main 필요)', () => {
  it('main 랜드마크가 정확히 1개 존재한다', () => {
    renderPage()

    expect(screen.getAllByRole('main')).toHaveLength(1)
  })

  it('비밀번호 방식이 있으면 배경만 렌더하고 로그인 폼을 소유하지 않는다', async () => {
    // 폼은 RootLayout 에 마운트된 전역 LoginDialog 가 그린다. 이 페이지가 폼을 또 렌더하면
    // 같은 이름의 필드가 둘이 되어 e2e strict mode 가 깨진다.
    renderPage()

    await waitForVerdict('dialog')
    expect(screen.queryByLabelText('비밀번호')).toBeNull()
    expect(screen.queryByRole('button', { name: '로그인' })).toBeNull()
    expect(replaceMock).not.toHaveBeenCalled()
  })
})

describe('LoginPage — SSO 하나뿐인 설치 (S21)', () => {
  it('표시가 없으면 화면 없이 그 SSO 로 바로 넘긴다', async () => {
    useSsoOnlyInstall()

    renderPage()

    await vi.waitFor(() => expect(replaceMock).toHaveBeenCalledWith('/oauth2/authorization/auth'))
    expect(screen.queryByRole('button')).toBeNull()
    // 고리 차단의 「쓰기」 절반 — 이 시각이 없으면 다음 방문의 고리 판정이 늘 비어 있다
    expect(Number(window.sessionStorage.getItem(AUTO_SSO_AT_KEY))).toBeGreaterThan(Date.now() - 5_000)
  })

  it('로그아웃 직후(?logged_out)는 넘기지 않고 카드를 보인다 — 넘기면 Auth 세션으로 곧장 다시 로그인된다', async () => {
    useSsoOnlyInstall()
    setLocation('?logged_out=true')
    // 방금 넘긴 시각이 있어도 로그아웃 표시가 먼저다 — 오류 카드가 아니라 로그아웃 카드
    window.sessionStorage.setItem(AUTO_SSO_AT_KEY, String(Date.now() - 1_000))

    renderPage()

    expect(await screen.findByRole('heading', { name: '로그아웃했습니다' })).toBeInTheDocument()
    expect(screen.getByText('BTS')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).toBeNull()
    expect(replaceMock).not.toHaveBeenCalled()

    // 기억한 계정이 없으면 SSO 버튼 하나 — 누르면 기록을 남기지 않고 간다
    await userEvent.click(screen.getByRole('button', { name: 'QA Hub 통합계정으로 로그인' }))
    expect(replaceMock).toHaveBeenCalledWith('/oauth2/authorization/auth')
  })

  it('SSO 실패(?error)로 돌아오면 넘기지 않고 오류 카드를 보인다 — 고리 금지', async () => {
    useSsoOnlyInstall()
    setLocation('?error')

    renderPage()

    expect(await screen.findByRole('heading', { name: '로그인하지 못했습니다' })).toBeInTheDocument()
    expect(screen.getByRole('alert')).toHaveTextContent('통합계정 로그인을 마치지 못했습니다')
    expect(screen.getByRole('button', { name: 'QA Hub 통합계정으로 로그인' })).toBeInTheDocument()
    expect(replaceMock).not.toHaveBeenCalled()
  })

  it('방금 바로 넘겼는데 또 오면 다시 넘기지 않는다 — Auth 는 통과했는데 세션을 못 이어받은 고리', async () => {
    useSsoOnlyInstall()
    window.sessionStorage.setItem(AUTO_SSO_AT_KEY, String(Date.now() - 1_000))

    renderPage()

    expect(await screen.findByRole('heading', { name: '로그인하지 못했습니다' })).toBeInTheDocument()
    expect(replaceMock).not.toHaveBeenCalled()
  })

  it('넘긴 지 오래됐으면 다시 바로 넘긴다 (대조군)', async () => {
    useSsoOnlyInstall()
    window.sessionStorage.setItem(AUTO_SSO_AT_KEY, String(Date.now() - 60_000))

    renderPage()

    await vi.waitFor(() => expect(replaceMock).toHaveBeenCalledWith('/oauth2/authorization/auth'))
  })

  it('SSO 가 둘이면 바로 넘기지 않는다 — 어느 쪽인지 사용자가 고른다', async () => {
    useSsoOnlyInstall()
    server.use(
      http.get('/api/v1/auth/saml/idps', () =>
        HttpResponse.json({ idps: [{ registrationId: 'okta', displayName: 'Okta SSO' }] }),
      ),
    )

    renderPage()

    // 판정이 끝나도 배경만 남는다 — 폼은 LoginDialog 몫
    await waitForVerdict('dialog')
    expect(replaceMock).not.toHaveBeenCalled()
    expect(screen.queryByRole('button')).toBeNull()
  })

  it('returnTo 를 SSO 진입 주소에 실어 보낸다 — 로그인 뒤 보던 화면으로 돌아온다', async () => {
    useSsoOnlyInstall()
    setLocation('?returnTo=%2Fprojects%2FPROJ%2Fboard')

    renderPage()

    await vi.waitFor(() =>
      expect(replaceMock).toHaveBeenCalledWith('/oauth2/authorization/auth?returnTo=%2Fprojects%2FPROJ%2Fboard'),
    )
  })
})

describe('LoginPage — 로그아웃 표시 · 계정 카드 (S22)', () => {
  it('브라우저의 로그아웃 표시만 있어도(새 탭 · 북마크) 넘기지 않고 카드를 보인다', async () => {
    useSsoOnlyInstall()
    markLoggedOut()

    renderPage()

    expect(await screen.findByRole('heading', { name: '로그아웃했습니다' })).toBeInTheDocument()
    expect(replaceMock).not.toHaveBeenCalled()
  })

  it('기억한 계정이 있으면 「이 계정으로 로그인」이 login_hint 로 가고 로그아웃 표시를 지운다', async () => {
    useSsoOnlyInstall()
    markLoggedOut()
    rememberLogin({ username: 'maxi', name: '한맥시' })
    // rememberLogin 이 표시를 지우므로 로그아웃 직후 상태를 다시 만든다
    markLoggedOut()

    renderPage()

    const continueButton = await screen.findByRole('button', { name: /이 계정으로 로그인/ })
    expect(continueButton).toHaveTextContent('한맥시 · maxi')
    expect(screen.queryByRole('button', { name: 'QA Hub 통합계정으로 로그인' })).toBeNull()

    await userEvent.click(continueButton)

    expect(replaceMock).toHaveBeenCalledWith('/oauth2/authorization/auth?login_hint=maxi')
    expect(isLoggedOut()).toBe(false)
    expect(readLastAccount()).toEqual({ username: 'maxi', name: '한맥시' })
  })

  it('새 탭 deep link(?returnTo) 의 카드도 돌아갈 곳을 싣는다 — 「이 계정으로」 뒤 보던 화면', async () => {
    useSsoOnlyInstall()
    rememberLogin({ username: 'maxi', name: '한맥시' })
    markLoggedOut()
    setLocation('?returnTo=%2Fprojects%2FPROJ%2Fboard')

    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: /이 계정으로 로그인/ }))

    expect(replaceMock).toHaveBeenCalledWith(
      '/oauth2/authorization/auth?login_hint=maxi&returnTo=%2Fprojects%2FPROJ%2Fboard',
    )
  })

  it('「다른 계정으로 로그인」은 prompt=login 으로 가고 기억한 계정을 지운다', async () => {
    useSsoOnlyInstall()
    rememberLogin({ username: 'maxi', name: '한맥시' })
    markLoggedOut()

    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: '다른 계정으로 로그인' }))

    expect(replaceMock).toHaveBeenCalledWith('/oauth2/authorization/auth?prompt=login')
    expect(readLastAccount()).toBeNull()
    expect(isLoggedOut()).toBe(false)
  })

  it('SAML 하나뿐인 설치는 계정을 기억해도 SSO 버튼 하나 — login_hint · prompt 는 OIDC 것이다', async () => {
    useSsoOnlyInstall()
    server.use(
      http.get('/api/v1/auth/saml/idps', () =>
        HttpResponse.json({ idps: [{ registrationId: 'okta', displayName: 'Okta SSO' }] }),
      ),
      http.get('/api/v1/auth/oidc/providers', () => HttpResponse.json({ providers: [] })),
    )
    rememberLogin({ username: 'maxi', name: '한맥시' })
    markLoggedOut()

    renderPage()

    await userEvent.click(await screen.findByRole('button', { name: 'Okta SSO로 로그인' }))
    expect(screen.queryByRole('button', { name: '다른 계정으로 로그인' })).toBeNull()
    expect(replaceMock).toHaveBeenCalledWith('/saml2/authenticate/okta')
  })
})
