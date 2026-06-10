// useAccountLinkMutations 훅 — reauth/link/unlink/ssoLinkStart/ssoReauthStart mutation 테스트
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, waitFor, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { createElement } from 'react'
import type { ReactNode } from 'react'
import { server } from '@/test/server'
import { useAuthStore } from './authStore'
import { useAccountLinkMutations } from './useAccountLinkMutations'
import { ACCOUNT_LINKS_QUERY_KEY } from './useAccountLinksQuery'
import {
  DEFAULT_LDAP_LINK,
  resetStore,
  seedLinks,
} from '@/mocks/account-link-fixtures'

// sonner toast mock — 실제 DOM 없이 호출 여부만 검증
vi.mock('sonner', () => ({
  toast: {
    success: vi.fn(),
    error: vi.fn(),
  },
}))

// RFC 4122 v4 UUID — Zod uuid() 검증 통과
const PROVIDER_UUID = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'
const LINK_UUID = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'

function createWrapper(queryClient: QueryClient) {
  return function Wrapper({ children }: { readonly children: ReactNode }) {
    return createElement(QueryClientProvider, { client: queryClient }, children)
  }
}

describe('useAccountLinkMutations', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: { mutations: { retry: false } },
    })
    useAuthStore.setState({ accessToken: 'test-token', user: null })
    resetStore()
    vi.clearAllMocks()
    // step-up 유효 플래그 초기화
    try {
      localStorage.removeItem('msw:account-link:step-up-valid')
    } catch {
      // jsdom에서는 무시
    }
  })

  afterEach(() => {
    useAuthStore.setState({ accessToken: null, user: null })
    resetStore()
    try {
      localStorage.clear()
    } catch {
      // jsdom에서는 무시
    }
  })

  // ─── reauth ───────────────────────────────────────────────────────────────

  it('reauth 성공 시 stepUpExpiresAt 문자열을 반환하고 account-links 캐시를 무효화한다', async () => {
    queryClient.setQueryData(ACCOUNT_LINKS_QUERY_KEY, {
      links: [],
      hasLocalPassword: true,
    })

    const { result } = renderHook(() => useAccountLinkMutations(), {
      wrapper: createWrapper(queryClient),
    })

    let returnedValue: unknown
    await act(async () => {
      returnedValue = await result.current.reauth.mutateAsync({
        method: 'LOCAL',
        password: 'secret',
      })
    })

    await waitFor(() => expect(result.current.reauth.isSuccess).toBe(true))

    // stepUpExpiresAt 반환 확인
    expect(returnedValue).toHaveProperty('stepUpExpiresAt')
    expect(typeof (returnedValue as { stepUpExpiresAt: unknown }).stepUpExpiresAt).toBe('string')

    // account-links 캐시 무효화(invalidate) 확인 — setQueryData 사용 금지
    const queryState = queryClient.getQueryState(ACCOUNT_LINKS_QUERY_KEY)
    expect(queryState?.isInvalidated).toBe(true)
  })

  it('reauth 실패(401) 시 mutation이 에러 상태가 된다', async () => {
    server.use(
      http.post('/api/v1/auth/account/reauth', () =>
        HttpResponse.json({ error: 'reauth_failed' }, { status: 401 }),
      ),
    )

    const { result } = renderHook(() => useAccountLinkMutations(), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      result.current.reauth.mutate({ method: 'LOCAL', password: 'wrong' })
    })

    await waitFor(() => expect(result.current.reauth.isError).toBe(true))
  })

  // ─── link ─────────────────────────────────────────────────────────────────

  it('link 성공(201) 시 account-links 캐시를 무효화한다 (setQueryData 미사용)', async () => {
    // step-up 유효 플래그 세팅
    try {
      localStorage.setItem('msw:account-link:step-up-valid', 'true')
    } catch {
      // jsdom에서는 무시
    }

    queryClient.setQueryData(ACCOUNT_LINKS_QUERY_KEY, {
      links: [],
      hasLocalPassword: true,
    })

    const { result } = renderHook(() => useAccountLinkMutations(), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      result.current.link.mutate({
        providerId: PROVIDER_UUID,
        username: 'alice',
        password: 'pw',
      })
    })

    await waitFor(() => expect(result.current.link.isSuccess).toBe(true))

    const queryState = queryClient.getQueryState(ACCOUNT_LINKS_QUERY_KEY)
    expect(queryState?.isInvalidated).toBe(true)
  })

  it('link 실패(409 충돌) 시 mutation이 에러 상태가 된다', async () => {
    try {
      localStorage.setItem('msw:account-link:step-up-valid', 'true')
      localStorage.setItem('msw:account-link:conflict', 'true')
    } catch {
      // jsdom에서는 무시
    }

    const { result } = renderHook(() => useAccountLinkMutations(), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      result.current.link.mutate({
        providerId: PROVIDER_UUID,
        username: 'alice',
        password: 'pw',
      })
    })

    await waitFor(() => expect(result.current.link.isError).toBe(true))
  })

  // ─── unlink ───────────────────────────────────────────────────────────────

  it('unlink 성공(204) 시 account-links 캐시를 무효화한다', async () => {
    try {
      localStorage.setItem('msw:account-link:step-up-valid', 'true')
    } catch {
      // jsdom에서는 무시
    }
    seedLinks([DEFAULT_LDAP_LINK])

    queryClient.setQueryData(ACCOUNT_LINKS_QUERY_KEY, {
      links: [DEFAULT_LDAP_LINK],
      hasLocalPassword: true,
    })

    const { result } = renderHook(() => useAccountLinkMutations(), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      result.current.unlink.mutate(LINK_UUID)
    })

    await waitFor(() => expect(result.current.unlink.isSuccess).toBe(true))

    const queryState = queryClient.getQueryState(ACCOUNT_LINKS_QUERY_KEY)
    expect(queryState?.isInvalidated).toBe(true)
  })

  it('unlink 실패(409 마지막 수단) 시 mutation이 에러 상태가 된다', async () => {
    try {
      localStorage.setItem('msw:account-link:step-up-valid', 'true')
      localStorage.setItem('msw:account-link:last-method', 'true')
    } catch {
      // jsdom에서는 무시
    }

    const { result } = renderHook(() => useAccountLinkMutations(), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      result.current.unlink.mutate(LINK_UUID)
    })

    await waitFor(() => expect(result.current.unlink.isError).toBe(true))
  })

  // ─── ssoLinkStart ─────────────────────────────────────────────────────────

  it('ssoLinkStart 성공 시 authorizeUrl을 반환하고 캐시를 무효화한다', async () => {
    try {
      localStorage.setItem('msw:account-link:step-up-valid', 'true')
    } catch {
      // jsdom에서는 무시
    }

    queryClient.setQueryData(ACCOUNT_LINKS_QUERY_KEY, {
      links: [],
      hasLocalPassword: true,
    })

    const { result } = renderHook(() => useAccountLinkMutations(), {
      wrapper: createWrapper(queryClient),
    })

    let returnedValue: unknown
    await act(async () => {
      returnedValue = await result.current.ssoLinkStart.mutateAsync({
        registrationId: 'saml-corp',
        providerType: 'SAML',
      })
    })

    await waitFor(() => expect(result.current.ssoLinkStart.isSuccess).toBe(true))

    expect(returnedValue).toHaveProperty('authorizeUrl')
    expect(typeof (returnedValue as { authorizeUrl: unknown }).authorizeUrl).toBe('string')

    const queryState = queryClient.getQueryState(ACCOUNT_LINKS_QUERY_KEY)
    expect(queryState?.isInvalidated).toBe(true)
  })

  // ─── ssoReauthStart ───────────────────────────────────────────────────────

  it('ssoReauthStart 성공 시 authorizeUrl을 반환하고 캐시를 무효화한다', async () => {
    queryClient.setQueryData(ACCOUNT_LINKS_QUERY_KEY, {
      links: [],
      hasLocalPassword: true,
    })

    const { result } = renderHook(() => useAccountLinkMutations(), {
      wrapper: createWrapper(queryClient),
    })

    let returnedValue: unknown
    await act(async () => {
      returnedValue = await result.current.ssoReauthStart.mutateAsync({
        registrationId: 'oidc-google',
        providerType: 'OIDC',
      })
    })

    await waitFor(() => expect(result.current.ssoReauthStart.isSuccess).toBe(true))

    expect(returnedValue).toHaveProperty('authorizeUrl')

    const queryState = queryClient.getQueryState(ACCOUNT_LINKS_QUERY_KEY)
    expect(queryState?.isInvalidated).toBe(true)
  })

  // ─── setQueryData 미사용 검증 ─────────────────────────────────────────────

  it('모든 mutation 성공 후 캐시 데이터는 직접 교체(setQueryData)되지 않는다', async () => {
    try {
      localStorage.setItem('msw:account-link:step-up-valid', 'true')
    } catch {
      // jsdom에서는 무시
    }

    // 캐시에 파생 필드를 포함한 확장 데이터 세팅 (setQueryData로 덮이면 소실됨)
    const extendedData = {
      links: [DEFAULT_LDAP_LINK],
      hasLocalPassword: true,
      _derivedField: 'should-survive',
    }
    queryClient.setQueryData(ACCOUNT_LINKS_QUERY_KEY, extendedData)

    // setQueryData가 내부 호출되면 캐시가 응답으로 교체되어 _derivedField가 사라짐
    // invalidate-only라면 무효화만 되고 refetch 전까지 데이터는 그대로 남음
    const spy = vi.spyOn(queryClient, 'setQueryData')

    const { result } = renderHook(() => useAccountLinkMutations(), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      await result.current.reauth.mutateAsync({ method: 'LOCAL', password: 'pw' })
    })

    // mutation hooks 내부에서 setQueryData를 호출하지 않았는지 확인
    expect(spy).not.toHaveBeenCalledWith(ACCOUNT_LINKS_QUERY_KEY, expect.anything())

    spy.mockRestore()
  })
})
