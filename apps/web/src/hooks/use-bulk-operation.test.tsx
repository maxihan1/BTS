// 일괄 작업 접수 mutation + 진행률 폴링 query 훅 단위 테스트
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { renderHook, waitFor, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { createElement } from 'react'
import type { ReactNode } from 'react'
import { server } from '@/test/server'
import { useAuthStore } from '@/auth/authStore'
import { useSubmitBulkOperation, useBulkOperationPolling } from './use-bulk-operation'
import type { BulkUpdateInput } from '@/api/bulk-operations'

vi.mock('sonner', () => ({
  toast: {
    success: vi.fn(),
    error: vi.fn(),
  },
}))

const BULK_OPERATION_ID = 'a1b2c3d4-e5f6-4a7b-8c9d-e0f1a2b3c4d5'

const MOCK_ACCEPTED = {
  data: {
    bulkOperationId: BULK_OPERATION_ID,
    status: 'PENDING',
    totalCount: 3,
  },
}

const MOCK_PENDING_RESPONSE = {
  data: {
    id: BULK_OPERATION_ID,
    operationType: 'BULK_EDIT',
    status: 'PENDING',
    payload: { priority: 2, impact: null },
    totalCount: 3,
    processedCount: 0,
    succeededCount: 0,
    failedCount: 0,
    items: [],
  },
}

const MOCK_COMPLETED_RESPONSE = {
  data: {
    id: BULK_OPERATION_ID,
    operationType: 'BULK_EDIT',
    status: 'COMPLETED',
    payload: { priority: 2, impact: null },
    totalCount: 3,
    processedCount: 3,
    succeededCount: 3,
    failedCount: 0,
    items: [
      { issueKey: 'PROJ-1', status: 'SUCCEEDED', failureReasonCode: null },
      { issueKey: 'PROJ-2', status: 'SUCCEEDED', failureReasonCode: null },
      { issueKey: 'PROJ-3', status: 'SUCCEEDED', failureReasonCode: null },
    ],
  },
}

const MOCK_INPUT: BulkUpdateInput = {
  operationType: 'BULK_EDIT',
  issueKeys: ['PROJ-1', 'PROJ-2', 'PROJ-3'],
  editPayload: { priority: 2, impact: null },
  transitionPayload: null,
}

function createWrapper(queryClient: QueryClient) {
  return function Wrapper({ children }: { readonly children: ReactNode }) {
    return createElement(QueryClientProvider, { client: queryClient }, children)
  }
}

describe('useSubmitBulkOperation', () => {
  let queryClient: QueryClient

  beforeEach(async () => {
    queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    useAuthStore.setState({ accessToken: 'test-token', user: null })
    const { toast } = await import('sonner')
    vi.mocked(toast.success).mockClear()
    vi.mocked(toast.error).mockClear()
  })

  afterEach(() => {
    useAuthStore.setState({ accessToken: null, user: null })
    queryClient.clear()
  })

  it('T-BULK-MUT-1: 접수 성공 시 bulkOperationId를 반환하고 성공 토스트를 표시한다', async () => {
    server.use(
      http.post('/api/v1/issues/bulk-update', () =>
        HttpResponse.json(MOCK_ACCEPTED, { status: 202 }),
      ),
    )

    const { result } = renderHook(() => useSubmitBulkOperation(), {
      wrapper: createWrapper(queryClient),
    })

    let mutateResult: Awaited<ReturnType<ReturnType<typeof useSubmitBulkOperation>['mutateAsync']>> | undefined

    await act(async () => {
      mutateResult = await result.current.mutateAsync(MOCK_INPUT)
    })

    expect(mutateResult?.bulkOperationId).toBe(BULK_OPERATION_ID)

    const { toast } = await import('sonner')
    expect(toast.success).toHaveBeenCalledOnce()
    const callArg = vi.mocked(toast.success).mock.calls[0]?.[0]
    expect(typeof callArg).toBe('string')
    expect(callArg as string).toContain('접수')
  })

  it('T-BULK-MUT-2: ApiError 시 ProblemDetail detail로 에러 토스트를 표시한다', async () => {
    server.use(
      http.post('/api/v1/issues/bulk-update', () =>
        HttpResponse.json(
          { type: 'about:blank', title: 'Bad Request', status: 400, detail: '대상 이슈가 없습니다.', instance: '/api/v1/issues/bulk-update' },
          { status: 400 },
        ),
      ),
    )

    const { result } = renderHook(() => useSubmitBulkOperation(), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      try {
        await result.current.mutateAsync(MOCK_INPUT)
      } catch {
        // 에러는 의도된 것, 무시
      }
    })

    const { toast } = await import('sonner')
    expect(toast.error).toHaveBeenCalledOnce()
    const callArg = vi.mocked(toast.error).mock.calls[0]?.[0]
    expect(typeof callArg).toBe('string')
    expect(callArg as string).toBe('대상 이슈가 없습니다.')
  })

  it('T-BULK-MUT-3: detail 없는 ApiError 시 기본 한국어 메시지로 에러 토스트를 표시한다', async () => {
    server.use(
      http.post('/api/v1/issues/bulk-update', () =>
        HttpResponse.json({ type: 'about:blank', status: 403 }, { status: 403 }),
      ),
    )

    const { result } = renderHook(() => useSubmitBulkOperation(), {
      wrapper: createWrapper(queryClient),
    })

    await act(async () => {
      try {
        await result.current.mutateAsync(MOCK_INPUT)
      } catch {
        // 에러는 의도된 것, 무시
      }
    })

    const { toast } = await import('sonner')
    expect(toast.error).toHaveBeenCalledOnce()
    const callArg = vi.mocked(toast.error).mock.calls[0]?.[0]
    expect(typeof callArg).toBe('string')
    expect(callArg as string).toBe('일괄 작업 접수에 실패했습니다.')
  })
})

describe('useBulkOperationPolling', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })
    useAuthStore.setState({ accessToken: 'test-token', user: null })
  })

  afterEach(() => {
    useAuthStore.setState({ accessToken: null, user: null })
    queryClient.clear()
  })

  it('T-BULK-POLL-1: enabled=false이면 fetchBulkOperation을 호출하지 않는다', async () => {
    let callCount = 0
    server.use(
      http.get(`/api/v1/bulk-operations/${BULK_OPERATION_ID}`, () => {
        callCount++
        return HttpResponse.json(MOCK_PENDING_RESPONSE)
      }),
    )

    const { result } = renderHook(
      () => useBulkOperationPolling(BULK_OPERATION_ID, false),
      { wrapper: createWrapper(queryClient) },
    )

    // 약간의 시간을 줘도 쿼리가 실행되지 않아야 함
    await new Promise((resolve) => setTimeout(resolve, 100))

    expect(result.current.fetchStatus).toBe('idle')
    expect(callCount).toBe(0)
  })

  it('T-BULK-POLL-2: id=null이면 fetchBulkOperation을 호출하지 않는다', async () => {
    let callCount = 0
    server.use(
      http.get(`/api/v1/bulk-operations/${BULK_OPERATION_ID}`, () => {
        callCount++
        return HttpResponse.json(MOCK_PENDING_RESPONSE)
      }),
    )

    const { result } = renderHook(
      () => useBulkOperationPolling(null, true),
      { wrapper: createWrapper(queryClient) },
    )

    await new Promise((resolve) => setTimeout(resolve, 100))

    expect(result.current.fetchStatus).toBe('idle')
    expect(callCount).toBe(0)
  })

  it('T-BULK-POLL-3: PENDING → COMPLETED 전환 시 폴링이 종료 상태에서 멈춘다', async () => {
    // 상태 저장소 — 첫 호출 PENDING, 이후 COMPLETED
    let callCount = 0
    server.use(
      http.get(`/api/v1/bulk-operations/${BULK_OPERATION_ID}`, () => {
        callCount++
        if (callCount === 1) {
          return HttpResponse.json(MOCK_PENDING_RESPONSE)
        }
        return HttpResponse.json(MOCK_COMPLETED_RESPONSE)
      }),
    )

    const { result } = renderHook(
      () => useBulkOperationPolling(BULK_OPERATION_ID, true),
      { wrapper: createWrapper(queryClient) },
    )

    // COMPLETED 상태로 수렴할 때까지 대기
    await waitFor(
      () => expect(result.current.data?.status).toBe('COMPLETED'),
      { timeout: 10000 },
    )

    // 종단 상태 도달 후 추가 호출이 없어야 함 (refetchInterval=false)
    const callCountAfterDone = callCount
    await new Promise((resolve) => setTimeout(resolve, 2000))
    expect(callCount).toBe(callCountAfterDone)
    expect(result.current.data?.status).toBe('COMPLETED')
  }, 15000)
})
