// useDeleteIssue 훅 단위 테스트 — 성공 시 캐시 무효화 + onSuccess 콜백, 실패 시 toast.error 검증
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { renderHook, waitFor, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement } from 'react'
import type { ReactNode } from 'react'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { useDeleteIssue } from './useDeleteIssue'

// sonner toast mock — 실제 DOM 없이 호출 여부만 검증
vi.mock('sonner', () => ({
  toast: {
    error: vi.fn(),
  },
}))

// toast mock 참조를 매 테스트 전에 초기화
import { toast } from 'sonner'

function createWrapper(queryClient: QueryClient) {
  return function Wrapper({ children }: { readonly children: ReactNode }) {
    return createElement(QueryClientProvider, { client: queryClient }, children)
  }
}

describe('useDeleteIssue', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: { mutations: { retry: false } },
    })
    vi.clearAllMocks()
  })

  it('성공 시 issues 목록 캐시를 무효화하고 onSuccess 콜백을 호출한다', async () => {
    server.use(
      http.delete('/api/v1/issues/ATLAS-1', () => new HttpResponse(null, { status: 204 })),
    )

    const onSuccess = vi.fn()
    const { result } = renderHook(() => useDeleteIssue({ onSuccess }), {
      wrapper: createWrapper(queryClient),
    })

    // 쿼리 캐시에 ['issues'] 키로 데이터를 미리 설정해 invalidate 검증에 활용
    queryClient.setQueryData(['issues'], [{ key: 'ATLAS-1' }])

    await act(async () => {
      result.current.mutate('ATLAS-1')
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    // invalidateQueries는 해당 쿼리를 stale 상태로 만들어 다음 사용 시 재fetch하게 한다
    // setQueryData로 설정한 데이터가 stale이 되었는지 확인
    const queryState = queryClient.getQueryState(['issues'])
    expect(queryState?.isInvalidated).toBe(true)

    expect(onSuccess).toHaveBeenCalledOnce()
  })

  it('실패(404) 시 toast.error를 호출한다', async () => {
    server.use(
      http.delete('/api/v1/issues/ATLAS-99', () =>
        HttpResponse.json({ message: '이슈를 찾을 수 없습니다' }, { status: 404 }),
      ),
    )

    const { result } = renderHook(() => useDeleteIssue({}), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      result.current.mutate('ATLAS-99')
    })

    await waitFor(() => expect(result.current.isError).toBe(true))

    expect(toast.error).toHaveBeenCalledOnce()
  })

  it('onSuccess 없이도 성공 시 캐시만 무효화한다', async () => {
    server.use(
      http.delete('/api/v1/issues/ATLAS-2', () => new HttpResponse(null, { status: 204 })),
    )

    queryClient.setQueryData(['issues'], [{ key: 'ATLAS-2' }])

    const { result } = renderHook(() => useDeleteIssue({}), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      result.current.mutate('ATLAS-2')
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    const queryState = queryClient.getQueryState(['issues'])
    expect(queryState?.isInvalidated).toBe(true)
  })
})
