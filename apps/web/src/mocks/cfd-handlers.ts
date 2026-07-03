// 누적 흐름도(CFD) BC MSW 핸들러 — GET /api/v1/projects/:projectKey/cfd, 시드 가능 store (FR-RP-03 D6/D7 Task-6)
//
// 교훈 반영.
//   - msw-derived-behavior-shared-store-e2e: 파생 응답은 공유 store에서 읽음
//   - fr-bd-01: 신규 store는 모듈 로드 시 자동 시드 필수(dev/E2E 빈 화면 방지, 단위 테스트는 MODE='test'에서 건너뜀)
//   - frontend-zod-backend-dto-contract-gap: 에러 errorCode(ISSUE_ACCESS_DENIED)는 실제 backend
//     CfdExceptionHandler.kt(handleAccessDenied)를 grep해 대조했다 — velocity/burndown(AGILE_ACCESS_DENIED)와는
//     다른 BC(issue-tracking)이므로 임의로 값을 재사용하지 않는다.
//
import { http, HttpResponse } from 'msw'
import type { CfdPointResponse, CfdResponse } from '@/api/cfd'

// ─────────────────────────────────────────────────────────────────────────────
// 시나리오 트리거 projectKey 상수 — 특정 값 요청 시 고정 응답 (E2E/단위테스트 공용)
// ─────────────────────────────────────────────────────────────────────────────

/** 이 projectKey로 요청하면 403(ISSUE_ACCESS_DENIED)을 반환한다 */
export const FORBIDDEN_PROJECT_KEY = 'PROJECT-FORBIDDEN'

/** 이 projectKey로 요청하면 200이되 모든 point의 카운트가 0인 빈 CFD를 반환한다 */
export const EMPTY_PROJECT_KEY = 'PROJECT-EMPTY'

// ─────────────────────────────────────────────────────────────────────────────
// 공유 stateful store — projectKey → CfdResponse
// ─────────────────────────────────────────────────────────────────────────────

/** CFD store — projectKey 기준. GET 핸들러가 읽어 응답을 구성한다 (읽기 전용 API라 mutation 없음) */
export let cfdStore: Map<string, CfdResponse> = new Map()

/** store를 초기 상태로 리셋한다. 각 테스트 beforeEach에서 호출해 테스트 간 격리를 보장한다. */
export function resetCfdStore(): void {
  cfdStore = new Map()
}

/**
 * CfdResponse를 store에 시드한다. 동일 projectKey가 이미 있으면 덮어쓴다.
 *
 * @param response 시드할 CFD 응답
 */
export function seedCfd(response: CfdResponse): void {
  cfdStore.set(response.projectKey, structuredClone(response))
}

// ─────────────────────────────────────────────────────────────────────────────
// 날짜/카운트 생성 헬퍼 — 리터럴 나열 금지, from~to 루프로 프로그래매틱 생성 (E-C2)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * ISO 날짜 문자열(YYYY-MM-DD)에 days일을 더한 결과를 ISO 문자열로 반환한다.
 *
 * @param dateIso 기준 날짜 (YYYY-MM-DD)
 * @param days 더할 일수 (음수 가능)
 * @returns 계산된 날짜 (YYYY-MM-DD)
 */
function addDaysIso(dateIso: string, days: number): string {
  const date = new Date(`${dateIso}T00:00:00Z`)
  date.setUTCDate(date.getUTCDate() + days)
  return date.toISOString().slice(0, 10)
}

/** 기본 CFD 픽스처가 시뮬레이션하는 프로젝트 총 이슈 수 */
const CFD_TOTAL_ISSUES = 20

/** TODO에서 진행중을 거쳐 DONE으로 이동하기까지 걸리는 지연 일수 (진행중 카운트를 소폭 유지) */
const CFD_INPROGRESS_LAG_DAYS = 3

/**
 * startDate부터 days일간의 CFD 시계열 포인트를 프로그래매틱으로 생성한다.
 *
 * 카운트는 단조 우상향한다 — todoCount는 감소, doneCount는 증가, inProgressCount는
 * CFD_INPROGRESS_LAG_DAYS만큼 소폭 유지되다 마지막에 0으로 수렴한다. 세 카운트 모두
 * 음수가 되지 않는 정수다.
 *
 * @param startDate 시작 날짜 (YYYY-MM-DD)
 * @param days 생성할 포인트 개수(일)
 * @returns CFD 시계열 포인트 배열 (날짜 오름차순)
 */
function buildCfdPoints(startDate: string, days: number): CfdPointResponse[] {
  return Array.from({ length: days }, (_, i) => {
    const date = addDaysIso(startDate, i)
    const todoCount = Math.max(0, CFD_TOTAL_ISSUES - i)
    const doneCount = Math.min(CFD_TOTAL_ISSUES, Math.max(0, i - CFD_INPROGRESS_LAG_DAYS))
    const inProgressCount = Math.max(0, CFD_TOTAL_ISSUES - todoCount - doneCount)
    return { date, todoCount, inProgressCount, doneCount }
  })
}

/**
 * CFD 시계열 포인트 배열의 날짜는 보존하고 모든 카운트를 0으로 치환한다.
 * (EMPTY_PROJECT_KEY / 미시드 projectKey 응답 생성에 사용 — 날짜는 buildCfdPoints 결과를 재사용한다)
 *
 * @param points 원본 포인트 배열
 * @returns 날짜는 동일, 카운트만 0인 포인트 배열
 */
function zeroCfdPoints(points: CfdPointResponse[]): CfdPointResponse[] {
  return points.map((point) => ({ ...point, todoCount: 0, inProgressCount: 0, doneCount: 0 }))
}

// ─────────────────────────────────────────────────────────────────────────────
// 기본 시드 픽스처 — backlog-fixtures.ts DEFAULT_BACKLOG / velocity-handlers.ts DEFAULT_VELOCITY와
// 동일한 projectKey('ATLAS')를 사용한다. 백로그→CFD 화면 이동 E2E에서 데이터가 이어지도록 정합.
// ─────────────────────────────────────────────────────────────────────────────

/** 기본 CFD 픽스처의 프로젝트 키 — backlog-fixtures/velocity-handlers와 동일 값(E2E 정합) */
const DEFAULT_CFD_PROJECT_KEY = 'ATLAS'

/** 기본 CFD 픽스처의 시작일 */
const DEFAULT_CFD_START_DATE = '2026-06-01'

/** 기본 CFD 픽스처의 창 길이(일) — 백엔드 기본 창(DEFAULT_WINDOW_DAYS=30)과 동일 */
const DEFAULT_CFD_WINDOW_DAYS = 30

/** 기본 CFD 픽스처의 시계열 포인트 (프로그래매틱 생성, 리터럴 나열 아님) */
const DEFAULT_CFD_POINTS = buildCfdPoints(DEFAULT_CFD_START_DATE, DEFAULT_CFD_WINDOW_DAYS)

/**
 * 기본 CFD 픽스처 — ATLAS 프로젝트, 30일 시계열.
 * from/to는 points 배열의 실제 첫/마지막 날짜와 정합되도록 addDaysIso로 계산한다(리터럴 중복 금지).
 */
export const DEFAULT_CFD: CfdResponse = {
  projectKey: DEFAULT_CFD_PROJECT_KEY,
  from: DEFAULT_CFD_START_DATE,
  to: addDaysIso(DEFAULT_CFD_START_DATE, DEFAULT_CFD_WINDOW_DAYS - 1),
  points: DEFAULT_CFD_POINTS,
}

// 모듈 로드 시 기본 픽스처를 자동 시드한다 — fr-bd-01 교훈 (신규 store 자동 시드 필수).
// dev(pnpm dev) · E2E 진입 시 store가 비어 있어 빈 화면이 노출되는 결함 방지.
// Vitest 단위 테스트 환경(MODE='test')에서는 건너뜀 — 각 테스트가 beforeEach/reset으로 직접 제어.
if (import.meta.env.MODE !== 'test') {
  seedCfd(DEFAULT_CFD)
}

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/projects/:projectKey/cfd
// ─────────────────────────────────────────────────────────────────────────────

/** projectKey에 대응하는 CFD 데이터가 없을 때(빈 프로젝트 / 미시드) 반환하는 응답을 구성한다 */
function emptyCfd(projectKey: string): CfdResponse {
  return {
    projectKey,
    from: DEFAULT_CFD.from,
    to: DEFAULT_CFD.to,
    points: zeroCfdPoints(DEFAULT_CFD_POINTS),
  }
}

/**
 * GET /api/v1/projects/{projectKey}/cfd — 프로젝트 CFD(누적 흐름도) 시계열 조회.
 *
 * 분기.
 * - projectKey=FORBIDDEN_PROJECT_KEY → 403 ISSUE_ACCESS_DENIED
 * - projectKey=EMPTY_PROJECT_KEY → 200 { data } 이되 모든 point 카운트 0
 * - store에 있으면 → 200 { data: 저장된 CfdResponse }
 * - store에 없으면 → 200 모든 point 카운트 0인 응답 (백엔드는 유효 프로젝트+이슈 0개면 200 빈 응답을
 *   반환한다. CfdController/CfdService 대조 — 404 아님)
 *
 * 에러 body 형식은 velocity-handlers.ts(같은 { errorCode, message } 외피) 선례를 따르되,
 * errorCode 값은 CfdExceptionHandler.kt(issue-tracking BC)를 grep해 ISSUE_ACCESS_DENIED로 맞췄다.
 */
const getProjectCfdHandler = http.get('/api/v1/projects/:projectKey/cfd', ({ params }) => {
  const projectKey = params['projectKey'] as string

  if (projectKey === FORBIDDEN_PROJECT_KEY) {
    return HttpResponse.json(
      { errorCode: 'ISSUE_ACCESS_DENIED', message: '이 작업을 수행할 권한이 없습니다.' },
      { status: 403 },
    )
  }

  if (projectKey === EMPTY_PROJECT_KEY) {
    return HttpResponse.json({ data: emptyCfd(projectKey) })
  }

  const stored = cfdStore.get(projectKey)
  if (stored === undefined) {
    return HttpResponse.json({ data: emptyCfd(projectKey) })
  }

  return HttpResponse.json({ data: stored })
})

// ─────────────────────────────────────────────────────────────────────────────
// Export
// ─────────────────────────────────────────────────────────────────────────────

/** CFD BC MSW 핸들러 배열 */
export const cfdHandlers = [getProjectCfdHandler]
