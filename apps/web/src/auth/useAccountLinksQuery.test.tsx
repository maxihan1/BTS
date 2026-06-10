// useAccountLinksQuery / useLinkableProvidersQuery 훅 — 계정 연결 목록·연결 가능 공급자 조회 테스트
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { createElement } from 'react'
import type { ReactNode } from 'react'
import { server } from '@/test/server'
import { useAuthStore } from './authStore'
import {
  useAccountLinksQuery,
  ACCOUNT_LINKS_QUERY_KEY,
} from './useAccountLinksQuery'
import { useLinkableProvidersQuery } from './useLinkableProvidersQuery'
import { DEFAULT_LDAP_LINK, DEFAULT_SSO_LINK, DEFAULT_LINKABLE_PROVIDERS } from '@/mocks/account-link-fixtures'

function createWrapper(queryClient: QueryClient) {
  return function Wrapper({ children }: { readonly children: ReactNode }) {
    return createElement(QueryClientProvider, { client: queryClient }, children)
  }
}

describe('useAccountLinksQuery', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })
    useAuthStore.setState({ accessToken: 'test-token', user: null })
  })

  afterEach(() => {
    useAuthStore.setState({ accessToken: null, user: null })
  })

  it('연결이 없으면 links 빈 배열·hasLocalPassword true를 반환한다', async () => {
    server.use(
      http.get('/api/v1/auth/account/links', () =>
        HttpResponse.json({ links: [], hasLocalPassword: true }),
      ),
    )

    const { result } = renderHook(() => useAccountLinksQuery(), {
      wrapper: createWrapper(queryClient),
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(result.current.data?.links).toEqual([])
    expect(result.current.data?.hasLocalPassword).toBe(true)
  })

  it('연결 목록이 있으면 AccountLinksResponse를 반환한다', async () => {
    server.use(
      http.get('/api/v1/auth/account/links', () =>
        HttpResponse.json({
          links: [DEFAULT_LDAP_LINK, DEFAULT_SSO_LINK],
          hasLocalPassword: true,
        }),
      ),
    )

    const { result } = renderHook(() => useAccountLinksQuery(), {
      wrapper: createWrapper(queryClient),
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(result.current.data?.links).toHaveLength(2)
    expect(result.current.data?.links[0]?.id).toBe(DEFAULT_LDAP_LINK.id)
    expect(result.current.data?.links[1]?.providerType).toBe('SAML')
  })

  it('401 응답 시 isError가 true가 된다', async () => {
    server.use(
      http.get('/api/v1/auth/account/links', () =>
        HttpResponse.json({ error: 'unauthorized' }, { status: 401 }),
      ),
    )

    const { result } = renderHook(() => useAccountLinksQuery(), {
      wrapper: createWrapper(queryClient),
    })

    await waitFor(() => expect(result.current.isError).toBe(true))
  })

  it('ACCOUNT_LINKS_QUERY_KEY 상수가 ["account-links"] 배열이다', () => {
    expect(ACCOUNT_LINKS_QUERY_KEY).toEqual(['account-links'])
  })
})

describe('useLinkableProvidersQuery', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })
    useAuthStore.setState({ accessToken: 'test-token', user: null })
  })

  afterEach(() => {
    useAuthStore.setState({ accessToken: null, user: null })
  })

  it('enabled=true(기본값)이면 공급자 목록을 반환한다', async () => {
    server.use(
      http.get('/api/v1/auth/account/linkable-providers', () =>
        HttpResponse.json({ linkable: DEFAULT_LINKABLE_PROVIDERS }),
      ),
    )

    const { result } = renderHook(() => useLinkableProvidersQuery(), {
      wrapper: createWrapper(queryClient),
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(result.current.data).toHaveLength(DEFAULT_LINKABLE_PROVIDERS.length)
    expect(result.current.data?.[0]?.kind).toBe('LDAP')
  })

  it('enabled=false이면 fetch를 수행하지 않는다(isPending 유지)', async () => {
    const { result } = renderHook(() => useLinkableProvidersQuery(false), {
      wrapper: createWrapper(queryClient),
    })

    // enabled=false면 fetch가 트리거되지 않아 pending 상태가 유지됨
    expect(result.current.isPending).toBe(true)
    expect(result.current.fetchStatus).toBe('idle')
  })

  it('SAML·OIDC 공급자도 올바른 kind 값으로 반환한다', async () => {
    server.use(
      http.get('/api/v1/auth/account/linkable-providers', () =>
        HttpResponse.json({ linkable: DEFAULT_LINKABLE_PROVIDERS }),
      ),
    )

    const { result } = renderHook(() => useLinkableProvidersQuery(), {
      wrapper: createWrapper(queryClient),
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    const kinds = result.current.data?.map((p) => p.kind)
    expect(kinds).toContain('SAML')
    expect(kinds).toContain('OIDC')
  })
})
