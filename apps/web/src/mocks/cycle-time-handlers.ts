// Cycle Time / Lead Time 분포 BC MSW 핸들러 — GET /api/v1/projects/:projectKey/cycle-time, 시드 가능 store (FR-RP-04 D6/D7 Task-8)
//
// 교훈 반영.
//   - msw-derived-behavior-shared-store-e2e: 파생 응답은 공유 store에서 읽음
//   - fr-bd-01: 신규 store는 모듈 로드 시 자동 시드 필수(dev/E2E 빈 화면 방지, 단위 테스트는 MODE='test'에서 건너뜀)
//   - frontend-zod-backend-dto-contract-gap: 응답 계약은 backend
//     backend/modules/issue-tracking/.../cycletime/web/dto/CycleTimeResponse.kt / CycleTimeController.kt를
//     grep해 대조했다 — from/to는 컨트롤러가 항상 기본 30일 창을 해석해 echo하고, 프론트 api 클라이언트
//     (apps/web/src/api/cycle-time.ts)는 from/to 쿼리 파라미터를 보내지 않는다. 이 핸들러도 동일하게
//     projectKey에만 반응하고 쿼리 파라미터를 읽거나 날짜를 비교하는 로직을 두지 않는다(날짜 lexical
//     비교 회귀 방지 — date-input-iso-instant-query-param 교훈).
//   - 403 errorCode는 CycleTimeExceptionHandler.kt(handleAccessDenied)를 grep해 ISSUE_ACCESS_DENIED로
//     맞췄다 — cfd-handlers.ts(같은 issue-tracking BC)와 동일한 값이다.
//
import { http, HttpResponse } from 'msw'
import type { CycleTimeResponse, MetricResponse, SampleResponse } from '@/api/cycle-time'

// ─────────────────────────────────────────────────────────────────────────────
// 시나리오 트리거 projectKey 상수 — 특정 값 요청 시 고정 응답 (E2E/단위테스트 공용)
// ─────────────────────────────────────────────────────────────────────────────

/** 이 projectKey로 요청하면 403(ISSUE_ACCESS_DENIED)을 반환한다 */
export const FORBIDDEN_PROJECT_KEY = 'PROJECT-FORBIDDEN'

/** 이 projectKey로 요청하면 200이되 cycleTime/leadTime 모두 count=0인 빈 응답을 반환한다 */
export const EMPTY_PROJECT_KEY = 'PROJECT-EMPTY'

// ─────────────────────────────────────────────────────────────────────────────
// 공유 stateful store — projectKey → CycleTimeResponse
// ─────────────────────────────────────────────────────────────────────────────

/** Cycle/Lead Time store — projectKey 기준. GET 핸들러가 읽어 응답을 구성한다 (읽기 전용 API라 mutation 없음) */
export let cycleTimeStore: Map<string, CycleTimeResponse> = new Map()

/** store를 초기 상태로 리셋한다. 각 테스트 beforeEach에서 호출해 테스트 간 격리를 보장한다. */
export function resetCycleTimeStore(): void {
  cycleTimeStore = new Map()
}

/**
 * CycleTimeResponse를 store에 시드한다. 동일 projectKey가 이미 있으면 덮어쓴다.
 *
 * @param response 시드할 Cycle/Lead Time 응답
 */
export function seedCycleTime(response: CycleTimeResponse): void {
  cycleTimeStore.set(response.projectKey, structuredClone(response))
}

// ─────────────────────────────────────────────────────────────────────────────
// 통계 계산 헬퍼 — backend CycleTimeStats.of(nearest-rank 백분위)와 동일한 공식을 재현한다.
// 리터럴 통계값 나열 금지, 표본 배열로부터 항상 계산한다 (E-C2 교훈).
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 오름차순 정렬된 초 단위 값 목록에서 nearest-rank 방식으로 p번째 백분위수를 구한다.
 *
 * 공식은 backend CycleTimeStats.percentile과 동일하다.
 * `idx = clamp(ceil(p / 100 * n) - 1, 0, n - 1)`.
 *
 * @param sortedSeconds 오름차순 정렬된 초 단위 값 목록 (비어있지 않아야 한다)
 * @param p 0~100 사이의 백분위 (예: 50 = 중앙값)
 * @returns p번째 백분위수(초)
 */
function nearestRankPercentile(sortedSeconds: number[], p: number): number {
  const n = sortedSeconds.length
  const rank = Math.ceil((p / 100) * n)
  const idx = Math.min(Math.max(rank - 1, 0), n - 1)
  const value = sortedSeconds[idx]
  if (value === undefined) {
    throw new Error('nearestRankPercentile: 인덱스가 범위를 벗어났습니다 (도달 불가 경로)')
  }
  return value
}

/**
 * 이슈 표본 목록으로부터 [MetricResponse]를 계산한다.
 *
 * 표본이 비어 있으면 count=0에 나머지 통계 필드는 전부 null이다(backend CycleTimeStats.of의
 * count=0 정책과 동일 — 값 0과 "표본 없음"을 구분).
 *
 * @param samples 이슈별 소요 시간(초) 표본 목록 (정렬 여부 무관, 내부에서 오름차순 정렬한다)
 * @returns 계산된 Metric (samples는 seconds 오름차순으로 정렬되어 반환된다)
 */
function buildMetric(samples: SampleResponse[]): MetricResponse {
  const sorted = [...samples].sort((a, b) => a.seconds - b.seconds)

  if (sorted.length === 0) {
    return {
      count: 0,
      min: null,
      max: null,
      avg: null,
      p25: null,
      p50: null,
      p75: null,
      p90: null,
      samples: [],
    }
  }

  const secondsSorted = sorted.map((sample) => sample.seconds)
  const first = secondsSorted[0]
  const last = secondsSorted[secondsSorted.length - 1]
  if (first === undefined || last === undefined) {
    throw new Error('buildMetric: 정렬된 표본 배열에서 첫/마지막 값을 찾을 수 없습니다 (도달 불가 경로)')
  }
  const sum = secondsSorted.reduce((acc, seconds) => acc + seconds, 0)

  return {
    count: sorted.length,
    min: first,
    max: last,
    avg: Math.round(sum / sorted.length),
    p25: nearestRankPercentile(secondsSorted, 25),
    p50: nearestRankPercentile(secondsSorted, 50),
    p75: nearestRankPercentile(secondsSorted, 75),
    p90: nearestRankPercentile(secondsSorted, 90),
    samples: sorted,
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 날짜 헬퍼 — cfd-handlers.ts와 동일한 addDaysIso (리터럴 나열 금지, from~to 계산)
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

// ─────────────────────────────────────────────────────────────────────────────
// 표본 생성 헬퍼 — 리터럴 나열 금지, 프로그래매틱 생성 (E-C2)
// ─────────────────────────────────────────────────────────────────────────────

/** 기본 픽스처의 Lead Time 표본 개수 (완료된 이슈 총 수) */
const LEAD_SAMPLE_COUNT = 20

/** 기본 픽스처의 Cycle Time 표본 개수 (IN_PROGRESS를 거친 이슈 수 — Lead 표본의 부분집합) */
const CYCLE_SAMPLE_COUNT = 15

/** Lead Time 최솟값(초) — 첫 이슈의 생성~완료 소요 시간 (1시간) */
const BASE_LEAD_SECONDS = 3600

/** 이슈 인덱스당 Lead Time 증분(초, 2시간) — 표본 간 값이 겹치지 않도록 단조 증가시킨다 */
const LEAD_STEP_SECONDS = 7200

/** Lead Time 대비 Cycle Time 차감분(초, 30분) — TODO 상태로 대기한 시간을 시뮬레이션한다 */
const CYCLE_WAIT_SECONDS = 1800

/**
 * projectKey 접두사로 Lead Time 표본 목록을 프로그래매틱으로 생성한다.
 *
 * 이슈 키는 `{prefix}-{n}` (1부터), 소요 시간은 [BASE_LEAD_SECONDS]에서 [LEAD_STEP_SECONDS]씩
 * 단조 증가한다.
 *
 * @param prefix 이슈 키 접두사(프로젝트 키)
 * @param count 생성할 표본 개수
 * @returns Lead Time 표본 목록
 */
function buildLeadSamples(prefix: string, count: number): SampleResponse[] {
  return Array.from({ length: count }, (_, i) => ({
    issueKey: `${prefix}-${i + 1}`,
    seconds: BASE_LEAD_SECONDS + i * LEAD_STEP_SECONDS,
  }))
}

/**
 * Lead Time 표본의 앞쪽 일부를 재사용해 Cycle Time 표본을 생성한다.
 *
 * 같은 이슈라도 Cycle Time은 Lead Time보다 짧다(착수 전 대기 시간 제외) — 각 표본에서
 * [CYCLE_WAIT_SECONDS]를 차감한다. leadSamples가 이미 오름차순이므로 동일 상수를 차감해도
 * 순서는 보존된다.
 *
 * @param leadSamples 원본 Lead Time 표본 목록 (오름차순)
 * @param count 재사용할 앞쪽 표본 개수 (IN_PROGRESS를 거친 이슈 수)
 * @returns Cycle Time 표본 목록
 */
function buildCycleSamplesFromLead(leadSamples: SampleResponse[], count: number): SampleResponse[] {
  return leadSamples.slice(0, count).map((sample) => ({
    issueKey: sample.issueKey,
    seconds: Math.max(0, sample.seconds - CYCLE_WAIT_SECONDS),
  }))
}

// ─────────────────────────────────────────────────────────────────────────────
// 기본 시드 픽스처 — backlog-fixtures.ts DEFAULT_BACKLOG / cfd-handlers.ts DEFAULT_CFD와
// 동일한 projectKey('ATLAS')를 사용한다. 리포트 화면 간 이동 E2E에서 데이터가 이어지도록 정합.
// ─────────────────────────────────────────────────────────────────────────────

/** 기본 Cycle/Lead Time 픽스처의 프로젝트 키 — cfd-handlers/velocity-handlers와 동일 값(E2E 정합) */
const DEFAULT_CYCLE_TIME_PROJECT_KEY = 'ATLAS'

/** 기본 Cycle/Lead Time 픽스처의 창 시작일 */
const DEFAULT_CYCLE_TIME_START_DATE = '2026-06-01'

/** 기본 Cycle/Lead Time 픽스처의 창 길이(일) — 백엔드 기본 창(CycleTimeController.DEFAULT_WINDOW_DAYS=30)과 동일 */
const DEFAULT_CYCLE_TIME_WINDOW_DAYS = 30

/** 기본 Lead Time 표본 (프로그래매틱 생성) */
const DEFAULT_LEAD_SAMPLES = buildLeadSamples(DEFAULT_CYCLE_TIME_PROJECT_KEY, LEAD_SAMPLE_COUNT)

/** 기본 Cycle Time 표본 — Lead 표본의 부분집합에서 대기 시간을 차감해 생성 */
const DEFAULT_CYCLE_SAMPLES = buildCycleSamplesFromLead(DEFAULT_LEAD_SAMPLES, CYCLE_SAMPLE_COUNT)

/**
 * 기본 Cycle/Lead Time 픽스처 — ATLAS 프로젝트, 30일 창.
 * to는 from + (창 길이 - 1)일로 addDaysIso를 통해 계산한다(리터럴 중복 금지).
 */
export const DEFAULT_CYCLE_TIME: CycleTimeResponse = {
  projectKey: DEFAULT_CYCLE_TIME_PROJECT_KEY,
  from: DEFAULT_CYCLE_TIME_START_DATE,
  to: addDaysIso(DEFAULT_CYCLE_TIME_START_DATE, DEFAULT_CYCLE_TIME_WINDOW_DAYS - 1),
  cycleTime: buildMetric(DEFAULT_CYCLE_SAMPLES),
  leadTime: buildMetric(DEFAULT_LEAD_SAMPLES),
}

// 모듈 로드 시 기본 픽스처를 자동 시드한다 — fr-bd-01 교훈 (신규 store 자동 시드 필수).
// dev(pnpm dev) · E2E 진입 시 store가 비어 있어 빈 화면이 노출되는 결함 방지.
// Vitest 단위 테스트 환경(MODE='test')에서는 건너뜀 — 각 테스트가 beforeEach/reset으로 직접 제어.
if (import.meta.env.MODE !== 'test') {
  seedCycleTime(DEFAULT_CYCLE_TIME)
}

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/projects/:projectKey/cycle-time
// ─────────────────────────────────────────────────────────────────────────────

/** projectKey에 대응하는 Cycle/Lead Time 데이터가 없을 때(빈 프로젝트 / 미시드) 반환하는 응답을 구성한다 */
function emptyCycleTime(projectKey: string): CycleTimeResponse {
  return {
    projectKey,
    from: DEFAULT_CYCLE_TIME.from,
    to: DEFAULT_CYCLE_TIME.to,
    cycleTime: buildMetric([]),
    leadTime: buildMetric([]),
  }
}

/**
 * GET /api/v1/projects/{projectKey}/cycle-time — 프로젝트 Cycle/Lead Time 분포 조회.
 *
 * 분기.
 * - projectKey=FORBIDDEN_PROJECT_KEY → 403 ISSUE_ACCESS_DENIED
 * - projectKey=EMPTY_PROJECT_KEY → 200 { data } 이되 cycleTime/leadTime 모두 count=0
 * - store에 있으면 → 200 { data: 저장된 CycleTimeResponse }
 * - store에 없으면 → 200 count=0인 응답 (백엔드는 유효 프로젝트+완료 이슈 0개면 200 빈 응답을
 *   반환한다. CycleTimeController/CycleTimeService 대조 — 404 아님)
 *
 * from/to 쿼리 파라미터는 읽지 않는다 — 실제 api 클라이언트(fetchProjectCycleTime)가 이를
 * 보내지 않고, 백엔드 기본 창(최근 30일)만 사용하기 때문이다. 쿼리를 읽어 날짜를 비교/필터링하는
 * 로직을 추가하면 lexical 날짜 비교 회귀(date-input-iso-instant-query-param 교훈)를 재현할 위험이
 * 있어 의도적으로 두지 않는다.
 *
 * 에러 body 형식은 cfd-handlers.ts(같은 { errorCode, message } 외피) 선례를 따르되,
 * errorCode 값은 CycleTimeExceptionHandler.kt(issue-tracking BC)를 grep해 ISSUE_ACCESS_DENIED로 맞췄다.
 */
const getProjectCycleTimeHandler = http.get('/api/v1/projects/:projectKey/cycle-time', ({ params }) => {
  const projectKey = params['projectKey'] as string

  if (projectKey === FORBIDDEN_PROJECT_KEY) {
    return HttpResponse.json(
      { errorCode: 'ISSUE_ACCESS_DENIED', message: '이 작업을 수행할 권한이 없습니다.' },
      { status: 403 },
    )
  }

  if (projectKey === EMPTY_PROJECT_KEY) {
    return HttpResponse.json({ data: emptyCycleTime(projectKey) })
  }

  const stored = cycleTimeStore.get(projectKey)
  if (stored === undefined) {
    return HttpResponse.json({ data: emptyCycleTime(projectKey) })
  }

  return HttpResponse.json({ data: stored })
})

// ─────────────────────────────────────────────────────────────────────────────
// Export
// ─────────────────────────────────────────────────────────────────────────────

/** Cycle Time / Lead Time BC MSW 핸들러 배열 */
export const cycleTimeHandlers = [getProjectCycleTimeHandler]
