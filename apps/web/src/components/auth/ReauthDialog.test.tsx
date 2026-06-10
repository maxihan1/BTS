// ReauthDialog 컴포넌트 단위 테스트 — 수단 결정 분기 / step-up 콜백 / 에러 메시지 (FR-AU-08/08b)
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { createElement } from 'react'
import type { ReactNode } from 'react'
import { server } from '@/test/server'
import { ReauthDialog } from './ReauthDialog'

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 래퍼 — QueryClientProvider 필수 (useAccountLinkMutations 내부 사용)
// ─────────────────────────────────────────────────────────────────────────────

function createWrapper(queryClient: QueryClient) {
  return function Wrapper({ children }: { readonly children: ReactNode }) {
    return createElement(QueryClientProvider, { client: queryClient }, children)
  }
}

const STEP_UP_EXPIRES = new Date(Date.now() + 5 * 60 * 1000).toISOString()

const LDAP_PROVIDER_ID = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'

// ─────────────────────────────────────────────────────────────────────────────
// 공통 props 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

interface BuildPropsOptions {
  hasLocalPassword?: boolean
  ldapProviderId?: string
  ldapUsername?: string
  ssoReauthProvider?: { registrationId: string; providerType: 'SAML' | 'OIDC' }
  onStepUpGranted?: (expiresAt: string) => void
  assignLocation?: (url: string) => void
}

function buildProps(opts: BuildPropsOptions = {}) {
  return {
    open: true,
    onOpenChange: vi.fn(),
    hasLocalPassword: opts.hasLocalPassword ?? true,
    ldapProviderId: opts.ldapProviderId,
    ldapUsername: opts.ldapUsername,
    ssoReauthProvider: opts.ssoReauthProvider,
    onStepUpGranted: opts.onStepUpGranted ?? vi.fn(),
    assignLocation: opts.assignLocation ?? vi.fn(),
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 수단 결정 분기
// ─────────────────────────────────────────────────────────────────────────────

describe('ReauthDialog — 수단 결정 분기', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    queryClient = new QueryClient({ defaultOptions: { mutations: { retry: false } } })
  })

  it('hasLocalPassword=true이면 비밀번호 입력 필드를 렌더한다', () => {
    render(
      <ReauthDialog {...buildProps({ hasLocalPassword: true })} />,
      { wrapper: createWrapper(queryClient) },
    )
    expect(screen.getByLabelText('비밀번호')).toBeInTheDocument()
  })

  it('hasLocalPassword=false + ldapProviderId 있으면 LDAP 사용자명/비밀번호 필드를 렌더한다', () => {
    render(
      <ReauthDialog
        {...buildProps({
          hasLocalPassword: false,
          ldapProviderId: LDAP_PROVIDER_ID,
          ldapUsername: 'alice',
        })}
      />,
      { wrapper: createWrapper(queryClient) },
    )
    expect(screen.getByLabelText('사용자명')).toBeInTheDocument()
    expect(screen.getByLabelText('비밀번호')).toBeInTheDocument()
  })

  it('hasLocalPassword=false + ldapProviderId 없음 + ssoReauthProvider 있으면 SSO 버튼을 렌더한다', () => {
    render(
      <ReauthDialog
        {...buildProps({
          hasLocalPassword: false,
          ssoReauthProvider: { registrationId: 'saml-corp', providerType: 'SAML' },
        })}
      />,
      { wrapper: createWrapper(queryClient) },
    )
    expect(screen.getByRole('button', { name: 'SSO로 재인증' })).toBeInTheDocument()
  })

  it('모달 제목(재인증 필요)을 렌더한다', () => {
    render(
      <ReauthDialog {...buildProps()} />,
      { wrapper: createWrapper(queryClient) },
    )
    expect(screen.getByText('재인증 필요')).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// LOCAL 재인증 흐름
// ─────────────────────────────────────────────────────────────────────────────

describe('ReauthDialog — LOCAL 재인증', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    queryClient = new QueryClient({ defaultOptions: { mutations: { retry: false } } })
  })

  it('비밀번호 입력 후 제출하면 reauth(LOCAL)를 호출하고 onStepUpGranted를 실행한다', async () => {
    server.use(
      http.post('/api/v1/auth/account/reauth', () =>
        HttpResponse.json({ stepUpExpiresAt: STEP_UP_EXPIRES }),
      ),
    )

    const onStepUpGranted = vi.fn()
    render(
      <ReauthDialog
        {...buildProps({ hasLocalPassword: true, onStepUpGranted })}
      />,
      { wrapper: createWrapper(queryClient) },
    )

    fireEvent.change(screen.getByLabelText('비밀번호'), { target: { value: 'mypassword' } })
    fireEvent.click(screen.getByRole('button', { name: '확인' }))

    await waitFor(() => expect(onStepUpGranted).toHaveBeenCalledWith(STEP_UP_EXPIRES))
  })

  it('제출 성공 후 비밀번호 필드가 초기화된다', async () => {
    server.use(
      http.post('/api/v1/auth/account/reauth', () =>
        HttpResponse.json({ stepUpExpiresAt: STEP_UP_EXPIRES }),
      ),
    )

    render(
      <ReauthDialog {...buildProps({ hasLocalPassword: true })} />,
      { wrapper: createWrapper(queryClient) },
    )

    const passwordInput = screen.getByLabelText('비밀번호')
    fireEvent.change(passwordInput, { target: { value: 'mypassword' } })
    fireEvent.click(screen.getByRole('button', { name: '확인' }))

    await waitFor(() => expect((passwordInput as HTMLInputElement).value).toBe(''))
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// LDAP 재인증 흐름
// ─────────────────────────────────────────────────────────────────────────────

describe('ReauthDialog — LDAP 재인증', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    queryClient = new QueryClient({ defaultOptions: { mutations: { retry: false } } })
  })

  it('LDAP 폼 제출 시 reauth(LDAP) + ldapProviderId를 포함해 호출하고 onStepUpGranted를 실행한다', async () => {
    let capturedBody: unknown
    server.use(
      http.post('/api/v1/auth/account/reauth', async ({ request }) => {
        capturedBody = await request.json()
        return HttpResponse.json({ stepUpExpiresAt: STEP_UP_EXPIRES })
      }),
    )

    const onStepUpGranted = vi.fn()
    render(
      <ReauthDialog
        {...buildProps({
          hasLocalPassword: false,
          ldapProviderId: LDAP_PROVIDER_ID,
          ldapUsername: 'alice',
          onStepUpGranted,
        })}
      />,
      { wrapper: createWrapper(queryClient) },
    )

    fireEvent.change(screen.getByLabelText('사용자명'), { target: { value: 'alice' } })
    fireEvent.change(screen.getByLabelText('비밀번호'), { target: { value: 'ldappass' } })
    fireEvent.click(screen.getByRole('button', { name: '확인' }))

    await waitFor(() => expect(onStepUpGranted).toHaveBeenCalledWith(STEP_UP_EXPIRES))
    expect((capturedBody as { method: string }).method).toBe('LDAP')
    expect((capturedBody as { providerId: string }).providerId).toBe(LDAP_PROVIDER_ID)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// SSO 재인증 흐름
// ─────────────────────────────────────────────────────────────────────────────

describe('ReauthDialog — SSO 재인증', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    queryClient = new QueryClient({ defaultOptions: { mutations: { retry: false } } })
  })

  it('SSO 버튼 클릭 시 ssoReauthStart를 호출하고 assignLocation을 실행한다', async () => {
    const authorizeUrl = '/saml2/authenticate/saml-corp'
    server.use(
      http.post('/api/v1/auth/account/reauth/sso/start', () =>
        HttpResponse.json({ authorizeUrl }),
      ),
    )

    const assignLocation = vi.fn()
    render(
      <ReauthDialog
        {...buildProps({
          hasLocalPassword: false,
          ssoReauthProvider: { registrationId: 'saml-corp', providerType: 'SAML' },
          assignLocation,
        })}
      />,
      { wrapper: createWrapper(queryClient) },
    )

    fireEvent.click(screen.getByRole('button', { name: 'SSO로 재인증' }))

    await waitFor(() => expect(assignLocation).toHaveBeenCalledWith(authorizeUrl))
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 에러 처리
// ─────────────────────────────────────────────────────────────────────────────

describe('ReauthDialog — 에러 처리', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    queryClient = new QueryClient({ defaultOptions: { mutations: { retry: false } } })
  })

  it('401 reauth_failed → accountLinkErrorMessage("reauth_failed") 메시지를 표시한다', async () => {
    server.use(
      http.post('/api/v1/auth/account/reauth', () =>
        HttpResponse.json(
          { status: 401, errorCode: 'reauth_failed', detail: 'reauth failed' },
          { status: 401 },
        ),
      ),
    )

    render(
      <ReauthDialog {...buildProps({ hasLocalPassword: true })} />,
      { wrapper: createWrapper(queryClient) },
    )

    fireEvent.change(screen.getByLabelText('비밀번호'), { target: { value: 'wrongpass' } })
    fireEvent.click(screen.getByRole('button', { name: '확인' }))

    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent('재인증에 실패했습니다.'),
    )
  })

  it('400 reauth_fields_required → 입력 검증 메시지를 표시한다', async () => {
    server.use(
      http.post('/api/v1/auth/account/reauth', () =>
        HttpResponse.json(
          { status: 400, errorCode: 'reauth_fields_required', detail: 'fields required' },
          { status: 400 },
        ),
      ),
    )

    render(
      <ReauthDialog {...buildProps({ hasLocalPassword: true })} />,
      { wrapper: createWrapper(queryClient) },
    )

    fireEvent.change(screen.getByLabelText('비밀번호'), { target: { value: 'pw' } })
    fireEvent.click(screen.getByRole('button', { name: '확인' }))

    await waitFor(() =>
      expect(screen.getByRole('alert')).toHaveTextContent('입력 정보를 모두 채워 주세요.'),
    )
  })

  it('비밀번호 미입력 상태로 제출하면 API를 호출하지 않는다', async () => {
    let called = false
    server.use(
      http.post('/api/v1/auth/account/reauth', () => {
        called = true
        return HttpResponse.json({ stepUpExpiresAt: STEP_UP_EXPIRES })
      }),
    )

    render(
      <ReauthDialog {...buildProps({ hasLocalPassword: true })} />,
      { wrapper: createWrapper(queryClient) },
    )

    fireEvent.click(screen.getByRole('button', { name: '확인' }))

    // 짧게 대기 후 호출 여부 확인
    await new Promise((r) => setTimeout(r, 50))
    expect(called).toBe(false)
  })
})
