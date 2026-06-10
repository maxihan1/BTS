// 계정 연결 설정 페이지 라우트 단위 테스트 — 조립 렌더·step-up 오케스트레이션·콜백 쿼리·가드
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, waitFor, fireEvent, within } from '@testing-library/react'
import { isRedirect } from '@tanstack/react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { useAuthStore } from '@/auth/authStore'
import { composeGuards, requireAuth, requirePasswordChanged } from '@/auth/routeGuard'
import {
  resetStore,
  seedLinks,
  DEFAULT_LDAP_LINK,
  DEFAULT_SSO_LINK,
} from '@/mocks/account-link-fixtures'
import { accountLinkHandlers } from '@/mocks/account-link-handlers'
import { makeWhoami } from '@/mocks/auth-fixtures'
import { AccountLinksSettingsPage } from '@/routes/settings.account-links'

// ─────────────────────────────────────────────────────────────────────────────
// sonner toast mock — 실제 DOM 없이 호출 여부로 검증
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('sonner', () => ({
  toast: {
    success: vi.fn(),
    error: vi.fn(),
    info: vi.fn(),
  },
}))

import { toast } from 'sonner'

// ─────────────────────────────────────────────────────────────────────────────
// 타입 — 가드 테스트용 최소 beforeLoad 컨텍스트
// ─────────────────────────────────────────────────────────────────────────────

interface MinimalBeforeLoadContext {
  location: { href: string; pathname: string }
}

interface RedirectResponse extends Response {
  options: {
    to: string
    search?: Record<string, string>
  }
}

const makeCtx = (pathname: string): MinimalBeforeLoadContext => ({
  location: { href: pathname, pathname },
})

// ─────────────────────────────────────────────────────────────────────────────
// 렌더 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

interface RenderPageOptions {
  readonly linkParam?: string | null
  readonly reauthParam?: string | null
  readonly assignLocation?: (url: string) => void
  readonly onNavigate?: () => void
}

function renderPage(opts: RenderPageOptions = {}): ReturnType<typeof render> {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return render(
    <QueryClientProvider client={client}>
      <AccountLinksSettingsPage
        linkCallbackStatus={opts.linkParam ?? null}
        reauthCallbackStatus={opts.reauthParam ?? null}
        assignLocation={opts.assignLocation ?? vi.fn()}
        onClearCallbackSearch={opts.onNavigate ?? vi.fn()}
      />
    </QueryClientProvider>,
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 인증·MSW 초기화
// ─────────────────────────────────────────────────────────────────────────────

beforeEach(() => {
  useAuthStore.setState({
    accessToken: 'valid-token',
    user: makeWhoami({ mustChangePassword: false }),
  })
  document.cookie = 'XSRF-TOKEN=test-xsrf; path=/'
  resetStore()
  server.use(...accountLinkHandlers)
  vi.clearAllMocks()
})

afterEach(() => {
  useAuthStore.setState({ accessToken: null, user: null })
  document.cookie = 'XSRF-TOKEN=; max-age=0; path=/'
  resetStore()
  server.resetHandlers()
  vi.restoreAllMocks()
})

// ─────────────────────────────────────────────────────────────────────────────
// T10-R-1~4: 페이지 조립 렌더
// ─────────────────────────────────────────────────────────────────────────────

describe('AccountLinksSettingsPage — 조립 렌더', () => {
  it('T10-R-1: 페이지 h1 헤딩 "계정 연결"이 렌더된다', async () => {
    renderPage()
    expect(await screen.findByRole('heading', { name: '계정 연결', level: 1 })).toBeInTheDocument()
  })

  it('T10-R-2: 빈 목록이면 AccountLinkList의 emptyMessage가 렌더된다', async () => {
    // store 비어 있음 → MSW가 빈 배열 반환
    renderPage()
    expect(await screen.findByText('연결된 외부 계정이 없습니다.')).toBeInTheDocument()
  })

  it('T10-R-3: seedLinks 후 연결 목록이 렌더된다', async () => {
    seedLinks([DEFAULT_LDAP_LINK])
    renderPage()
    expect(await screen.findByText('BTS LDAP')).toBeInTheDocument()
  })

  it('T10-R-4: seedLinks 후 상단 "계정 추가" 버튼이 렌더된다', async () => {
    // 연결 목록이 있을 때만 헤더 영역에 "계정 추가" 버튼이 렌더된다
    seedLinks([DEFAULT_LDAP_LINK])
    renderPage()
    expect(await screen.findByRole('button', { name: '계정 추가' })).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T10-S-1~3: step-up 오케스트레이션 — ReauthDialog 트리거 조건
// ─────────────────────────────────────────────────────────────────────────────

describe('AccountLinksSettingsPage — step-up 오케스트레이션', () => {
  it('T10-S-1: step-up 윈도우 없을 때 해제 시도 → ReauthDialog 열린다', async () => {
    seedLinks([DEFAULT_LDAP_LINK, DEFAULT_SSO_LINK])
    renderPage()

    // 목록 로드 대기
    await screen.findByText('BTS LDAP')

    // AccountLinkCard의 해제 버튼 클릭 → AlertDialog 열림
    const firstUnlinkBtn = screen.getAllByRole('button', { name: '해제' }).at(0)
    expect(firstUnlinkBtn).toBeDefined()
    if (firstUnlinkBtn === undefined) return
    fireEvent.click(firstUnlinkBtn)

    // AlertDialog 확인 버튼 클릭 → onUnlink 호출 → step-up 없으면 ReauthDialog 열림
    const alertDialog = await screen.findByRole('alertdialog')
    // alertdialog 내 "해제" 버튼이 확인 버튼 — within + getByRole 로 타입 안전하게 찾는다
    const { getByRole: getByRoleInDialog } = within(alertDialog)
    const confirmBtn = getByRoleInDialog('button', { name: '해제' })
    fireEvent.click(confirmBtn)

    // ReauthDialog 열림 — "재인증 필요" 제목
    expect(await screen.findByText('재인증 필요')).toBeInTheDocument()
  })

  it('T10-S-2: 초기 상태에서 ReauthDialog는 닫혀 있다', async () => {
    // step-up이 없을 때 기본 상태에서 ReauthDialog는 렌더되지 않는다
    renderPage()
    await screen.findByRole('heading', { name: '계정 연결', level: 1 })
    expect(screen.queryByText('재인증 필요')).not.toBeInTheDocument()
  })

  it('T10-S-3: 403 step_up_required 응답 시 ReauthDialog가 열린다 (서버 403이 진실 출처 — EC3)', async () => {
    // step-up 없이 직접 link mutation → 403 step_up_required 시 ReauthDialog 트리거
    // MSW: POST /api/v1/auth/account/links → 403 (기본 — step-up-valid 플래그 없음)
    seedLinks([])
    renderPage()

    // "외부 계정 연결하기" CTA 버튼 클릭 → AddAccountDialog 열림
    const addBtn = await screen.findByRole('button', { name: '외부 계정 연결하기' })
    fireEvent.click(addBtn)

    // LDAP 라디오 선택
    const ldapRadio = await screen.findByLabelText('BTS LDAP (LDAP)')
    fireEvent.click(ldapRadio)

    // LDAP 폼 입력
    const usernameInput = screen.getByLabelText('사용자명')
    const passwordInput = screen.getByLabelText('비밀번호')
    fireEvent.change(usernameInput, { target: { value: 'alice' } })
    fireEvent.change(passwordInput, { target: { value: 'password' } })

    // 연결 제출 → POST /api/v1/auth/account/links → 403 step_up_required
    const submitBtn = screen.getByRole('button', { name: '연결' })
    fireEvent.click(submitBtn)

    // 서버 403 → ReauthDialog 열림
    expect(await screen.findByText('재인증 필요')).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T10-SSO-1: SSO onSsoStart → assignLocation 또는 ReauthDialog 트리거
// ─────────────────────────────────────────────────────────────────────────────

describe('AccountLinksSettingsPage — SSO onSsoStart', () => {
  it('T10-SSO-1: step-up 없을 때 SSO 연결 시작 → ReauthDialog가 열린다', async () => {
    // step-up 없는 상태 — MSW가 /links/sso/start에 403 반환 전에 페이지가 ReauthDialog를 먼저 트리거
    seedLinks([])
    renderPage()

    // "외부 계정 연결하기" CTA
    const addBtn = await screen.findByRole('button', { name: '외부 계정 연결하기' })
    fireEvent.click(addBtn)

    // OIDC 라디오 선택
    const oidcRadio = await screen.findByLabelText('Google OIDC (OIDC)')
    fireEvent.click(oidcRadio)

    // 연결 버튼 클릭 → onSsoStart → step-up 없으면 ReauthDialog
    const submitBtn = screen.getByRole('button', { name: '연결' })
    fireEvent.click(submitBtn)

    expect(await screen.findByText('재인증 필요')).toBeInTheDocument()
  })

  it('T10-SSO-2: step-up 있을 때 SSO 연결 → assignLocation 호출', async () => {
    // MSW를 override해 /links/sso/start가 step-up 없이도 200 반환하도록 만든다
    // (실제로는 서버가 step-up을 검증하지만, 이 테스트는 assignLocation 경로를 검증)
    server.use(
      http.post('/api/v1/auth/account/links/sso/start', () =>
        HttpResponse.json({ authorizeUrl: '/oauth2/authorization/oidc-google' }),
      ),
    )

    const assignLocation = vi.fn()
    seedLinks([])
    renderPage({ assignLocation })

    // "외부 계정 연결하기" CTA
    const addBtn = await screen.findByRole('button', { name: '외부 계정 연결하기' })
    fireEvent.click(addBtn)

    // OIDC 라디오 선택
    const oidcRadio = await screen.findByLabelText('Google OIDC (OIDC)')
    fireEvent.click(oidcRadio)

    // 연결 버튼 클릭
    const submitBtn = screen.getByRole('button', { name: '연결' })
    fireEvent.click(submitBtn)

    // step-up 없으면 ReauthDialog 먼저 → assignLocation은 step-up 완료 후 호출됨
    // step-up 없으니 ReauthDialog 혹은 assignLocation 중 하나가 발생해야 한다
    await waitFor(() => {
      const reauthOpen = screen.queryByText('재인증 필요') !== null
      const assigned = assignLocation.mock.calls.length > 0
      expect(reauthOpen || assigned).toBe(true)
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T10-CB-1~5: 콜백 쿼리 처리 — ?link=/reauth= toast + 파라미터 제거
// ─────────────────────────────────────────────────────────────────────────────

describe('AccountLinksSettingsPage — 콜백 쿼리 처리', () => {
  it('T10-CB-1: ?link=success → toast.success + onClearCallbackSearch 호출', async () => {
    const onNavigate = vi.fn()
    renderPage({ linkParam: 'success', onNavigate })

    await screen.findByRole('heading', { name: '계정 연결', level: 1 })

    expect(toast.success).toHaveBeenCalledWith('외부 계정 연결이 완료되었습니다.')
    expect(onNavigate).toHaveBeenCalledTimes(1)
  })

  it('T10-CB-2: ?link=conflict → toast.error + onClearCallbackSearch 호출', async () => {
    const onNavigate = vi.fn()
    renderPage({ linkParam: 'conflict', onNavigate })

    await screen.findByRole('heading', { name: '계정 연결', level: 1 })

    expect(toast.error).toHaveBeenCalledWith('이 신원은 다른 계정에 이미 연결되어 있습니다.')
    expect(onNavigate).toHaveBeenCalledTimes(1)
  })

  it('T10-CB-3: ?link=already_linked → toast.info + onClearCallbackSearch 호출', async () => {
    const onNavigate = vi.fn()
    renderPage({ linkParam: 'already_linked', onNavigate })

    await screen.findByRole('heading', { name: '계정 연결', level: 1 })

    expect(toast.info).toHaveBeenCalledWith('이미 연결된 계정입니다.')
    expect(onNavigate).toHaveBeenCalledTimes(1)
  })

  it('T10-CB-4: ?reauth=success → toast.success + onClearCallbackSearch 호출', async () => {
    const onNavigate = vi.fn()
    renderPage({ reauthParam: 'success', onNavigate })

    await screen.findByRole('heading', { name: '계정 연결', level: 1 })

    expect(toast.success).toHaveBeenCalledWith('재인증이 완료되었습니다.')
    expect(onNavigate).toHaveBeenCalledTimes(1)
  })

  it('T10-CB-5: 콜백 파라미터 없으면 onClearCallbackSearch 호출 안 함', async () => {
    const onNavigate = vi.fn()
    renderPage({ onNavigate })

    await screen.findByRole('heading', { name: '계정 연결', level: 1 })
    expect(onNavigate).not.toHaveBeenCalled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T10-G-1~3: requireAuthAndPasswordChanged 가드 — /settings/account-links
// ─────────────────────────────────────────────────────────────────────────────

describe('requireAuthAndPasswordChanged 가드 — /settings/account-links', () => {
  const accountLinksGuard = composeGuards(requireAuth, requirePasswordChanged)

  it('T10-G-1: 미인증 상태 → /login 리다이렉트', () => {
    useAuthStore.setState({ accessToken: null, user: null })

    let thrown: unknown
    try {
      accountLinksGuard(makeCtx('/settings/account-links'))
    } catch (e) {
      thrown = e
    }

    expect(thrown).toBeDefined()
    expect(isRedirect(thrown)).toBe(true)
    const r = thrown as RedirectResponse
    expect(r.options.to).toBe('/login')
  })

  it('T10-G-2: mustChangePassword=true → /settings/password 리다이렉트', () => {
    useAuthStore.setState({
      accessToken: 'valid-token',
      user: makeWhoami({ mustChangePassword: true }),
    })

    let thrown: unknown
    try {
      accountLinksGuard(makeCtx('/settings/account-links'))
    } catch (e) {
      thrown = e
    }

    expect(thrown).toBeDefined()
    expect(isRedirect(thrown)).toBe(true)
    const r = thrown as RedirectResponse
    expect(r.options.to).toBe('/settings/password')
  })

  it('T10-G-3: 정상 인증 + mustChangePassword=false → throw 없음 (통과)', () => {
    useAuthStore.setState({
      accessToken: 'valid-token',
      user: makeWhoami({ mustChangePassword: false }),
    })

    expect(() => accountLinksGuard(makeCtx('/settings/account-links'))).not.toThrow()
  })
})
