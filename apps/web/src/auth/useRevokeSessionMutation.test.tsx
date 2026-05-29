// useRevokeSessionMutation 훅 — 세션 강제 종료 mutation 테스트
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, waitFor, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { createElement } from 'react'
import type { ReactNode } from 'react'
import { server } from '@/test/server'
import { useRevokeSessionMutation } from './useRevokeSessionMutation'
import { SESSIONS_QUERY_KEY } from './useSessionsQuery'

// sonner toast mock — 실제 DOM 없이 호출 여부만 검증
vi.mock('sonner', () => ({
  toast: {
    success: vi.fn(),
    error: vi.fn(),
  },
}))

import { toast } from 'sonner'

const TEST_SID = '33333333-3333-3333-3333-333333333333'

function createWrapper(queryClient: QueryClient) {
  return function Wrapper({ children }: { readonly children: ReactNode }) {
    return createElement(QueryClientProvider, { client: queryClient }, children)
  }
}

describe('useRevokeSessionMutation', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: { mutations: { retry: false } },
    })
    vi.clearAllMocks()
  })

  it('성공(204) 시 sessions 캐시를 무효화하고 toast.success를 호출한다', async () => {
    server.use(
      http.delete(`/api/v1/auth/sessions/${TEST_SID}`, () =>
        new HttpResponse(null, { status: 204 }),
      ),
    )

    // 미리 캐시에 데이터 설정 — invalidate 검증에 활용
    queryClient.setQueryData(SESSIONS_QUERY_KEY, [{ sid: TEST_SID }])

    const { result } = renderHook(() => useRevokeSessionMutation(), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      result.current.mutate(TEST_SID)
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    // sessions 캐시가 stale(무효화) 상태가 되어야 함
    const queryState = queryClient.getQueryState(SESSIONS_QUERY_KEY)
    expect(queryState?.isInvalidated).toBe(true)

    // 성공 토스트 호출 확인
    expect(toast.success).toHaveBeenCalledOnce()
    expect(toast.error).not.toHaveBeenCalled()
  })

  it('409(현재 세션 종료 시도) 실패 시 toast.error를 호출한다', async () => {
    server.use(
      http.delete(`/api/v1/auth/sessions/${TEST_SID}`, () =>
        HttpResponse.json(
          { error: 'cannot_revoke_current_session' },
          { status: 409 },
        ),
      ),
    )

    const { result } = renderHook(() => useRevokeSessionMutation(), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      result.current.mutate(TEST_SID)
    })

    await waitFor(() => expect(result.current.isError).toBe(true))

    expect(toast.error).toHaveBeenCalledOnce()
    expect(toast.success).not.toHaveBeenCalled()
  })

  it('404(IDOR / 미존재 sid) 실패 시 toast.error를 호출한다', async () => {
    server.use(
      http.delete(`/api/v1/auth/sessions/${TEST_SID}`, () =>
        HttpResponse.json({ error: 'not_found' }, { status: 404 }),
      ),
    )

    const { result } = renderHook(() => useRevokeSessionMutation(), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      result.current.mutate(TEST_SID)
    })

    await waitFor(() => expect(result.current.isError).toBe(true))

    expect(toast.error).toHaveBeenCalledOnce()
  })

  it('네트워크 에러 시 toast.error를 호출한다', async () => {
    server.use(
      http.delete(`/api/v1/auth/sessions/${TEST_SID}`, () => HttpResponse.error()),
    )

    const { result } = renderHook(() => useRevokeSessionMutation(), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      result.current.mutate(TEST_SID)
    })

    await waitFor(() => expect(result.current.isError).toBe(true))

    expect(toast.error).toHaveBeenCalledOnce()
  })
})
