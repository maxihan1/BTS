// URL search params ↔ BoardCardFilterParams 순수 매핑 유틸 (FR-BD-02)
import type { BoardCardFilterParams } from '../api/boards'

/**
 * URL search params에서 보드 필터 3종(assignee·label·component)을 표현하는 타입.
 * TanStack Router가 단일 문자열 또는 문자열 배열 양쪽을 모두 줄 수 있으므로
 * 각 필드를 `string | string[]` 유니온으로 정의한다.
 */
export interface BoardFilterSearch {
  assignee?: string | string[]
  label?: string | string[]
  component?: string | string[]
}

/** 미배정 카드를 가리키는 센티널 값. 백엔드 계약 (FR-BD-02) */
const UNASSIGNED = 'unassigned'

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
 * URL search params 객체를 `BoardCardFilterParams`로 변환한다.
 *
 * - `assignee` 배열 내 `'unassigned'` 센티널은 `assigneeIds`에서 제외하고
 *   `includeUnassigned: true`로 변환한다.
 * - 단일 문자열 값도 배열로 정규화한다.
 * - undefined 필드는 빈 배열로 처리한다.
 *
 * @param search URL search params 객체
 * @returns 정규화된 `BoardCardFilterParams`
 */
export function searchToFilter(search: BoardFilterSearch): BoardCardFilterParams {
  const rawAssignee = toArray(search.assignee)
  const assigneeIds = rawAssignee.filter((v) => v !== UNASSIGNED)
  const includeUnassigned = rawAssignee.includes(UNASSIGNED)

  return {
    assigneeIds,
    includeUnassigned,
    labels: toArray(search.label),
    componentIds: toArray(search.component),
  }
}

/**
 * `BoardCardFilterParams`를 URL search params 객체로 변환한다.
 *
 * - `includeUnassigned: true`이면 `'unassigned'` 센티널을 `assignee` 배열 끝에 추가한다.
 * - 빈 배열이거나 false인 필드의 키는 결과 객체에서 **완전히 생략**한다.
 *   (`?component=` 같은 빈 파라미터 잔존 방지)
 *
 * @param filter 변환할 필터 파라미터
 * @returns URL search params 객체 (빈 필드 키 생략)
 */
export function filterToSearch(filter: BoardCardFilterParams): BoardFilterSearch {
  const result: BoardFilterSearch = {}

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
export function isEmptyFilter(filter: BoardCardFilterParams): boolean {
  return (
    filter.assigneeIds.length === 0 &&
    !filter.includeUnassigned &&
    filter.labels.length === 0 &&
    filter.componentIds.length === 0
  )
}

/**
 * 저장된 퀵필터 query 문자열(접두 `?` 없는 쿼리스트링)을 URL search params 객체로 변환한다(FR-UX-01).
 *
 * `URLSearchParams`가 `application/x-www-form-urlencoded` 규칙(`+`→공백)으로 자동 디코딩하므로
 * 백엔드 저장 형식(`BoardFilterQueryParser.serialize`)과 인코딩 계약이 일치한다(spec §API 인터페이스
 * 인코딩 계약, 리뷰 B1 함정 — `+`를 리터럴로 오처리하면 `"my bug"`가 `"my+bug"`로 어긋난다).
 *
 * `filterToSearch`와 동일하게 빈 필드의 키는 결과 객체에서 생략한다 — 왕복 시(`buildBoardFilterQuery` →
 * `queryStringToSearch` → `searchToFilter`) 원본 필터와 동등하려면 빈 배열이 아니라 키 자체가 없어야
 * `filterToSearch`의 생략 규칙과 대칭을 이룬다.
 *
 * @param query 접두 `?` 없는 쿼리스트링. 예: `"assignee=uuid&label=bug"`
 * @returns `BoardFilterSearch` — `navigate({ search })` 또는 `searchToFilter`에 바로 사용 가능
 */
export function queryStringToSearch(query: string): BoardFilterSearch {
  const params = new URLSearchParams(query)
  const result: BoardFilterSearch = {}

  const assignee = params.getAll('assignee')
  if (assignee.length > 0) {
    result.assignee = assignee
  }

  const label = params.getAll('label')
  if (label.length > 0) {
    result.label = label
  }

  const component = params.getAll('component')
  if (component.length > 0) {
    result.component = component
  }

  return result
}
