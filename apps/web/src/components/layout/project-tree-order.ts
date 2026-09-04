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
