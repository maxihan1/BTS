// 워크로그 집계 TanStack Query 훅 테스트 — TDD RED phase (FR-TT-02 Task 2)
import React from 'react'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { vi } from 'vitest'
import type { WorklogAggregateResponse } from '@/api/worklog-aggregate'

// fetchWorklogAggregate를 vi.mock으로 격리
vi.mock('@/api/worklog-aggregate', () => ({
  fetchWorklogAggregate: vi.fn(),
}))

import { fetchWorklogAggregate } from '@/api/worklog-aggregate'
import { useWorklogAggregate, WORKLOG_AGGREGATE_KEYS } from './use-worklog-aggregate'

// ─────────────────────────────────────────────────────────────────────────────
// 공통 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** 테스트마다 독립된 QueryClient + Provider 래퍼를 생성한다 */
function createWrapper() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  const wrapper = ({ children }: { children: React.ReactNode }) =>
    React.createElement(QueryClientProvider, { client }, children)
  return { client, wrapper }
}

/** 정상 응답 픽스처 */
const mockResponse: WorklogAggregateResponse = {
  by: 'user',
  buckets: [
    { key: 'alice', label: 'Alice', timeSpentSeconds: 3600, worklogCount: 2 },
  ],
  totalTimeSpentSeconds: 3600,
}

const mockFetch = vi.mocked(fetchWorklogAggregate)

// ─────────────────────────────────────────────────────────────────────────────
// WORKLOG_AGGREGATE_KEYS — queryKey 팩토리 정확성
// ─────────────────────────────────────────────────────────────────────────────

describe('WORKLOG_AGGREGATE_KEYS', () => {
  it('all 키는 projectKey를 포함한다', () => {
    const key = WORKLOG_AGGREGATE_KEYS.all('BTS', { by: 'user' })
    expect(key).toContain('BTS')
  })

  it('파라미터 by가 다르면 서로 다른 queryKey를 반환한다 — 캐시 분리', () => {
    const keyUser = WORKLOG_AGGREGATE_KEYS.all('BTS', { by: 'user' })
    const keyIssue = WORKLOG_AGGREGATE_KEYS.all('BTS', { by: 'issue' })
    expect(keyUser).not.toEqual(keyIssue)
  })

  it('granularity가 다르면 서로 다른 queryKey를 반환한다 — 캐시 분리', () => {
    const keyDay = WORKLOG_AGGREGATE_KEYS.all('BTS', { by: 'period', granularity: 'day' })
    const keyWeek = WORKLOG_AGGREGATE_KEYS.all('BTS', { by: 'period', granularity: 'week' })
    expect(keyDay).not.toEqual(keyWeek)
  })

  it('from이 다르면 서로 다른 queryKey를 반환한다 — 캐시 분리', () => {
    const keyA = WORKLOG_AGGREGATE_KEYS.all('BTS', { by: 'user', from: '2026-01-01' })
    const keyB = WORKLOG_AGGREGATE_KEYS.all('BTS', { by: 'user', from: '2026-06-01' })
    expect(keyA).not.toEqual(keyB)
  })

  it('to가 다르면 서로 다른 queryKey를 반환한다 — 캐시 분리', () => {
    const keyA = WORKLOG_AGGREGATE_KEYS.all('BTS', { by: 'user', to: '2026-01-31' })
    const keyB = WORKLOG_AGGREGATE_KEYS.all('BTS', { by: 'user', to: '2026-06-30' })
    expect(keyA).not.toEqual(keyB)
  })

  it('projectKey가 다르면 서로 다른 queryKey를 반환한다 — 캐시 분리', () => {
    const keyBts = WORKLOG_AGGREGATE_KEYS.all('BTS', { by: 'user' })
    const keyAtlas = WORKLOG_AGGREGATE_KEYS.all('ATLAS', { by: 'user' })
    expect(keyBts).not.toEqual(keyAtlas)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useWorklogAggregate — enabled 제어
// ─────────────────────────────────────────────────────────────────────────────

describe('useWorklogAggregate — enabled 제어', () => {
  beforeEach(() => {
    mockFetch.mockClear()
  })

  it('projectKey가 빈 문자열이면 enabled: false로 fetch가 발생하지 않는다', () => {
    const { wrapper } = createWrapper()
    const { result } = renderHook(
      () => useWorklogAggregate('', { by: 'user' }),
      { wrapper },
    )

    expect(result.current.fetchStatus).toBe('idle')
    expect(mockFetch).not.toHaveBeenCalled()
  })

  it('projectKey가 있으면 fetch가 발생한다', async () => {
    mockFetch.mockResolvedValueOnce(mockResponse)
    const { wrapper } = createWrapper()
    const { result } = renderHook(
      () => useWorklogAggregate('BTS', { by: 'user' }),
      { wrapper },
    )

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(mockFetch).toHaveBeenCalledTimes(1)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useWorklogAggregate — queryFn 인자 검증
// ─────────────────────────────────────────────────────────────────────────────

describe('useWorklogAggregate — queryFn 인자', () => {
  beforeEach(() => {
    mockFetch.mockClear()
  })

  it('fetchWorklogAggregate를 projectKey와 params로 호출한다', async () => {
    mockFetch.mockResolvedValueOnce(mockResponse)
    const { wrapper } = createWrapper()
    const params = { by: 'user' as const, from: '2026-01-01', to: '2026-06-30' }

    renderHook(() => useWorklogAggregate('BTS', params), { wrapper })

    await waitFor(() => expect(mockFetch).toHaveBeenCalledTimes(1))
    expect(mockFetch).toHaveBeenCalledWith('BTS', params)
  })

  it('granularity 포함 시 파라미터 그대로 전달된다', async () => {
    mockFetch.mockResolvedValueOnce({ ...mockResponse, by: 'period', granularity: 'week' })
    const { wrapper } = createWrapper()
    const params = { by: 'period' as const, granularity: 'week' as const }

    renderHook(() => useWorklogAggregate('ATLAS', params), { wrapper })

    await waitFor(() => expect(mockFetch).toHaveBeenCalledTimes(1))
    expect(mockFetch).toHaveBeenCalledWith('ATLAS', params)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useWorklogAggregate — 정상 응답
// ─────────────────────────────────────────────────────────────────────────────

describe('useWorklogAggregate — 정상 응답', () => {
  beforeEach(() => {
    mockFetch.mockClear()
  })

  it('정상 응답 시 data를 반환한다', async () => {
    mockFetch.mockResolvedValueOnce(mockResponse)
    const { wrapper } = createWrapper()

    const { result } = renderHook(
      () => useWorklogAggregate('BTS', { by: 'user' }),
      { wrapper },
    )

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data).toEqual(mockResponse)
  })

  it('초기 로딩 상태에서 isPending이 true다', () => {
    mockFetch.mockImplementationOnce(
      () => new Promise<WorklogAggregateResponse>((resolve) => setTimeout(() => resolve(mockResponse), 200)),
    )
    const { wrapper } = createWrapper()

    const { result } = renderHook(
      () => useWorklogAggregate('BTS', { by: 'user' }),
      { wrapper },
    )

    expect(result.current.isPending).toBe(true)
  })

  it('에러 발생 시 isError가 true이고 error를 반환한다', async () => {
    const fetchError = new Error('403 Forbidden')
    mockFetch.mockRejectedValueOnce(fetchError)
    const { wrapper } = createWrapper()

    const { result } = renderHook(
      () => useWorklogAggregate('BTS', { by: 'user' }),
      { wrapper },
    )

    await waitFor(() => expect(result.current.isError).toBe(true))
    expect(result.current.error).toBe(fetchError)
  })
})
