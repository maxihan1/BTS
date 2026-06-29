// 타임라인 BC MSW 핸들러 — 읽기 전용 정적 반환 (FR-TL-01 D6 Task-4 + FR-TL-02 D6 Task-3)
//
// 교훈 반영.
//   - frontend-zod-backend-dto-contract-gap: 응답 봉투 { data: TimelineResponse } 형식 준수
//   - e2e-msw-scenario-toggle-localstorage-flag: 403/truncated 토글은 localStorage 플래그로 분기
//   - worklog-aggregate-handlers: 읽기 전용 정적 반환 선례 (stateful store 불필요)
//   - msw-derived-behavior-shared-store-e2e: 정적 반환이면 충분 (deps는 읽기 전용)
//
import { http, HttpResponse } from 'msw'
import { BTS_TIMELINE_DEPS, BTS_TIMELINE_ITEMS, TRUNCATED_TIMELINE_ITEMS } from './timeline-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// E2E 시나리오 토글용 localStorage 키
// ─────────────────────────────────────────────────────────────────────────────

/**
 * E2E 테스트 전용 localStorage 플래그 키 — 타임라인 아이템 시나리오 전환.
 * '__bts_e2e_timeline_scenario' 값에 따라 응답 시나리오를 전환한다.
 *
 * - 'forbidden': AGILE_ACCESS_DENIED 403 반환
 * - 'truncated': items 반환 + truncated=true
 * - 'empty': items=[] + truncated=false
 * - 그 외(또는 미설정): 기본 BTS_TIMELINE_ITEMS 반환
 *
 * @see e2e-msw-scenario-toggle-localstorage-flag 교훈
 */
export const E2E_TIMELINE_SCENARIO_KEY = '__bts_e2e_timeline_scenario'

/**
 * E2E 테스트 전용 localStorage 플래그 키 — 의존 라인(deps) 시나리오 전환.
 * '__bts_e2e_timeline_deps_scenario' 값에 따라 deps 응답 시나리오를 전환한다.
 *
 * - 'truncated': deps 반환 + truncated=true (deps 누락 경고 배너 검증용)
 * - 'empty': deps=[] + truncated=false
 * - 그 외(또는 미설정): 기본 BTS_TIMELINE_DEPS 반환
 *
 * @see e2e-msw-scenario-toggle-localstorage-flag 교훈
 * @see FR-TL-02 S6 — deps truncated 누락 경고
 */
export const E2E_TIMELINE_DEPS_SCENARIO_KEY = '__bts_e2e_timeline_deps_scenario'

// ─────────────────────────────────────────────────────────────────────────────
// 특수 프로젝트 키 상수 — unit test / E2E 분기용
// ─────────────────────────────────────────────────────────────────────────────

/** 이 프로젝트 키가 요청되면 403 AGILE_ACCESS_DENIED를 반환한다 */
const FORBIDDEN_PROJECT_KEY = 'FORBIDDEN'

/** 이 프로젝트 키가 요청되면 truncated=true 응답을 반환한다 */
const TRUNCATED_PROJECT_KEY = 'TRUNCATED'

/** 이 프로젝트 키가 요청되면 items=[] 빈 응답을 반환한다 */
const EMPTY_PROJECT_KEY = 'EMPTY'

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/timeline
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 타임라인 조회 핸들러.
 *
 * 응답 형식: `{ data: { items: TimelineItem[], truncated: boolean } }`
 *
 * 쿼리파라미터 분기.
 * - project=FORBIDDEN → 403 AGILE_ACCESS_DENIED
 * - project=TRUNCATED → 200 truncated=true (부분 목록)
 * - project=EMPTY → 200 items=[], truncated=false
 * - project=BTS (또는 기본) → 200 BTS_TIMELINE_ITEMS, truncated=false
 * - 그 외 알 수 없는 프로젝트 → 200 items=[], truncated=false
 *
 * E2E 시나리오 플래그(localStorage `E2E_TIMELINE_SCENARIO_KEY`) 분기.
 * - 'forbidden' → 403
 * - 'truncated' → truncated=true
 * - 'empty' → items=[]
 * - 기본 → BTS_TIMELINE_ITEMS
 *
 * @see 백엔드 계약 PR #192 — TimelineController.kt
 * @see 교훈 worklog-aggregate-handlers: 정적 반환 패턴
 */
const getTimelineHandler = http.get('/api/v1/timeline', ({ request }) => {
  const url = new URL(request.url)
  const project = url.searchParams.get('project') ?? ''

  // ── 프로젝트 키 기반 단위 테스트 분기 (unit test 명시 시나리오) ──────────

  if (project === FORBIDDEN_PROJECT_KEY) {
    return HttpResponse.json(
      {
        type: 'https://bts.example.com/problems/access-denied',
        title: 'Access Denied',
        status: 403,
        detail: '해당 프로젝트 타임라인에 접근할 권한이 없습니다.',
        errorCode: 'AGILE_ACCESS_DENIED',
        timestamp: new Date().toISOString(),
      },
      { status: 403 },
    )
  }

  if (project === TRUNCATED_PROJECT_KEY) {
    return HttpResponse.json({
      data: { items: TRUNCATED_TIMELINE_ITEMS, truncated: true },
    })
  }

  if (project === EMPTY_PROJECT_KEY) {
    return HttpResponse.json({
      data: { items: [], truncated: false },
    })
  }

  // ── E2E localStorage 시나리오 분기 ────────────────────────────────────────
  // Node.js(MSW) 환경에서는 localStorage 없음 — 브라우저(E2E) 환경에서만 시나리오 전환
  const scenario: string | null =
    typeof localStorage !== 'undefined'
      ? localStorage.getItem(E2E_TIMELINE_SCENARIO_KEY)
      : null

  if (scenario === 'forbidden') {
    return HttpResponse.json(
      {
        type: 'https://bts.example.com/problems/access-denied',
        title: 'Access Denied',
        status: 403,
        detail: '해당 프로젝트 타임라인에 접근할 권한이 없습니다.',
        errorCode: 'AGILE_ACCESS_DENIED',
        timestamp: new Date().toISOString(),
      },
      { status: 403 },
    )
  }

  if (scenario === 'truncated') {
    return HttpResponse.json({
      data: { items: TRUNCATED_TIMELINE_ITEMS, truncated: true },
    })
  }

  if (scenario === 'empty') {
    return HttpResponse.json({
      data: { items: [], truncated: false },
    })
  }

  // ── 기본: BTS 또는 알 수 없는 프로젝트 ────────────────────────────────────
  if (project === 'BTS') {
    return HttpResponse.json({
      data: { items: BTS_TIMELINE_ITEMS, truncated: false },
    })
  }

  // 알 수 없는 프로젝트 — 빈 응답 폴백
  return HttpResponse.json({
    data: { items: [], truncated: false },
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// Unit test override 핸들러 — server.use(handler) 로 특정 시나리오 강제
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 403 AGILE_ACCESS_DENIED 시나리오 핸들러 (unit test override용).
 * 항상 403을 반환한다.
 *
 * @example
 * server.use(timelineForbiddenHandler)
 */
export const timelineForbiddenHandler = http.get('/api/v1/timeline', () =>
  HttpResponse.json(
    {
      type: 'https://bts.example.com/problems/access-denied',
      title: 'Access Denied',
      status: 403,
      detail: '해당 프로젝트 타임라인에 접근할 권한이 없습니다.',
      errorCode: 'AGILE_ACCESS_DENIED',
      timestamp: new Date().toISOString(),
    },
    { status: 403 },
  ),
)

/**
 * truncated=true 시나리오 핸들러 (unit test override용).
 * TRUNCATED_TIMELINE_ITEMS + truncated=true를 반환한다.
 *
 * @example
 * server.use(timelineTruncatedHandler)
 */
export const timelineTruncatedHandler = http.get('/api/v1/timeline', () =>
  HttpResponse.json({
    data: { items: TRUNCATED_TIMELINE_ITEMS, truncated: true },
  }),
)

/**
 * 빈 목록 시나리오 핸들러 (unit test override용).
 * items=[], truncated=false를 반환한다.
 *
 * @example
 * server.use(timelineEmptyHandler)
 */
export const timelineEmptyHandler = http.get('/api/v1/timeline', () =>
  HttpResponse.json({
    data: { items: [], truncated: false },
  }),
)

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/timeline/deps
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 의존 라인(blocks 관계) 조회 핸들러.
 *
 * 응답 형식: `{ data: { deps: TimelineDepEdge[], truncated: boolean } }`
 *
 * 쿼리파라미터 분기.
 * - project=TRUNCATED → 200 truncated=true (부분 엣지 목록)
 * - project=EMPTY → 200 deps=[], truncated=false
 * - project=BTS (또는 기본) → 200 BTS_TIMELINE_DEPS, truncated=false
 * - 그 외 알 수 없는 프로젝트 → 200 deps=[], truncated=false
 *
 * E2E 시나리오 플래그(localStorage `E2E_TIMELINE_DEPS_SCENARIO_KEY`) 분기.
 * - 'truncated' → truncated=true
 * - 'empty' → deps=[]
 * - 기본 → BTS_TIMELINE_DEPS
 *
 * 정적 반환으로 충분 (읽기 전용, stateful store 불필요).
 *
 * @see 백엔드 계약 PR #200 — TimelineController.getDeps
 * @see msw-derived-behavior-shared-store-e2e: 정적 반환 패턴
 */
const getTimelineDepsHandler = http.get('/api/v1/timeline/deps', ({ request }) => {
  const url = new URL(request.url)
  const project = url.searchParams.get('project') ?? ''

  // ── 프로젝트 키 기반 단위 테스트 분기 ────────────────────────────────────

  if (project === TRUNCATED_PROJECT_KEY) {
    return HttpResponse.json({
      data: { deps: BTS_TIMELINE_DEPS, truncated: true },
    })
  }

  if (project === EMPTY_PROJECT_KEY) {
    return HttpResponse.json({
      data: { deps: [], truncated: false },
    })
  }

  // ── E2E localStorage 시나리오 분기 ────────────────────────────────────────
  // Node.js(MSW) 환경에서는 localStorage 없음 — 브라우저(E2E) 환경에서만 시나리오 전환
  const scenario: string | null =
    typeof localStorage !== 'undefined'
      ? localStorage.getItem(E2E_TIMELINE_DEPS_SCENARIO_KEY)
      : null

  if (scenario === 'truncated') {
    return HttpResponse.json({
      data: { deps: BTS_TIMELINE_DEPS, truncated: true },
    })
  }

  if (scenario === 'empty') {
    return HttpResponse.json({
      data: { deps: [], truncated: false },
    })
  }

  // ── 기본: BTS 또는 알 수 없는 프로젝트 ────────────────────────────────────
  if (project === 'BTS') {
    return HttpResponse.json({
      data: { deps: BTS_TIMELINE_DEPS, truncated: false },
    })
  }

  // 알 수 없는 프로젝트 — 빈 응답 폴백
  return HttpResponse.json({
    data: { deps: [], truncated: false },
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// deps Unit test override 핸들러 — server.use(handler) 로 특정 시나리오 강제
// ─────────────────────────────────────────────────────────────────────────────

/**
 * deps truncated=true 시나리오 핸들러 (unit test override용).
 * BTS_TIMELINE_DEPS + truncated=true를 반환한다.
 *
 * @example
 * server.use(timelineDepsTruncatedHandler)
 */
export const timelineDepsTruncatedHandler = http.get('/api/v1/timeline/deps', () =>
  HttpResponse.json({
    data: { deps: BTS_TIMELINE_DEPS, truncated: true },
  }),
)

/**
 * deps 빈 목록 시나리오 핸들러 (unit test override용).
 * deps=[], truncated=false를 반환한다.
 *
 * @example
 * server.use(timelineDepsEmptyHandler)
 */
export const timelineDepsEmptyHandler = http.get('/api/v1/timeline/deps', () =>
  HttpResponse.json({
    data: { deps: [], truncated: false },
  }),
)

// ─────────────────────────────────────────────────────────────────────────────
// Export
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 타임라인 BC MSW 핸들러 배열.
 *
 * handlers.ts에서 `...timelineHandlers`로 spread해 등록한다.
 * GET /api/v1/timeline + GET /api/v1/timeline/deps 포함.
 */
export const timelineHandlers = [getTimelineHandler, getTimelineDepsHandler]
