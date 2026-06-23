// URL search params ↔ IssueFilterParams 순수 매핑 유틸 (FR-SR-01 D6)
import type { IssueFilterParams } from '../api/issues'

/**
 * URL search params에서 이슈 필터 4종(status·assignee·label·component)을 표현하는 타입.
 * TanStack Router가 단일 문자열 또는 문자열 배열 양쪽을 모두 줄 수 있으므로
 * 각 필드를 `string | string[]` 유니온으로 정의한다.
 */
export interface IssueFilterSearch {
  status?: string | string[]
  assignee?: string | string[]
  label?: string | string[]
  component?: string | string[]
}

/** 미배정 이슈를 가리키는 센티널 값. 백엔드 계약 (FR-SR-01, #180) */
export const UNASSIGNED = 'unassigned'

/**
 * 단일 문자열 또는 문자열 배열을 항상 배열로 정규화한다.
 * undefined이면 빈 배열을 반환한다.
 *
 * @param v TanStack Router URL 파라미터 값 (단일·배열·미정의)
 * @returns 정규화된 문자열 배열
 */
export function toArray(v?: string | string[]): string[] {
  if (v === undefined) return []
  return Array.isArray(v) ? v : [v]
}

/**
 * URL search params 객체를 `IssueFilterParams`로 변환한다.
 *
 * - `status` → `statusKeys`
 * - `assignee` 배열 내 `'unassigned'` 센티널은 `assigneeIds`에서 제외하고
 *   `includeUnassigned: true`로 변환한다.
 * - `label` → `labels`
 * - `component` → `componentIds`
 * - 단일 문자열 값도 배열로 정규화한다.
 * - undefined 필드는 빈 배열로 처리한다.
 *
 * @param search URL search params 객체
 * @returns 정규화된 `IssueFilterParams`
 */
export function searchToIssueFilter(search: IssueFilterSearch): IssueFilterParams {
  const rawAssignee = toArray(search.assignee)
  const assigneeIds = rawAssignee.filter((v) => v !== UNASSIGNED)
  const includeUnassigned = rawAssignee.includes(UNASSIGNED)

  return {
    statusKeys: toArray(search.status),
    assigneeIds,
    includeUnassigned,
    labels: toArray(search.label),
    componentIds: toArray(search.component),
  }
}

/**
 * `IssueFilterParams`를 URL search params 객체로 변환한다.
 *
 * - `includeUnassigned: true`이면 `'unassigned'` 센티널을 `assignee` 배열 끝에 추가한다.
 * - 빈 배열이거나 false인 필드의 키는 결과 객체에서 **완전히 생략**한다.
 *   (`?status=` 같은 빈 파라미터 잔존 방지)
 *
 * @param filter 변환할 필터 파라미터
 * @returns URL search params 객체 (빈 필드 키 생략)
 */
export function issueFilterToSearch(filter: IssueFilterParams): IssueFilterSearch {
  const result: IssueFilterSearch = {}

  if (filter.statusKeys.length > 0) {
    result.status = filter.statusKeys
  }

  const assignee: string[] = [
    ...filter.assigneeIds,
    ...(filter.includeUnassigned ? [UNASSIGNED] : []),
  ]
  if (assignee.length > 0) {
    result.assignee = assignee
  }

  if (filter.labels.length > 0) {
    result.label = filter.labels
  }

  if (filter.componentIds.length > 0) {
    result.component = filter.componentIds
  }

  return result
}

/**
 * 필터가 아무 조건도 갖지 않는지 확인한다.
 *
 * 모든 배열이 비어 있고 `includeUnassigned`가 false이면 true를 반환한다.
 *
 * @param filter 검사할 필터 파라미터
 * @returns 필터가 비어 있으면 true
 */
export function isEmptyIssueFilter(filter: IssueFilterParams): boolean {
  return (
    filter.statusKeys.length === 0 &&
    filter.assigneeIds.length === 0 &&
    !filter.includeUnassigned &&
    filter.labels.length === 0 &&
    filter.componentIds.length === 0
  )
}

/**
 * `IssueFilterParams`를 결정적(안정적)으로 정규화한다.
 *
 * 배열 내 요소 순서가 달라도 동일한 filter를 동일한 queryKey로 취급하도록
 * 각 배열을 정렬해 객체를 재구성한다. 원본 배열은 변이하지 않는다.
 * `use-boards.ts` normalizeFilter(L20-32) 미러 — queryKey 안정성 보장용 (B1).
 *
 * @param filter 원본 필터 파라미터
 * @returns 정렬 정규화된 필터 객체 (JSON.stringify 안정적)
 */
export function normalizeIssueFilter(filter: IssueFilterParams): IssueFilterParams {
  return {
    statusKeys: [...filter.statusKeys].sort(),
    assigneeIds: [...filter.assigneeIds].sort(),
    includeUnassigned: filter.includeUnassigned,
    labels: [...filter.labels].sort(),
    componentIds: [...filter.componentIds].sort(),
  }
}
