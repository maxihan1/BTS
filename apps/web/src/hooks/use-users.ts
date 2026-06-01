// 사용자 목록 TanStack Query 훅 — GET /api/v1/users?query= 검색 결과
import { useQuery } from '@tanstack/react-query'
import { fetchUsers } from '@/api/users'
import type { UserSummary } from '@/api/users'

/**
 * 사용자 목록 조회 훅.
 *
 * - queryKey: ['users', query] — query 변경 시 자동 재조회
 * - query가 빈 문자열이면 전체 목록 반환
 * - debounce는 호출 측에서 처리할 것 (이 훅은 순수 데이터 레이어)
 *
 * @param query 검색어 문자열 (빈 문자열이면 전체 조회)
 * @returns UseQueryResult<UserSummary[]>
 */
export function useUsers(query: string) {
  return useQuery<UserSummary[]>({
    queryKey: ['users', query],
    queryFn: () => fetchUsers(query),
    staleTime: 30_000,
  })
}
