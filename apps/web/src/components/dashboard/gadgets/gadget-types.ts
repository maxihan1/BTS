// 가젯 데이터 공유 타입 — GadgetIssueRow 정규화 인터페이스 (FR-DB-02 D6/D7 Task-4)

/**
 * 가젯 이슈 행 — key/summary 최소 표현 (Gap 2).
 * IssueResponse 및 AqlSearchHit에서 정규화해 이슈 목록·건수 가젯이 공통으로 사용한다.
 */
export interface GadgetIssueRow {
  /** 이슈 식별 키 (예: "ATLAS-1") */
  key: string
  /** 이슈 제목 */
  summary: string
}

/**
 * 데이터 가젯 타입 식별자.
 * 정적 가젯(text_widget / link_list)은 useGadgetData 훅을 사용하지 않는다.
 */
export type DataGadgetType = 'assigned_to_me' | 'recently_created' | 'filter_result' | 'issue_count'

/**
 * 가젯 설정 인터페이스.
 * 가젯 타입별로 사용하는 필드가 다르다.
 *
 * - assigned_to_me / recently_created: projectKey(필수), maxItems(선택)
 * - filter_result / issue_count: filterId(필수), maxItems(선택)
 */
export interface GadgetConfig {
  /** 프로젝트 키 (이슈 목록 가젯 — projectKey 지정형) */
  projectKey?: string
  /** 저장 필터 UUID (필터 기반 가젯) */
  filterId?: string
  /** 최대 표시 건수 (1~50, 기본 10). clamp 적용. */
  maxItems?: number
}

/**
 * useGadgetData 훅 반환 타입.
 *
 * - rows: 이슈 목록 가젯(assigned_to_me/recently_created/filter_result)에서 채워짐.
 * - totalElements: 건수 가젯(issue_count)에서 채워짐.
 * 해당하지 않는 필드는 undefined.
 */
export interface GadgetDataResult {
  /** 데이터를 실제로 fetch 중인 동안 true */
  isLoading: boolean
  /** fetch 실패 시 true */
  isError: boolean
  /**
   * 이슈 목록 가젯(assigned_to_me / recently_created / filter_result)용 정규화 행 목록.
   * issue_count 가젯에서는 undefined.
   */
  rows: GadgetIssueRow[] | undefined
  /**
   * 건수 가젯(issue_count)용 전체 이슈 건수.
   * 이슈 목록 가젯에서는 undefined.
   */
  totalElements: number | undefined
}
