// 스프린트 번다운/번업 BC MSW 핸들러 — GET /api/v1/sprints/:id/burndown, 시드 가능 store (FR-RP-01 D6/D7 Task-4)
//
// 교훈 반영.
//   - msw-derived-behavior-shared-store-e2e: 파생 응답은 공유 store에서 읽음
//   - fr-bd-01: 신규 store는 모듈 로드 시 자동 시드 필수(dev/E2E 빈 화면 방지, 단위 테스트는 MODE='test'에서 건너뜀)
//
import { http, HttpResponse } from 'msw'
import type { BurndownPoint, BurndownResponse } from '@/api/burndown'
import { findSprintInStore } from './backlog-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 시나리오 트리거 sprintId 상수 — 특정 값 요청 시 고정 에러 응답 (E2E/단위테스트 공용)
// ─────────────────────────────────────────────────────────────────────────────

/** 이 sprintId로 요청하면 403(AGILE_ACCESS_DENIED)을 반환한다 */
export const FORBIDDEN_SPRINT_ID = 'sprint-forbidden'

/** 이 sprintId로 요청하면 422(AGILE_SPRINT_DATES_REQUIRED)를 반환한다 */
export const DATES_REQUIRED_SPRINT_ID = 'sprint-dates-required'

// ─────────────────────────────────────────────────────────────────────────────
// 공유 stateful store — sprintId → BurndownResponse
// ─────────────────────────────────────────────────────────────────────────────

/** 번다운 store — sprintId 기준. GET 핸들러가 읽어 응답을 구성한다 (읽기 전용 API라 mutation 없음) */
export let burndownStore: Map<string, BurndownResponse> = new Map()

/** store를 초기 상태로 리셋한다. 각 테스트 beforeEach에서 호출해 테스트 간 격리를 보장한다. */
export function resetBurndownStore(): void {
  burndownStore = new Map()
}

/**
 * BurndownResponse를 store에 시드한다. 동일 sprintId가 이미 있으면 덮어쓴다.
 *
 * @param response 시드할 번다운 응답
 */
export function seedBurndown(response: BurndownResponse): void {
  burndownStore.set(response.sprintId, structuredClone(response))
}

// ─────────────────────────────────────────────────────────────────────────────
// 기본 시드 픽스처 — backlog-fixtures.ts DEFAULT_BACKLOG의 스프린트 1과 동일 sprintId
// ─────────────────────────────────────────────────────────────────────────────

/** 기본 시드 스프린트 ID — backlog-fixtures.ts DEFAULT_BACKLOG.sprints[0]과 동일 값 */
export const DEFAULT_SPRINT_ID = 'a0000000-0000-4000-8000-000000000001'

/**
 * 기본 번다운 픽스처 — ATLAS 프로젝트, 5일 시계열, 마지막 2일은 미래(remaining/completed=null).
 * BurndownResponse 계약과 정확히 일치 (프론트-백엔드 drift 방지, frontend-zod-backend-dto-contract-gap 교훈).
 */
export const DEFAULT_BURNDOWN: BurndownResponse = {
  sprintId: DEFAULT_SPRINT_ID,
  projectKey: 'ATLAS',
  status: 'ACTIVE',
  startDate: '2026-06-01',
  endDate: '2026-06-05',
  totalScopeSeconds: 36000,
  points: [
    { date: '2026-06-01', remainingSeconds: 36000, idealSeconds: 36000, completedSeconds: 0, scopeSeconds: 36000 },
    { date: '2026-06-02', remainingSeconds: 27000, idealSeconds: 27000, completedSeconds: 9000, scopeSeconds: 36000 },
    { date: '2026-06-03', remainingSeconds: 18000, idealSeconds: 18000, completedSeconds: 18000, scopeSeconds: 36000 },
    { date: '2026-06-04', remainingSeconds: null, idealSeconds: 9000, completedSeconds: null, scopeSeconds: 36000 },
    { date: '2026-06-05', remainingSeconds: null, idealSeconds: 0, completedSeconds: null, scopeSeconds: 36000 },
  ],
}

// 모듈 로드 시 기본 픽스처를 자동 시드한다 — fr-bd-01 교훈 (신규 store 자동 시드 필수).
// dev(pnpm dev) · E2E 진입 시 store가 비어 있어 빈 화면이 노출되는 결함 방지.
// Vitest 단위 테스트 환경(MODE='test')에서는 건너뜀 — 각 테스트가 beforeEach/reset으로 직접 제어.
if (import.meta.env.MODE !== 'test') {
  seedBurndown(DEFAULT_BURNDOWN)
}

// ─────────────────────────────────────────────────────────────────────────────
// 보드 「작업일」 설정에서 points 를 파생한다 (부채 177 Task 33 · J38·J39·J40)
// ─────────────────────────────────────────────────────────────────────────────
//
// ## 왜 여기서 파생하나
//
// 프론트에는 번다운을 좁히는 코드가 없다 — `BurndownChart` 는 응답 `points` 를 그대로 그린다.
// 그래서 이 store 가 정적이면 **근무일을 저장해도 차트가 한 점도 안 바뀌고**, 「설정은 되는데
// 차트가 안 바뀐다」는 침묵 실패를 프론트 E2E 가 원리적으로 못 본다(부채 177 리뷰 critical gap).
//
// ## 어디서 읽나 — 공유 store 를 HTTP 로 경유한다
//
// 작업일 설정의 정본은 `board-handlers.ts` 의 `boardSettingsStore` 인데 **export 되지 않는다**.
// 그래서 그 store 의 유일한 공개 창구인 `GET /api/v1/boards/{id}` 를 핸들러 안에서 부른다
// (`msw-derived-behavior-shared-store-e2e` — 파생 응답은 공유 store 에서 읽는다. 여기 값을
// 복제해 두면 저장 경로와 조회 경로가 서로를 검사하지 않는 두 번째 진실이 된다).
// 스프린트 → 보드는 `backlog-fixtures.StoredSprint.boardId` 가 잇는다 — 백엔드도 `sprint.boardId`
// 로 보드 설정을 찾는다(`SprintBurndownService.getBurndown`).
//
// ## ★목이 백엔드와 **같은** 것 (`BurndownCalculator` · `SprintBurndownService` 대조)
//
// | 규칙 | 정본 |
// |---|---|
// | `standardDays === null` 이면 축은 **달력일 전부**이고 `nonWorkingDates` 도 **무시**한다 | `SprintBurndownService.toWorkingCalendar` 의 `settings?.standardDays ?: return null` |
// | 축 = 표준 요일에 들고 비근무일이 아닌 날 | `WorkingDayCalendar.isWorkingDay` |
// | 요일 판정은 **달력 날짜**의 요일이다 — 보드 타임존을 태우지 않는다 | `LocalDate.dayOfWeek` |
// | 축이 비면 point 0개(전 기간 비근무일 · 스펙 E2) | `BurndownCalculator.buildAxis` + `coerceAtLeast(0)` |
// | ideal 은 **축 위 순번**으로 보간하고 분모도 축 구간 수다 | `computeIdealSeconds` |
// | ideal 반올림은 half-up 정수 나눗셈이다 | `(numerator + totalDays / 2) / totalDays` |
// | 누적은 **달력일 전부**를 걸어가고 점만 축에서 낸다(비근무일 worklog 를 버리지 않는다) | `BurndownCalculator.calculate` 의 while 루프 |
// | worklog 일 귀속은 **보드 타임존** 기준이고 미설정이면 UTC 다 | `SprintBurndownService.aggregateByBoardDate` · `resolveBoardZone` |
// | remaining 은 0 미만으로 내려가지 않고 completed 는 클램프하지 않는다 | `coerceAtLeast(0L)` |
//
// ## ★목이 백엔드와 **다른** 것 — 목은 근사이지 재구현이 아니다
//
// - **`today`/asOf 가 없다.** 백엔드는 `min(end, today)` 이후를 `remainingSeconds=null`(미래)로
//   내는데, 목은 시계를 모르므로 파생 경로의 값이 **전부 non-null** 이다. 그래서 미설정 경로는
//   파생하지 않고 시드를 그대로 돌려준다 — `DEFAULT_BURNDOWN` 이 들고 있는 null 두 칸이 그대로
//   남아야 기존 e2e/단위 테스트의 기준선이 바이트 단위로 유지된다.
// - **스코프가 고정이다.** 백엔드는 Σ original_estimate_seconds 를 이슈에서 집계하고 이슈별
//   보안(security_level) 필터까지 태운다. 목은 시드의 `totalScopeSeconds` 를 그대로 쓴다.
// - **worklog 가 시드 상수다.** 백엔드는 `SprintBurndownLookupPort` 로 실제 worklog 를 읽는다.
//   목은 아래 [SPRINT_WORKLOG_SEED] 만 안다 — 화면에서 worklog 를 기록해도 차트는 안 움직인다.
// - **스프린트 시작 이전 worklog 선합산이 없다.** 백엔드는 start 이전 키를 start 버킷에 미리
//   더한다(`preStartSum`). 시드가 전부 기간 안이라 그 경로를 두지 않았다.
// - **요일 키 검증이 없다.** 저장 시점(`board-handlers` PUT)이 이미 걸렀다고 믿는다.

/** 목이 파생에 쓰는 worklog 한 건 — 백엔드 `WorklogContribution` 의 최소 미러. */
interface WorklogSeed {
  /** 기록 시각(UTC Instant, ISO 8601). **날짜가 아니라 시각**이어야 타임존이 칸을 옮긴다. */
  startedAt: string
  /** 기록 시간(초). */
  timeSpentSeconds: number
}

/**
 * 스프린트별 worklog 시드 — 타임존 파생의 유일한 원천.
 *
 * ★값을 고른 이유. 둘 다 **UTC 심야**라 타임존이 칸을 옮긴다.
 * - `Asia/Seoul`(+9) 에서는 각각 06-03 · 06-04 08:30 으로 **다음 날 칸**에 붙는다.
 * - `America/New_York`(-4, EDT) 에서는 06-02 · 06-03 19:30 으로 **UTC 와 같은 칸**에 남는다.
 * 그래서 서울↔뉴욕이 갈리고, 「타임존을 무시하고 UTC 고정」 구현은 뉴욕 쪽과 구분되지 않는다.
 * 백엔드 `BurndownTimezoneTest` 의 `LATE_NIGHT_UTC` 와 같은 수법이다.
 *
 * ★합계는 [DEFAULT_BURNDOWN] 의 `completedSeconds` 증분(06-02 +9000 · 06-03 +9000)과 같다 —
 * UTC(=타임존 미설정)로 파생하면 시드의 앞 세 점과 값이 일치한다. 설정을 켜는 순간 차트가
 * 엉뚱한 값으로 튀지 않게 하려는 것이다.
 */
const SPRINT_WORKLOG_SEED: Record<string, readonly WorklogSeed[]> = {
  [DEFAULT_SPRINT_ID]: [
    { startedAt: '2026-06-02T23:30:00Z', timeSpentSeconds: 9000 },
    { startedAt: '2026-06-03T23:30:00Z', timeSpentSeconds: 9000 },
  ],
}

/** `boards.working_days` 3글자 요일 키 — `Date.getUTCDay()` 순서(0=일). 백엔드 `WEEKDAY_BY_KEY` 의 짝. */
const WEEKDAY_KEYS = ['SUN', 'MON', 'TUE', 'WED', 'THU', 'FRI', 'SAT'] as const

/** 보드 조회 응답이 싣는 「작업일」 3축 — `board-handlers.BoardSettingsPayload.workingDays` 미러. */
interface BoardWorkingDaysPayload {
  /** 표준 근무일 요일 키. ★`null` 은 **미설정**이고 `[]`(근무일 0개)와 뜻이 다르다. */
  standardDays: string[] | null
  /** 비근무일(ISO 날짜). */
  nonWorkingDates: string[]
  /** 보드 타임존(IANA). null 이면 미설정 = UTC. */
  timezone: string | null
}

/**
 * `YYYY-MM-DD` 를 하루 단위로 [start]~[end](양끝 포함) 순회한다.
 *
 * @param start 시작일(ISO 날짜).
 * @param end 종료일(ISO 날짜). [start] 보다 이르면 빈 배열이다.
 */
function eachCalendarDate(start: string, end: string): string[] {
  const dates: string[] = []
  for (let day = new Date(`${start}T00:00:00Z`); day.toISOString().slice(0, 10) <= end; day.setUTCDate(day.getUTCDate() + 1)) {
    dates.push(day.toISOString().slice(0, 10))
  }
  return dates
}

/**
 * ISO 날짜의 요일 키를 돌려준다.
 *
 * ★**보드 타임존을 태우지 않는다.** 백엔드도 `LocalDate.dayOfWeek` 라 타임존과 무관하다 —
 * 여기서 타임존을 태우면 목만 「뉴욕에서는 월요일이 아니다」라고 말하게 된다.
 *
 * @param isoDate `YYYY-MM-DD`.
 */
function weekdayKey(isoDate: string): string {
  return WEEKDAY_KEYS[new Date(`${isoDate}T00:00:00Z`).getUTCDay()] ?? ''
}

/**
 * worklog 시각이 **보드 타임존 기준으로** 어느 날짜 칸에 붙는지 계산한다.
 *
 * 백엔드 `SprintBurndownService.aggregateByBoardDate` 의 `LocalDate.ofInstant(startedAt, zone)` 짝이다.
 * 알 수 없는 타임존은 UTC 로 떨어뜨린다 — 저장 시점이 IANA 를 이미 검증했으므로 판정을 복제하지 않는다.
 *
 * @param startedAt UTC Instant(ISO 8601).
 * @param timezone 보드 타임존. null 이면 미설정 = UTC(백엔드 `resolveBoardZone` 과 같다).
 */
function boardLocalDate(startedAt: string, timezone: string | null): string {
  const zone = timezone ?? 'UTC'
  let parts: Intl.DateTimeFormatPart[]
  try {
    parts = new Intl.DateTimeFormat('en-US', {
      timeZone: zone,
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
    }).formatToParts(new Date(startedAt))
  } catch {
    return startedAt.slice(0, 10)
  }
  const at = (type: string): string => parts.find((part) => part.type === type)?.value ?? ''
  return `${at('year')}-${at('month')}-${at('day')}`
}

/**
 * ideal 라인의 [axisIndex] 번째 값 — 백엔드 `BurndownCalculator.computeIdealSeconds` 의 half-up 미러.
 *
 * [totalDays] 가 0 이면 0 나눗셈을 피해 [scopeSeconds] 를 그대로 준다(근무일이 하루뿐인 스프린트).
 */
function idealSeconds(scopeSeconds: number, axisIndex: number, totalDays: number): number {
  if (totalDays === 0) return scopeSeconds
  return Math.floor((scopeSeconds * (totalDays - axisIndex) + Math.floor(totalDays / 2)) / totalDays)
}

/**
 * 보드 「작업일」 설정으로 [stored] 의 `points` 를 다시 만든다.
 *
 * ★**`null`(미설정)과 `[]`(근무일 0개)를 갈라야 한다.** `??` 로 뭉개면 미설정이 조용히
 * 「모든 날 근무」나 「모든 날 비근무」가 된다 — 이 PR 이 프론트 전 층에서 지켜 온 구분이다.
 * - `null` → **파생하지 않고 시드를 그대로** 돌려준다. 축은 달력일 전부이고 `nonWorkingDates`
 *   도 무시한다(근무일 축이 없는데 구멍만 뚫으면 설정한 적 없는 규칙이 차트를 바꾼다 · 스펙 R6·E7).
 * - `[]` → 모든 날이 비근무일이라 축이 비고 `points` 가 0개다.
 *   🛑 이 상태는 **API 로는 도달할 수 없다** — `PUT /boards/{id}/working-days` 가 0개를 400 으로
 *      막는다(스펙 E1). store 를 직접 심었을 때만 나오므로 **E2E 가 덮지 못하는 가지**다.
 *      그래도 `??` 로 뭉개지 않는 이유는, 뭉개는 순간 위 두 줄이 같은 코드가 되기 때문이다.
 *
 * @param stored 시드된 응답.
 * @param workingDays 보드 조회 응답이 실어 온 작업일 3축.
 */
function deriveFromWorkingDays(stored: BurndownResponse, workingDays: BoardWorkingDaysPayload): BurndownResponse {
  const standardDays = workingDays.standardDays
  if (standardDays === null) return stored

  const working = new Set(standardDays)
  const nonWorking = new Set(workingDays.nonWorkingDates)
  const calendar = eachCalendarDate(stored.startDate, stored.endDate)
  const axis = new Set(calendar.filter((date) => working.has(weekdayKey(date)) && !nonWorking.has(date)))

  const buckets = new Map<string, number>()
  for (const entry of SPRINT_WORKLOG_SEED[stored.sprintId] ?? []) {
    const bucket = boardLocalDate(entry.startedAt, workingDays.timezone)
    buckets.set(bucket, (buckets.get(bucket) ?? 0) + entry.timeSpentSeconds)
  }

  const scopeSeconds = stored.totalScopeSeconds
  const totalDays = Math.max(axis.size - 1, 0)
  const points: BurndownPoint[] = []
  let cumulative = 0
  for (const date of calendar) {
    // ★누적은 달력일 전부를 걸어간다 — 비근무일에 적힌 worklog 를 버리면 잔여가 영원히 안 준다.
    cumulative += buckets.get(date) ?? 0
    if (!axis.has(date)) continue
    points.push({
      date,
      remainingSeconds: Math.max(scopeSeconds - cumulative, 0),
      idealSeconds: idealSeconds(scopeSeconds, points.length, totalDays),
      completedSeconds: cumulative,
      scopeSeconds,
    })
  }
  return { ...stored, points }
}

/**
 * 스프린트가 속한 보드의 작업일 설정을 읽어 [stored] 를 파생한다.
 *
 * 보드를 못 찾거나 조회가 실패하면 **시드를 그대로** 돌려준다 — 미설정과 같은 취급이라
 * 기존 화면이 조용히 망가지지 않는다(대신 S5·S6 이 red 로 알린다).
 *
 * @param stored 시드된 응답.
 */
async function withBoardWorkingDays(stored: BurndownResponse): Promise<BurndownResponse> {
  const boardId = findSprintInStore(stored.sprintId)?.storedSprint.boardId
  if (boardId === undefined || boardId === null) return stored

  try {
    const response = await fetch(`/api/v1/boards/${boardId}`)
    if (!response.ok) return stored
    const body = (await response.json()) as { data?: { workingDays?: BoardWorkingDaysPayload } }
    const workingDays = body.data?.workingDays
    if (workingDays === undefined) return stored
    return deriveFromWorkingDays(stored, workingDays)
  } catch {
    return stored
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/sprints/:id/burndown
// ─────────────────────────────────────────────────────────────────────────────

/**
 * GET /api/v1/sprints/{id}/burndown — 스프린트 번다운/번업 시계열 조회.
 *
 * 분기.
 * - sprintId=FORBIDDEN_SPRINT_ID → 403 AGILE_ACCESS_DENIED
 * - sprintId=DATES_REQUIRED_SPRINT_ID → 422 AGILE_SPRINT_DATES_REQUIRED
 * - store에 없는 sprintId → 404 AGILE_SPRINT_NOT_FOUND
 * - store에 있으면 → 200 { data: BurndownResponse }
 *
 * ★200 경로의 `points` 는 **보드 「작업일」 설정에서 파생**한다([withBoardWorkingDays]).
 * 설정이 미설정이면 시드를 그대로 낸다 — 경계는 위 「목이 백엔드와 같은/다른 것」 표가 정본이다.
 *
 * 에러 body 형식은 backlog-handlers.ts(같은 agile-planning BC) 선례를 따른다 — { errorCode, message }.
 */
const getSprintBurndownHandler = http.get('/api/v1/sprints/:id/burndown', async ({ params }) => {
  const sprintId = params['id'] as string

  if (sprintId === FORBIDDEN_SPRINT_ID) {
    return HttpResponse.json(
      { errorCode: 'AGILE_ACCESS_DENIED', message: '이 작업을 수행할 권한이 없습니다.' },
      { status: 403 },
    )
  }

  if (sprintId === DATES_REQUIRED_SPRINT_ID) {
    return HttpResponse.json(
      { errorCode: 'AGILE_SPRINT_DATES_REQUIRED', message: '스프린트 기간이 설정되지 않았습니다.' },
      { status: 422 },
    )
  }

  const stored = burndownStore.get(sprintId)
  if (stored === undefined) {
    return HttpResponse.json(
      { errorCode: 'AGILE_SPRINT_NOT_FOUND', message: `스프린트를 찾을 수 없습니다: ${sprintId}` },
      { status: 404 },
    )
  }

  return HttpResponse.json({ data: await withBoardWorkingDays(stored) })
})

// ─────────────────────────────────────────────────────────────────────────────
// Export
// ─────────────────────────────────────────────────────────────────────────────

/** 스프린트 번다운/번업 BC MSW 핸들러 배열 */
export const burndownHandlers = [getSprintBurndownHandler]
