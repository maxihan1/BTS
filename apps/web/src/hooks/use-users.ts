// 사용자 목록 TanStack Query 훅 — GET /api/v1/users?query= 검색 결과 + id 다건 조회
import { useMemo } from 'react'
import { useQuery, useQueries, keepPreviousData } from '@tanstack/react-query'
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
 * - **id 가 50개를 넘으면 서버가 400 을 준다** — 그런 호출부는 {@link useUsersByIdsChunked} 를 쓸 것
 *
 * @param ids UUID 문자열 배열 (50개 이하)
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

/**
 * 사용자 다건 조회 1회 상한.
 *
 * 백엔드 `UsersController.MAX_RESULTS`(50) 와 **같은 값이어야 한다** —
 * 초과해서 보내면 `?ids=` 가 400 을 돌려준다(`UsersController.kt:71`).
 */
export const USERS_BY_IDS_CHUNK_SIZE = 50

/**
 * id 목록을 중복 제거 후 상한 이하 묶음으로 자른다.
 *
 * @param ids 사용자 UUID 목록 (중복 허용)
 * @param size 묶음당 최대 개수 — 기본 {@link USERS_BY_IDS_CHUNK_SIZE}
 * @returns 각 묶음의 길이가 `size` 이하인 2차원 배열 (입력이 비면 빈 배열)
 */
export function chunkUserIds(
  ids: string[],
  size: number = USERS_BY_IDS_CHUNK_SIZE,
): string[][] {
  const unique = [...new Set(ids)]
  const chunks: string[][] = []
  for (let i = 0; i < unique.length; i += size) {
    chunks.push(unique.slice(i, i + size))
  }
  return chunks
}

/**
 * id 개수와 무관하게 **전량** 조회한다 — 50개씩 나눠 병렬로 부르고 결과를 합친다.
 *
 * 기존 {@link useUsersByIds} 를 고치지 않고 새로 두는 이유. 그 훅은 소비처가 5곳이고
 * queryKey 구조를 바꾸면 그 5곳의 캐시·테스트가 함께 흔들린다. 백로그만 청크가 필요하다.
 *
 * 합치기를 `useQueries` 의 `combine` 으로 하는 이유. 반환값을 매 렌더 새로 만들면
 * 소비처의 `memo(BacklogColumn)` 재렌더 스킵이 통째로 죽는다(드래그 중에는
 * `overDroppableId` 변경으로 상시 재렌더된다). `combine` 은 결과가 안 바뀌면
 * 같은 참조를 돌려주므로 그 최적화를 지킨다.
 *
 * @param ids 담당자 UUID 목록 (중복 허용 — 내부에서 제거)
 * @returns `data` 합쳐진 사용자 목록 · `isError` 한 묶음이라도 실패했는지
 */
export function useUsersByIdsChunked(ids: string[]) {
  const chunks = useMemo(() => chunkUserIds(ids), [ids])
  return useQueries({
    queries: chunks.map((chunk) => ({
      queryKey: ['users', 'byIds', chunk],
      queryFn: () => fetchUsersByIds(chunk),
      staleTime: 30_000,
    })),
    combine: (results) => ({
      data: results.flatMap((r) => r.data ?? []),
      isError: results.some((r) => r.isError),
    }),
  })
}
