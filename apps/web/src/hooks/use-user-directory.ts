// 사용자 디렉토리 검색 TanStack Query 훅 — typeahead 용 enabled 가드 (FR-PM-01)
import { useQuery } from '@tanstack/react-query'
import { searchUsers } from '@/api/users'
import type { UserSummary } from '@/api/users'

// ─────────────────────────────────────────────────────────────────────────────
// queryKey 상수
// ─────────────────────────────────────────────────────────────────────────────

/** 사용자 검색 queryKey 상수 */
export const USER_SEARCH_KEYS = {
  /** 검색 질의별 queryKey */
  search: (query: string) => ['users', 'search', query] as const,
} satisfies Record<string, (...args: string[]) => readonly string[]>

/** 검색을 활성화하는 최소 쿼리 길이 */
const MIN_QUERY_LENGTH = 2

// ─────────────────────────────────────────────────────────────────────────────
// useUserSearch
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 사용자 디렉토리를 검색한다.
 *
 * GET /api/v1/users?query={query} → UserSummary[]
 *
 * query 길이가 {@link MIN_QUERY_LENGTH}(2) 미만이면 요청을 보내지 않는다.
 * 멤버 추가 typeahead UI에서 짧은 입력으로 과도한 요청이 발생하는 것을 방지한다.
 *
 * PII(email) 포함 응답이므로 로그 출력 금지.
 *
 * @param query 검색 질의 문자열
 * @returns UserSummary 배열을 담은 쿼리 결과. query 미달 시 data는 undefined.
 */
export function useUserSearch(query: string) {
  const enabled = query.length >= MIN_QUERY_LENGTH

  return useQuery<UserSummary[]>({
    queryKey: USER_SEARCH_KEYS.search(query),
    queryFn: () => searchUsers(query),
    enabled,
    staleTime: 10_000,
  })
}
