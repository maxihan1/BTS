// useSessionsQuery 훅 — 세션 목록 조회 TanStack Query 훅 테스트
import { describe, it, expect, beforeEach } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { createElement } from 'react'
import type { ReactNode } from 'react'
import { server } from '@/test/server'
import { useSessionsQuery, SESSIONS_QUERY_KEY } from './useSessionsQuery'

const MOCK_SESSIONS = [
  {
    sid: '11111111-1111-1111-1111-111111111111',
    providerId: 'local',
    userAgent: 'Mozilla/5.0',
    ipAddress: '127.0.0.1',
    lastSeenAt: '2026-05-29T10:00:00Z',
    createdAt: '2026-05-29T09:00:00Z',
    current: true,
  },
  {
    sid: '22222222-2222-2222-2222-222222222222',
    providerId: 'local',
    userAgent: null,
    ipAddress: null,
    lastSeenAt: '2026-05-28T10:00:00Z',
    createdAt: '2026-05-28T09:00:00Z',
    current: false,
  },
]

function createWrapper(queryClient: QueryClient) {
  return function Wrapper({ children }: { readonly children: ReactNode }) {
    return createElement(QueryClientProvider, { client: queryClient }, children)
  }
}

describe('useSessionsQuery', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: { queries: { retry: false } },
    })
  })

  it('성공 시 listSessions 결과 배열을 반환한다', async () => {
    server.use(
      http.get('/api/v1/auth/sessions', () =>
        HttpResponse.json({ sessions: MOCK_SESSIONS }),
      ),
    )

    const { result } = renderHook(() => useSessionsQuery(), {
      wrapper: createWrapper(queryClient),
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(result.current.data).toHaveLength(2)
    expect(result.current.data?.[0]?.sid).toBe('11111111-1111-1111-1111-111111111111')
    expect(result.current.data?.[0]?.current).toBe(true)
    expect(result.current.data?.[1]?.userAgent).toBeNull()
  })

  it('세션이 없으면 빈 배열을 반환한다', async () => {
    server.use(
      http.get('/api/v1/auth/sessions', () =>
        HttpResponse.json({ sessions: [] }),
      ),
    )

    const { result } = renderHook(() => useSessionsQuery(), {
      wrapper: createWrapper(queryClient),
    })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))

    expect(result.current.data).toEqual([])
  })

  it('401 응답 시 isError가 true가 된다', async () => {
    server.use(
      http.get('/api/v1/auth/sessions', () =>
        HttpResponse.json({ error: 'unauthorized' }, { status: 401 }),
      ),
    )

    const { result } = renderHook(() => useSessionsQuery(), {
      wrapper: createWrapper(queryClient),
    })

    await waitFor(() => expect(result.current.isError).toBe(true))
  })

  it('SESSIONS_QUERY_KEY 상수가 ["sessions"] 배열이다', () => {
    expect(SESSIONS_QUERY_KEY).toEqual(['sessions'])
  })
})
