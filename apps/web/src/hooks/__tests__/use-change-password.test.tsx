// useChangePassword 훅 테스트 — MSW 핸들러 위에서 성공/실패 시나리오 + isPending 전이 검증
import { renderHook, waitFor, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
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

// MSW 핸들러(passwordHandlers)는 @/test/server를 통해 전역 등록돼 있음.
// seed currentPassword = 'CurrentPass123!'
// 정상 new = 'NewSecurePass99!' (12자+, 대소문자+숫자+특수 충족)
const VALID_NEW_PASSWORD = 'NewSecurePass99!'
const SEED_CURRENT = 'CurrentPass123!'

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

  it('mutate 직후 isPending이 true가 된다', async () => {
    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useChangePassword(), { wrapper })

    // 비동기 완료를 기다리지 않고 pending 상태를 즉시 확인
    act(() => {
      result.current.mutate({
        currentPassword: SEED_CURRENT,
        newPassword: VALID_NEW_PASSWORD,
      })
    })

    // mutate 직후 isPending이 true여야 한다
    expect(result.current.isPending).toBe(true)

    // 완료까지 대기 (다음 테스트 격리를 위해)
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

    expect(result.current.isError).toBe(false)
    expect(result.current.error).toBeNull()
  })
})
