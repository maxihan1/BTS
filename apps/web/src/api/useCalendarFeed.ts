// 캘린더 iCal 구독 피드 발급/조회/취소 TanStack Query 훅 (FR-CA-02)
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import type { UseMutationResult, UseQueryResult } from '@tanstack/react-query'
import { issueCalendarFeed, getCalendarFeed, revokeCalendarFeed } from './calendarFeed'
import type { CalendarFeedIssued, CalendarFeedStatus } from './calendarFeed'
import type { ApiError } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// queryKey 상수 — 캐시 키 문자열을 한 곳에서 관리해 오타·drift 방지 (useWebhooks.ts 관례)
// mutation onSuccess에서 invalidateQueries를 호출할 때 이 상수를 직접 참조한다.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 캘린더 피드 상태 TanStack Query 캐시 키.
 *
 * mutation(발급/취소) onSuccess → invalidateQueries 대상. FR-CA-01
 * `CALENDAR_QUERY_KEY(from, to)`(`['calendar', from, to]`, calendar.ts)와 두 번째 세그먼트가
 * 고정 문자열 `'feed'`라 날짜 문자열 키와 충돌하지 않는다(둘 다 `'calendar'` prefix 공유하지만
 * 별도 캐시 엔트리).
 */
export const CALENDAR_FEED_QUERY_KEY = ['calendar', 'feed'] as const

// ─────────────────────────────────────────────────────────────────────────────
// Query 훅
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 본인 캘린더 구독 피드 상태(발급 여부/발급 시각) 조회 훅.
 *
 * `GET /api/v1/users/me/calendar/feed` 결과를 조회한다.
 *
 * @returns TanStack Query `useQuery` 결과
 */
export function useCalendarFeedStatus(): UseQueryResult<CalendarFeedStatus, ApiError> {
  return useQuery<CalendarFeedStatus, ApiError>({
    queryKey: CALENDAR_FEED_QUERY_KEY,
    queryFn: getCalendarFeed,
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// Mutation 훅 — 모든 onSuccess는 invalidate-only (setQueryData 금지).
// 발급 응답을 setQueryData로 캐시에 그대로 덮으면 상태 조회 응답 형태(enabled/createdAt)와
// 발급 응답 형태(feedUrl/token/createdAt)가 달라 캐시가 오염된다
// (memory: mutation-setquerydata-partial-response-flicker).
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 캘린더 구독 피드 발급(신규 또는 재발급=rotate) mutation 훅.
 *
 * - `POST /api/v1/users/me/calendar/feed` → 201 {@link CalendarFeedIssued}
 * - onSuccess: {@link CALENDAR_FEED_QUERY_KEY} 쿼리 invalidate(상태 refetch 유도)
 * - 발급 응답의 raw `token`은 캐시에 저장하지 않는다 — 호출부가 `mutate()`의 `onSuccess` 콜백
 *   또는 `data`를 통해 컴포넌트 state로 1회만 보관한 뒤 화면에 표시한다(PatTokenModal 선례).
 *
 * @returns UseMutationResult — `mutate()` 호출로 발급 실행
 */
export function useIssueCalendarFeed(): UseMutationResult<CalendarFeedIssued, ApiError, void> {
  const queryClient = useQueryClient()

  return useMutation<CalendarFeedIssued, ApiError, void>({
    mutationFn: issueCalendarFeed,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: CALENDAR_FEED_QUERY_KEY })
    },
  })
}

/**
 * 캘린더 구독 피드 취소(하드삭제) mutation 훅.
 *
 * - `DELETE /api/v1/users/me/calendar/feed` → 204
 * - onSuccess: {@link CALENDAR_FEED_QUERY_KEY} 쿼리 invalidate
 *
 * @returns UseMutationResult — `mutate()` 호출로 취소 실행
 */
export function useRevokeCalendarFeed(): UseMutationResult<void, ApiError, void> {
  const queryClient = useQueryClient()

  return useMutation<void, ApiError, void>({
    mutationFn: revokeCalendarFeed,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: CALENDAR_FEED_QUERY_KEY })
    },
  })
}
