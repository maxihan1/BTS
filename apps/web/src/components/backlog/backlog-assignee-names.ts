// 백로그 카드 담당자 이름 조립 — 순수 함수 (FR-UX-13 F5)
import type { BacklogView } from '@/api/backlog'
import type { UserSummary } from '@/api/users'

/** 백로그 + 전 스프린트 이슈를 한 줄로 편다. view 가 없으면 빈 배열. */
function allIssues(view: BacklogView | undefined) {
  if (view === undefined) return []
  return [...view.backlog, ...view.sprints.flatMap((sprintGroup) => sprintGroup.issues)]
}

/**
 * 화면에 실제로 등장하는 담당자 UUID 를 중복 없이 모은다.
 *
 * `undefined` 를 받아내는 이유 — `BacklogBoard` 의 조기 반환보다 앞에서 호출되므로
 * 데이터가 아직 없는 렌더에서도 반드시 안전해야 한다.
 */
export function collectAssigneeIds(view: BacklogView | undefined): string[] {
  const ids = new Set<string>()
  for (const issue of allIssues(view)) {
    if (issue.assigneeId !== null) ids.add(issue.assigneeId)
  }
  return [...ids]
}

/**
 * issueKey → 담당자 표시이름 Map.
 *
 * 이름을 못 찾은 담당자는 **넣지 않는다** — `BacklogCard` 의 `AssigneeSlot` 이
 * 「이름 없음 + assigneeId 있음」을 `?` 로, 「이름 없음 + assigneeId 없음」을 `미배정` 로
 * 이미 가른다. 여기서 빈 문자열 같은 걸 넣으면 그 3상태가 깨진다.
 *
 * ★공통화 시점 조건 (지금은 합치지 않는다). 담당자 이름을 푸는 지점이 이 파일 포함 **다섯**이다 —
 * `routes/projects.$projectKey.timeline.tsx` `buildAssigneeNames`(issueKey→이름, 계약 동일) ·
 * `routes/projects.$projectKey.board.tsx` `buildAssigneeNames`(issueKey→**3상태 union**) ·
 * `routes/issues.index.tsx` 인라인 `assigneeNameMap`(userId→이름) ·
 * `components/filters/FilterBar.tsx` 인라인 `assigneeNameMap`(userId→이름).
 * 입력 타입이 전부 다르고 board 만 출력이 union 이라 지금 묶으면 제네릭 + 어댑터가 본체보다 커진다.
 * **여섯 번째 지점이 생기거나 board 의 3상태를 다른 화면이 필요로 하는 순간**
 * `{ key, assigneeId }[]` 를 받는 공통 함수로 합칠 것.
 */
export function buildAssigneeNameMap(
  view: BacklogView | undefined,
  users: UserSummary[],
): Map<string, string> {
  const nameById = new Map(users.map((user) => [user.id, user.displayName ?? user.username]))
  const map = new Map<string, string>()
  for (const issue of allIssues(view)) {
    if (issue.assigneeId === null) continue
    const name = nameById.get(issue.assigneeId)
    if (name !== undefined) map.set(issue.key, name)
  }
  return map
}
