// 상태 이관 진행률 폴링 훅 단위 테스트 — 정지 조건(종료 상태·에러 축·id 없음·언마운트) 전수 검증
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { z } from 'zod'
import type { ZodError } from 'zod'
import { createElement } from 'react'
import type { ReactNode } from 'react'
import {
  useMigrationProgress,
  computeRefetchInterval,
  calculateProgressRatio,
  migrationProgressQueryKey,
  POLL_INTERVAL_MS,
  MAX_ERROR_RETRIES,
} from '../use-migration-progress'
import { ApiError } from '@/api/client'
import type { BulkOperationResponse } from '@/api/bulk-operations'

vi.mock('@/api/bulk-operations', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/bulk-operations')>()
  return {
    ...actual,
    fetchBulkOperation: vi.fn(),
  }
})

const { fetchBulkOperation } = await import('@/api/bulk-operations')

const OPERATION_ID = 'a1b2c3d4-e5f6-4a7b-8c9d-e0f1a2b3c4d5'

/** ZodError를 실제로 발생시켜 캡처한다 — 손으로 구성하지 않는다(생성자 형태에 의존하지 않기 위함). */
function makeZodError(): ZodError {
  try {
    z.string().parse(123)
  } catch (error) {
    return error as ZodError
  }
  throw new Error('unreachable — z.string().parse(123) must throw')
}

function buildResponse(overrides: Partial<BulkOperationResponse>): BulkOperationResponse {
  return {
    id: OPERATION_ID,
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

/** computeRefetchInterval에 넘길 최소 query.state 목(mock)을 만든다. */
function buildQueryState(overrides: {
  status: 'pending' | 'success' | 'error'
  data?: BulkOperationResponse
  error?: unknown
  errorUpdateCount?: number
}) {
  return {
    state: {
      status: overrides.status,
      data: overrides.data,
      error: overrides.error ?? null,
      errorUpdateCount: overrides.errorUpdateCount ?? 0,
    },
  } as Parameters<typeof computeRefetchInterval>[0]
}

function createWrapper(queryClient: QueryClient) {
  return function Wrapper({ children }: { readonly children: ReactNode }) {
    return createElement(QueryClientProvider, { client: queryClient }, children)
  }
}

describe('POLL_INTERVAL_MS', () => {
  it('폴링 간격은 2초로 고정돼 있다', () => {
    expect(POLL_INTERVAL_MS).toBe(2000)
  })
})

describe('migrationProgressQueryKey', () => {
  it('id를 포함한 queryKey 튜플을 반환한다', () => {
    expect(migrationProgressQueryKey(OPERATION_ID)).toEqual(['migration-progress', OPERATION_ID])
    expect(migrationProgressQueryKey(null)).toEqual(['migration-progress', null])
  })
})

describe('computeRefetchInterval — 성공 응답 축', () => {
  it('T-MIG-POLL-1: PENDING 상태면 POLL_INTERVAL_MS 뒤 재조회한다', () => {
    const result = computeRefetchInterval(
      buildQueryState({ status: 'success', data: buildResponse({ status: 'PENDING' }) }),
    )
    expect(result).toBe(POLL_INTERVAL_MS)
  })

  it('T-MIG-POLL-2: RUNNING 상태면 POLL_INTERVAL_MS 뒤 재조회한다', () => {
    const result = computeRefetchInterval(
      buildQueryState({ status: 'success', data: buildResponse({ status: 'RUNNING' }) }),
    )
    expect(result).toBe(POLL_INTERVAL_MS)
  })

  it('T-MIG-POLL-3: COMPLETED 상태에 도달하면 폴링을 멈춘다(false)', () => {
    const result = computeRefetchInterval(
      buildQueryState({ status: 'success', data: buildResponse({ status: 'COMPLETED', processedCount: 4 }) }),
    )
    expect(result).toBe(false)
  })

  it('T-MIG-POLL-4: FAILED 상태에 도달해도 폴링을 멈춘다(false) — 성공만 멈추면 영구 폴링이다', () => {
    const result = computeRefetchInterval(
      buildQueryState({ status: 'success', data: buildResponse({ status: 'FAILED' }) }),
    )
    expect(result).toBe(false)
  })
})

describe('computeRefetchInterval — 에러 축 (G4 403 재발 방지)', () => {
  it('T-MIG-POLL-5: ApiError 403이면 재시도 횟수와 무관하게 즉시 정지한다', () => {
    const result = computeRefetchInterval(
      buildQueryState({ status: 'error', error: new ApiError(403, {}), errorUpdateCount: 1 }),
    )
    expect(result).toBe(false)
  })

  it('T-MIG-POLL-6: ApiError 404이면 재시도 횟수와 무관하게 즉시 정지한다', () => {
    const result = computeRefetchInterval(
      buildQueryState({ status: 'error', error: new ApiError(404, {}), errorUpdateCount: 1 }),
    )
    expect(result).toBe(false)
  })

  it('T-MIG-POLL-7: ApiError 500이 상한 미만이면 재시도한다', () => {
    const result = computeRefetchInterval(
      buildQueryState({
        status: 'error',
        error: new ApiError(500, {}),
        errorUpdateCount: MAX_ERROR_RETRIES - 1,
      }),
    )
    expect(result).toBe(POLL_INTERVAL_MS)
  })

  it('T-MIG-POLL-8: ApiError 500이 상한에 도달하면 정지한다', () => {
    const result = computeRefetchInterval(
      buildQueryState({
        status: 'error',
        error: new ApiError(500, {}),
        errorUpdateCount: MAX_ERROR_RETRIES,
      }),
    )
    expect(result).toBe(false)
  })

  it('T-MIG-POLL-9: 네트워크 오류(ApiError 아님)도 상한 미만이면 재시도한다', () => {
    const result = computeRefetchInterval(
      buildQueryState({
        status: 'error',
        error: new TypeError('Failed to fetch'),
        errorUpdateCount: 1,
      }),
    )
    expect(result).toBe(POLL_INTERVAL_MS)
  })

  it('T-MIG-POLL-10: ZodError(스키마 불일치)는 재시도 횟수와 무관하게 즉시 정지한다', () => {
    const result = computeRefetchInterval(
      buildQueryState({ status: 'error', error: makeZodError(), errorUpdateCount: 1 }),
    )
    expect(result).toBe(false)
  })
})

describe('calculateProgressRatio', () => {
  it('T-MIG-RATIO-1: processedCount/totalCount 비율을 계산한다', () => {
    expect(calculateProgressRatio(buildResponse({ processedCount: 2, totalCount: 4 }))).toBe(0.5)
  })

  it('T-MIG-RATIO-2: 데이터가 없으면 null이다', () => {
    expect(calculateProgressRatio(undefined)).toBeNull()
  })

  it('T-MIG-RATIO-3: totalCount가 0이면 null이다(0으로 나누기 방지)', () => {
    expect(calculateProgressRatio(buildResponse({ totalCount: 0, processedCount: 0 }))).toBeNull()
  })
})

describe('useMigrationProgress', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    vi.mocked(fetchBulkOperation).mockReset()
  })

  afterEach(() => {
    queryClient.clear()
  })

  it('T-MIG-HOOK-1: id가 없으면 아예 시작하지 않는다', async () => {
    const { result } = renderHook(() => useMigrationProgress(null), {
      wrapper: createWrapper(queryClient),
    })

    await new Promise((resolve) => setTimeout(resolve, 50))

    expect(fetchBulkOperation).not.toHaveBeenCalled()
    expect(result.current.fetchStatus).toBe('idle')
  })

  it('T-MIG-HOOK-2: 언마운트하면 이후 폴링 호출이 없다', async () => {
    vi.mocked(fetchBulkOperation).mockResolvedValue(buildResponse({ status: 'PENDING' }))

    const { result, unmount } = renderHook(() => useMigrationProgress(OPERATION_ID), {
      wrapper: createWrapper(queryClient),
    })

    await waitFor(() => expect(result.current.data?.status).toBe('PENDING'))
    const callCountAtUnmount = vi.mocked(fetchBulkOperation).mock.calls.length

    unmount()

    // POLL_INTERVAL_MS(2초)를 넘겨도 추가 호출이 없어야 언마운트가 폴링을 멈춘 것이다.
    await new Promise((resolve) => setTimeout(resolve, POLL_INTERVAL_MS + 500))
    expect(vi.mocked(fetchBulkOperation).mock.calls.length).toBe(callCountAtUnmount)
  }, 10000)

  it('T-MIG-HOOK-3: 진행률 파생값(progressRatio)을 함께 반환한다', async () => {
    vi.mocked(fetchBulkOperation).mockResolvedValue(
      buildResponse({ status: 'RUNNING', processedCount: 1, totalCount: 4 }),
    )

    const { result } = renderHook(() => useMigrationProgress(OPERATION_ID), {
      wrapper: createWrapper(queryClient),
    })

    await waitFor(() => expect(result.current.progressRatio).toBe(0.25))
  })
})
