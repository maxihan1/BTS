// useLogoutMutation 훅 — POST /api/v1/auth/logout 후 클라이언트 세션 정리 테스트
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { useLogoutMutation } from './useLogoutMutation'
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

// 각 테스트 전 authStore 세션 초기화
beforeEach(() => {
  useAuthStore.setState({ accessToken: 'test-token', user: null })
})

afterEach(() => {
  useAuthStore.setState({ accessToken: null, user: null })
})

describe('useLogoutMutation', () => {
  it('성공 (204) 시 POST /api/v1/auth/logout 호출 후 clearSession을 실행한다', async () => {
    server.use(
      http.post('/api/v1/auth/logout', () => {
        return new HttpResponse(null, { status: 204 })
      }),
    )

    const { result } = renderHook(() => useLogoutMutation(), { wrapper: createWrapper() })

    result.current.mutate()

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    // clearSession 호출 후 accessToken이 null이어야 함
    expect(useAuthStore.getState().accessToken).toBeNull()
  })

  it('서버 5xx 실패 시에도 clearSession을 호출한다 (사용자 의도 우선)', async () => {
    server.use(
      http.post('/api/v1/auth/logout', () => {
        return new HttpResponse(null, { status: 500 })
      }),
    )

    const { result } = renderHook(() => useLogoutMutation(), { wrapper: createWrapper() })

    result.current.mutate()

    await waitFor(() => expect(result.current.isError).toBe(true))

    // 서버 실패여도 클라이언트 세션은 반드시 정리
    expect(useAuthStore.getState().accessToken).toBeNull()
  })

  it('네트워크 에러 시에도 clearSession을 호출한다', async () => {
    server.use(
      http.post('/api/v1/auth/logout', () => {
        return HttpResponse.error()
      }),
    )

    const { result } = renderHook(() => useLogoutMutation(), { wrapper: createWrapper() })

    result.current.mutate()

    await waitFor(() => expect(result.current.isError).toBe(true))

    // 네트워크 단절이어도 클라이언트 세션은 반드시 정리
    expect(useAuthStore.getState().accessToken).toBeNull()
  })

  it('onSuccess 콜백을 호출한다', async () => {
    server.use(
      http.post('/api/v1/auth/logout', () => {
        return new HttpResponse(null, { status: 204 })
      }),
    )

    const onSuccess = vi.fn()
    const { result } = renderHook(() => useLogoutMutation(), { wrapper: createWrapper() })

    result.current.mutate(undefined, { onSuccess })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(onSuccess).toHaveBeenCalledOnce()
  })
})
