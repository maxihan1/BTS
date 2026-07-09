// 개인 캘린더 iCal 구독 피드 발급/조회/취소 API 클라이언트 (FR-CA-02)
import { z } from 'zod'
import { apiFetch, apiGet, ApiError } from './client'
import { readXsrfToken } from './sessions'

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 — spec §API 인터페이스 1:1 미러
// (docs/specs/2026-07-09-fr-ca-02-ical-export.md — 백엔드 CalendarFeedController/CalendarFeedDtos는
// 아직 미구현(Task 5 depends-on Task 4)이라 spec 표를 정본으로 삼는다, invent 금지)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 캘린더 피드 발급(POST 201) 응답 Zod 스키마 (spec FR1/S1).
 *
 * `token`은 발급 응답에서만 1회 노출되며 이후 어디에서도 재조회할 수 없다.
 * PAT의 `token` 필드(pats.ts `PatIssuedSchema`)와 동일 취급 — localStorage/sessionStorage 등
 * 영속 저장소에 절대 쓰지 않고, 호출부 화면 상태로만 취급한다.
 */
export const CalendarFeedIssuedSchema = z.object({
  /** 외부 캘린더 앱에 등록할 전체 구독 URL (`issuer-uri` + `/ical/feed/{token}.ics`) */
  feedUrl: z.string(),
  /** 원문 불투명 토큰 — 발급 응답 1회 한정 노출(spec FR1) */
  token: z.string(),
  /** 발급(최초 발급 또는 재발급=rotate) 시각 ISO Instant */
  createdAt: z.string(),
})

/**
 * 캘린더 피드 상태(GET 200) 응답 Zod 스키마 (spec FR6/S6).
 *
 * - `createdAt`은 미발급 시(`enabled: false`) 키 자체가 없을 수 있다(spec S6 `{enabled:false}` 예시) —
 *   `.nullish()`로 키 누락(undefined)과 명시적 null을 모두 수용한다(dashboards.ts `@JsonInclude(NON_NULL)`
 *   관례 동형 — 백엔드가 어느 직렬화 방식을 택하든 안전).
 * - 원문 토큰/해시는 이 응답에 없다(spec FR6 — 발급 시 1회만 노출).
 */
export const CalendarFeedStatusSchema = z.object({
  /** 피드 발급 여부 */
  enabled: z.boolean(),
  /** 발급 시각 ISO Instant — 미발급 시 키 없음 또는 null */
  createdAt: z.string().nullish(),
})

// ─────────────────────────────────────────────────────────────────────────────
// 추론된 타입 (interface 중복 정의 금지)
// ─────────────────────────────────────────────────────────────────────────────

/** 캘린더 피드 발급 응답 타입(raw token 포함) — z.infer로 자동 추론 */
export type CalendarFeedIssued = z.infer<typeof CalendarFeedIssuedSchema>

/** 캘린더 피드 상태 응답 타입 — z.infer로 자동 추론 */
export type CalendarFeedStatus = z.infer<typeof CalendarFeedStatusSchema>

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 본인 캘린더 구독 피드를 발급(또는 재발급=rotate)한다.
 *
 * `POST /api/v1/users/me/calendar/feed` → 201 {@link CalendarFeedIssued}.
 * - 상태 변경 요청이므로 X-XSRF-TOKEN 헤더를 포함한다(double submit cookie 패턴, pats.ts 선례).
 * - 재호출 시 기존 토큰을 무효화하고 새로 발급한다(rotate, spec FR4) — 기존 구독 URL은 다음
 *   폴링부터 404.
 * - 반환된 `token`은 이 호출에서만 확인 가능 — 호출부는 화면 상태로만 보관하고
 *   localStorage/sessionStorage 등 영속 저장소에 절대 쓰지 않는다.
 *
 * @returns CalendarFeedIssued (feedUrl, token, createdAt)
 * @throws ApiError(403) PAT 인증 호출(calendar_feed_requires_interactive_login)
 * @throws ApiError(401) 미인증
 * @throws ZodError 응답 스키마 불일치 시
 */
export async function issueCalendarFeed(): Promise<CalendarFeedIssued> {
  const res = await apiFetch('/api/v1/users/me/calendar/feed', {
    method: 'POST',
    headers: {
      'X-XSRF-TOKEN': readXsrfToken(),
    },
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  return CalendarFeedIssuedSchema.parse(await res.json())
}

/**
 * 본인 캘린더 구독 피드 상태(발급 여부/발급 시각)를 조회한다.
 *
 * `GET /api/v1/users/me/calendar/feed` → 200 {@link CalendarFeedStatus}.
 * 원문 토큰/해시는 응답에 없다(spec FR6).
 *
 * @returns CalendarFeedStatus (enabled, createdAt?)
 * @throws ApiError(403) PAT 인증 호출(calendar_feed_requires_interactive_login)
 * @throws ApiError(401) 미인증
 * @throws ZodError 응답 스키마 불일치 시
 */
export async function getCalendarFeed(): Promise<CalendarFeedStatus> {
  return apiGet('/api/v1/users/me/calendar/feed', CalendarFeedStatusSchema)
}

/**
 * 본인 캘린더 구독 피드를 취소(하드삭제)한다.
 *
 * `DELETE /api/v1/users/me/calendar/feed` → 204 No Content.
 * - 상태 변경 요청이므로 X-XSRF-TOKEN 헤더를 포함한다.
 * - 취소 이후 기존 구독 URL은 404(spec S4).
 *
 * @returns void — 204 No Content
 * @throws ApiError(403) PAT 인증 호출(calendar_feed_requires_interactive_login)
 * @throws ApiError(401) 미인증
 */
export async function revokeCalendarFeed(): Promise<void> {
  const res = await apiFetch('/api/v1/users/me/calendar/feed', {
    method: 'DELETE',
    headers: {
      'X-XSRF-TOKEN': readXsrfToken(),
    },
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
}
