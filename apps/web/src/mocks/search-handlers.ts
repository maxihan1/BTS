// AQL 검색 + CSV/XLSX 내보내기 MSW 핸들러 — search-export-import BC (FR-SR-02/FR-EX-01 D6)
import { http, HttpResponse } from 'msw'
import { DEFAULT_SEARCH_PAGE, EMPTY_SEARCH_PAGE } from './search-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// E2E 시나리오 토글용 localStorage 키
// ─────────────────────────────────────────────────────────────────────────────

/**
 * E2E 테스트 전용 localStorage 플래그 키.
 * '__bts_e2e_search_scenario' 값에 따라 응답 시나리오를 전환한다.
 *
 * - 'syntax-error': SEARCH_SYNTAX_ERROR 400 반환
 * - 'empty': 0건 결과 반환
 * - 'unsupported-field': SEARCH_FIELD_NOT_YET_SUPPORTED 400 반환
 * - 'forbidden': SEARCH_ACCESS_DENIED 403 반환
 * - 그 외(또는 미설정): 기본 3건 결과 반환
 */
export const E2E_SEARCH_SCENARIO_KEY = '__bts_e2e_search_scenario'

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/search/aql
// ─────────────────────────────────────────────────────────────────────────────

/**
 * POST /api/v1/search/aql — AQL 쿼리 검색 메인 핸들러.
 *
 * E2E 시나리오 플래그(localStorage)에 따라 분기한다.
 * 기본 동작: 3건 결과 반환.
 *
 * 시나리오.
 * - 'syntax-error' → 400 SEARCH_SYNTAX_ERROR (문법오류 + position 포함)
 * - 'empty' → 200 0건 결과
 * - 'unsupported-field' → 400 SEARCH_FIELD_NOT_YET_SUPPORTED
 * - 'forbidden' → 403 SEARCH_ACCESS_DENIED
 * - 기본 → 200 3건 결과
 *
 * 주의: 401 시나리오는 핸들러 수준에서 refresh 엔드포인트도 함께 실패해야
 * apiFetch의 401 retry 후 ApiError(401)가 최종 throw된다 (C4 — auth-handlers 참고).
 */
const searchAqlHandler = http.post('/api/v1/search/aql', async ({ request }) => {
  // Node.js(MSW) 환경에서는 localStorage 없음 — 브라우저(E2E) 환경에서만 시나리오 전환
  const scenario: string | null =
    typeof localStorage !== 'undefined'
      ? localStorage.getItem(E2E_SEARCH_SCENARIO_KEY)
      : null

  if (scenario === 'syntax-error') {
    // 요청 body에서 query를 읽어 에러 메시지에 포함 (position은 고정 7)
    const rawBody: unknown = await request.json().catch(() => ({}))
    const query =
      rawBody !== null &&
      typeof rawBody === 'object' &&
      'query' in rawBody &&
      typeof (rawBody as Record<string, unknown>)['query'] === 'string'
        ? ((rawBody as Record<string, string>)['query'])
        : ''
    return HttpResponse.json(
      {
        errorCode: 'SEARCH_SYNTAX_ERROR',
        detail: `Unexpected token in query: "${query}"`,
        position: 7,
        title: 'AQL syntax error',
        status: 400,
        timestamp: new Date().toISOString(),
      },
      { status: 400 },
    )
  }

  if (scenario === 'empty') {
    return HttpResponse.json(EMPTY_SEARCH_PAGE)
  }

  if (scenario === 'unsupported-field') {
    return HttpResponse.json(
      {
        errorCode: 'SEARCH_FIELD_NOT_YET_SUPPORTED',
        detail: 'Field is not yet supported in AQL',
        title: 'Unsupported field',
        status: 400,
        timestamp: new Date().toISOString(),
      },
      { status: 400 },
    )
  }

  if (scenario === 'forbidden') {
    return HttpResponse.json(
      {
        errorCode: 'SEARCH_ACCESS_DENIED',
        detail: 'BROWSE permission required for this project',
        title: 'Access denied',
        status: 403,
        timestamp: new Date().toISOString(),
      },
      { status: 403 },
    )
  }

  // 기본: 정상 3건 결과 반환
  return HttpResponse.json(DEFAULT_SEARCH_PAGE)
})

/**
 * POST /api/v1/search/aql 문법오류 시나리오 핸들러 (unit test override용).
 * 항상 SEARCH_SYNTAX_ERROR 400을 반환한다.
 */
export const searchAqlSyntaxErrorHandler = http.post('/api/v1/search/aql', () =>
  HttpResponse.json(
    {
      errorCode: 'SEARCH_SYNTAX_ERROR',
      detail: 'Unexpected token at position 7',
      position: 7,
      title: 'AQL syntax error',
      status: 400,
      timestamp: new Date().toISOString(),
    },
    { status: 400 },
  ),
)

/**
 * POST /api/v1/search/aql 0건 시나리오 핸들러 (unit test override용).
 * 항상 빈 페이지를 반환한다.
 */
export const searchAqlEmptyHandler = http.post('/api/v1/search/aql', () =>
  HttpResponse.json(EMPTY_SEARCH_PAGE),
)

/**
 * POST /api/v1/search/aql 미지원 필드 시나리오 핸들러 (unit test override용).
 * 항상 SEARCH_FIELD_NOT_YET_SUPPORTED 400을 반환한다.
 */
export const searchAqlUnsupportedFieldHandler = http.post('/api/v1/search/aql', () =>
  HttpResponse.json(
    {
      errorCode: 'SEARCH_FIELD_NOT_YET_SUPPORTED',
      detail: 'Field "assignee" is not yet supported',
      title: 'Unsupported field',
      status: 400,
      timestamp: new Date().toISOString(),
    },
    { status: 400 },
  ),
)

/**
 * POST /api/v1/auth/refresh 실패 핸들러.
 * 401 시나리오 테스트 시 refresh도 실패시켜야 apiFetch가 ApiError(401)를 throw한다 (C4).
 */
export const searchRefreshFailHandler = http.post('/api/v1/auth/refresh', () =>
  HttpResponse.json({ error: 'invalid_grant' }, { status: 401 }),
)

// ─────────────────────────────────────────────────────────────────────────────
// E2E 시나리오 토글 키 — export
// ─────────────────────────────────────────────────────────────────────────────

/**
 * E2E 테스트 전용 localStorage 플래그 키 — export 시나리오 전환.
 *
 * - 'limit-exceeded': SEARCH_EXPORT_LIMIT_EXCEEDED 400 반환
 * - 그 외(또는 미설정): 정상 CSV 반환
 */
export const E2E_EXPORT_SCENARIO_KEY = '__bts_e2e_export_scenario'

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/search/export
// ─────────────────────────────────────────────────────────────────────────────

/** 최소 CSV 바이트 — UTF-8 BOM 포함 헤더 + 샘플 1행 */
const MINIMAL_CSV_BYTES = new TextEncoder().encode(
  '﻿Key,Summary,Type,Status,Assignee ID,Priority,Priority Name,Project,Updated At\r\n' +
    'ATLAS-1,Test issue,bug,open,,3,Medium,ATLAS,2026-06-29T00:00:00Z\r\n',
)

/**
 * POST /api/v1/search/export — 이슈 내보내기 메인 핸들러.
 *
 * format 파라미터를 읽어 Content-Type을 동적으로 설정한다.
 * E2E 시나리오 플래그에 따라 상한초과 에러를 시뮬레이션할 수 있다.
 */
const exportIssuesHandler = http.post('/api/v1/search/export', async ({ request }) => {
  // Node.js(MSW Node) 환경에서는 localStorage 없음 — 브라우저(E2E) 환경에서만 시나리오 전환
  const scenario: string | null =
    typeof localStorage !== 'undefined'
      ? localStorage.getItem(E2E_EXPORT_SCENARIO_KEY)
      : null

  if (scenario === 'limit-exceeded') {
    return HttpResponse.json(
      {
        errorCode: 'SEARCH_EXPORT_LIMIT_EXCEEDED',
        detail: '내보내기 한도(10,000건)를 초과했습니다. 쿼리를 좁혀 다시 시도하세요.',
        resultCount: 15000,
        limit: 10000,
        status: 400,
      },
      { status: 400 },
    )
  }

  const rawBody: unknown = await request.json().catch(() => ({}))
  const format =
    rawBody !== null &&
    typeof rawBody === 'object' &&
    'format' in rawBody &&
    typeof (rawBody as Record<string, unknown>)['format'] === 'string'
      ? ((rawBody as Record<string, string>)['format'])
      : 'CSV'

  if (format === 'XLSX') {
    // 최소 XLSX 매직 바이트 (PK\x03\x04 = ZIP local file header)
    return new HttpResponse(new Uint8Array([0x50, 0x4b, 0x03, 0x04]), {
      headers: {
        'Content-Type':
          'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
        'Content-Disposition': 'attachment; filename="ATLAS-issues-20260629T000000Z.xlsx"',
      },
    })
  }

  // 기본: CSV 응답
  return new HttpResponse(MINIMAL_CSV_BYTES, {
    headers: {
      'Content-Type': 'text/csv; charset=UTF-8',
      'Content-Disposition': 'attachment; filename="ATLAS-issues-20260629T000000Z.csv"',
    },
  })
})

/**
 * POST /api/v1/search/export 상한초과 핸들러 (unit test override용).
 * 항상 SEARCH_EXPORT_LIMIT_EXCEEDED 400을 반환한다.
 */
export const exportLimitExceededOverrideHandler = http.post('/api/v1/search/export', () =>
  HttpResponse.json(
    {
      errorCode: 'SEARCH_EXPORT_LIMIT_EXCEEDED',
      detail: '내보내기 한도(10,000건)를 초과했습니다.',
      resultCount: 15000,
      limit: 10000,
      status: 400,
    },
    { status: 400 },
  ),
)

/** search-export-import BC MSW 핸들러 배열 */
export const searchHandlers = [searchAqlHandler, exportIssuesHandler]
