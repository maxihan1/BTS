// useChangePassword 훅 테스트 — MSW 핸들러 위에서 성공/실패 시나리오 + isPending 전환 검증
import { renderHook, waitFor, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { passwordHandlers } from '@/mocks/password-handlers'
import { useChangePassword } from '../use-change-password'
import { ApiError } from '@/api/client'

function createWrapper() {
  const client = new QueryClient({
    defaultOptions: { mutations: { retry: false } },
  })
  return {
    wrapper: ({ children }: { children: React.ReactNode }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    ),
  }
}

// passwordHandlers 를 각 테스트 직전에 등록
// (setup.ts afterEach → server.resetHandlers()로 초기화되므로 beforeEach가 필요)
// seed currentPassword = 'CurrentPass123!'
// 정상 new = 'NewSecurePass99!' (12자+, 대소문자+숫자+특수 충족)
const VALID_NEW_PASSWORD = 'NewSecurePass99!'
const SEED_CURRENT = 'CurrentPass123!'

beforeEach(() => server.use(...passwordHandlers))

describe('useChangePassword', () => {
  it('성공 시 isSuccess가 true가 된다', async () => {
    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useChangePassword(), { wrapper })

    expect(result.current.isPending).toBe(false)

    await act(async () => {
      result.current.mutate({
        currentPassword: SEED_CURRENT,
        newPassword: VALID_NEW_PASSWORD,
      })
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.isError).toBe(false)
  })

  it('mutate 직후 isPending이 true가 된다 (delay 핸들러로 검증)', async () => {
    // MSW 응답을 지연시켜 isPending 상태를 포착한다
    let resolveResponse!: () => void
    const deferred = new Promise<void>((res) => { resolveResponse = res })

    server.use(
      http.post('/api/v1/users/me/password', async () => {
        await deferred
        return HttpResponse.json({ changed: true })
      }),
    )

    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useChangePassword(), { wrapper })

    act(() => {
      result.current.mutate({
        currentPassword: SEED_CURRENT,
        newPassword: VALID_NEW_PASSWORD,
      })
    })

    // mutate 직후 응답이 아직 안 왔으므로 isPending이 true여야 한다
    await waitFor(() => expect(result.current.isPending).toBe(true))

    // 응답 해제 후 성공 상태로 전환
    resolveResponse()
    await waitFor(() => expect(result.current.isSuccess).toBe(true))
  })

  it('현재 비밀번호 틀림(CURRENT_PASSWORD_MISMATCH) 시 isError=true이고 error가 ApiError(400)이다', async () => {
    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useChangePassword(), { wrapper })

    await act(async () => {
      result.current.mutate({
        currentPassword: 'WrongPassword123!',
        newPassword: VALID_NEW_PASSWORD,
      })
    })

    await waitFor(() => expect(result.current.isError).toBe(true))

    expect(result.current.isSuccess).toBe(false)
    const err = result.current.error
    expect(err).toBeInstanceOf(ApiError)

    if (!(err instanceof ApiError)) throw new Error('type guard missed')
    expect(err.status).toBe(400)
    expect((err.body as { code?: string } | null)?.code).toBe('CURRENT_PASSWORD_MISMATCH')
  })

  it('reset 호출 후 isError가 false로 초기화된다', async () => {
    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useChangePassword(), { wrapper })

    await act(async () => {
      result.current.mutate({
        currentPassword: 'WrongPassword123!',
        newPassword: VALID_NEW_PASSWORD,
      })
    })

    await waitFor(() => expect(result.current.isError).toBe(true))

    act(() => {
      result.current.reset()
    })

    await waitFor(() => {
      expect(result.current.isError).toBe(false)
      expect(result.current.error).toBeNull()
    })
  })
})
