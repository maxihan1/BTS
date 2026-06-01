// 사용자 목록 TanStack Query 훅 — GET /api/v1/users?query= 검색 결과 + id 다건 조회
import { useQuery } from '@tanstack/react-query'
import { fetchUsers, fetchUsersByIds } from '@/api/users'
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

/**
 * 사용자 id 다건 조회 훅.
 * 현재 담당자 이름을 안정적으로 표시하기 위해 사용한다 (C1 버그 수정).
 *
 * - queryKey: ['users', 'byIds', ids] — 검색결과 캐시와 분리
 * - ids가 빈 배열이면 쿼리 실행 안 함 (enabled: false)
 * - staleTime: 30s (사용자 정보는 자주 바뀌지 않음)
 *
 * @param ids UUID 문자열 배열
 * @returns UseQueryResult<UserSummary[]>
 */
export function useUsersByIds(ids: string[]) {
  return useQuery<UserSummary[]>({
    queryKey: ['users', 'byIds', ids],
    queryFn: () => fetchUsersByIds(ids),
    enabled: ids.length > 0,
    staleTime: 30_000,
  })
}
