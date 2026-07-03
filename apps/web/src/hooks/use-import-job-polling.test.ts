// Import 잡 폴링 훅 단위 테스트 — enabled/disabled 분기와 refetchInterval 종단 판별 검증
import { describe, it, expect, vi, beforeEach } from 'vitest'
import type { Mock } from 'vitest'
import { renderHook } from '@testing-library/react'
import { QueryClient, QueryClientProvider, useQuery } from '@tanstack/react-query'
import type { Query } from '@tanstack/react-query'
import { createElement } from 'react'
import type { ReactNode } from 'react'
import { useImportJobPolling, importJobQueryKey } from './use-import-job-polling'
import { fetchImportJobStatus, IMPORT_POLL_INTERVAL_MS } from '@/api/imports'
import type { ImportJobStatus } from '@/api/imports'

vi.mock('@/api/imports', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/api/imports')>()
  return {
    ...actual,
    fetchImportJobStatus: vi.fn(),
  }
})

vi.mock('@tanstack/react-query', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@tanstack/react-query')>()
  return {
    ...actual,
    useQuery: vi.fn(actual.useQuery),
  }
})

const JOB_ID = 'a1b2c3d4-e5f6-4a7b-8c9d-e0f1a2b3c4d5'

function buildStatus(overrides: Partial<ImportJobStatus>): ImportJobStatus {
  return {
    jobId: JOB_ID,
    status: 'RUNNING',
    progress: 50,
    totalRows: 10,
    succeededRows: 5,
    failedRows: 0,
    errorCode: null,
    errorLogReady: false,
    dryRun: false,
    ...overrides,
  }
}

function createWrapper(queryClient: QueryClient) {
  return function Wrapper({ children }: { readonly children: ReactNode }) {
    return createElement(QueryClientProvider, { client: queryClient }, children)
  }
}

/** 마지막 useQuery 호출에 전달된 options에서 refetchInterval 콜백을 추출한다. */
function extractRefetchInterval(): (query: Pick<Query<ImportJobStatus>, 'state'>) => number | false | undefined {
  const mockUseQuery = vi.mocked(useQuery) as unknown as Mock
  const lastCallOptions = mockUseQuery.mock.calls.at(-1)?.[0] as
    | {
        refetchInterval?: (query: Pick<Query<ImportJobStatus>, 'state'>) => number | false | undefined
      }
    | undefined
  const refetchInterval = lastCallOptions?.refetchInterval
  if (refetchInterval === undefined) {
    throw new Error('refetchInterval 옵션이 설정되지 않았습니다.')
  }
  return refetchInterval
}

describe('importJobQueryKey', () => {
  it('id를 포함한 queryKey 튜플을 반환한다', () => {
    expect(importJobQueryKey(JOB_ID)).toEqual(['import-job', JOB_ID])
    expect(importJobQueryKey(null)).toEqual(['import-job', null])
  })
})

describe('useImportJobPolling', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    vi.mocked(fetchImportJobStatus).mockReset()
    vi.mocked(useQuery).mockClear()
  })

  it('T-IMPORT-POLL-1: id=null이면 enabled=true여도 fetchImportJobStatus를 호출하지 않는다', async () => {
    renderHook(() => useImportJobPolling(null, true), { wrapper: createWrapper(queryClient) })

    await new Promise((resolve) => setTimeout(resolve, 50))

    expect(fetchImportJobStatus).not.toHaveBeenCalled()
  })

  it('T-IMPORT-POLL-2: query.state.status가 error이면 refetchInterval이 false를 반환한다', () => {
    renderHook(() => useImportJobPolling(JOB_ID, true), { wrapper: createWrapper(queryClient) })

    const refetchInterval = extractRefetchInterval()
    const result = refetchInterval({
      state: { status: 'error', data: undefined },
    } as Pick<Query<ImportJobStatus>, 'state'>)

    expect(result).toBe(false)
  })

  it('T-IMPORT-POLL-3: 종단 상태(COMPLETED)에서 refetchInterval이 false를 반환한다', () => {
    renderHook(() => useImportJobPolling(JOB_ID, true), { wrapper: createWrapper(queryClient) })

    const refetchInterval = extractRefetchInterval()
    const result = refetchInterval({
      state: { status: 'success', data: buildStatus({ status: 'COMPLETED' }) },
    } as Pick<Query<ImportJobStatus>, 'state'>)

    expect(result).toBe(false)
  })

  it('T-IMPORT-POLL-4: 종단 상태(FAILED)에서 refetchInterval이 false를 반환한다', () => {
    renderHook(() => useImportJobPolling(JOB_ID, true), { wrapper: createWrapper(queryClient) })

    const refetchInterval = extractRefetchInterval()
    const result = refetchInterval({
      state: { status: 'success', data: buildStatus({ status: 'FAILED', errorCode: 'IMPORT_PARSE_FAILED' }) },
    } as Pick<Query<ImportJobStatus>, 'state'>)

    expect(result).toBe(false)
  })

  it('T-IMPORT-POLL-5: 진행 중(RUNNING) 상태에서 refetchInterval이 IMPORT_POLL_INTERVAL_MS를 반환한다', () => {
    renderHook(() => useImportJobPolling(JOB_ID, true), { wrapper: createWrapper(queryClient) })

    const refetchInterval = extractRefetchInterval()
    const result = refetchInterval({
      state: { status: 'success', data: buildStatus({ status: 'RUNNING' }) },
    } as Pick<Query<ImportJobStatus>, 'state'>)

    expect(result).toBe(IMPORT_POLL_INTERVAL_MS)
    expect(result).toBe(1500)
  })
})
