// 사이드바 스페이스 트리의 3그룹 분할 — 별표/최근/추가 순수 함수 (Jira 패리티 캠페인 PR ⑩ · J2)
//
// `components/project/project-switcher-order.ts` 의 2구간 분할과 **형제**이되 별개다.
// 스위처는 팝오버 안에서 `{ recent, rest }` 둘로 나누고, 트리는 사이드바에서 셋으로 나눈다.
// 하나로 합치면 한쪽의 그룹 수를 바꾸는 순간 다른 쪽 화면이 함께 움직인다.
import type { Project } from '@/api/projects'

/** 사이드바 스페이스 트리의 세 구간 */
export interface TreePartition {
  /** 별표 표시됨 — 백엔드 순서(name 오름차순) 보존 */
  readonly starred: readonly Project[]
  /** 최근 방문 — MRU 순(맨 앞이 가장 최근). 별표에 든 것은 빠진다 */
  readonly recent: readonly Project[]
  /** 나머지 — 백엔드 순서 그대로. 프론트 재정렬 금지 */
  readonly more: readonly Project[]
}

/**
 * 프로젝트 목록을 「별표 표시됨 · 최근 방문 · 추가 스페이스」 세 구간으로 나눈다.
 *
 * **재정렬이 아니라 구간 분리다.** `starred` 와 `more` 는 백엔드가 준 순서
 * (`ProjectQueryRepository.kt` `ORDER BY name ASC`)를 그대로 보존한다 — 저장소 관례
 * "백엔드 정렬 신뢰, 프론트 재정렬 없음"(FR-UX-07 FR3). `recent` 만 MRU 순인데,
 * 그 순서 자체가 정보이기 때문이다.
 *
 * ### 겹칠 때는 별표가 이긴다
 * 한 프로젝트가 별표이면서 최근 방문일 수 있다. 양쪽에 렌더하면 같은 행이 두 번 보이고
 * React `key` 도 중복된다. 별표는 사용자가 **명시적으로** 남긴 의사표시이고 최근 방문은
 * 부수효과로 쌓인 기록이라 별표가 가져간다.
 *
 * @param projects 접근 가능한 프로젝트 목록 (백엔드 순서)
 * @param favoriteKeys 즐겨찾기한 프로젝트 키 — `useFavorites('PROJECT')` 의 `targetId`.
 *   접근 불가 키가 섞여 있을 수 있다
 * @param recentKeys 최근 방문 프로젝트 키 (MRU 순). 마찬가지로 접근 불가 키가 섞일 수 있다
 * @returns 세 구간. 합은 언제나 `projects` 와 같고, 어떤 프로젝트도 두 구간에 걸치지 않는다
 */
export function partitionProjectsForTree(
  projects: readonly Project[],
  favoriteKeys: readonly string[],
  recentKeys: readonly string[],
): TreePartition {
  const byKey = new Map(projects.map((p) => [p.key, p]))

  /**
   * 키 목록을 실재하는 프로젝트로 바꾼다. 접근 가능 목록에 없는 키(삭제·권한 회수)와
   * 중복은 조용히 탈락한다 — 중복 제거가 없으면 같은 행이 두 번 렌더된다.
   */
  const resolve = (keys: readonly string[], taken: ReadonlySet<string>): Project[] => {
    const seen = new Set<string>()
    const resolved: Project[] = []
    for (const key of keys) {
      if (seen.has(key) || taken.has(key)) continue
      const project = byKey.get(key)
      if (project === undefined) continue
      seen.add(key)
      resolved.push(project)
    }
    return resolved
  }

  const starredKeys = new Set(resolve(favoriteKeys, new Set()).map((p) => p.key))
  // 별표는 백엔드 순서로 되돌린다 — 즐겨찾기 API 의 created_at DESC 를 화면에 노출하지 않는다
  const starred = projects.filter((p) => starredKeys.has(p.key))

  // 최근은 MRU 순 그대로 두되 별표에 든 것은 뺀다
  const recent = resolve(recentKeys, starredKeys)
  const recentKeySet = new Set(recent.map((p) => p.key))

  const more = projects.filter((p) => !starredKeys.has(p.key) && !recentKeySet.has(p.key))

  return { starred, recent, more }
}

/**
 * 한 번에 보드 목록을 조회할 프로젝트 수 상한.
 *
 * 상한이 필요한 이유. 펼침 집합은 `useProjectTreeExpanded` 가 localStorage 에 영속하고
 * `expand()` 는 **더하기만** 한다 — 프로젝트를 방문할 때마다 키가 하나씩 쌓이고 지워지지
 * 않는다. 상한이 없으면 프로젝트 20개를 며칠에 걸쳐 방문한 사용자가 `/dashboards` 처럼
 * **보드가 한 개도 안 보이는 화면**을 풀 리로드하는 것만으로 `GET /api/v1/boards` 를
 * 20건 동시에 쏜다. `staleTime` 은 콜드 로드를 막지 못한다.
 *
 * `use-backlog-epics.ts` 의 `EPIC_NAME_LOOKUP_LIMIT` 이 같은 이유로 둔 상한이고, 이 값은
 * 그 선례를 따른 것이다 — 사이드바에 **동시에 펼쳐 둘 만한** 프로젝트 수의 현실적 상한.
 */
export const EXPANDED_BOARD_LOOKUP_LIMIT = 8

/**
 * 펼쳐진 프로젝트 중 **보드 목록을 실제로 조회할** 키를 고른다.
 *
 * ### 활성 프로젝트가 언제나 맨 앞이다
 * 상한에 걸려 잘리는 것은 목록 뒤쪽인데, 사용자가 **지금 보고 있는** 프로젝트가 거기
 * 있으면 정작 눈앞의 보드 목록이 비어 버린다. 활성 키를 앞으로 당겨 그 자리를 없앤다.
 *
 * 잘린 프로젝트의 행은 보드 목록만 비고 백로그·타임라인·리포트·설정 링크는 그대로 남는다 —
 * 화면이 깨지지 않는 것이 이 상한을 안전하게 만드는 근거다.
 *
 * @param expandedKeys 펼쳐진 프로젝트 키 (프로젝트 목록 순)
 * @param activeKey URL 이 담은 프로젝트 키. 없거나 펼쳐져 있지 않으면 순서를 바꾸지 않는다
 * @param limit 조회 상한. 기본값 {@link EXPANDED_BOARD_LOOKUP_LIMIT}
 * @returns 조회할 키 (활성 우선, 그 뒤는 입력 순). 길이는 언제나 `limit` 이하
 */
export function pickBoardLookupKeys(
  expandedKeys: readonly string[],
  activeKey: string | undefined,
  limit: number = EXPANDED_BOARD_LOOKUP_LIMIT,
): string[] {
  const ordered =
    activeKey !== undefined && expandedKeys.includes(activeKey)
      ? [activeKey, ...expandedKeys.filter((key) => key !== activeKey)]
      : [...expandedKeys]
  return ordered.slice(0, limit)
}
