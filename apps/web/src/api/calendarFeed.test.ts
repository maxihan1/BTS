// calendarFeed API 클라이언트 단위 테스트 — MSW로 HTTP 가로채기 + Zod 파싱 + CSRF 헤더 검증 (FR-CA-02 Task 8)
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { ApiError } from './client'
import {
  CalendarFeedIssuedSchema,
  CalendarFeedStatusSchema,
  issueCalendarFeed,
  getCalendarFeed,
  revokeCalendarFeed,
} from './calendarFeed'

// ─────────────────────────────────────────────────────────────────────────────
// XSRF 쿠키 설정 / 해제 — CSRF 헤더 검증용 (pats.test.ts/sessions.test.ts 선례)
// ─────────────────────────────────────────────────────────────────────────────
beforeEach(() => {
  document.cookie = 'XSRF-TOKEN=test-calendar-feed-xsrf-token; path=/'
})

afterEach(() => {
  document.cookie = 'XSRF-TOKEN=; max-age=0; path=/'
})

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처 — spec §API 인터페이스 응답 예시 1:1 (docs/specs/2026-07-09-fr-ca-02-ical-export.md)
// ─────────────────────────────────────────────────────────────────────────────

const issuedFixture = {
  feedUrl: 'http://localhost:8080/ical/feed/aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899.ics',
  token: 'aabbccddeeff00112233445566778899aabbccddeeff00112233445566778899',
  createdAt: '2026-07-09T08:00:00Z',
}

const enabledStatusFixture = {
  enabled: true,
  createdAt: '2026-07-09T08:00:00Z',
}

/** 미발급 상태 — spec S6 GWT: `{enabled:false}` (createdAt 키 자체 없음) */
const disabledStatusFixture = {
  enabled: false,
}

beforeEach(() => {
  server.use(
    // POST /api/v1/users/me/calendar/feed — 201 발급(신규 또는 재발급)
    http.post('/api/v1/users/me/calendar/feed', ({ request }) => {
      const xsrfHeader = request.headers.get('X-XSRF-TOKEN')
      if (xsrfHeader === null || xsrfHeader === '') {
        return HttpResponse.json({ error: 'calendar_feed_requires_interactive_login' }, { status: 403 })
      }
      return HttpResponse.json(issuedFixture, { status: 201 })
    }),

    // GET /api/v1/users/me/calendar/feed — 200 상태 조회
    http.get('/api/v1/users/me/calendar/feed', () => {
      return HttpResponse.json(enabledStatusFixture)
    }),

    // DELETE /api/v1/users/me/calendar/feed — 204 취소
    http.delete('/api/v1/users/me/calendar/feed', ({ request }) => {
      const xsrfHeader = request.headers.get('X-XSRF-TOKEN')
      if (xsrfHeader === null || xsrfHeader === '') {
        return HttpResponse.json({ error: 'calendar_feed_requires_interactive_login' }, { status: 403 })
      }
      return new HttpResponse(null, { status: 204 })
    }),
  )
})

// ─────────────────────────────────────────────────────────────────────────────
// T1. CalendarFeedIssuedSchema — 발급 응답 파싱
// ─────────────────────────────────────────────────────────────────────────────
describe('CalendarFeedIssuedSchema', () => {
  it('T1-a: feedUrl/token/createdAt 3필드를 파싱한다', () => {
    const result = CalendarFeedIssuedSchema.parse(issuedFixture)

    expect(result.feedUrl).toBe(issuedFixture.feedUrl)
    expect(result.token).toBe(issuedFixture.token)
    expect(result.createdAt).toBe(issuedFixture.createdAt)
  })

  it('T1-b: 필수 필드(token) 누락 시 ZodError throw', () => {
    const { token, ...withoutToken } = issuedFixture
    void token
    expect(() => CalendarFeedIssuedSchema.parse(withoutToken)).toThrow()
  })

  it('T1-c: 필수 필드(feedUrl) 누락 시 ZodError throw', () => {
    const { feedUrl, ...withoutFeedUrl } = issuedFixture
    void feedUrl
    expect(() => CalendarFeedIssuedSchema.parse(withoutFeedUrl)).toThrow()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T2. CalendarFeedStatusSchema — 상태 조회 응답 파싱
// ─────────────────────────────────────────────────────────────────────────────
describe('CalendarFeedStatusSchema', () => {
  it('T2-a: 발급됨 — enabled=true + createdAt을 파싱한다', () => {
    const result = CalendarFeedStatusSchema.parse(enabledStatusFixture)

    expect(result.enabled).toBe(true)
    expect(result.createdAt).toBe(enabledStatusFixture.createdAt)
  })

  it('T2-b: 미발급 — createdAt 키 자체가 없어도(spec S6) parse 성공한다', () => {
    const result = CalendarFeedStatusSchema.parse(disabledStatusFixture)

    expect(result.enabled).toBe(false)
    expect(result.createdAt).toBeUndefined()
  })

  it('T2-c: 미발급 — createdAt이 명시적 null이어도 parse 성공한다 (NON_NULL 미적용 대비)', () => {
    const result = CalendarFeedStatusSchema.parse({ enabled: false, createdAt: null })

    expect(result.createdAt).toBeNull()
  })

  it('T2-d: 필수 필드(enabled) 누락 시 ZodError throw', () => {
    expect(() => CalendarFeedStatusSchema.parse({ createdAt: '2026-07-09T08:00:00Z' })).toThrow()
  })

  it('T2-e: 응답에 원문 토큰/해시가 섞여 있어도 스키마엔 없어 무시된다 (spec FR6)', () => {
    const withSecret = { ...enabledStatusFixture, token: 'leak', tokenHash: 'hash' }
    const result = CalendarFeedStatusSchema.parse(withSecret)
    expect('token' in result).toBe(false)
    expect('tokenHash' in result).toBe(false)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T3. issueCalendarFeed — POST /api/v1/users/me/calendar/feed
// ─────────────────────────────────────────────────────────────────────────────
describe('issueCalendarFeed', () => {
  it('T3-a: 201 발급 응답을 CalendarFeedIssued로 반환한다', async () => {
    const result = await issueCalendarFeed()

    expect(result.feedUrl).toBe(issuedFixture.feedUrl)
    expect(result.token).toBe(issuedFixture.token)
    expect(result.createdAt).toBe(issuedFixture.createdAt)
  })

  it('T3-b: POST 요청에 X-XSRF-TOKEN 헤더가 포함된다 (CSRF 방어)', async () => {
    let capturedXsrf: string | null = null
    server.use(
      http.post('/api/v1/users/me/calendar/feed', ({ request }) => {
        capturedXsrf = request.headers.get('X-XSRF-TOKEN')
        return HttpResponse.json(issuedFixture, { status: 201 })
      }),
    )
    document.cookie = 'XSRF-TOKEN=issue-csrf-token; path=/'
    await issueCalendarFeed()
    expect(capturedXsrf).toBe('issue-csrf-token')
  })

  it('T3-c: 서버 403 calendar_feed_requires_interactive_login(PAT) 시 ApiError(403)를 throw한다', async () => {
    server.use(
      http.post('/api/v1/users/me/calendar/feed', () =>
        HttpResponse.json({ error: 'calendar_feed_requires_interactive_login' }, { status: 403 }),
      ),
    )
    await expect(issueCalendarFeed()).rejects.toThrow(ApiError)
  })

  it('T3-d: 서버 401 미인증 시 ApiError(401)를 throw한다', async () => {
    server.use(
      http.post('/api/v1/users/me/calendar/feed', () =>
        HttpResponse.json({ message: 'Unauthorized' }, { status: 401 }),
      ),
    )
    await expect(issueCalendarFeed()).rejects.toThrow(ApiError)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T4. getCalendarFeed — GET /api/v1/users/me/calendar/feed
// ─────────────────────────────────────────────────────────────────────────────
describe('getCalendarFeed', () => {
  it('T4-a: 발급됨 상태를 CalendarFeedStatus로 반환한다', async () => {
    const result = await getCalendarFeed()

    expect(result.enabled).toBe(true)
    expect(result.createdAt).toBe(enabledStatusFixture.createdAt)
  })

  it('T4-b: 미발급 상태(createdAt 키 없음)도 반환한다', async () => {
    server.use(
      http.get('/api/v1/users/me/calendar/feed', () => HttpResponse.json(disabledStatusFixture)),
    )
    const result = await getCalendarFeed()
    expect(result.enabled).toBe(false)
  })

  it('T4-c: 서버 401 응답 시 ApiError를 throw한다', async () => {
    server.use(
      http.get('/api/v1/users/me/calendar/feed', () =>
        HttpResponse.json({ message: 'Unauthorized' }, { status: 401 }),
      ),
    )
    await expect(getCalendarFeed()).rejects.toThrow(ApiError)
  })

  it('T4-d: 서버 403 PAT 인증 호출 응답 시 ApiError(403)를 throw한다', async () => {
    server.use(
      http.get('/api/v1/users/me/calendar/feed', () =>
        HttpResponse.json({ error: 'calendar_feed_requires_interactive_login' }, { status: 403 }),
      ),
    )
    await expect(getCalendarFeed()).rejects.toThrow(ApiError)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T5. revokeCalendarFeed — DELETE /api/v1/users/me/calendar/feed
// ─────────────────────────────────────────────────────────────────────────────
describe('revokeCalendarFeed', () => {
  it('T5-a: 정상 요청 시 204로 완료된다', async () => {
    await expect(revokeCalendarFeed()).resolves.toBeUndefined()
  })

  it('T5-b: DELETE 요청에 X-XSRF-TOKEN 헤더가 포함된다 (CSRF 방어)', async () => {
    let capturedXsrf: string | null = null
    server.use(
      http.delete('/api/v1/users/me/calendar/feed', ({ request }) => {
        capturedXsrf = request.headers.get('X-XSRF-TOKEN')
        return new HttpResponse(null, { status: 204 })
      }),
    )
    document.cookie = 'XSRF-TOKEN=revoke-csrf-token; path=/'
    await revokeCalendarFeed()
    expect(capturedXsrf).toBe('revoke-csrf-token')
  })

  it('T5-c: 서버 403 PAT 인증 호출 응답 시 ApiError(403)를 throw한다', async () => {
    server.use(
      http.delete('/api/v1/users/me/calendar/feed', () =>
        HttpResponse.json({ error: 'calendar_feed_requires_interactive_login' }, { status: 403 }),
      ),
    )
    await expect(revokeCalendarFeed()).rejects.toThrow(ApiError)
  })

  it('T5-d: 서버 401 미인증 시 ApiError(401)를 throw한다', async () => {
    server.use(
      http.delete('/api/v1/users/me/calendar/feed', () =>
        HttpResponse.json({ message: 'Unauthorized' }, { status: 401 }),
      ),
    )
    await expect(revokeCalendarFeed()).rejects.toThrow(ApiError)
  })
})
