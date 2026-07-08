// useCalendar 훅 테스트 — filter-aware queryKey(from/to) + MSW 응답 검증 (FR-CA-01 Task 6)
import { describe, it, expect, beforeEach } from 'vitest'
import { renderHook, waitFor } from '@testing-library/react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { createElement, type ReactNode } from 'react'
import { server } from '@/test/server'
import { calendarHandlers } from '@/test/msw/handlers/calendar'
import { CALENDAR_QUERY_KEY, useCalendar } from './useCalendar'

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function createWrapper() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  })
  return {
    queryClient,
    wrapper: ({ children }: { children: ReactNode }) =>
      createElement(QueryClientProvider, { client: queryClient }, children),
  }
}

beforeEach(() => {
  server.use(...calendarHandlers)
})

// ─────────────────────────────────────────────────────────────────────────────
// CALENDAR_QUERY_KEY
// ─────────────────────────────────────────────────────────────────────────────

describe('CALENDAR_QUERY_KEY', () => {
  it('["calendar", from, to] 를 반환한다', () => {
    expect(CALENDAR_QUERY_KEY('2026-07-01', '2026-07-31')).toEqual([
      'calendar',
      '2026-07-01',
      '2026-07-31',
    ])
  })

  it('from/to가 다르면 다른 키를 반환한다 (filter-aware)', () => {
    const key1 = CALENDAR_QUERY_KEY('2026-07-01', '2026-07-31')
    const key2 = CALENDAR_QUERY_KEY('2026-08-01', '2026-08-31')
    expect(key1).not.toEqual(key2)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// useCalendar — 조회 쿼리
// ─────────────────────────────────────────────────────────────────────────────

describe('useCalendar', () => {
  it('본인 캘린더(담당 이슈 일정 + Worklog)를 조회한다', async () => {
    const { wrapper } = createWrapper()
    const { result } = renderHook(() => useCalendar('2026-07-01', '2026-07-31'), { wrapper })

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(result.current.data?.timezone).toBe('Asia/Seoul')
    expect(result.current.data?.from).toBe('2026-07-01')
  })

  it('쿼리 키는 ["calendar", from, to] 로 등록된다', async () => {
    const { queryClient, wrapper } = createWrapper()
    renderHook(() => useCalendar('2026-07-01', '2026-07-31'), { wrapper })

    await waitFor(() =>
      expect(
        queryClient.getQueryState(['calendar', '2026-07-01', '2026-07-31']),
      ).not.toBeUndefined(),
    )
  })

  it('from/to가 바뀌면 별도 캐시 엔트리를 사용한다 (filter-aware queryKey — 두 창 모두 캐시에 공존)', async () => {
    const { queryClient, wrapper } = createWrapper()
    const { result, rerender } = renderHook(
      ({ from, to }: { from: string; to: string }) => useCalendar(from, to),
      { wrapper, initialProps: { from: '2026-07-01', to: '2026-07-31' } },
    )

    await waitFor(() => expect(result.current.isSuccess).toBe(true))
    expect(
      queryClient.getQueryState(['calendar', '2026-07-01', '2026-07-31']),
    ).not.toBeUndefined()

    rerender({ from: '2026-08-01', to: '2026-08-31' })

    await waitFor(() => expect(result.current.data?.from).toBe('2026-08-01'))
    // 이전 창의 캐시가 새 창 요청으로 덮이지 않고 별도 엔트리로 공존한다
    expect(
      queryClient.getQueryState(['calendar', '2026-07-01', '2026-07-31']),
    ).not.toBeUndefined()
    expect(
      queryClient.getQueryState(['calendar', '2026-08-01', '2026-08-31']),
    ).not.toBeUndefined()
  })
})
