// AQL 검색 결과를 목록 표(IssueListContent) 한 페이지로 바꾸는 어댑터 (R4 · JC-L-1)
import type { AqlSearchPage } from '@/api/search'
import type { IssueTablePage } from '@/components/issues/IssueTable'

/** 검색 결과 표 한 페이지 — 목록 표 모양 + 이 페이지의 셀 편집 가능 여부 */
export interface SearchTablePage extends IssueTablePage {
  /** 조건에 맞는 전체 건수 — 0 이면 표 대신 빈 상태 안내 */
  totalElements: number
  /** 모든 행이 version 을 실었을 때만 true — 아니면 셀 편집을 끈다 */
  editable: boolean
}

/**
 * AQL 결과 한 페이지를 목록 표(`IssueListContent`)가 읽는 모양으로 바꾼다.
 *
 * ★queryFn 안에서 바꿔 **캐시 자체가 이 모양**이 되게 한다. 셀 편집 훅
 * (`useIssueListCellField`)이 `listQueryKey` 캐시의 `content` 를 낙관적으로 patch 하기 때문이다 —
 * `select` 로 바꾸면 캐시는 AQL 모양 그대로라 patch 가 아무 행에도 닿지 않는다.
 *
 * @param aql 백엔드 AQL 검색 응답
 * @returns 목록 표 한 페이지
 */
export function toSearchTablePage(aql: AqlSearchPage): SearchTablePage {
  const pg = aql.meta.page
  return {
    content: aql.data.map((hit) => ({
      key: hit.key,
      summary: hit.summary,
      typeKey: hit.typeKey,
      currentStateKey: hit.currentStateKey,
      assigneeId: hit.assigneeId,
      priority: hit.priority,
      priorityName: hit.priorityName,
      updatedAt: hit.updatedAt,
      // null 이면 아래 editable=false 로 셀 편집이 꺼져 이 값은 어디에도 실리지 않는다
      version: hit.version ?? 0,
      restrictedFields: hit.restrictedFields,
      noneditableFields: hit.noneditableFields,
    })),
    empty: pg.totalElements === 0,
    totalPages: pg.totalPages,
    first: pg.number === 0,
    last: pg.number >= pg.totalPages - 1,
    totalElements: pg.totalElements,
    editable: aql.data.every((hit) => hit.version !== null),
  }
}
