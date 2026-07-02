// 스프린트 벨로시티 차트 API 클라이언트 단위 테스트 — FR-RP-02 D6/D7 Task-1
import { describe, it, expect } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { ApiError } from '@/api/client'
import {
  velocityPointResponseSchema,
  velocityResponseSchema,
  fetchProjectVelocity,
  type VelocityResponse,
  type VelocityPointResponse,
} from './velocity'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const projectKey = 'BTS'

const sprintPointFixture: VelocityPointResponse = {
  sprintId: '11111111-1111-4111-8111-111111111111',
  name: 'Sprint 1',
  startDate: '2026-06-01',
  endDate: '2026-06-14',
  commitmentSeconds: 144000,
  completedSeconds: 108000,
}

const openSprintPointFixture: VelocityPointResponse = {
  sprintId: '22222222-2222-4222-8222-222222222222',
  name: 'Sprint 2',
  startDate: null,
  endDate: null,
  commitmentSeconds: 72000,
  completedSeconds: 36000,
}

const velocityFixture: VelocityResponse = {
  projectKey,
  averageCommitmentSeconds: 108000,
  averageCompletedSeconds: 72000,
  sprints: [sprintPointFixture, openSprintPointFixture],
}

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 파싱
// ─────────────────────────────────────────────────────────────────────────────

describe('velocityPointResponseSchema', () => {
  it('startDate/endDate가 있는 포인트를 파싱한다', () => {
    const result = velocityPointResponseSchema.parse(sprintPointFixture)
    expect(result.sprintId).toBe(sprintPointFixture.sprintId)
    expect(result.name).toBe('Sprint 1')
    expect(result.startDate).toBe('2026-06-01')
    expect(result.endDate).toBe('2026-06-14')
    expect(result.commitmentSeconds).toBe(144000)
    expect(result.completedSeconds).toBe(108000)
  })

  it('startDate/endDate=null인 포인트도 파싱한다', () => {
    const result = velocityPointResponseSchema.parse(openSprintPointFixture)
    expect(result.startDate).toBeNull()
    expect(result.endDate).toBeNull()
  })

  it('commitmentSeconds 누락 시 파싱에 실패한다', () => {
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    const { commitmentSeconds: _commitmentSeconds, ...withoutCommitment } = sprintPointFixture
    expect(() => velocityPointResponseSchema.parse(withoutCommitment)).toThrow()
  })
})

describe('velocityResponseSchema', () => {
  it('유효한 응답(null startDate/endDate 포함)을 파싱한다', () => {
    const result = velocityResponseSchema.parse(velocityFixture)
    expect(result.projectKey).toBe(projectKey)
    expect(result.averageCommitmentSeconds).toBe(108000)
    expect(result.averageCompletedSeconds).toBe(72000)
    expect(result.sprints).toHaveLength(2)
    expect(result.sprints[1]?.startDate).toBeNull()
  })

  it('averageCommitmentSeconds 누락 시 파싱에 실패한다', () => {
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    const { averageCommitmentSeconds: _averageCommitmentSeconds, ...withoutAverage } =
      velocityFixture
    expect(() => velocityResponseSchema.parse(withoutAverage)).toThrow()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// fetchProjectVelocity
// ─────────────────────────────────────────────────────────────────────────────

describe('fetchProjectVelocity', () => {
  it('올바른 URL을 GET 호출하고 200 응답의 data를 반환한다', async () => {
    let capturedUrl = ''
    let capturedMethod = ''
    server.use(
      http.get(`/api/v1/projects/${projectKey}/velocity`, ({ request }) => {
        capturedUrl = request.url
        capturedMethod = request.method
        return HttpResponse.json({ data: velocityFixture })
      }),
    )
    const result = await fetchProjectVelocity(projectKey)
    expect(capturedUrl).toContain(`/api/v1/projects/${projectKey}/velocity`)
    expect(capturedMethod).toBe('GET')
    expect(result.projectKey).toBe(projectKey)
    expect(result.sprints).toHaveLength(2)
  })

  it('401 응답 시 ApiError(401)를 throw한다', async () => {
    server.use(
      http.get(`/api/v1/projects/${projectKey}/velocity`, () =>
        HttpResponse.json({ errorCode: 'UNAUTHORIZED' }, { status: 401 }),
      ),
    )
    await expect(fetchProjectVelocity(projectKey)).rejects.toBeInstanceOf(ApiError)
    await expect(fetchProjectVelocity(projectKey)).rejects.toMatchObject({ status: 401 })
  })

  it('403 응답 시 ApiError(403)를 throw한다', async () => {
    server.use(
      http.get(`/api/v1/projects/${projectKey}/velocity`, () =>
        HttpResponse.json({ errorCode: 'FORBIDDEN' }, { status: 403 }),
      ),
    )
    await expect(fetchProjectVelocity(projectKey)).rejects.toMatchObject({ status: 403 })
  })
})
