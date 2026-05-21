// useLoginMutation 훅 — login + whoami 연쇄 호출 및 authStore 세션 저장 테스트
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { useLoginMutation } from './useLoginMutation'
import { useAuthStore } from './authStore'

// QueryClient 래퍼 — 테스트마다 독립된 캐시 보장
function createWrapper() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return ({ children }: { children: React.ReactNode }) => (
    <QueryClientProvider client={client}>{children}</QueryClientProvider>
  )
}

beforeEach(() => {
  useAuthStore.setState({ accessToken: null, user: null })
})

afterEach(() => {
  useAuthStore.setState({ accessToken: null, user: null })
})

describe('useLoginMutation', () => {
  it('성공 시 login → whoami 순서로 호출하고 authStore에 accessToken + user를 저장한다', async () => {
    server.use(
      http.post('/api/v1/auth/login', () =>
        HttpResponse.json({
          access_token: 'test-access-token',
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
        }),
      ),
    )

    const onSuccess = vi.fn()
    const { result } = renderHook(() => useLoginMutation(), { wrapper: createWrapper() })

    result.current.mutate(
      { provider: 'local', username: 'alice', password: 'password' },
      { onSuccess },
    )

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    // accessToken 저장 확인
    expect(useAuthStore.getState().accessToken).toBe('test-access-token')
    // user 저장 확인
    expect(useAuthStore.getState().user).toEqual({
      username: 'alice',
      email: 'alice@bts.local',
      authMethod: 'local',
      userId: 'u1',
    })
    // onSuccess 콜백 호출 확인
    expect(onSuccess).toHaveBeenCalledOnce()
  })

  it('invalid_credentials (401) 시 authStore를 변경하지 않고 onError를 호출한다', async () => {
    server.use(
      http.post('/api/v1/auth/login', () =>
        HttpResponse.json({ error: 'invalid_credentials' }, { status: 401 }),
      ),
    )

    const onError = vi.fn()
    const { result } = renderHook(() => useLoginMutation(), { wrapper: createWrapper() })

    result.current.mutate(
      { provider: 'local', username: 'alice', password: 'wrong' },
      { onError },
    )

    await waitFor(() => expect(result.current.isError).toBe(true))

    // authStore 미변경 확인
    expect(useAuthStore.getState().accessToken).toBeNull()
    expect(useAuthStore.getState().user).toBeNull()

    // onError 호출 확인
    expect(onError).toHaveBeenCalledOnce()

    // 한국어 에러 메시지 확인
    const error = result.current.error
    expect(error).toBeDefined()
    expect((error as Error).message).toBe('사용자명 또는 비밀번호가 올바르지 않습니다.')
  })

  it('mfa_required (401) 시 한국어 MFA 에러 메시지를 반환한다', async () => {
    server.use(
      http.post('/api/v1/auth/login', () =>
        HttpResponse.json({ error: 'mfa_required' }, { status: 401 }),
      ),
    )

    const { result } = renderHook(() => useLoginMutation(), { wrapper: createWrapper() })

    result.current.mutate({ provider: 'local', username: 'alice', password: 'password' })

    await waitFor(() => expect(result.current.isError).toBe(true))

    expect(useAuthStore.getState().accessToken).toBeNull()

    const error = result.current.error
    expect((error as Error).message).toBe('추가 인증이 필요합니다. 관리자에게 문의하세요.')
  })
})
