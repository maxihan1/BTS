// 사용자 목록 TanStack Query 훅 — GET /api/v1/users?query= 검색 결과 + id 다건 조회
import { useQuery, keepPreviousData } from '@tanstack/react-query'
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

/** {@link useUsersByIds} 선택 옵션 */
export interface UseUsersByIdsOptions {
  /**
   * id 집합이 바뀌는 동안 **이전 결과를 유지**할지 (기본 false — 기존 동작).
   *
   * queryKey 에 `ids` 가 들어가므로 id 하나만 늘어도 **캐시 미스 → 빈 배열**이 되고,
   * 이 결과로 만든 이름 맵을 쓰는 화면은 **이미 알던 이름까지 순간 잃는다**
   * (이슈 목록 담당자 낙관 갱신에서 실제로 발생 — 리뷰 C3).
   *
   * ★기본값을 바꾸지 않는 이유. "id 는 있는데 조회 결과에 없다" 를 **삭제/비활성 사용자**의
   * 신호로 쓰는 소비처가 있다(`projects.$projectKey.settings.project-lead.tsx:84`).
   * 전역으로 켜면 낡은 사용자가 남아 그 판정을 가린다. `OooModal` 도 로딩 윈도용 fallback
   * 을 따로 갖고 있다. 그래서 **필요한 호출부만 켜는 opt-in** 으로 둔다.
   */
  keepPreviousWhileIdsChange?: boolean
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
 * @param options 선택 옵션 — {@link UseUsersByIdsOptions}
 * @returns UseQueryResult<UserSummary[]>
 */
export function useUsersByIds(ids: string[], options: UseUsersByIdsOptions = {}) {
  return useQuery<UserSummary[]>({
    queryKey: ['users', 'byIds', ids],
    queryFn: () => fetchUsersByIds(ids),
    enabled: ids.length > 0,
    staleTime: 30_000,
    placeholderData:
      options.keepPreviousWhileIdsChange === true ? keepPreviousData : undefined,
  })
}
