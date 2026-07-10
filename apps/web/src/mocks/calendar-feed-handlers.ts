// 캘린더 iCal 구독 피드 MSW stateful 핸들러 — 발급(rotate)/조회/취소 (FR-CA-02 Task 9)
import { http, HttpResponse } from 'msw'
import type { CalendarFeedIssued, CalendarFeedStatus } from '@/api/calendarFeed'

// ─────────────────────────────────────────────────────────────────────────────
// stateful store — 캘린더 피드는 사용자당 1개(rotate)이므로 단일 nullable 레코드로 충분하다
// (목록형 pat-handlers.ts와 달리 단건 상태라 Map이 아닌 단일 값을 쓴다).
//
// 각 Playwright 테스트는 새 브라우저 컨텍스트(→ 새 페이지 로드 → 모듈 재평가)이므로 이 모듈
// top-level 변수는 테스트마다 자동으로 초기 상태로 돌아간다 — 별도 리셋 API 없이도 안전하다
// (webhook-fixtures.ts 동형, msw-derived-behavior-shared-store-e2e 학습 반영).
// vitest 단위 테스트가 이 모듈을 직접 재사용할 때를 대비해 {@link resetCalendarFeedStore}도
// 함께 내보낸다(keymap-handlers.ts resetKeymapStore 선례).
// ─────────────────────────────────────────────────────────────────────────────

interface FeedRecord {
  token: string
  createdAt: string
}

let feed: FeedRecord | null = null

/** 캘린더 피드 상태를 초기(미발급) 상태로 되돌린다 — 각 테스트 beforeEach에서 호출 */
export function resetCalendarFeedStore(): void {
  feed = null
}

// ─────────────────────────────────────────────────────────────────────────────
// 토큰 생성 헬퍼 — 신규 의존성 금지, crypto.getRandomValues 표준 API로 hex 문자열 생성
// (pat-handlers.ts generateUuidV4 동형 패턴 — 화면 표시/URL 조합 검증에는 고유 hex 문자열이면 충분)
// ─────────────────────────────────────────────────────────────────────────────

function generateHexToken(): string {
  const bytes = new Uint8Array(32)
  if (typeof crypto !== 'undefined' && typeof crypto.getRandomValues === 'function') {
    crypto.getRandomValues(bytes)
  } else {
    for (let i = 0; i < bytes.length; i += 1) {
      bytes[i] = Math.floor(Math.random() * 256)
    }
  }
  return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
}

/** 발급 응답 feedUrl 조합에 쓰는 base — 백엔드 `bts.auth.issuer-uri` 기본값과 동일(spec T5) */
const FEED_BASE_URL = 'http://localhost:8080'

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/users/me/calendar/feed
// ─────────────────────────────────────────────────────────────────────────────

/**
 * GET /api/v1/users/me/calendar/feed — 발급 여부/발급 시각 조회.
 *
 * 미발급 시 `createdAt` 키 자체를 생략한다(spec S6 `{enabled:false}` 예시 — 필수-nullable
 * 신규 필드 stub 누락 회귀 방지, CalendarFeedStatusSchema는 `createdAt`을 `.nullish()`로
 * 선언해 키 부재/undefined/null을 모두 수용한다).
 */
const getCalendarFeedHandler = http.get('/api/v1/users/me/calendar/feed', () => {
  const body: CalendarFeedStatus = feed === null ? { enabled: false } : { enabled: true, createdAt: feed.createdAt }
  return HttpResponse.json(body)
})

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/users/me/calendar/feed — 발급(최초) 또는 재발급(rotate)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * POST /api/v1/users/me/calendar/feed — 발급/재발급(rotate).
 *
 * 재호출 시 기존 토큰을 새 토큰으로 덮어쓴다(upsert) — 백엔드
 * `INSERT ... ON CONFLICT (user_id) DO UPDATE`와 동일한 rotate 동작을 시뮬레이션한다.
 * 응답은 `CalendarFeedIssuedSchema`(feedUrl/token/createdAt 전부 required)를 만족하도록
 * 3개 필드 모두 채운다.
 */
const issueCalendarFeedHandler = http.post('/api/v1/users/me/calendar/feed', () => {
  const token = generateHexToken()
  const createdAt = new Date().toISOString()
  feed = { token, createdAt }
  const body: CalendarFeedIssued = {
    feedUrl: `${FEED_BASE_URL}/ical/feed/${token}.ics`,
    token,
    createdAt,
  }
  return HttpResponse.json(body, { status: 201 })
})

// ─────────────────────────────────────────────────────────────────────────────
// DELETE /api/v1/users/me/calendar/feed — 취소(하드삭제)
// ─────────────────────────────────────────────────────────────────────────────

const revokeCalendarFeedHandler = http.delete('/api/v1/users/me/calendar/feed', () => {
  feed = null
  return new HttpResponse(null, { status: 204 })
})

// ─────────────────────────────────────────────────────────────────────────────
// Export
// ─────────────────────────────────────────────────────────────────────────────

/** 캘린더 iCal 구독 피드 MSW 핸들러 배열 — handlers.ts에서 spread해 전역 등록한다. */
export const calendarFeedHandlers = [getCalendarFeedHandler, issueCalendarFeedHandler, revokeCalendarFeedHandler]
