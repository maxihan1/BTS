// 개인 캘린더 MSW 핸들러(vitest 전용) — 고정 시드 응답 (FR-CA-01 Task 6)
//
// ⚠ 이 파일은 `apps/web/src/mocks/handlers.ts`(전역 aggregator, dev worker + E2E가 소비)에
// 등록되어 있지 않다 — Task 6 파일 허용 목록이 vitest 전용 경로(`test/msw/handlers/`)로
// 한정되어 있기 때문. vitest `test/server.ts`는 테스트별 `server.use(...)`로 핸들러를
// 주입하는 방식이라(전역 aggregator 미경유, profileHandlers 등 기존 BC 선례 동일) 이 위치로도
// 단위 테스트는 문제없이 동작한다. 다만 `mocks/browser.ts`가 소비하는 dev/E2E 서비스 워커에는
// 이 핸들러가 아직 연결되어 있지 않으므로, E2E(Task 8)에서 이 요청을 가로채려면 별도로
// `mocks/handlers.ts`에 등록하는 배선 작업이 필요하다(파일 허용 목록 확장 필요 — 리뷰/계획 확인 대상).
import { http, HttpResponse } from 'msw'
import type { CalendarResponse } from '@/api/calendar'

// ─────────────────────────────────────────────────────────────────────────────
// 고정 시드 — spec §API 응답 예시 1:1 (docs/specs/2026-07-08-fr-ca-01-calendar.md)
// ─────────────────────────────────────────────────────────────────────────────

const SEED_ISSUE_EVENTS: CalendarResponse['issueEvents'] = [
  {
    key: 'ATLAS-12',
    summary: '결제 모듈 리팩터링',
    issueType: 'task',
    currentStateKey: 'in_progress',
    startDate: '2026-07-03',
    dueDate: '2026-07-10',
  },
  {
    key: 'ATLAS-30',
    summary: '릴리스 노트',
    issueType: 'task',
    currentStateKey: 'todo',
    startDate: null,
    dueDate: '2026-07-25',
  },
]

const SEED_WORKLOG_EVENTS: CalendarResponse['worklogEvents'] = [
  {
    id: 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11',
    issueKey: 'ATLAS-12',
    issueSummary: '결제 모듈 리팩터링',
    date: '2026-07-05',
    timeSpentSeconds: 10800,
  },
]

/** from/to 쿼리파라미터가 없을 때 사용하는 기본 창 — 스펙 예시와 동일 */
const DEFAULT_FROM = '2026-07-01'
const DEFAULT_TO = '2026-07-31'

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/users/me/calendar
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 개인 캘린더 조회 핸들러(vitest 전용).
 *
 * 요청 쿼리파라미터 `from`/`to`를 응답에 그대로 반영하고, 이벤트 목록(issueEvents/
 * worklogEvents)은 고정 시드를 반환한다 — 읽기 전용 조회라 stateful store가 불필요하다
 * (worklog-aggregate-handlers 선례).
 *
 * @see 스펙 docs/specs/2026-07-08-fr-ca-01-calendar.md §API 인터페이스
 */
const getCalendarHandler = http.get('/api/v1/users/me/calendar', ({ request }) => {
  const url = new URL(request.url)
  const from = url.searchParams.get('from') ?? DEFAULT_FROM
  const to = url.searchParams.get('to') ?? DEFAULT_TO

  const response: CalendarResponse = {
    from,
    to,
    timezone: 'Asia/Seoul',
    issueEvents: SEED_ISSUE_EVENTS,
    worklogEvents: SEED_WORKLOG_EVENTS,
    truncated: false,
  }

  return HttpResponse.json(response)
})

// ─────────────────────────────────────────────────────────────────────────────
// Export
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 개인 캘린더 BC MSW 핸들러 배열(vitest 전용).
 *
 * 테스트 파일에서 `server.use(...calendarHandlers)`로 등록한다(profileHandlers 선례).
 */
export const calendarHandlers = [getCalendarHandler]
