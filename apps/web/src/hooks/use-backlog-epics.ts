// 백로그 응답에서 에픽 키를 모아 이름을 해석하는 훅 (FR-UX-13 F16 Task 3)
import { useMemo } from 'react'
import { useQueries } from '@tanstack/react-query'
import { fetchIssue } from '@/api/issues'
import { issueQueryKey } from '@/api/useUpdateIssueSummary'
import type { BacklogView } from '@/api/backlog'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이름을 조회할 에픽 개수 상한.
 *
 * 에픽 목록 API 가 없어 이름을 **에픽 1건당 요청 1건**으로 얻는다. 상한이 없으면
 * 이슈가 1,000건을 넘어 `truncated` 가 걸리는 프로젝트에서 백로그를 여는 것만으로
 * 수백 건의 요청이 한꺼번에 나간다. 상한을 넘는 에픽은 **조회하지 않고 키로 보여준다** —
 * 이름이 사라지는 것보다 키라도 남는 편이 낫다.
 */
export const EPIC_NAME_LOOKUP_LIMIT = 50

/** 에픽 이름 캐시 유지 시간(ms). 에픽 제목은 거의 안 바뀌므로 백로그 조회와 같은 30초를 쓴다. */
const EPIC_NAME_STALE_TIME_MS = 30_000

// ─────────────────────────────────────────────────────────────────────────────
// 순수 파생
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 백로그 + 전 스프린트에 등장하는 에픽 키를 중복 없이 모은다 (등장 순).
 *
 * `undefined` 를 받아내는 이유 — 소비처의 조기 반환(`isLoading`)보다 **위**에서 호출되므로
 * 데이터가 아직 없는 렌더에서도 안전해야 한다 (`collectAssigneeIds` 선례).
 */
function collectEpicKeys(view: BacklogView | undefined): string[] {
  if (view === undefined) return []
  const keys = new Set<string>()
  for (const issue of [...view.backlog, ...view.sprints.flatMap((group) => group.issues)]) {
    if (issue.epicKey !== null) keys.add(issue.epicKey)
  }
  return [...keys]
}

// ─────────────────────────────────────────────────────────────────────────────
// 훅
// ─────────────────────────────────────────────────────────────────────────────

/** {@link useBacklogEpics} 반환값 */
export interface BacklogEpics {
  /**
   * 백로그에 등장하는 에픽 키 (중복 제거, 등장 순).
   * **조회 상한과 무관하게 전량**이다 — 이름을 못 얻은 에픽도 목록에서 사라지지 않는다.
   */
  epicKeys: string[]
  /**
   * 에픽 키 → 표시 이름.
   *
   * {@link epicKeys} 의 **모든 항목에 엔트리가 있고**, 이름을 아직 못 얻었거나(조회 중)
   * 못 얻는 경우(조회 실패 · 상한 초과)에는 **값이 키 자체**다. 빈 문자열이나 엔트리 부재를
   * 돌려주지 않는다 — 화면에서 이름이 통째로 사라지는 것보다 키가 보이는 편이 낫다.
   */
  epicNames: Map<string, string>
}

/**
 * 백로그 응답에서 에픽 키를 모아 **키 → 이름** 맵을 만든다.
 *
 * ### 왜 백로그 응답에서 파생하나
 * 프로젝트의 에픽 목록을 주는 전용 API 가 없다. AQL 의 `MVP_FIELDS` 에 `type` 축이 없고
 * 이슈 목록 필터에도 유형 축이 없어, 백로그 응답의 `epicKey` 를 distinct 로 모으는 것이
 * 유일한 경로다. 이름만 기존 이슈 상세 조회(`GET /api/v1/issues/{key}`)로 채운다 —
 * **신규 API 0**.
 *
 * ### 조회 계약
 * - 요청 수는 카드 수가 아니라 **distinct 에픽 수**이며, {@link EPIC_NAME_LOOKUP_LIMIT} 에서 멈춘다.
 * - `queryKey` 는 이슈 상세 화면과 **같은 키**(`issueQueryKey`)라 세션 중 캐시에 적중한다
 *   (`RecentIssuesMenu` 선례).
 * - `retry: false` — 403/404 는 재시도해도 결과가 같고 백로그 표시만 늦춘다.
 *
 * ### 반환 참조 안정성
 * `useQueries` 의 `combine` 이 결과가 안 바뀌면 같은 참조를 돌려주므로(`replaceEqualDeep`),
 * 소비처의 `memo` 재렌더 스킵이 살아 있다. 그래서 `combine` 은 **원시값 배열**만 돌려주고
 * (`Map` 은 deep-equal 대상이 아니라 매번 새 참조가 된다) 맵 조립은 바깥 `useMemo` 가 한다.
 *
 * @param view 백로그 전체 뷰. 아직 로딩 중이면 `undefined` (그 렌더에서는 조회가 나가지 않는다)
 */
export function useBacklogEpics(view: BacklogView | undefined): BacklogEpics {
  const epicKeys = useMemo(() => collectEpicKeys(view), [view])
  const lookupKeys = useMemo(() => epicKeys.slice(0, EPIC_NAME_LOOKUP_LIMIT), [epicKeys])

  // summaries[i] 는 lookupKeys[i] 의 이름. 아직 못 얻었으면 null.
  // `isPending` 을 따로 보지 않는다 — 조회 중과 실패는 화면에서 같은 처리(키 표시)이고,
  // `isPending ⟹ data === undefined` 라 둘을 나누면 도달 불가 분기가 된다 (F15 실측).
  const { summaries } = useQueries({
    queries: lookupKeys.map((epicKey) => ({
      queryKey: issueQueryKey(epicKey),
      queryFn: () => fetchIssue(epicKey),
      staleTime: EPIC_NAME_STALE_TIME_MS,
      retry: false,
    })),
    combine: (results) => ({
      summaries: results.map((result) => result.data?.summary ?? null),
    }),
  })

  return useMemo(() => {
    const epicNames = new Map<string, string>()
    // 먼저 전량을 키로 채운다 — 이후 이름을 얻은 것만 덮는다.
    for (const epicKey of epicKeys) epicNames.set(epicKey, epicKey)
    lookupKeys.forEach((epicKey, index) => {
      const summary = summaries[index]
      if (summary !== null && summary !== undefined) epicNames.set(epicKey, summary)
    })
    return { epicKeys, epicNames }
  }, [epicKeys, lookupKeys, summaries])
}
