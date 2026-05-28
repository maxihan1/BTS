// 이슈 타입 MSW 핸들러 — GET /api/v1/issue-types (5 표준 반환)
import { http, HttpResponse } from 'msw'
import { allIssueTypeFixtures } from './issue-type-fixtures'

/**
 * issue-type MSW 핸들러 목록.
 *
 * - GET /api/v1/issue-types — 5 표준 이슈 타입 배열 반환 (`{ data: [...] }`)
 *
 * 응답 형식: backend의 `DataResponse<T>` 래퍼 (`{ data: T }`)와 일치.
 */
export const issueTypeHandlers = [
  /** GET /api/v1/issue-types — 5 표준 이슈 타입 목록 */
  http.get('/api/v1/issue-types', () => {
    return HttpResponse.json({ data: allIssueTypeFixtures })
  }),
]
