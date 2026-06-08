// 사용자 그룹 목록 조회 TanStack Query 훅 (FR-PM-07)
import { useQuery } from '@tanstack/react-query'
import { fetchGroups } from '@/api/groups'

// ─────────────────────────────────────────────────────────────────────────────
// queryKey 팩토리 — 매직 문자열 방지
// ─────────────────────────────────────────────────────────────────────────────

/** 그룹 BC queryKey 팩토리 */
export const GROUP_KEYS = {
  /** 전체 그룹 목록 queryKey */
  all: ['groups'] as const,
} satisfies Record<string, readonly string[]>

// ─────────────────────────────────────────────────────────────────────────────
// useGroups — 목록 조회
// ─────────────────────────────────────────────────────────────────────────────

/** useGroups 옵션 타입 */
export interface UseGroupsOptions {
  /** false이면 쿼리를 idle 상태로 유지해 fetch를 지연한다. 기본값 true. */
  enabled?: boolean
}

/**
 * 전체 그룹 목록(멤버 수 포함)을 조회한다.
 *
 * GET /api/v1/groups → GroupResponse[]
 * staleTime 60초 — 그룹 목록은 자주 바뀌지 않으므로 커스텀 필드보다 긴 stale 시간을 사용한다.
 *
 * SYSTEM_ADMIN 전용 엔드포인트다. 권한 없는 사용자는 403 에러를 받는다.
 *
 * @param options 쿼리 옵션 — enabled: false이면 즉시 fetch하지 않음 (lazy 로드)
 * @returns GroupResponse 배열
 */
export function useGroups(options?: UseGroupsOptions) {
  return useQuery({
    queryKey: GROUP_KEYS.all,
    queryFn: () => fetchGroups(),
    staleTime: 60_000,
    enabled: options?.enabled ?? true,
  })
}
