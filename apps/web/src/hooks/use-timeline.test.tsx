// 타임라인 TanStack Query 훅 단위 테스트 (FR-TL-01)
import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement } from 'react'
import type { ReactNode } from 'react'

// api/timeline 전체 mock — 실제 HTTP 요청 없이 단위 테스트
vi.mock('@/api/timeline')

import { fetchTimeline } from '@/api/timeline'
import type { TimelineResponse } from '@/api/timeline'
import { useTimeline, timelineKeys } from './use-timeline'

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const MOCK_TIMELINE_RESPONSE: TimelineResponse = {
  items: [
    {
      key: 'ATLAS-1',
      summary: '첫 번째 이슈',
      issueType: 'task',
      currentStateKey: 'IN_PROGRESS',
      assigneeId: 'a1b2c3d4-e5f6-4a7b-8c9d-e0f1a2b3c4d5',
      startDate: '2026-06-01',
      dueDate: '2026-06-30',
      targetDate: null,
      epicKey: null,
    },
  ],
  truncated: false,
}

// ─────────────────────────────────────────────────────────────────────────────
// QueryClient wrapper 팩토리
// ─────────────────────────────────────────────────────────────────────────────

function createWrapper(queryClient: QueryClient) {
  return function Wrapper({ children }: { readonly children: ReactNode }) {
    return createElement(QueryClientProvider, { client: queryClient }, children)
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// timelineKeys — queryKey 팩토리 검증
// ─────────────────────────────────────────────────────────────────────────────

describe('timelineKeys', () => {
  it('T-TL-KEY-1: list는 ["timeline", projectKey] 2요소 tuple을 반환한다', () => {
    const key = timelineKeys.list('ATLAS')
    expect(key).toEqual(['timeline', 'ATLAS'])
    expect(key).toHaveLength(2)
  })

  it('T-TL-KEY-2: projectKey가 달라지면 다른 tuple을 반환한다', () => {
    const keyAtlas = timelineKeys.list('ATLAS')
    const keyBts = timelineKeys.list('BTS')
    expect(keyAtlas).not.toEqual(keyBts)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useTimeline
// ─────────────────────────────────────────────────────────────────────────────

describe('useTimeline', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
    })
    vi.mocked(fetchTimeline).mockResolvedValue(MOCK_TIMELINE_RESPONSE)
  })

  afterEach(() => {
    queryClient.clear()
    vi.clearAllMocks()
  })

  it('T-TL-1: projectKey가 있으면 fetchTimeline을 호출하고 데이터를 반환한다', async () => {
    const { result } = renderHook(() => useTimeline('ATLAS'), {
      wrapper: createWrapper(queryClient),
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(fetchTimeline).toHaveBeenCalledWith('ATLAS')
    expect(result.current.data).toEqual(MOCK_TIMELINE_RESPONSE)
  })

  it('T-TL-2: queryKey가 ["timeline", projectKey] 형태로 캐시에 저장된다', async () => {
    const { result } = renderHook(() => useTimeline('ATLAS'), {
      wrapper: createWrapper(queryClient),
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    // queryKey ['timeline', 'ATLAS']로 데이터가 캐시에 저장돼 있어야 한다
    const cachedData = queryClient.getQueryData(['timeline', 'ATLAS'])
    expect(cachedData).toEqual(MOCK_TIMELINE_RESPONSE)
  })

  it('T-TL-3: projectKey가 빈 문자열이면 enabled=false로 fetchTimeline을 호출하지 않는다', async () => {
    const { result } = renderHook(() => useTimeline(''), {
      wrapper: createWrapper(queryClient),
    })

    // idle 상태 유지 확인
    await new Promise((resolve) => setTimeout(resolve, 100))

    expect(result.current.fetchStatus).toBe('idle')
    expect(fetchTimeline).not.toHaveBeenCalled()
  })

  it('T-TL-4: fetchTimeline이 에러를 던지면 isError가 true가 되고 error가 전파된다', async () => {
    const apiError = new Error('API Error')
    vi.mocked(fetchTimeline).mockRejectedValue(apiError)

    const { result } = renderHook(() => useTimeline('ATLAS'), {
      wrapper: createWrapper(queryClient),
    })

    await waitFor(() => expect(result.current.isError).toBe(true))

    expect(result.current.error).toBeInstanceOf(Error)
  })
})
