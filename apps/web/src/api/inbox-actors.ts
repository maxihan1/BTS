// InboxItem actorUserId → 표시 이름 Map 변환 훅 (FR-UX-03)
import { useQuery } from '@tanstack/react-query'
import { fetchUsersByIds } from '@/api/users'
import type { InboxItem } from '@/api/inbox'

// ─────────────────────────────────────────────────────────────────────────────
// 쿼리 키 접두사 상수
// ─────────────────────────────────────────────────────────────────────────────

/** actor 이름 조회 쿼리 키 네임스페이스 */
const ACTOR_NAMES_KEY_PREFIX = 'actor-names' as const

// ─────────────────────────────────────────────────────────────────────────────
// 순수 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * InboxItem 배열에서 고유 non-null actorUserId를 추출한다.
 * 결과는 캐시 키 안정성을 위해 오름차순 정렬된다.
 *
 * @param items Inbox 항목 배열
 * @returns 정렬된 고유 actorUserId 배열 (null 제외)
 */
function extractUniqueActorIds(items: InboxItem[]): string[] {
  const seen = new Set<string>()
  for (const item of items) {
    if (item.actorUserId !== null) {
      seen.add(item.actorUserId)
    }
  }
  return Array.from(seen).sort()
}

/**
 * UserSummary 배열을 id → 표시 이름 Map으로 변환한다.
 * 이름 폴백: displayName ?? username.
 * 미존재 id는 Map에 포함되지 않는다.
 *
 * @param users 사용자 요약 배열
 * @returns Map<userId, 표시 이름>
 */
function buildActorNameMap(
  users: Awaited<ReturnType<typeof fetchUsersByIds>>,
): Map<string, string> {
  const map = new Map<string, string>()
  for (const user of users) {
    map.set(user.id, user.displayName ?? user.username)
  }
  return map
}

// ─────────────────────────────────────────────────────────────────────────────
// 훅
// ─────────────────────────────────────────────────────────────────────────────

/**
 * InboxItem 목록의 발신자 이름을 일괄 조회하는 훅.
 *
 * - items에서 고유 non-null actorUserId를 추출 후 `fetchUsersByIds`로 1회 조회한다.
 * - actorUserId가 전부 null이거나 items가 빈 배열이면 네트워크를 호출하지 않는다
 *   (enabled: false, data: undefined).
 * - 미존재 id는 반환 Map에 포함되지 않는다 — 호출 측이 폴백(예: "시스템")을 처리한다.
 * - queryKey는 정렬된 고유 id 배열 기반으로 안정적 캐싱을 보장한다.
 *
 * @param items 현재 표시 중인 InboxItem 배열
 * @returns useQuery 결과 — `data: Map<userId, 표시 이름> | undefined`
 */
export function useActorNames(items: InboxItem[]) {
  const actorIds = extractUniqueActorIds(items)
  const enabled = actorIds.length > 0

  return useQuery({
    queryKey: [ACTOR_NAMES_KEY_PREFIX, actorIds] as const,
    queryFn: async () => {
      const users = await fetchUsersByIds(actorIds)
      return buildActorNameMap(users)
    },
    enabled,
    staleTime: 60_000,
  })
}
