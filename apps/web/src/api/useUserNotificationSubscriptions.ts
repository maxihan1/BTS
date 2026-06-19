// 사용자 알림 구독 설정 조회/갱신 React Query 훅 — query + mutation (invalidate-only)
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import type { SubscriptionMatrix, SubscriptionEntry } from './user-notification-subscriptions'
import { getSubscriptions, patchSubscriptions } from './user-notification-subscriptions'

// ─────────────────────────────────────────────────────────────────────────────
// queryKey 상수 — 캐시 키 문자열을 한 곳에서 관리해 오타·drift 방지
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 사용자 알림 구독 설정 TanStack Query 캐시 키.
 * mutation onSuccess → invalidateQueries 대상.
 */
export const USER_NOTIFICATION_SUBSCRIPTIONS_QUERY_KEY = ['user-notification-subscriptions'] as const

// ─────────────────────────────────────────────────────────────────────────────
// Query 훅
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 현재 인증 사용자의 알림 구독 설정 전체 매트릭스를 조회하는 훅.
 *
 * - `GET /api/v1/users/me/notifications` → `SubscriptionMatrix`
 * - 항상 20개 셀 (10 eventType × { IN_APP, EMAIL }).
 * - staleTime 30초.
 *
 * @returns TanStack Query 훅 반환 객체. `data`는 `SubscriptionMatrix` 또는 undefined
 */
export function useUserNotificationSubscriptions() {
  return useQuery<SubscriptionMatrix>({
    queryKey: USER_NOTIFICATION_SUBSCRIPTIONS_QUERY_KEY,
    queryFn: getSubscriptions,
    staleTime: 30_000,
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// Mutation 훅 — onSuccess는 invalidate-only (setQueryData 금지)
// setQueryData로 캐시를 통째 덮으면 파생 필드 누락으로 화면 플리커 발생
// (memory: mutation-setquerydata-partial-response-flicker)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 현재 인증 사용자의 알림 구독 설정을 일괄 갱신하는 mutation 훅.
 *
 * - `PATCH /api/v1/users/me/notifications` → 갱신된 `SubscriptionMatrix`
 * - onSuccess: `user-notification-subscriptions` 쿼리 invalidate (refetch 유도)
 * - ⚠️ setQueryData 직접 호출 금지 — invalidate-only 패턴
 *
 * @returns UseMutationResult — mutate(SubscriptionEntry[]) 호출로 갱신 실행
 */
export function useUpdateUserNotificationSubscriptions() {
  const queryClient = useQueryClient()

  return useMutation<SubscriptionMatrix, Error, SubscriptionEntry[]>({
    mutationFn: (entries: SubscriptionEntry[]) => patchSubscriptions(entries),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: USER_NOTIFICATION_SUBSCRIPTIONS_QUERY_KEY })
    },
  })
}
