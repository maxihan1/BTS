// 개인 캘린더 조회 API + Zod 계약 (FR-CA-01)
import { z } from 'zod'
import { apiGet } from './client'

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 — spec §API 인터페이스 1:1 미러
// (docs/specs/2026-07-08-fr-ca-01-calendar.md, backend DTO invent 금지)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 캘린더 이슈 이벤트(담당 이슈의 start/due 일정) 단건 스키마.
 *
 * - `startDate`/`dueDate`: nullable ISO date 문자열(`"2026-07-03"`). 스펙상 둘 중 하나는
 *   항상 non-null이지만(FR-CA-01.3), 이 불변식 검증은 백엔드 책임 — Zod는 필드 형태만 검증한다.
 * - identity-access 모듈은 `@JsonInclude(NON_NULL)`을 쓰지 않는다(profile.ts 선례) — 키 자체
 *   누락 방어용 `.default(null)` 폴백은 불필요.
 */
export const calendarIssueEventSchema = z.object({
  key: z.string(),
  summary: z.string(),
  issueType: z.string(),
  currentStateKey: z.string(),
  startDate: z.string().nullable(),
  dueDate: z.string().nullable(),
})

/**
 * 캘린더 Worklog 이벤트 단건 스키마.
 *
 * - `id`: Worklog UUID.
 * - `issueSummary`: nullable — 조회자에게 비가시인 이슈를 참조하는 worklog는 제목이 마스킹되어
 *   `null`로 내려온다(C4, adapter 가시성 필터 — issueSummary가 있어도 이슈 자체 상세 접근은 별도 권한 검사).
 * - `timeSpentSeconds`: 초 단위 정수.
 */
export const calendarWorklogEventSchema = z.object({
  id: z.string().uuid(),
  issueKey: z.string(),
  issueSummary: z.string().nullable(),
  date: z.string(),
  timeSpentSeconds: z.number().int().nonnegative(),
})

/**
 * 개인 캘린더 조회 응답 스키마.
 *
 * `GET /api/v1/users/me/calendar?from=&to=` 응답 — 봉투 래퍼 없음(identity-access
 * `/users/me/*` 관례, profile.ts 선례).
 *
 * - `timezone`: 백엔드가 이미 사용자 로컬 날짜로 계산을 마친 결과 — 프론트는 이 값을 참고만
 *   하고 `startDate`/`dueDate`/`date` 문자열을 재변환하지 않는다(이중 변환 시 날짜 밀림 버그).
 */
export const calendarResponseSchema = z.object({
  from: z.string(),
  to: z.string(),
  timezone: z.string(),
  issueEvents: z.array(calendarIssueEventSchema),
  worklogEvents: z.array(calendarWorklogEventSchema),
  truncated: z.boolean(),
})

// ─────────────────────────────────────────────────────────────────────────────
// 타입 export — lib/hooks 공유용
// ─────────────────────────────────────────────────────────────────────────────

/** 캘린더 이슈 이벤트 타입 — `calendarIssueEventSchema`에서 도출 */
export type CalendarIssueEvent = z.infer<typeof calendarIssueEventSchema>

/** 캘린더 Worklog 이벤트 타입 — `calendarWorklogEventSchema`에서 도출 */
export type CalendarWorklogEvent = z.infer<typeof calendarWorklogEventSchema>

/** 개인 캘린더 조회 응답 타입 — `calendarResponseSchema`에서 도출 */
export type CalendarResponse = z.infer<typeof calendarResponseSchema>

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 본인 개인 캘린더(담당 이슈 일정 + Worklog)를 조회한다.
 *
 * `GET /api/v1/users/me/calendar?from={from}&to={to}` → 200 {@link CalendarResponse}.
 *
 * @param from 조회 시작일 (ISO date, `"YYYY-MM-DD"`)
 * @param to 조회 종료일 (ISO date, `"YYYY-MM-DD"`, 포함)
 * @returns CalendarResponse
 * @throws ApiError(400) INVALID_CALENDAR_RANGE — from>to, 창(from~to)>90일, 날짜 형식 오류 중 하나
 * @throws ApiError(401) 미인증
 * @throws ZodError 응답 스키마 불일치 시
 *
 * @see 스펙 docs/specs/2026-07-08-fr-ca-01-calendar.md §API 인터페이스
 */
export async function fetchCalendar(from: string, to: string): Promise<CalendarResponse> {
  const searchParams = new URLSearchParams({ from, to })
  return apiGet(`/api/v1/users/me/calendar?${searchParams.toString()}`, calendarResponseSchema)
}
