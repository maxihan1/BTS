// 일괄 작업 접수 mutation + 진행률 폴링 query 훅 단위 테스트
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { renderHook, waitFor, act } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { createElement } from 'react'
import { z } from 'zod'
import type { ReactNode } from 'react'
import type { ZodError } from 'zod'
import { server } from '@/test/server'
import { useAuthStore } from '@/auth/authStore'
import { ApiError } from '@/api/client'
import {
  useSubmitBulkOperation,
  useBulkOperationPolling,
  computeRefetchInterval,
  shouldRetryPoll,
  calculateProgressRatio,
  MAX_ERROR_RETRIES,
  POLL_INTERVAL_MS,
} from './use-bulk-operation'
import type { BulkUpdateInput, BulkOperationResponse } from '@/api/bulk-operations'

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
    // fake timer를 사용한 테스트가 다음 테스트에 영향을 주지 않도록 복구한다.
    vi.useRealTimers()
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

  it('T-BULK-POLL-4: 폴링 중 HTTP 에러(403)가 발생하면 폴링이 즉시 중단되고 추가 요청이 없다', async () => {
    let callCount = 0
    server.use(
      http.get(`/api/v1/bulk-operations/${BULK_OPERATION_ID}`, () => {
        callCount++
        return HttpResponse.json({ type: 'about:blank', status: 403 }, { status: 403 })
      }),
    )

    const { result } = renderHook(
      () => useBulkOperationPolling(BULK_OPERATION_ID, true),
      { wrapper: createWrapper(queryClient) },
    )

    // 첫 번째 요청이 에러 상태로 정착할 때까지 대기
    await waitFor(() => expect(result.current.status).toBe('error'))

    const callCountAfterError = callCount
    // 403 은 재시도 대상이 아니라 즉시 error 로 정착한다 — POLL_INTERVAL_MS 보다 짧은 대기
    // 뒤에도, 그 뒤로도 추가 요청이 없어야 한다.
    await new Promise((resolve) => setTimeout(resolve, 500))
    expect(callCount).toBe(callCountAfterError)
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

  /** fake timer 위에서 폴링 사이클 `ticks` 번을 진행시킨다 — 재시도 간격도 POLL_INTERVAL_MS 다. */
  async function advancePolls(ticks: number): Promise<void> {
    for (let tick = 0; tick < ticks; tick += 1) {
      await act(async () => {
        await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS)
      })
    }
  }

  // ★ 게이트 2 concern — 재시도 예산은 「연속 실패 수」여야 한다. 아래 두 판정이 그 축의 양쪽
  //   끝(간헐 장애는 흡수 · 영구 장애는 정지)을 각각 재며, 목 상태를 손으로 만들지 않고 실제
  //   훅을 MSW 위에서 돌린다.
  it('T-BULK-POLL-17: 폴 사이사이 5xx 가 스쳐도 성공이 예산을 되돌려 COMPLETED 까지 간다', async () => {
    vi.useFakeTimers()
    // 500 → 200 을 세 번 되풀이한 뒤 COMPLETED. 예산을 쿼리 생애 누적(errorUpdateCount)으로 세면
    // 세 번째 500 에서 상한에 닿아 진행 중인 채로 영구 정지하므로 COMPLETED 에 닿지 못한다.
    const script = ['fail', 'ok', 'fail', 'ok', 'fail', 'ok', 'done'] as const
    let callCount = 0
    server.use(
      http.get(`/api/v1/bulk-operations/${BULK_OPERATION_ID}`, () => {
        const step = script[callCount] ?? 'done'
        callCount++
        if (step === 'fail') {
          return HttpResponse.json({ type: 'about:blank', status: 500 }, { status: 500 })
        }
        return HttpResponse.json(step === 'done' ? MOCK_COMPLETED_RESPONSE : MOCK_PENDING_RESPONSE)
      }),
    )

    const { result } = renderHook(
      () => useBulkOperationPolling(BULK_OPERATION_ID, true),
      { wrapper: createWrapper(queryClient) },
    )
    await advancePolls(script.length * 2)

    expect(result.current.data?.status).toBe('COMPLETED')
    // 스쳐 간 5xx 는 화면에 에러로 새지 않는다 — 배너가 뜨면 사용자가 멀쩡한 작업을 중단한다.
    expect(result.current.isError).toBe(false)
  })

  it('T-BULK-POLL-18: 5xx 가 연속되면 예산을 쓴 뒤 멈춘다 — 무한 폴링이 되지 않는다', async () => {
    vi.useFakeTimers()
    let callCount = 0
    server.use(
      http.get(`/api/v1/bulk-operations/${BULK_OPERATION_ID}`, () => {
        callCount++
        return HttpResponse.json({ type: 'about:blank', status: 500 }, { status: 500 })
      }),
    )

    const { result } = renderHook(
      () => useBulkOperationPolling(BULK_OPERATION_ID, true),
      { wrapper: createWrapper(queryClient) },
    )
    await advancePolls(MAX_ERROR_RETRIES + 2)

    expect(result.current.isError).toBe(true)
    // 최초 1회 + 연속 재시도 MAX_ERROR_RETRIES 회. 그 뒤로는 아무리 기다려도 늘지 않는다.
    expect(callCount).toBe(MAX_ERROR_RETRIES + 1)
    await advancePolls(5)
    expect(callCount).toBe(MAX_ERROR_RETRIES + 1)
  })
})

/**
 * ★ 아래 블록은 FR-WF-07 D6b Task 3 이 별도 훅(`use-migration-progress`)으로 만들었던 판정을
 * 이 훅으로 **이전**한 것이다. 같은 엔드포인트를 폴링하는 훅이 둘이 되는 중복을 없애면서
 * 판정은 하나도 버리지 않는다 — 테스트를 지우는 것이 아니라 옮기는 것이다(절대 규칙 14).
 *
 * `computeRefetchInterval` 과 `calculateProgressRatio` 는 순수 함수라 목이 필요 없다.
 * 훅 레벨 축(enabled·id null·403 중단·종단 정지)은 위 MSW 기반 판정이 이미 덮는다.
 */

/**
 * computeRefetchInterval 에 넘길 최소 query.state 목(mock)을 만든다.
 *
 * ★ 재시도 예산은 여기 없다 — `shouldRetryPoll` 이 쥐고 있고, 그건 목이 필요 없는 순수 함수라
 * 카운터를 손으로 밀어 넣지 않아도 잰다. 목이 들고 있던 `errorUpdateCount` 를 지운 이유다.
 */
function buildQueryState(overrides: {
  readonly status: 'pending' | 'success' | 'error'
  readonly data?: BulkOperationResponse
}) {
  return {
    state: { status: overrides.status, data: overrides.data },
  } as Parameters<typeof computeRefetchInterval>[0]
}

function buildResponse(overrides: Partial<BulkOperationResponse>): BulkOperationResponse {
  return {
    id: BULK_OPERATION_ID,
    operationType: 'STATUS_MIGRATION',
    status: 'PENDING',
    payload: { mappings: {}, projectKeys: [] },
    totalCount: 4,
    processedCount: 0,
    succeededCount: 0,
    failedCount: 0,
    items: [],
    ...overrides,
  }
}

/** ZodError 를 실제로 발생시켜 캡처한다 — 손으로 구성하지 않는다(생성자 형태에 의존하지 않기 위함). */
function makeZodError(): ZodError {
  try {
    z.string().parse(123)
  } catch (error) {
    return error as ZodError
  }
  throw new Error('unreachable — z.string().parse(123) must throw')
}

describe('POLL_INTERVAL_MS', () => {
  it('T-BULK-POLL-5: 폴링 간격은 2초로 고정돼 있다 (FR-WF-07 D6b 리뷰 D3)', () => {
    expect(POLL_INTERVAL_MS).toBe(2000)
  })
})

describe('computeRefetchInterval — 성공 응답 축', () => {
  it('T-BULK-POLL-6: PENDING 상태면 POLL_INTERVAL_MS 뒤 재조회한다', () => {
    expect(
      computeRefetchInterval(
        buildQueryState({ status: 'success', data: buildResponse({ status: 'PENDING' }) }),
      ),
    ).toBe(POLL_INTERVAL_MS)
  })

  it('T-BULK-POLL-7: RUNNING 상태면 POLL_INTERVAL_MS 뒤 재조회한다', () => {
    expect(
      computeRefetchInterval(
        buildQueryState({ status: 'success', data: buildResponse({ status: 'RUNNING' }) }),
      ),
    ).toBe(POLL_INTERVAL_MS)
  })

  it('T-BULK-POLL-8: COMPLETED 상태에 도달하면 폴링을 멈춘다(false)', () => {
    expect(
      computeRefetchInterval(
        buildQueryState({
          status: 'success',
          data: buildResponse({ status: 'COMPLETED', processedCount: 4 }),
        }),
      ),
    ).toBe(false)
  })

  it('T-BULK-POLL-9: FAILED 상태에 도달해도 멈춘다 — 성공만 멈추면 실패 시 영구 폴링이다', () => {
    expect(
      computeRefetchInterval(
        buildQueryState({ status: 'success', data: buildResponse({ status: 'FAILED' }) }),
      ),
    ).toBe(false)
  })
})

/**
 * ★ 에러 축의 판정은 `computeRefetchInterval` 에서 `shouldRetryPoll` 로 **옮겼다**. 판정 자체는
 * 하나도 버리지 않았다(절대 규칙 14) — 옮긴 이유는 재시도 예산이 「연속 실패 수」여야 하는데
 * 쿼리 상태에는 그 값이 없기 때문이다(`errorUpdateCount` 는 생애 누적 · `fetchFailureCount` 는
 * 매 fetch 마다 0 으로 리셋). 예산을 TanStack Query 의 `retry` 옵션에 넘기면 그 인자가 정확히
 * 연속 실패 수다.
 */
describe('shouldRetryPoll — 에러 축 (G4 403 재발 방지)', () => {
  it('T-BULK-POLL-10: ApiError 403 이면 재시도 횟수와 무관하게 즉시 정지한다', () => {
    expect(shouldRetryPoll(0, new ApiError(403, {}))).toBe(false)
  })

  it('T-BULK-POLL-11: ApiError 404 이면 재시도 횟수와 무관하게 즉시 정지한다', () => {
    expect(shouldRetryPoll(0, new ApiError(404, {}))).toBe(false)
  })

  it('T-BULK-POLL-12: ApiError 500 이 상한 미만이면 재시도한다', () => {
    expect(shouldRetryPoll(MAX_ERROR_RETRIES - 1, new ApiError(500, {}))).toBe(true)
  })

  it('T-BULK-POLL-13: ApiError 500 이 상한에 도달하면 정지한다', () => {
    expect(shouldRetryPoll(MAX_ERROR_RETRIES, new ApiError(500, {}))).toBe(false)
  })

  it('T-BULK-POLL-14: 네트워크 오류(ApiError 아님)도 상한 미만이면 재시도한다', () => {
    expect(shouldRetryPoll(1, new TypeError('Failed to fetch'))).toBe(true)
  })

  it('T-BULK-POLL-15: ZodError(스키마 불일치)는 재시도 횟수와 무관하게 즉시 정지한다', () => {
    expect(shouldRetryPoll(0, makeZodError())).toBe(false)
  })
})

describe('computeRefetchInterval — 에러 정착 축', () => {
  it('T-BULK-POLL-16: error 로 정착하면 멈춘다 — 재시도 예산은 shouldRetryPoll 이 이미 다 썼다', () => {
    expect(computeRefetchInterval(buildQueryState({ status: 'error' }))).toBe(false)
  })
})

describe('calculateProgressRatio', () => {
  it('T-BULK-RATIO-1: processedCount/totalCount 비율을 계산한다', () => {
    expect(calculateProgressRatio(buildResponse({ processedCount: 2, totalCount: 4 }))).toBe(0.5)
  })

  it('T-BULK-RATIO-2: 데이터가 없으면 null 이다', () => {
    expect(calculateProgressRatio(undefined)).toBeNull()
  })

  it('T-BULK-RATIO-3: totalCount 가 0 이면 null 이다 (0 으로 나누기 방지)', () => {
    expect(calculateProgressRatio(buildResponse({ totalCount: 0, processedCount: 0 }))).toBeNull()
  })
})
