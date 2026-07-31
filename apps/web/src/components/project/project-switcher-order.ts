// 프로젝트 스위처의 목록 구간 분리 + 착지점 판정 — 순수 함수 (FR-UX-08 PR-A Task 5)
//
// 컴포넌트(`ProjectSwitcher.tsx`)에서 분리한 이유 두 가지.
// 1. 라우터·쿼리 없이 단독 테스트할 수 있다.
// 2. 컴포넌트 파일이 컴포넌트 외 심볼을 export 하면 `react-refresh/only-export-components`
//    경고가 붙는다 — 새 경고를 남기지 않는다.
import type { Project } from '@/api/projects'

/** 스위처 목록의 두 구간 */
export interface SwitcherPartition {
  /** 최근 방문 그룹 — MRU 순. 접근 가능 목록에 없는 키는 탈락한다(E1) */
  readonly recent: readonly Project[]
  /** 나머지 — **백엔드 순서 그대로**(name 오름차순). 프론트 재정렬 금지 */
  readonly rest: readonly Project[]
}

/**
 * 프로젝트 목록을 "최근 방문 그룹 + 나머지" 두 구간으로 나눈다.
 *
 * **이것은 재정렬이 아니라 구간 분리다.** `rest` 는 백엔드가 준 순서
 * (`ProjectQueryRepository.kt:62` `ORDER BY name ASC`)를 그대로 보존한다 — 저장소 관례
 * "백엔드 정렬 신뢰, 프론트 재정렬 없음"(FR-UX-07 FR3)이 그 구간에 그대로 적용된다.
 *
 * @param projects 접근 가능한 프로젝트 목록 (백엔드 순서)
 * @param recentKeys 최근 방문 키 목록 (MRU 순). 접근 불가 키가 섞여 있을 수 있다
 * @returns 두 구간. 같은 프로젝트가 양쪽에 중복 등장하지 않는다(E11)
 */
export function partitionProjectsForSwitcher(
  projects: readonly Project[],
  recentKeys: readonly string[],
): SwitcherPartition {
  const byKey = new Map(projects.map((p) => [p.key, p]))
  // 접근 가능 목록에 실재하는 키만 남긴다 — 삭제·권한 회수된 키는 조용히 탈락(E1)
  const recent = recentKeys
    .map((key) => byKey.get(key))
    .filter((p): p is Project => p !== undefined)
  const recentKeySet = new Set(recent.map((p) => p.key))
  const rest = projects.filter((p) => !recentKeySet.has(p.key))
  return { recent, rest }
}

/** 스위처 선택 시 착지점 종류 — 스펙 §1-B */
export type SwitcherLanding = 'path' | 'search' | 'none'

/**
 * 선택한 프로젝트로 어떻게 착지할지 정한다 (스펙 §1-B, plan 리뷰 BLOCKER-1).
 *
 * **판별자는 "URL 이 `projectKey` 를 담고 있는가" 다.** 경로 파라미터만 보면
 * `/issues?projectKey=ATLAS` 가 어느 분기에도 안 걸려 스위처가 먹지 않는다 — 활성값만
 * 바꿔도 해소 ①(URL)이 이기고, `useResolvedActiveProject:85-91` 이 원래 키를 **되기록**해
 * 선택이 즉시 되돌려진다. 경로 파라미터 경우에는 `useTrackActiveProject:50` 도 같은 일을
 * 하므로 **되기록 지점이 둘**이다.
 *
 * @param pathProjectKey `/projects/$projectKey/*` 경로 파라미터
 * @param searchProjectKey `?projectKey=` 검색 파라미터
 */
export function resolveSwitcherLanding(
  pathProjectKey: string | undefined,
  searchProjectKey: string | undefined,
): SwitcherLanding {
  if (pathProjectKey !== undefined && pathProjectKey.length > 0) return 'path'
  if (searchProjectKey !== undefined && searchProjectKey.length > 0) return 'search'
  return 'none'
}
