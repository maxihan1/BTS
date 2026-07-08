// 캘린더 API 클라이언트 단위 테스트 — Zod 스키마 계약 + fetchCalendar (FR-CA-01 Task 6)
import { describe, it, expect } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { ApiError } from './client'
import { calendarResponseSchema, fetchCalendar } from './calendar'
import type { CalendarResponse, CalendarIssueEvent, CalendarWorklogEvent } from './calendar'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처 — spec §API 계약 응답 예시 1:1 (docs/specs/2026-07-08-fr-ca-01-calendar.md)
// ─────────────────────────────────────────────────────────────────────────────

/** RFC4122 version/variant 니블을 충족하는 실제 v4 형식 UUID (memory zod-v4-uuid-fixture-strictness) */
const VALID_WORKLOG_ID = 'a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11'

const issueEventFull: CalendarIssueEvent = {
  key: 'ATLAS-12',
  summary: '결제 모듈',
  issueType: 'task',
  currentStateKey: 'in_progress',
  startDate: '2026-07-03',
  dueDate: '2026-07-10',
}

const worklogEventFull: CalendarWorklogEvent = {
  id: VALID_WORKLOG_ID,
  issueKey: 'ATLAS-12',
  issueSummary: '결제 모듈',
  date: '2026-07-05',
  timeSpentSeconds: 10800,
}

/** startDate=null(dueDate만 존재) 케이스 — 스펙 예시 "ATLAS-30" */
const issueEventNullStart: CalendarIssueEvent = {
  key: 'ATLAS-30',
  summary: '릴리스 노트',
  issueType: 'task',
  currentStateKey: 'todo',
  startDate: null,
  dueDate: '2026-07-25',
}

/** issueSummary=null(비가시 이슈 마스킹, C4) 케이스 */
const worklogEventMaskedSummary: CalendarWorklogEvent = {
  id: VALID_WORKLOG_ID,
  issueKey: 'ATLAS-40',
  issueSummary: null,
  date: '2026-07-06',
  timeSpentSeconds: 3600,
}

const fullResponse: CalendarResponse = {
  from: '2026-07-01',
  to: '2026-07-31',
  timezone: 'Asia/Seoul',
  issueEvents: [issueEventFull],
  worklogEvents: [worklogEventFull],
  truncated: false,
}

const nullableResponse: CalendarResponse = {
  from: '2026-07-01',
  to: '2026-07-31',
  timezone: 'Asia/Seoul',
  issueEvents: [issueEventNullStart],
  worklogEvents: [worklogEventMaskedSummary],
  truncated: false,
}

// ─────────────────────────────────────────────────────────────────────────────
// calendarResponseSchema — Zod 계약 파싱
// ─────────────────────────────────────────────────────────────────────────────

describe('calendarResponseSchema', () => {
  it('스펙 §API 응답 예시(모든 필드 채워짐)를 parse 성공한다', () => {
    const result = calendarResponseSchema.parse(fullResponse)
    expect(result.issueEvents[0]?.key).toBe('ATLAS-12')
    expect(result.worklogEvents[0]?.id).toBe(VALID_WORKLOG_ID)
    expect(result.truncated).toBe(false)
  })

  it('nullable 필드(startDate=null / issueSummary=null)가 있어도 parse 성공한다', () => {
    const result = calendarResponseSchema.parse(nullableResponse)
    expect(result.issueEvents[0]?.startDate).toBeNull()
    expect(result.issueEvents[0]?.dueDate).toBe('2026-07-25')
    expect(result.worklogEvents[0]?.issueSummary).toBeNull()
  })

  it('worklogEvents[].id가 UUID 형식이 아니면 ZodError를 throw한다', () => {
    const invalidResponse = {
      ...fullResponse,
      worklogEvents: [{ ...worklogEventFull, id: 'not-a-uuid' }],
    }
    expect(() => calendarResponseSchema.parse(invalidResponse)).toThrow()
  })

  it('truncated 필드가 없으면 ZodError를 throw한다', () => {
    const { truncated: _truncated, ...withoutTruncated } = fullResponse
    expect(() => calendarResponseSchema.parse(withoutTruncated)).toThrow()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// fetchCalendar — apiFetch(apiGet) 기반 호출
// ─────────────────────────────────────────────────────────────────────────────

describe('fetchCalendar', () => {
  it('GET /api/v1/users/me/calendar?from=&to= 호출 후 쿼리파라미터를 실어 보내고 응답을 파싱해 반환한다', async () => {
    server.use(
      http.get('/api/v1/users/me/calendar', ({ request }) => {
        const url = new URL(request.url)
        expect(url.searchParams.get('from')).toBe('2026-07-01')
        expect(url.searchParams.get('to')).toBe('2026-07-31')
        return HttpResponse.json(fullResponse)
      }),
    )

    const result = await fetchCalendar('2026-07-01', '2026-07-31')
    expect(result.from).toBe('2026-07-01')
    expect(result.to).toBe('2026-07-31')
    expect(result.issueEvents).toHaveLength(1)
  })

  it('400 응답 시 ApiError(400)를 throw하고 body에 errorCode가 포함된다', async () => {
    server.use(
      http.get('/api/v1/users/me/calendar', () =>
        HttpResponse.json({ errorCode: 'INVALID_CALENDAR_RANGE' }, { status: 400 }),
      ),
    )

    await expect(fetchCalendar('2026-07-31', '2026-07-01')).rejects.toBeInstanceOf(ApiError)
    await expect(fetchCalendar('2026-07-31', '2026-07-01')).rejects.toMatchObject({
      status: 400,
      body: { errorCode: 'INVALID_CALENDAR_RANGE' },
    })
  })
})
