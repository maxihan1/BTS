// 워크로그(issue-tracking BC) REST API 클라이언트 단위 테스트 — FR-TT-01 D6/D7 Task-3
import { describe, it, expect, beforeEach } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { ApiError } from '@/api/client'
import {
  worklogResponseSchema,
  worklogSummarySchema,
  worklogListResponseSchema,
  fetchWorklogs,
  addWorklog,
  updateWorklog,
  deleteWorklog,
  type WorklogResponse,
  type WorklogSummary,
  type WorklogListResponse,
} from './worklogs'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const worklogFixture: WorklogResponse = {
  id: '11111111-1111-4111-a111-111111111111',
  issueKey: 'ATLAS-1',
  authorId: '22222222-2222-4222-a222-222222222222',
  timeSpentSeconds: 3600,
  startedAt: '2026-06-20T09:00:00Z',
  comment: '초기 분석 작업',
  createdAt: '2026-06-20T09:00:00Z',
  updatedAt: '2026-06-20T09:00:00Z',
}

const worklogNullCommentFixture: WorklogResponse = {
  id: '33333333-3333-4333-a333-333333333333',
  issueKey: 'ATLAS-1',
  authorId: '22222222-2222-4222-a222-222222222222',
  timeSpentSeconds: 1800,
  startedAt: '2026-06-20T10:00:00Z',
  comment: null,
  createdAt: '2026-06-20T10:00:00Z',
  updatedAt: '2026-06-20T10:00:00Z',
}

const summaryFixture: WorklogSummary = {
  originalEstimateSeconds: 7200,
  timeSpentSeconds: 3600,
  remainingEstimateSeconds: 3600,
}

const summaryNullEstimateFixture: WorklogSummary = {
  originalEstimateSeconds: null,
  timeSpentSeconds: 3600,
  remainingEstimateSeconds: null,
}

const worklogListFixture: WorklogListResponse = {
  worklogs: [worklogFixture],
  summary: summaryFixture,
}

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 파싱
// ─────────────────────────────────────────────────────────────────────────────

describe('worklogResponseSchema', () => {
  it('정상 픽스처를 파싱한다', () => {
    const result = worklogResponseSchema.parse(worklogFixture)
    expect(result.id).toBe('11111111-1111-4111-a111-111111111111')
    expect(result.issueKey).toBe('ATLAS-1')
    expect(result.timeSpentSeconds).toBe(3600)
    expect(result.comment).toBe('초기 분석 작업')
  })

  it('comment가 null인 픽스처를 파싱한다', () => {
    const result = worklogResponseSchema.parse(worklogNullCommentFixture)
    expect(result.comment).toBeNull()
  })

  it('id가 UUID 형식이 아니면 파싱에 실패한다', () => {
    expect(() =>
      worklogResponseSchema.parse({ ...worklogFixture, id: 'not-a-uuid' }),
    ).toThrow()
  })
})

describe('worklogSummarySchema', () => {
  it('추정 초가 있는 summary를 파싱한다', () => {
    const result = worklogSummarySchema.parse(summaryFixture)
    expect(result.originalEstimateSeconds).toBe(7200)
    expect(result.remainingEstimateSeconds).toBe(3600)
  })

  it('originalEstimateSeconds와 remainingEstimateSeconds가 null인 summary를 파싱한다', () => {
    const result = worklogSummarySchema.parse(summaryNullEstimateFixture)
    expect(result.originalEstimateSeconds).toBeNull()
    expect(result.remainingEstimateSeconds).toBeNull()
    expect(result.timeSpentSeconds).toBe(3600)
  })

  it('timeSpentSeconds 필드가 없으면 파싱에 실패한다', () => {
    expect(() =>
      worklogSummarySchema.parse({ originalEstimateSeconds: null, remainingEstimateSeconds: null }),
    ).toThrow()
  })
})

describe('worklogListResponseSchema', () => {
  it('worklogs 배열과 summary를 파싱한다', () => {
    const result = worklogListResponseSchema.parse(worklogListFixture)
    expect(result.worklogs).toHaveLength(1)
    expect(result.worklogs[0]?.id).toBe('11111111-1111-4111-a111-111111111111')
    expect(result.summary.timeSpentSeconds).toBe(3600)
  })

  it('빈 worklogs 배열도 파싱한다', () => {
    const result = worklogListResponseSchema.parse({
      worklogs: [],
      summary: summaryNullEstimateFixture,
    })
    expect(result.worklogs).toHaveLength(0)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// fetchWorklogs
// ─────────────────────────────────────────────────────────────────────────────

describe('fetchWorklogs', () => {
  beforeEach(() => {
    server.use(
      http.get('/api/v1/issues/ATLAS-1/worklogs', () =>
        HttpResponse.json({ data: worklogListFixture }),
      ),
    )
  })

  it('GET /api/v1/issues/{key}/worklogs 호출 후 {worklogs, summary}를 언랩해 반환한다', async () => {
    const result = await fetchWorklogs('ATLAS-1')
    expect(result.worklogs).toHaveLength(1)
    expect(result.worklogs[0]?.id).toBe('11111111-1111-4111-a111-111111111111')
    expect(result.summary.timeSpentSeconds).toBe(3600)
  })

  it('404 응답 시 ApiError(404)를 throw한다', async () => {
    server.use(
      http.get('/api/v1/issues/NO-EXIST/worklogs', () =>
        HttpResponse.json({ errorCode: 'ISSUE_NOT_FOUND' }, { status: 404 }),
      ),
    )
    await expect(fetchWorklogs('NO-EXIST')).rejects.toBeInstanceOf(ApiError)
    await expect(fetchWorklogs('NO-EXIST')).rejects.toMatchObject({ status: 404 })
  })

  it('403 응답 시 ApiError(403)를 throw한다', async () => {
    server.use(
      http.get('/api/v1/issues/ATLAS-1/worklogs', () =>
        HttpResponse.json({ errorCode: 'FORBIDDEN' }, { status: 403 }),
      ),
    )
    await expect(fetchWorklogs('ATLAS-1')).rejects.toMatchObject({ status: 403 })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// addWorklog
// ─────────────────────────────────────────────────────────────────────────────

describe('addWorklog', () => {
  beforeEach(() => {
    server.use(
      http.post('/api/v1/issues/ATLAS-1/worklogs', async ({ request }) => {
        const body = await request.json() as Record<string, unknown>
        if (typeof body.timeSpentSeconds !== 'number') {
          return HttpResponse.json({ errorCode: 'INVALID_INPUT' }, { status: 400 })
        }
        return HttpResponse.json({ data: { ...worklogFixture, ...body } }, { status: 201 })
      }),
    )
  })

  it('POST /api/v1/issues/{key}/worklogs 호출 후 WorklogResponse를 반환한다', async () => {
    const result = await addWorklog('ATLAS-1', {
      timeSpentSeconds: 3600,
      startedAt: '2026-06-20T09:00:00Z',
    })
    expect(result.id).toBe('11111111-1111-4111-a111-111111111111')
    expect(result.timeSpentSeconds).toBe(3600)
  })

  it('comment와 newRemainingEstimateSeconds를 포함해 POST한다', async () => {
    let capturedBody: Record<string, unknown> = {}
    server.use(
      http.post('/api/v1/issues/ATLAS-1/worklogs', async ({ request }) => {
        capturedBody = await request.json() as Record<string, unknown>
        return HttpResponse.json({ data: worklogFixture }, { status: 201 })
      }),
    )
    await addWorklog('ATLAS-1', {
      timeSpentSeconds: 1800,
      startedAt: '2026-06-20T10:00:00Z',
      comment: '추가 작업',
      newRemainingEstimateSeconds: 1800,
    })
    expect(capturedBody.comment).toBe('추가 작업')
    expect(capturedBody.newRemainingEstimateSeconds).toBe(1800)
  })

  it('undefined 필드는 body에 포함하지 않는다', async () => {
    let capturedBody: Record<string, unknown> = {}
    server.use(
      http.post('/api/v1/issues/ATLAS-1/worklogs', async ({ request }) => {
        capturedBody = await request.json() as Record<string, unknown>
        return HttpResponse.json({ data: worklogFixture }, { status: 201 })
      }),
    )
    await addWorklog('ATLAS-1', {
      timeSpentSeconds: 3600,
      startedAt: '2026-06-20T09:00:00Z',
    })
    expect(capturedBody).not.toHaveProperty('comment')
    expect(capturedBody).not.toHaveProperty('newRemainingEstimateSeconds')
  })

  it('400 응답 시 ApiError(400)를 throw한다', async () => {
    server.use(
      http.post('/api/v1/issues/ATLAS-1/worklogs', () =>
        HttpResponse.json({ errorCode: 'INVALID_INPUT' }, { status: 400 }),
      ),
    )
    await expect(
      addWorklog('ATLAS-1', { timeSpentSeconds: -1, startedAt: '2026-06-20T09:00:00Z' }),
    ).rejects.toMatchObject({ status: 400 })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// updateWorklog
// ─────────────────────────────────────────────────────────────────────────────

describe('updateWorklog', () => {
  const worklogId = '11111111-1111-4111-a111-111111111111'

  beforeEach(() => {
    server.use(
      http.patch(`/api/v1/issues/ATLAS-1/worklogs/${worklogId}`, async ({ request }) => {
        const body = await request.json() as Record<string, unknown>
        return HttpResponse.json({ data: { ...worklogFixture, ...body } })
      }),
    )
  })

  it('PATCH /api/v1/issues/{key}/worklogs/{id} 호출 후 WorklogResponse를 반환한다', async () => {
    const result = await updateWorklog('ATLAS-1', worklogId, { timeSpentSeconds: 7200 })
    expect(result.issueKey).toBe('ATLAS-1')
  })

  it('수정한 필드만 body에 포함한다 (미수정 필드 생략)', async () => {
    let capturedBody: Record<string, unknown> = {}
    server.use(
      http.patch(`/api/v1/issues/ATLAS-1/worklogs/${worklogId}`, async ({ request }) => {
        capturedBody = await request.json() as Record<string, unknown>
        return HttpResponse.json({ data: worklogFixture })
      }),
    )
    await updateWorklog('ATLAS-1', worklogId, { timeSpentSeconds: 7200 })
    expect(capturedBody.timeSpentSeconds).toBe(7200)
    expect(capturedBody).not.toHaveProperty('startedAt')
    expect(capturedBody).not.toHaveProperty('comment')
  })

  it('comment: null은 "무변경" 의미로 그대로 전달한다', async () => {
    let capturedBody: Record<string, unknown> = {}
    server.use(
      http.patch(`/api/v1/issues/ATLAS-1/worklogs/${worklogId}`, async ({ request }) => {
        capturedBody = await request.json() as Record<string, unknown>
        return HttpResponse.json({ data: worklogFixture })
      }),
    )
    await updateWorklog('ATLAS-1', worklogId, { comment: null })
    expect(capturedBody).toHaveProperty('comment', null)
  })

  it('403 응답 시 ApiError(403)를 throw한다', async () => {
    server.use(
      http.patch(`/api/v1/issues/ATLAS-1/worklogs/${worklogId}`, () =>
        HttpResponse.json({ errorCode: 'FORBIDDEN' }, { status: 403 }),
      ),
    )
    await expect(
      updateWorklog('ATLAS-1', worklogId, { timeSpentSeconds: 3600 }),
    ).rejects.toMatchObject({ status: 403 })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// deleteWorklog
// ─────────────────────────────────────────────────────────────────────────────

describe('deleteWorklog', () => {
  const worklogId = '11111111-1111-4111-a111-111111111111'

  beforeEach(() => {
    server.use(
      http.delete(`/api/v1/issues/ATLAS-1/worklogs/${worklogId}`, () =>
        new HttpResponse(null, { status: 204 }),
      ),
    )
  })

  it('DELETE /api/v1/issues/{key}/worklogs/{id} 204 → void를 반환한다', async () => {
    await expect(deleteWorklog('ATLAS-1', worklogId)).resolves.toBeUndefined()
  })

  it('404 응답 시 ApiError(404)를 throw한다', async () => {
    server.use(
      http.delete(`/api/v1/issues/ATLAS-1/worklogs/no-exist`, () =>
        HttpResponse.json({ errorCode: 'WORKLOG_NOT_FOUND' }, { status: 404 }),
      ),
    )
    await expect(deleteWorklog('ATLAS-1', 'no-exist')).rejects.toMatchObject({ status: 404 })
  })

  it('403 응답 시 ApiError(403)를 throw한다', async () => {
    server.use(
      http.delete(`/api/v1/issues/ATLAS-1/worklogs/${worklogId}`, () =>
        HttpResponse.json({ errorCode: 'FORBIDDEN' }, { status: 403 }),
      ),
    )
    await expect(deleteWorklog('ATLAS-1', worklogId)).rejects.toMatchObject({ status: 403 })
  })
})
