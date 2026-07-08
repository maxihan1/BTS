// 개인 캘린더 조회 TanStack Query 훅 (FR-CA-01)
import { useQuery } from '@tanstack/react-query'
import type { UseQueryResult } from '@tanstack/react-query'
import { fetchCalendar } from './calendar'
import type { CalendarResponse } from './calendar'
import type { ApiError } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// 쿼리 키 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 캘린더 조회 쿼리 키 생성 헬퍼.
 *
 * from/to를 키에 포함해 filter-aware 캐싱을 보장한다 — 조회 창(from/to)이 바뀌면
 * 별도 캐시 엔트리를 사용하므로, 이전 창을 다시 조회할 때(예: "이전" 버튼) 기존 캐시가
 * 재사용되고 다른 창끼리 서로 덮어쓰지 않는다.
 *
 * @param from 조회 시작일 (`"YYYY-MM-DD"`)
 * @param to 조회 종료일 (`"YYYY-MM-DD"`)
 * @returns TanStack Query queryKey 배열 (예: `["calendar", "2026-07-01", "2026-07-31"]`)
 */
export const CALENDAR_QUERY_KEY = (from: string, to: string): [string, string, string] => [
  'calendar',
  from,
  to,
]

// ─────────────────────────────────────────────────────────────────────────────
// 훅
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 본인 개인 캘린더(담당 이슈 일정 + Worklog) 조회 훅.
 *
 * - queryKey: {@link CALENDAR_QUERY_KEY}`(from, to)` — from/to가 바뀌면 별도 캐시 엔트리를 사용한다.
 * - `GET /api/v1/users/me/calendar?from=&to=` 결과를 조회한다.
 *
 * @param from 조회 시작일 (ISO date, `"YYYY-MM-DD"`)
 * @param to 조회 종료일 (ISO date, `"YYYY-MM-DD"`, 포함)
 * @returns TanStack Query `useQuery` 결과
 */
export function useCalendar(from: string, to: string): UseQueryResult<CalendarResponse, ApiError> {
  return useQuery<CalendarResponse, ApiError>({
    queryKey: CALENDAR_QUERY_KEY(from, to),
    queryFn: () => fetchCalendar(from, to),
    staleTime: 30_000,
  })
}
