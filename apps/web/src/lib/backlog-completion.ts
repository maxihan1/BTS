// 스프린트 완료 시 미완료 이슈를 안전측으로 판정하는 순수 함수 — 보드 맨 오른쪽 열 = 완료 (결정 D3 · FR-UX-13 F15 FR-7)
import type { BoardColumn } from '@/api/boards'

/** 보드 맨 오른쪽 열이 담은 상태 키 집합 */
export interface DoneColumnMap {
  /** 완료로 인정하는 상태 키 — 맨 오른쪽 열에 매핑된 상태들 */
  readonly doneStateKeys: ReadonlySet<string>
  /** 맨 오른쪽 열 이름. 다이얼로그 설명 문구에 쓴다. 열이 없으면 null */
  readonly lastColumnName: string | null
  /** 보드를 받지 못했다(조회 실패·미완료 로딩). 안내 문구(E14)의 조건 */
  readonly unavailable: boolean
}

/**
 * 판정에 필요한 최소 이슈 모양.
 *
 * `BacklogIssue` 전체를 받지 않는다 — 응답 스키마에 required 필드가 늘 때마다
 * 이 모듈을 소비하는 인라인 픽스처가 전부 깨지기 때문이다.
 */
export interface CompletionCandidateIssue {
  readonly currentStateKey: string
}

/**
 * 스프린트가 속한 보드의 열에서 맨 오른쪽(`displayOrder` 최대) 열의 상태 키를 모은다.
 *
 * Jira company-managed 와 같은 규칙이다 — 「완료된 업무 항목에는 보드의 마지막 열에 있는
 * 모든 내용이 포함됩니다」(갭 JCB-5). 상태 카테고리 DONE 은 보지 않는다(결정 D3).
 *
 * @param columns `useBoard(sprint.boardId).data?.columns`. 조회 실패·로딩 중이면 `undefined`
 */
export function buildDoneColumnMap(columns: readonly BoardColumn[] | undefined): DoneColumnMap {
  if (columns === undefined) {
    return { doneStateKeys: new Set(), lastColumnName: null, unavailable: true }
  }
  const last = columns.reduce<BoardColumn | undefined>(
    (max, col) => (max === undefined || col.displayOrder > max.displayOrder ? col : max),
    undefined,
  )
  return {
    doneStateKeys: new Set(last?.states.map((state) => state.key) ?? []),
    lastColumnName: last?.name ?? null,
    unavailable: false,
  }
}

/**
 * 이슈가 미완료인지 판정한다. **불확실하면 전부 미완료다.**
 *
 * | 조건 | 판정 |
 * |---|---|
 * | 상태가 맨 오른쪽 열에 매핑됐다 | 완료 |
 * | 다른 열 · 어느 열에도 없는 상태 | 미완료 |
 * | 보드 조회 자체가 실패 (`unavailable`) | 미완료 |
 *
 * ★ 왜 한쪽으로만 기우는가 (ADR C1 — 완료는 되돌릴 수 없다).
 * 과다 분류(완료를 미완료로 봄)는 이관 대상이 한 건 느는 것뿐이고,
 * `DELETE /api/v1/sprints/{id}/issues/{key}` 가 멱등 204 라 손실이 없다.
 * 반면 **과소 분류(미완료를 완료로 봄)는 이슈를 영구 동결시킨다.**
 * 스프린트가 COMPLETED 가 된 뒤에는 제거 API 가 `STATUS <> 'COMPLETED'` 조건부 DELETE 라
 * 조용히 204 만 주고 아무 행도 지우지 않으며, `sprint_issues` 의 `UNIQUE (issue_key)`
 * 때문에 다른 스프린트로 옮길 수도 없다. 꺼낼 방법이 남지 않는다.
 *
 * @param issue 판정 대상 (`currentStateKey` 만 읽는다)
 * @param map `buildDoneColumnMap` 결과
 * @returns 미완료면 `true`
 */
export function isIssueIncomplete(issue: CompletionCandidateIssue, map: DoneColumnMap): boolean {
  if (map.unavailable) return true
  return !map.doneStateKeys.has(issue.currentStateKey)
}
