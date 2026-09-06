// 스프린트 번다운/번업 API 클라이언트 단위 테스트 — FR-RP-01 D6/D7 Task-1
import { describe, it, expect, beforeEach } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { ApiError } from '@/api/client'
import {
  burndownPointSchema,
  burndownResponseSchema,
  fetchSprintBurndown,
  type BurndownResponse,
  type BurndownPoint,
} from './burndown'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const sprintId = '11111111-1111-4111-8111-111111111111'

const pointFixture: BurndownPoint = {
  date: '2026-07-01',
  remainingSeconds: 28800,
  idealSeconds: 25920,
  completedSeconds: 7200,
  scopeSeconds: 36000,
}

const futurePointFixture: BurndownPoint = {
  date: '2026-07-10',
  remainingSeconds: null,
  idealSeconds: 0,
  completedSeconds: null,
  scopeSeconds: 36000,
}

const burndownFixture: BurndownResponse = {
  sprintId,
  projectKey: 'BTS',
  status: 'ACTIVE',
  startDate: '2026-07-01',
  endDate: '2026-07-10',
  totalScopeSeconds: 36000,
  // 백엔드 `BurndownResponse.unit` 은 non-null 이라 스키마도 필수다(부채 177 task-38).
  unit: 'SECONDS',
  points: [pointFixture, futurePointFixture],
}

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 파싱
// ─────────────────────────────────────────────────────────────────────────────

describe('burndownPointSchema', () => {
  it('remaining/completed=null인 미래 포인트를 파싱한다', () => {
    const result = burndownPointSchema.parse(futurePointFixture)
    expect(result.date).toBe('2026-07-10')
    expect(result.remainingSeconds).toBeNull()
    expect(result.completedSeconds).toBeNull()
    expect(result.idealSeconds).toBe(0)
    expect(result.scopeSeconds).toBe(36000)
  })

  it('remaining/completed 값이 있는 포인트를 파싱한다', () => {
    const result = burndownPointSchema.parse(pointFixture)
    expect(result.remainingSeconds).toBe(28800)
    expect(result.completedSeconds).toBe(7200)
  })

  it('scopeSeconds 누락 시 파싱에 실패한다', () => {
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    const { scopeSeconds: _scopeSeconds, ...withoutScope } = pointFixture
    expect(() => burndownPointSchema.parse(withoutScope)).toThrow()
  })
})

describe('burndownResponseSchema', () => {
  it('유효한 응답(remaining/completed=null 포함)을 파싱한다', () => {
    const result = burndownResponseSchema.parse(burndownFixture)
    expect(result.sprintId).toBe(sprintId)
    expect(result.projectKey).toBe('BTS')
    expect(result.status).toBe('ACTIVE')
    expect(result.startDate).toBe('2026-07-01')
    expect(result.endDate).toBe('2026-07-10')
    expect(result.totalScopeSeconds).toBe(36000)
    expect(result.points).toHaveLength(2)
    expect(result.points[1]?.remainingSeconds).toBeNull()
  })

  it('scopeSeconds가 누락된 point가 있으면 파싱에 실패한다', () => {
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    const { scopeSeconds: _scopeSeconds, ...withoutScope } = pointFixture
    const invalid = { ...burndownFixture, points: [withoutScope] }
    expect(() => burndownResponseSchema.parse(invalid)).toThrow()
  })

  it('totalScopeSeconds 누락 시 파싱에 실패한다', () => {
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    const { totalScopeSeconds: _totalScopeSeconds, ...withoutTotal } = burndownFixture
    expect(() => burndownResponseSchema.parse(withoutTotal)).toThrow()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// fetchSprintBurndown
// ─────────────────────────────────────────────────────────────────────────────

describe('fetchSprintBurndown', () => {
  beforeEach(() => {
    server.use(
      http.get(`/api/v1/sprints/${sprintId}/burndown`, () =>
        HttpResponse.json({ data: burndownFixture }),
      ),
    )
  })

  it('올바른 URL을 GET 호출하고 200 응답의 data를 반환한다', async () => {
    let capturedUrl = ''
    let capturedMethod = ''
    server.use(
      http.get(`/api/v1/sprints/${sprintId}/burndown`, ({ request }) => {
        capturedUrl = request.url
        capturedMethod = request.method
        return HttpResponse.json({ data: burndownFixture })
      }),
    )
    const result = await fetchSprintBurndown(sprintId)
    expect(capturedUrl).toContain(`/api/v1/sprints/${sprintId}/burndown`)
    expect(capturedMethod).toBe('GET')
    expect(result.sprintId).toBe(sprintId)
    expect(result.points).toHaveLength(2)
  })

  it('403 응답 시 ApiError(403)를 throw한다', async () => {
    server.use(
      http.get(`/api/v1/sprints/${sprintId}/burndown`, () =>
        HttpResponse.json({ errorCode: 'FORBIDDEN' }, { status: 403 }),
      ),
    )
    await expect(fetchSprintBurndown(sprintId)).rejects.toBeInstanceOf(ApiError)
    await expect(fetchSprintBurndown(sprintId)).rejects.toMatchObject({ status: 403 })
  })

  it('404 응답 시 ApiError(404)를 throw한다', async () => {
    server.use(
      http.get(`/api/v1/sprints/${sprintId}/burndown`, () =>
        HttpResponse.json({ errorCode: 'SPRINT_NOT_FOUND' }, { status: 404 }),
      ),
    )
    await expect(fetchSprintBurndown(sprintId)).rejects.toMatchObject({ status: 404 })
  })

  it('422 응답 시 ApiError(422)를 throw한다', async () => {
    server.use(
      http.get(`/api/v1/sprints/${sprintId}/burndown`, () =>
        HttpResponse.json({ errorCode: 'SPRINT_DATES_REQUIRED' }, { status: 422 }),
      ),
    )
    await expect(fetchSprintBurndown(sprintId)).rejects.toMatchObject({ status: 422 })
  })
})
