// useCalendarFeed TanStack Query 훅 테스트 — 상태 조회 + mutation invalidate-only 검증 (FR-CA-02 Task 8)
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { createElement } from 'react'
import type { ReactNode } from 'react'
import { server } from '@/test/server'
import {
  CALENDAR_FEED_QUERY_KEY,
  useCalendarFeedStatus,
  useIssueCalendarFeed,
  useRevokeCalendarFeed,
} from './useCalendarFeed'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const issuedFixture = {
  feedUrl: 'http://localhost:8080/ical/feed/aabbcc0011223344aabbcc0011223344aabbcc0011223344aabbcc001122.ics',
  token: 'aabbcc0011223344aabbcc0011223344aabbcc0011223344aabbcc001122',
  createdAt: '2026-07-09T08:00:00Z',
}

const enabledStatusFixture = {
  enabled: true,
  createdAt: '2026-07-09T08:00:00Z',
}

const disabledStatusFixture = {
  enabled: false,
}

// ─────────────────────────────────────────────────────────────────────────────
// QueryClient wrapper 헬퍼 (useWebhooks.test.ts 선례)
// ─────────────────────────────────────────────────────────────────────────────

function createWrapper(client: QueryClient) {
  return function Wrapper({ children }: { readonly children: ReactNode }) {
    return createElement(QueryClientProvider, { client }, children)
  }
}

describe('useCalendarFeed 훅 묶음', () => {
  let queryClient: QueryClient

  beforeEach(() => {
    queryClient = new QueryClient({
      defaultOptions: {
        queries: { retry: false },
        mutations: { retry: false },
      },
    })
    document.cookie = 'XSRF-TOKEN=test-hook-xsrf-token; path=/'
  })

  // ─────────────────────────────────────────────────────────────────────────
  // useCalendarFeedStatus
  // ─────────────────────────────────────────────────────────────────────────

  describe('useCalendarFeedStatus', () => {
    it('발급됨 상태를 로드한다', async () => {
      server.use(
        http.get('/api/v1/users/me/calendar/feed', () => HttpResponse.json(enabledStatusFixture)),
      )

      const { result } = renderHook(() => useCalendarFeedStatus(), {
        wrapper: createWrapper(queryClient),
      })

      await waitFor(() => expect(result.current.isSuccess).toBe(true))
      expect(result.current.data?.enabled).toBe(true)
      expect(result.current.data?.createdAt).toBe(enabledStatusFixture.createdAt)
    })

    it('미발급 상태를 로드한다', async () => {
      server.use(
        http.get('/api/v1/users/me/calendar/feed', () => HttpResponse.json(disabledStatusFixture)),
      )

      const { result } = renderHook(() => useCalendarFeedStatus(), {
        wrapper: createWrapper(queryClient),
      })

      await waitFor(() => expect(result.current.isSuccess).toBe(true))
      expect(result.current.data?.enabled).toBe(false)
    })

    it("CALENDAR_FEED_QUERY_KEY(['calendar','feed'])로 캐시된다", async () => {
      server.use(
        http.get('/api/v1/users/me/calendar/feed', () => HttpResponse.json(enabledStatusFixture)),
      )

      const { result } = renderHook(() => useCalendarFeedStatus(), {
        wrapper: createWrapper(queryClient),
      })

      await waitFor(() => expect(result.current.isSuccess).toBe(true))

      expect(CALENDAR_FEED_QUERY_KEY).toEqual(['calendar', 'feed'])
      const cached = queryClient.getQueryData(CALENDAR_FEED_QUERY_KEY)
      expect(cached).toBeDefined()
    })
  })

  // ─────────────────────────────────────────────────────────────────────────
  // useIssueCalendarFeed
  // ─────────────────────────────────────────────────────────────────────────

  describe('useIssueCalendarFeed', () => {
    it("성공(201) 시 ['calendar','feed'] 쿼리가 invalidate된다", async () => {
      server.use(
        http.post('/api/v1/users/me/calendar/feed', () => HttpResponse.json(issuedFixture, { status: 201 })),
      )
      queryClient.setQueryData(CALENDAR_FEED_QUERY_KEY, disabledStatusFixture)

      const { result } = renderHook(() => useIssueCalendarFeed(), {
        wrapper: createWrapper(queryClient),
      })

      result.current.mutate()

      await waitFor(() => expect(result.current.isSuccess).toBe(true))
      expect(result.current.data?.token).toBe(issuedFixture.token)

      const queryState = queryClient.getQueryState(CALENDAR_FEED_QUERY_KEY)
      expect(queryState?.isInvalidated).toBe(true)
    })

    it('setQueryData를 직접 호출하지 않는다 (invalidate-only, mutation-setquerydata-partial-response-flicker 회귀 방지)', async () => {
      server.use(
        http.post('/api/v1/users/me/calendar/feed', () => HttpResponse.json(issuedFixture, { status: 201 })),
      )
      queryClient.setQueryData(CALENDAR_FEED_QUERY_KEY, disabledStatusFixture)
      const spy = vi.spyOn(queryClient, 'setQueryData')

      const { result } = renderHook(() => useIssueCalendarFeed(), {
        wrapper: createWrapper(queryClient),
      })

      result.current.mutate()

      await waitFor(() => expect(result.current.isSuccess).toBe(true))

      expect(spy).not.toHaveBeenCalledWith(CALENDAR_FEED_QUERY_KEY, expect.anything())
      spy.mockRestore()
    })
  })

  // ─────────────────────────────────────────────────────────────────────────
  // useRevokeCalendarFeed
  // ─────────────────────────────────────────────────────────────────────────

  describe('useRevokeCalendarFeed', () => {
    it("성공(204) 시 ['calendar','feed'] 쿼리가 invalidate된다", async () => {
      server.use(
        http.delete('/api/v1/users/me/calendar/feed', () => new HttpResponse(null, { status: 204 })),
      )
      queryClient.setQueryData(CALENDAR_FEED_QUERY_KEY, enabledStatusFixture)

      const { result } = renderHook(() => useRevokeCalendarFeed(), {
        wrapper: createWrapper(queryClient),
      })

      result.current.mutate()

      await waitFor(() => expect(result.current.isSuccess).toBe(true))

      const queryState = queryClient.getQueryState(CALENDAR_FEED_QUERY_KEY)
      expect(queryState?.isInvalidated).toBe(true)
    })
  })
})
