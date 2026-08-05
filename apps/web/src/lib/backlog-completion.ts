// 스프린트 완료 시 미완료 이슈를 안전측으로 판정하는 순수 함수 (FR-UX-13 F15 · FR-7)
import type { WorkflowView } from '@/api/workflows'

/**
 * 워크플로우 상태 카테고리 3종.
 *
 * `api/workflows.ts` 의 `stateCategorySchema` 에서 파생한다 — 여기서 다시 열거하면
 * 두 목록이 서로를 확인하지 않는 drift 가 생긴다.
 * `NonNullable` 은 `noUncheckedIndexedAccess` 가 배열 인덱스 접근에 붙이는 `undefined` 를 벗긴다.
 */
export type StateCategory = NonNullable<WorkflowView['states'][number]>['category']

/** 완료로 인정하는 유일한 카테고리 */
const DONE_CATEGORY: StateCategory = 'DONE'

/** 상태 키 → 그 키가 전체 워크플로우에서 갖는 카테고리 집합. */
export interface StateCategoryMap {
  /** 상태 키별 카테고리 집합. 어느 워크플로우에도 없는 키는 항목 자체가 없다 */
  readonly categoriesByStateKey: ReadonlyMap<string, ReadonlySet<StateCategory>>
  /** 워크플로우 목록을 받지 못했다(조회 실패·미완료 로딩). 안내 문구(E14)의 조건 */
  readonly unavailable: boolean
}

/**
 * 판정에 필요한 최소 이슈 모양.
 *
 * `BacklogIssue` 전체를 받지 않는다 — 응답 스키마에 required 필드가 늘 때마다
 * 이 모듈을 소비하는 인라인 픽스처가 전부 깨지기 때문이다. `BacklogIssue` 는
 * 이 모양을 구조적으로 만족하므로 호출부는 그대로 넘기면 된다.
 */
export interface CompletionCandidateIssue {
  readonly currentStateKey: string
}

/**
 * 전체 워크플로우의 `states` 를 훑어 상태 키 → 카테고리 집합 사상을 만든다.
 *
 * 백로그 응답(`backlogIssueSchema`)에는 카테고리 필드가 없고 `currentStateKey`
 * 문자열만 있어서, 완료 여부를 알려면 이 사상이 필요하다.
 *
 * @param workflows `useWorkflows().data`. 조회 실패·로딩 중이면 `undefined`
 * @returns 사상과 `unavailable` 플래그. 조회 실패면 빈 사상 + `unavailable: true`
 */
export function buildStateCategoryMap(
  workflows: readonly WorkflowView[] | undefined,
): StateCategoryMap {
  if (workflows === undefined) {
    return { categoriesByStateKey: new Map(), unavailable: true }
  }

  const categoriesByStateKey = new Map<string, Set<StateCategory>>()
  for (const workflow of workflows) {
    for (const state of workflow.states) {
      const categories = categoriesByStateKey.get(state.key)
      if (categories === undefined) {
        categoriesByStateKey.set(state.key, new Set([state.category]))
      } else {
        categories.add(state.category)
      }
    }
  }

  return { categoriesByStateKey, unavailable: false }
}

/**
 * 이슈가 미완료인지 판정한다. **불확실하면 전부 미완료다.**
 *
 * @param issue 판정 대상 (`currentStateKey` 만 읽는다)
 * @param map `buildStateCategoryMap` 결과
 * @returns 미완료면 `true`
 */
export function isIssueIncomplete(
  issue: CompletionCandidateIssue,
  map: StateCategoryMap,
): boolean {
  if (map.unavailable) return true

  const categories = map.categoriesByStateKey.get(issue.currentStateKey)
  if (categories === undefined) return true

  return !(categories.size === 1 && categories.has(DONE_CATEGORY))
}
