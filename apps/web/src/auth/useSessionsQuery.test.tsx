// useSessionsQuery 훅 — 세션 목록 조회 TanStack Query 훅 테스트
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { createElement } from 'react'
import type { ReactNode } from 'react'
import { server } from '@/test/server'
import { useAuthStore } from './authStore'
import { useSessionsQuery, SESSIONS_QUERY_KEY } from './useSessionsQuery'

// RFC 4122 표준 UUID — 버전(v4) + 변형(variant 10xx) 비트 정합 필수 (Zod uuid() 검증 통과)
const MOCK_SESSIONS = [
  {
    sid: 'a1b2c3d4-e5f6-4a7b-8c9d-e0f1a2b3c4d5',
    providerId: 'local',
    userAgent: 'Mozilla/5.0',
    ipAddress: '127.0.0.1',
    lastSeenAt: '2026-05-29T10:00:00Z',
    createdAt: '2026-05-29T09:00:00Z',
    current: true,
  },
  {
    sid: 'f6e5d4c3-b2a1-4f8e-9d0c-b1a2f3e4d5c6',
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
    // apiFetch가 Authorization 헤더를 포함해 요청하도록 accessToken 설정
    // 없으면 401 → refresh 자동 시도 → MSW 핸들러 미등록 시 요청이 막힘
    useAuthStore.setState({ accessToken: 'test-token', user: null })
  })

  afterEach(() => {
    useAuthStore.setState({ accessToken: null, user: null })
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
    expect(result.current.data?.[0]?.sid).toBe('a1b2c3d4-e5f6-4a7b-8c9d-e0f1a2b3c4d5')
    expect(result.current.data?.[0]?.current).toBe(true)
    expect(result.current.data?.[1]?.userAgent).toBeNull()
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
