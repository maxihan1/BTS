// AQL 검색 MSW 핸들러 — POST /api/v1/search/aql 시나리오 (FR-SR-02 D6 Task-3)
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
    // 요청 body에서 query를 읽어 position 계산 (단순 고정 position 7 사용)
    const body = await request.json().catch(() => ({}) as Record<string, unknown>)
    const query = typeof body === 'object' && body !== null && 'query' in body
      ? String((body as Record<string, unknown>)['query'])
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

/** search-export-import BC MSW 핸들러 배열 */
export const searchHandlers = [searchAqlHandler]
