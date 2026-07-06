// useStatus 훅 테스트 — 상태 조회/mutation(invalidate-only, whoami 재조회는 하지 않음) 검증 (FR-PR-02 Task 6)
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, act, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement, type ReactNode } from 'react'
import { server } from '@/test/server'
import { statusHandlers, resetStatusStore } from '@/mocks/status-handlers'
import { aliceUser, mockAccessToken } from '@/mocks/auth-fixtures'
import { useAuthStore } from '@/auth/authStore'
import { STATUS_QUERY_KEY, useStatusQuery, useUpdateStatusMutation } from './useStatus'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })
  return {
    queryClient,
    wrapper: ({ children }: { children: ReactNode }) =>
      createElement(QueryClientProvider, { client: queryClient }, children),
  }
}

const ALICE_TOKEN = mockAccessToken('alice')

beforeEach(() => {
  resetStatusStore()
  server.use(...statusHandlers)
  useAuthStore.getState().setSession({ accessToken: ALICE_TOKEN, user: aliceUser })
})

afterEach(() => {
  useAuthStore.getState().clearSession()
})

// ─────────────────────────────────────────────────────────────────────────────
// STATUS_QUERY_KEY
// ─────────────────────────────────────────────────────────────────────────────

describe('STATUS_QUERY_KEY', () => {
  it('["status", "me"] 를 반환한다', () => {
    expect(STATUS_QUERY_KEY).toEqual(['status', 'me'])
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useStatusQuery — 조회 쿼리
// ─────────────────────────────────────────────────────────────────────────────

describe('useStatusQuery', () => {
  it('본인 상태를 조회한다(미설정 시 all-null, EC1)', async () => {
    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useStatusQuery(), { wrapper })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(result.current.data?.emoji).toBeNull()
    expect(result.current.data?.text).toBeNull()
    expect(result.current.data?.expiresAt).toBeNull()
  })

  it('쿼리 키는 ["status", "me"] 로 등록된다', async () => {
    const { queryClient, wrapper } = createWrapper()
    renderHook(() => useStatusQuery(), { wrapper })

    await waitFor(() => expect(queryClient.getQueryState(['status', 'me'])).not.toBeUndefined())
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useUpdateStatusMutation — 원자적 교체 mutation (invalidate-only)
// ─────────────────────────────────────────────────────────────────────────────

describe('useUpdateStatusMutation', () => {
  it('성공 시 status 쿼리를 invalidate한다', async () => {
    const { queryClient, wrapper } = createWrapper()
    const invalidateSpy = vi.spyOn(queryClient, 'invalidateQueries')

    const { result } = renderHook(() => useUpdateStatusMutation(), { wrapper })

    await act(async () => {
      result.current.mutate({ emoji: '🌴', text: '휴가 중' })
      await waitFor(() => expect(result.current.isSuccess).toBe(true))
    })

    expect(invalidateSpy).toHaveBeenCalledWith(
      expect.objectContaining({ queryKey: STATUS_QUERY_KEY }),
    )
    expect(result.current.data?.emoji).toBe('🌴')
  })

  it('mutation 성공 후 status 쿼리를 재조회하면 갱신된 값이 반영된다(stateful 오버라이드 영속)', async () => {
    const { wrapper } = createWrapper()

    const { result: mutationResult } = renderHook(() => useUpdateStatusMutation(), { wrapper })
    await act(async () => {
      mutationResult.current.mutate({ emoji: '🌴', text: '휴가 중' })
      await waitFor(() => expect(mutationResult.current.isSuccess).toBe(true))
    })

    const { result: queryResult } = renderHook(() => useStatusQuery(), { wrapper })
    await waitFor(() => expect(queryResult.current.isSuccess).toBe(true))
    expect(queryResult.current.data?.emoji).toBe('🌴')
    expect(queryResult.current.data?.text).toBe('휴가 중')
  })

  it('실패(text 100자 초과) 시 isError가 true가 된다', async () => {
    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useUpdateStatusMutation(), { wrapper })

    await act(async () => {
      // text 101자 초과 → 400 STATUS_VALIDATION_FAILED (status-handlers.ts 검증 미러)
      result.current.mutate({ text: 'x'.repeat(101) })
      await waitFor(() => expect(result.current.isError).toBe(true))
    })
  })

  it(
    '성공해도 whoami는 재조회하지 않는다(authStore.user 불변 — whoami 재조회는 Task 8 StatusModal 책임)',
    async () => {
      // 이 테스트는 base test/handlers.ts에 whoami 핸들러가 없어(onUnhandledRequest:'error'),
      // 훅이 실수로 whoami를 호출하면 MSW 미핸들 에러로 테스트 자체가 실패한다 — 이중 안전장치.
      const { wrapper } = createWrapper()
      const { result } = renderHook(() => useUpdateStatusMutation(), { wrapper })

      await act(async () => {
        result.current.mutate({ emoji: '🌴', text: '휴가 중' })
        await waitFor(() => expect(result.current.isSuccess).toBe(true))
      })

      expect(useAuthStore.getState().user).toEqual(aliceUser)
    },
  )
})
