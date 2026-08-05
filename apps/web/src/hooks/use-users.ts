// 사용자 목록 TanStack Query 훅 — GET /api/v1/users?query= 검색 결과 + id 다건 조회
import { useMemo } from 'react'
import {
  useQuery,
  useQueries,
  useQueryClient,
  keepPreviousData,
} from '@tanstack/react-query'
import type { QueryClient } from '@tanstack/react-query'
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
 * id 목록을 중복 제거 + **사전순 정렬** 후 상한 이하 묶음으로 자른다.
 *
 * ★정렬이 계약이다. 이 묶음이 그대로 `queryKey` 에 들어가는데 TanStack Query 의 배열
 * queryKey 는 **순서 민감**이다. 삽입 순서를 그대로 두면 담당자 집합이 똑같아도 순서만
 * 바뀌는 순간 다른 캐시 엔트리가 되어 `data` 가 한 번 빈 배열로 떨어지고, 그 결과로
 * 이름 맵을 만드는 화면은 **전 카드가 `?` 로 깜빡인다**. 백로그의 주 조작인 드래그
 * 재정렬이 정확히 그 경로다 (재정렬 → 백로그 재조회 → 이슈 순서 변경 → 담당자 id 수집
 * 순서 반전).
 *
 * @param ids 사용자 UUID 목록 (중복 허용)
 * @param size 묶음당 최대 개수 — 기본 {@link USERS_BY_IDS_CHUNK_SIZE}
 * @returns 각 묶음의 길이가 `size` 이하이고 전체가 사전순인 2차원 배열 (입력이 비면 빈 배열)
 */
export function chunkUserIds(
  ids: string[],
  size: number = USERS_BY_IDS_CHUNK_SIZE,
): string[][] {
  const unique = [...new Set(ids)].sort()
  const chunks: string[][] = []
  for (let i = 0; i < unique.length; i += size) {
    chunks.push(unique.slice(i, i + size))
  }
  return chunks
}

/**
 * 이미 조회해 둔 사용자 캐시에서 `ids` 에 해당하는 것만 건져낸다.
 *
 * `['users','byIds', …]` 로 시작하는 모든 캐시 엔트리를 훑는다 — {@link useUsersByIds} 가
 * 채운 것도 같은 사용자이므로 함께 쓴다.
 *
 * @param client 조회할 QueryClient
 * @param ids 찾으려는 사용자 UUID 목록
 * @returns 하나라도 찾으면 그 사용자 목록, 아무것도 못 찾으면 `undefined` (= placeholder 없음)
 */
function pickCachedUsers(client: QueryClient, ids: string[]): UserSummary[] | undefined {
  const wanted = new Set(ids)
  const found = new Map<string, UserSummary>()
  for (const [, cached] of client.getQueriesData<UserSummary[]>({
    queryKey: ['users', 'byIds'],
  })) {
    if (cached === undefined) continue
    for (const user of cached) {
      if (wanted.has(user.id)) found.set(user.id, user)
    }
  }
  return found.size === 0 ? undefined : [...found.values()]
}

/**
 * id 개수와 무관하게 **전량** 조회한다 — 50개씩 나눠 병렬로 부르고 결과를 합친다.
 *
 * 기존 {@link useUsersByIds} 를 고치지 않고 새로 두는 이유. 그 훅은 소비처가 많고
 * queryKey 구조를 바꾸면 그 소비처들의 캐시·테스트가 함께 흔들린다. 백로그만 청크가 필요하다.
 *
 * 합치기를 `useQueries` 의 `combine` 으로 하는 이유. 반환값을 매 렌더 새로 만들면
 * 소비처의 `memo(BacklogColumn)` 재렌더 스킵이 통째로 죽는다(드래그 중에는
 * `overDroppableId` 변경으로 상시 재렌더된다). `combine` 은 결과가 안 바뀌면
 * 같은 참조를 돌려주므로 그 최적화를 지킨다.
 *
 * 묶음마다 placeholder 를 채우는 이유. queryKey 에 id 묶음이 들어가므로 담당자가 한 명만
 * 늘어도 캐시 미스가 나고, 그 순간 **이미 알던 이름까지** 잃어 전 카드가 `?` 로 깜빡인다
 * (리뷰 C3 와 같은 사고 — 백로그에서는 담당자를 지정해 이슈를 생성하는 경로가 이에 해당한다).
 * {@link useUsersByIds} 와 달리 이 훅은 소비처가 백로그 하나뿐이고 "결과에 없다" 를
 * 삭제 사용자 신호로 쓰지 않으므로 상시 켠다.
 *
 * ★그 placeholder 를 {@link keepPreviousData} 로 만들 수 없다. `useQueries` 는 묶음의
 * `queryHash` 로만 기존 옵저버를 재사용하는데(`queriesObserver.#findMatchingObservers`),
 * 묶음이 바뀌면 매칭에 실패해 **새 QueryObserver** 가 생기고 그 옵저버에는
 * `keepPreviousData` 가 읽는 직전 결과(`#lastQueryWithDefinedData`)가 아예 없다.
 * 그래서 묶음이 바뀌는 시점의 **캐시에서 직접** 아는 이름을 건져 넣는다
 * ({@link pickCachedUsers}). 값은 `useMemo` 로 참조를 고정한다 — 매 렌더 새 배열을 주면
 * 옵저버가 placeholder 를 다시 만들어 `data` 참조 안정성이 깨진다.
 *
 * @param ids 담당자 UUID 목록 (중복 허용 — 내부에서 제거). **참조가 안정적이어야 한다** —
 *   묶음 계산이 `useMemo(…, [ids])` 라 매 렌더 새 배열을 넘기면 `combine` 의 참조 안정성
 *   보장이 무효가 된다. 호출부에서 `useMemo` 로 감쌀 것
 * @returns `UseQueryResult` 전체가 아니라 **축약형** — 합쳐진 사용자 목록을 담은 `data` 하나뿐이다
 *   (이름이 대칭인 {@link useUsersByIds} 와 계약이 다르니 서로 대체할 수 없다).
 *   이름을 못 찾은 담당자는 소비처가 `?` 로 판정하므로 실패 표면을 따로 내보내지 않는다 (fail-soft)
 */
export function useUsersByIdsChunked(ids: string[]) {
  const queryClient = useQueryClient()
  const chunks = useMemo(() => chunkUserIds(ids), [ids])
  const placeholders = useMemo(
    () => chunks.map((chunk) => pickCachedUsers(queryClient, chunk)),
    [chunks, queryClient],
  )
  return useQueries({
    queries: chunks.map((chunk, index) => ({
      queryKey: ['users', 'byIds', chunk],
      queryFn: () => fetchUsersByIds(chunk),
      staleTime: 30_000,
      placeholderData: placeholders[index],
    })),
    combine: (results) => ({
      data: results.flatMap((r) => r.data ?? []),
    }),
  })
}
