// 여러 이슈의 가용 전이 목록에서 공통 전이(교집합)를 구하는 순수 함수
import type { IssueTransition } from '@/api/issues'

/**
 * 여러 이슈의 가용 전이 목록에서 공통 전이(교집합)를 계산한다.
 *
 * - 모든 이슈에 공통으로 존재하는 `toStateKey`를 가진 전이만 반환한다.
 * - 한 이슈라도 빈 배열이면 교집합은 항상 `[]`이다.
 * - 결과는 `toStateKey` 기준으로 중복 제거하며, 첫 번째 이슈에서 처음 등장한 항목의 `key`/`name`을 보존한다.
 * - 입력이 빈 배열(`[]`)이면 `[]`를 반환한다.
 *
 * @param perIssue - 이슈별 가용 전이 목록의 배열
 * @returns 교집합에 해당하는 전이 목록 (순서: 첫 번째 이슈의 등장 순서 기준)
 */
export const intersectTransitions = (perIssue: IssueTransition[][]): IssueTransition[] => {
  if (perIssue.length === 0) return []

  const [first, ...rest] = perIssue

  // 첫 이슈가 없거나 빈 배열이면 교집합은 0
  if (first === undefined || first.length === 0) return []

  // 나머지 이슈 중 하나라도 빈 배열이면 교집합은 0
  if (rest.some((transitions) => transitions.length === 0)) return []

  // 나머지 각 이슈에서 등장하는 toStateKey 집합을 미리 계산
  const restSets = rest.map((transitions) => new Set(transitions.map((t) => t.toStateKey)))

  // 첫 이슈를 순서대로 순회하며 toStateKey 기준 dedup + 교집합 필터
  const seen = new Set<string>()
  const result: IssueTransition[] = []

  for (const transition of first) {
    if (seen.has(transition.toStateKey)) continue

    const inAll = restSets.every((set) => set.has(transition.toStateKey))
    if (inAll) {
      seen.add(transition.toStateKey)
      result.push(transition)
    }
  }

  return result
}
