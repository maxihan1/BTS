// 백로그 클라이언트 필터 순수 함수 — URL search 왕복 + 표시용 파생 뷰 (FR-UX-13 F16)
import type { BacklogView } from '@/api/backlog'
import type { BoardCardFilterParams } from '@/api/boards'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 「에픽 없음」을 가리키는 예약 센티널.
 *
 * 이슈 키 문법(`PROJ-123`)과 겹치지 않는 값이어야 실제 에픽 키와 충돌하지 않는다.
 */
export const NO_EPIC = '__none__'

/** 미배정 담당자 센티널 — `lib/board-filter.ts` 와 같은 문자열(백엔드 계약 FR-BD-02). */
const UNASSIGNED = 'unassigned'

// ─────────────────────────────────────────────────────────────────────────────
// 타입
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 백로그 필터 축 4종.
 *
 * **`labels`·`componentIds` 는 의도적으로 없다.** 백로그 응답(`backlogIssueSchema`)에
 * 두 필드가 아예 없어 클라이언트가 거를 근거가 없다. 모델이 필드를 들고 있으면 픽스처가
 * 그것을 채울 수 있고, 결국 **도달 불가 상태를 지키는 가짜 테스트**가 생긴다.
 * `FilterBar` 가 요구하는 두 빈 배열은 {@link toFilterBarValue} 경계에서만 만든다.
 */
export interface BacklogFilter {
  /** 제목 부분일치 검색어. 공백만 있는 값은 조건으로 치지 않는다. */
  query: string
  /** 담당자 UUID 목록 */
  assigneeIds: string[]
  /** true면 미배정 이슈를 포함한다 */
  includeUnassigned: boolean
  /** 에픽 이슈 키 목록. {@link NO_EPIC} 은 「에픽 없음」을 뜻한다. */
  epicKeys: string[]
}

/** URL search params 표현 — TanStack Router 가 단일/배열 양쪽을 줄 수 있다. */
export interface BacklogFilterSearch {
  q?: string
  assignee?: string | string[]
  epic?: string | string[]
}

/**
 * 필터가 적용된 **표시용** 백로그 뷰.
 *
 * ★ `BacklogView` 와 **호환되지 않는 별개 타입**이다. 스프린트 완료·DnD 처럼
 * **동작 대상 집합**을 다루는 함수는 `BacklogView` 만 받아야 하고, 여기에 필터 결과를 넘기면
 * 컴파일이 깨진다.
 *
 * 왜 타입으로 막는가 — 완료된 스프린트의 이슈는 `SprintRepository.unassignIssue` 가
 * `status <> COMPLETED` 조건부 DELETE 라 **조용히 204** 를 주고 `UNIQUE(issue_key)` 때문에
 * 다른 스프린트로도 못 옮긴다. 즉 **영구 동결**된다. 필터로 안 보이는 이슈가 이관 대상에서
 * 빠지면 그 이슈들이 그대로 갇힌다. 테스트 한 줄로 지키기엔 대가가 너무 크다.
 *
 * 차단 기전은 **`readonly` 배열**이다. `T[]` 는 `readonly T[]` 에 대입되지만 그 역은 안 된다.
 * 런타임 마커 필드가 필요 없고 캐스팅도 없다 — 필터 결과는 애초에 읽기 전용 파생이니
 * 타입이 의미까지 맞다.
 */
export interface FilteredBacklogView {
  readonly backlog: readonly BacklogView['backlog'][number][]
  readonly sprints: readonly {
    readonly sprint: BacklogView['sprints'][number]['sprint']
    readonly issues: readonly BacklogView['sprints'][number]['issues'][number][]
  }[]
  readonly truncated: boolean
}

// ─────────────────────────────────────────────────────────────────────────────
// 생성자 / 판정
// ─────────────────────────────────────────────────────────────────────────────

/** 아무 조건도 없는 필터를 만든다. */
export function emptyBacklogFilter(): BacklogFilter {
  return { query: '', assigneeIds: [], includeUnassigned: false, epicKeys: [] }
}

/** 필터가 아무 조건도 갖지 않는지 확인한다. 공백만 있는 `query` 는 조건이 아니다. */
export function isEmptyFilter(filter: BacklogFilter): boolean {
  return (
    filter.query.trim().length === 0 &&
    filter.assigneeIds.length === 0 &&
    !filter.includeUnassigned &&
    filter.epicKeys.length === 0
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// URL search 왕복
// ─────────────────────────────────────────────────────────────────────────────

/** 단일 문자열·배열·미정의를 항상 배열로 정규화한다. */
function toArray(v?: string | string[]): string[] {
  if (v === undefined) return []
  return Array.isArray(v) ? v : [v]
}

/**
 * URL search params 를 {@link BacklogFilter} 로 변환한다.
 *
 * 값의 유효성(존재하지 않는 에픽 키 등)은 **판단하지 않는다** — 해석은 소비처 책임이다.
 */
export function searchToFilter(search: BacklogFilterSearch): BacklogFilter {
  const rawAssignee = toArray(search.assignee)
  return {
    query: search.q ?? '',
    assigneeIds: rawAssignee.filter((v) => v !== UNASSIGNED),
    includeUnassigned: rawAssignee.includes(UNASSIGNED),
    epicKeys: toArray(search.epic),
  }
}

/**
 * {@link BacklogFilter} 를 URL search params 로 변환한다.
 *
 * 빈 값의 키는 **완전히 생략**한다 (`?epic=` 같은 빈 파라미터 잔존 방지).
 */
export function filterToSearch(filter: BacklogFilter): BacklogFilterSearch {
  const result: BacklogFilterSearch = {}

  if (filter.query.trim().length > 0) {
    result.q = filter.query
  }

  const assignee: string[] = [...filter.assigneeIds, ...(filter.includeUnassigned ? [UNASSIGNED] : [])]
  if (assignee.length > 0) {
    result.assignee = assignee
  }

  if (filter.epicKeys.length > 0) {
    result.epic = filter.epicKeys
  }

  return result
}

// ─────────────────────────────────────────────────────────────────────────────
// FilterBar 경계 어댑터
// ─────────────────────────────────────────────────────────────────────────────

/**
 * `FilterBar<T extends BoardCardFilterParams>` 제약을 만족시키는 값으로 변환한다.
 *
 * `labels`·`componentIds` 의 빈 배열이 **이 함수 한 곳에서만** 생기게 해서, 백로그 도메인
 * 어디에도 「채울 수 있지만 절대 안 채워지는 필드」가 남지 않도록 한다.
 */
export function toFilterBarValue(filter: BacklogFilter): BoardCardFilterParams {
  return {
    assigneeIds: filter.assigneeIds,
    includeUnassigned: filter.includeUnassigned,
    labels: [],
    componentIds: [],
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 필터 적용
// ─────────────────────────────────────────────────────────────────────────────

/** 이슈 하나가 필터를 통과하는지 판정한다. 축끼리는 AND, 축 안에서는 OR. */
function matches(issue: BacklogView['backlog'][number], filter: BacklogFilter): boolean {
  const q = filter.query.trim().toLowerCase()
  if (q.length > 0 && !issue.summary.toLowerCase().includes(q)) return false

  const assigneeSelected = filter.assigneeIds.length > 0 || filter.includeUnassigned
  if (assigneeSelected) {
    const byId = issue.assigneeId !== null && filter.assigneeIds.includes(issue.assigneeId)
    const byUnassigned = filter.includeUnassigned && issue.assigneeId === null
    if (!byId && !byUnassigned) return false
  }

  if (filter.epicKeys.length > 0) {
    const key = issue.epicKey ?? NO_EPIC
    if (!filter.epicKeys.includes(key)) return false
  }

  return true
}

/**
 * 백로그 뷰에 필터를 적용한 **표시용 파생 뷰**를 만든다.
 *
 * - 백로그 섹션과 **모든 스프린트 섹션에 동일하게** 적용한다 (FR F16-7).
 * - 이슈가 0건이 되어도 **스프린트 섹션 자체는 보존**한다 — 섹션이 사라지면
 *   「스프린트가 없어졌다」로 읽힌다.
 * - `truncated` 를 **그대로 전달**한다. 필터는 이미 잘려서 도착한 응답 위에서 돌 뿐이고
 *   잘림을 풀지 못한다 (스펙 §8 C2).
 * - 입력 `view` 를 **변형하지 않는다**. 반환 타입이 {@link FilteredBacklogView} 라
 *   동작 대상 집합을 다루는 함수에 잘못 넘길 수 없다.
 */
export function filterBacklogView(view: BacklogView, filter: BacklogFilter): FilteredBacklogView {
  return {
    backlog: view.backlog.filter((issue) => matches(issue, filter)),
    sprints: view.sprints.map((s) => ({
      sprint: s.sprint,
      issues: s.issues.filter((issue) => matches(issue, filter)),
    })),
    truncated: view.truncated,
  }
}
