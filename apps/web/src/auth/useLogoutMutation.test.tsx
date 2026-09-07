// useLogoutMutation 훅 — POST /api/v1/auth/logout 후 세션 정리 + /login 이동 테스트
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { useLogoutMutation } from './useLogoutMutation'
import { useAuthStore } from './authStore'

/**
 * 이 훅이 부르는 `navigate` — 실 라우터를 세우지 않고 **호출 자체**를 잰다.
 *
 * 🛑 이동은 이 훅의 계약이다(세션만 지우고 끝나면 로그인 모달이 뜨지 않는다). 그래서
 *    라우터를 통째로 mock 하되 `useNavigate` 만 관측 가능한 스텁으로 바꾼다.
 */
const mockNavigate = vi.fn()
vi.mock('@tanstack/react-router', () => ({
  useNavigate: () => mockNavigate,
}))

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
  mockNavigate.mockClear()
})

afterEach(() => {
  useAuthStore.setState({ accessToken: null, user: null })
})

describe('useLogoutMutation', () => {
  /**
   * ★이동 계약 — 이것이 빠져 있어 「로그아웃해도 로그인 모달이 안 뜬다」가 났다.
   *
   * 종전에는 `AccountMenu` 가 `mutate(_, { onSettled })` 로 이동을 걸었고, `clearSession()` 이
   * 그 컴포넌트를 언마운트해 콜백이 통째로 버려졌다. 소유를 훅으로 옮긴 것이 처방이므로
   * 판별식도 훅에 둔다 — 호출자에 두면 다음에 호출자가 바뀔 때 같이 사라진다.
   */
  it('성공하면 /login 으로 이동한다', async () => {
    server.use(http.post('/api/v1/auth/logout', () => new HttpResponse(null, { status: 204 })))

    const { result } = renderHook(() => useLogoutMutation(), { wrapper: createWrapper() })
    result.current.mutate()

    await waitFor(() => expect(mockNavigate).toHaveBeenCalledWith({ to: '/login' }))
  })

  it('서버가 실패해도 /login 으로 이동한다 — 사용자 로그아웃 의도 우선', async () => {
    // 🛑 성공 경로만 재면 「서버가 500 이면 로그인 화면에 갇힌 채 세션만 지워진」 상태를
    //    놓친다. 세션 정리가 onSettled 인 것과 같은 이유로 이동도 onSettled 다.
    server.use(http.post('/api/v1/auth/logout', () => new HttpResponse(null, { status: 500 })))

    const { result } = renderHook(() => useLogoutMutation(), { wrapper: createWrapper() })
    result.current.mutate()

    await waitFor(() => expect(mockNavigate).toHaveBeenCalledWith({ to: '/login' }))
    expect(useAuthStore.getState().accessToken).toBeNull()
  })

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
