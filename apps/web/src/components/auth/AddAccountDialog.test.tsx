// AddAccountDialog 컴포넌트 단위 테스트 — 피커 렌더 / LDAP 폼 / SSO 콜백 (FR-AU-08/08b D6)
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen, fireEvent, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { createElement } from 'react'
import type { ReactNode } from 'react'
import { server } from '@/test/server'
import { AddAccountDialog } from './AddAccountDialog'
import type { LinkableProvider } from '@/api/account-links'

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 래퍼 — QueryClientProvider 필수 (useLinkableProvidersQuery 내부 사용)
// ─────────────────────────────────────────────────────────────────────────────

function createWrapper(queryClient: QueryClient) {
  return function Wrapper({ children }: { readonly children: ReactNode }) {
    return createElement(QueryClientProvider, { client: queryClient }, children)
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const LDAP_PROVIDER_ID = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'

const THREE_PROVIDERS: LinkableProvider[] = [
  { kind: 'LDAP', providerId: LDAP_PROVIDER_ID, displayName: 'BTS LDAP' },
  { kind: 'SAML', registrationId: 'saml-corp', displayName: 'Corp SAML' },
  { kind: 'OIDC', registrationId: 'oidc-google', displayName: 'Google OIDC' },
]

function mockLinkableProviders(providers: LinkableProvider[]): void {
  server.use(
    http.get('/api/v1/auth/account/linkable-providers', () =>
      HttpResponse.json({ linkable: providers }),
    ),
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 공통 props 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

interface BuildPropsOptions {
  onLink?: (req: { providerId: string; username: string; password: string }) => void
  onSsoStart?: (req: { registrationId: string; providerType: 'SAML' | 'OIDC' }) => void
  isSubmitting?: boolean
}

function buildProps(opts: BuildPropsOptions = {}) {
  return {
    open: true,
    onOpenChange: vi.fn(),
    onLink: opts.onLink ?? vi.fn(),
    onSsoStart: opts.onSsoStart ?? vi.fn(),
    isSubmitting: opts.isSubmitting ?? false,
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 피커 렌더
// ─────────────────────────────────────────────────────────────────────────────

describe('AddAccountDialog — 피커 렌더', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    mockLinkableProviders(THREE_PROVIDERS)
  })

  it('다이얼로그 제목을 렌더한다', async () => {
    render(
      <AddAccountDialog {...buildProps()} />,
      { wrapper: createWrapper(queryClient) },
    )
    expect(screen.getByRole('dialog')).toBeInTheDocument()
    expect(screen.getByText('계정 추가')).toBeInTheDocument()
  })

  it('LDAP provider를 목록에 표시한다', async () => {
    render(
      <AddAccountDialog {...buildProps()} />,
      { wrapper: createWrapper(queryClient) },
    )
    await waitFor(() => expect(screen.getByText('BTS LDAP')).toBeInTheDocument())
  })

  it('SAML provider를 목록에 표시한다', async () => {
    render(
      <AddAccountDialog {...buildProps()} />,
      { wrapper: createWrapper(queryClient) },
    )
    await waitFor(() => expect(screen.getByText('Corp SAML')).toBeInTheDocument())
  })

  it('OIDC provider를 목록에 표시한다', async () => {
    render(
      <AddAccountDialog {...buildProps()} />,
      { wrapper: createWrapper(queryClient) },
    )
    await waitFor(() => expect(screen.getByText('Google OIDC')).toBeInTheDocument())
  })

  it('각 항목에 kind 배지(LDAP/SAML/OIDC)를 표시한다', async () => {
    render(
      <AddAccountDialog {...buildProps()} />,
      { wrapper: createWrapper(queryClient) },
    )
    await waitFor(() => {
      expect(screen.getByText('LDAP')).toBeInTheDocument()
      expect(screen.getByText('SAML')).toBeInTheDocument()
      expect(screen.getByText('OIDC')).toBeInTheDocument()
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// linkable 0건 안내
// ─────────────────────────────────────────────────────────────────────────────

describe('AddAccountDialog — linkable 0건', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    mockLinkableProviders([])
  })

  it('linkable이 없으면 안내 메시지를 표시한다', async () => {
    render(
      <AddAccountDialog {...buildProps()} />,
      { wrapper: createWrapper(queryClient) },
    )
    await waitFor(() =>
      expect(
        screen.getByText('현재 연결 가능한 인증 방식이 없습니다.'),
      ).toBeInTheDocument(),
    )
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// LDAP 폼 흐름
// ─────────────────────────────────────────────────────────────────────────────

describe('AddAccountDialog — LDAP 폼 제출', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    mockLinkableProviders(THREE_PROVIDERS)
  })

  it('LDAP 선택 시 사용자명/비밀번호 입력 폼을 렌더한다', async () => {
    render(
      <AddAccountDialog {...buildProps()} />,
      { wrapper: createWrapper(queryClient) },
    )
    await waitFor(() => expect(screen.getByText('BTS LDAP')).toBeInTheDocument())

    fireEvent.click(screen.getByRole('radio', { name: /BTS LDAP/i }))

    expect(screen.getByLabelText('사용자명')).toBeInTheDocument()
    expect(screen.getByLabelText('비밀번호')).toBeInTheDocument()
  })

  it('LDAP 폼 제출 시 onLink를 정확한 인자로 호출한다', async () => {
    const onLink = vi.fn()
    render(
      <AddAccountDialog {...buildProps({ onLink })} />,
      { wrapper: createWrapper(queryClient) },
    )
    await waitFor(() => expect(screen.getByText('BTS LDAP')).toBeInTheDocument())

    fireEvent.click(screen.getByRole('radio', { name: /BTS LDAP/i }))

    fireEvent.change(screen.getByLabelText('사용자명'), { target: { value: 'alice' } })
    fireEvent.change(screen.getByLabelText('비밀번호'), { target: { value: 'secret' } })
    fireEvent.click(screen.getByRole('button', { name: '연결' }))

    expect(onLink).toHaveBeenCalledWith({
      providerId: LDAP_PROVIDER_ID,
      username: 'alice',
      password: 'secret',
    })
  })

  it('사용자명이 비어있으면 onLink를 호출하지 않는다', async () => {
    const onLink = vi.fn()
    render(
      <AddAccountDialog {...buildProps({ onLink })} />,
      { wrapper: createWrapper(queryClient) },
    )
    await waitFor(() => expect(screen.getByText('BTS LDAP')).toBeInTheDocument())

    fireEvent.click(screen.getByRole('radio', { name: /BTS LDAP/i }))

    fireEvent.change(screen.getByLabelText('비밀번호'), { target: { value: 'secret' } })
    fireEvent.click(screen.getByRole('button', { name: '연결' }))

    expect(onLink).not.toHaveBeenCalled()
  })

  it('비밀번호가 비어있으면 onLink를 호출하지 않는다', async () => {
    const onLink = vi.fn()
    render(
      <AddAccountDialog {...buildProps({ onLink })} />,
      { wrapper: createWrapper(queryClient) },
    )
    await waitFor(() => expect(screen.getByText('BTS LDAP')).toBeInTheDocument())

    fireEvent.click(screen.getByRole('radio', { name: /BTS LDAP/i }))

    fireEvent.change(screen.getByLabelText('사용자명'), { target: { value: 'alice' } })
    fireEvent.click(screen.getByRole('button', { name: '연결' }))

    expect(onLink).not.toHaveBeenCalled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// SSO 선택 흐름
// ─────────────────────────────────────────────────────────────────────────────

describe('AddAccountDialog — SSO 선택', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    mockLinkableProviders(THREE_PROVIDERS)
  })

  it('SAML 선택 시 onSsoStart를 registrationId/providerType 인자로 호출한다', async () => {
    const onSsoStart = vi.fn()
    render(
      <AddAccountDialog {...buildProps({ onSsoStart })} />,
      { wrapper: createWrapper(queryClient) },
    )
    await waitFor(() => expect(screen.getByText('Corp SAML')).toBeInTheDocument())

    fireEvent.click(screen.getByRole('radio', { name: /Corp SAML/i }))
    fireEvent.click(screen.getByRole('button', { name: '연결' }))

    expect(onSsoStart).toHaveBeenCalledWith({
      registrationId: 'saml-corp',
      providerType: 'SAML',
    })
  })

  it('OIDC 선택 시 onSsoStart를 registrationId/providerType 인자로 호출한다', async () => {
    const onSsoStart = vi.fn()
    render(
      <AddAccountDialog {...buildProps({ onSsoStart })} />,
      { wrapper: createWrapper(queryClient) },
    )
    await waitFor(() => expect(screen.getByText('Google OIDC')).toBeInTheDocument())

    fireEvent.click(screen.getByRole('radio', { name: /Google OIDC/i }))
    fireEvent.click(screen.getByRole('button', { name: '연결' }))

    expect(onSsoStart).toHaveBeenCalledWith({
      registrationId: 'oidc-google',
      providerType: 'OIDC',
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// isSubmitting disabled
// ─────────────────────────────────────────────────────────────────────────────

describe('AddAccountDialog — isSubmitting disabled', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    mockLinkableProviders(THREE_PROVIDERS)
  })

  it('isSubmitting=true이면 제출 버튼이 disabled 상태다', async () => {
    render(
      <AddAccountDialog {...buildProps({ isSubmitting: true })} />,
      { wrapper: createWrapper(queryClient) },
    )
    await waitFor(() => expect(screen.getByText('BTS LDAP')).toBeInTheDocument())

    fireEvent.click(screen.getByRole('radio', { name: /BTS LDAP/i }))

    const submitButton = screen.getByRole('button', { name: '연결' })
    expect(submitButton).toBeDisabled()
  })

  it('isSubmitting=true이면 라디오 버튼들이 disabled 상태다', async () => {
    render(
      <AddAccountDialog {...buildProps({ isSubmitting: true })} />,
      { wrapper: createWrapper(queryClient) },
    )
    await waitFor(() => expect(screen.getByText('BTS LDAP')).toBeInTheDocument())

    const radios = screen.getAllByRole('radio')
    for (const radio of radios) {
      expect(radio).toBeDisabled()
    }
  })
})
