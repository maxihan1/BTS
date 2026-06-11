// useLoginMutation 훅 — login + whoami 연쇄 호출, authStore 세션 저장, MFA union 분기 테스트
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
          mustChangePassword: false,
          isSystemAdmin: false,
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
      mustChangePassword: false,
      isSystemAdmin: false,
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

  it('login 200 mfa_required:true 응답 시 MFA 진입 신호(MfaRequiredResult)를 반환하고 authStore를 변경하지 않는다', async () => {
    server.use(
      http.post('/api/v1/auth/login', () =>
        HttpResponse.json({
          mfa_required: true,
          mfa_challenge_token: 'challenge-token-xyz',
          expires_in: 300,
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

    // authStore 미변경 확인 — 챌린지 토큰은 authStore에 저장되면 안 된다(NFR-1)
    expect(useAuthStore.getState().accessToken).toBeNull()
    expect(useAuthStore.getState().user).toBeNull()

    // MFA 진입 신호 반환 확인
    const data = result.current.data
    expect(data).toBeDefined()
    expect(data?.kind).toBe('mfa_required')
    if (data?.kind === 'mfa_required') {
      expect(data.challengeToken).toBe('challenge-token-xyz')
    }

    expect(onSuccess).toHaveBeenCalledOnce()
  })

  // CONCERN-union 음성 테스트 — mfa_required:true이면 access_token이 동봉돼도 MFA step으로 진입(토큰 무시)
  it('[CONCERN-union] mfa_required:true + access_token 동봉 응답 시 MFA step으로 진입하고 토큰을 무시한다', async () => {
    server.use(
      http.post('/api/v1/auth/login', () =>
        HttpResponse.json({
          mfa_required: true,
          mfa_challenge_token: 'challenge-token-xyz',
          expires_in: 300,
          // access_token이 함께 내려와도 무시해야 한다
          access_token: 'should-be-ignored',
          token_type: 'Bearer',
        }),
      ),
    )

    const { result } = renderHook(() => useLoginMutation(), { wrapper: createWrapper() })

    result.current.mutate({ provider: 'local', username: 'alice', password: 'password' })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    // MFA step으로 분기되어야 한다 — kind === 'mfa_required'
    expect(result.current.data?.kind).toBe('mfa_required')

    // 절대 토큰을 authStore에 저장해선 안 된다(NFR-1, MFA 우회 회귀 차단)
    expect(useAuthStore.getState().accessToken).toBeNull()
    expect(useAuthStore.getState().user).toBeNull()
  })

  it('login 200 정상 응답(mfa_required 없음) 시 기존 TokenResult를 반환하고 세션을 저장한다', async () => {
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
          mustChangePassword: false,
          isSystemAdmin: false,
        }),
      ),
    )

    const { result } = renderHook(() => useLoginMutation(), { wrapper: createWrapper() })

    result.current.mutate({ provider: 'local', username: 'alice', password: 'password' })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    // kind === 'success'여야 한다
    expect(result.current.data?.kind).toBe('success')

    // 세션 저장 확인
    expect(useAuthStore.getState().accessToken).toBe('test-access-token')
    expect(useAuthStore.getState().user).not.toBeNull()
  })
})
