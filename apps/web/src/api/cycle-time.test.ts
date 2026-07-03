// Cycle Time / Lead Time 분포 API 클라이언트 단위 테스트 — FR-RP-04 D6/D7 Task-1
import { describe, it, expect } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { ApiError } from '@/api/client'
import {
  sampleResponseSchema,
  metricResponseSchema,
  cycleTimeResponseSchema,
  fetchProjectCycleTime,
  isCycleTimeEmpty,
  type CycleTimeResponse,
  type MetricResponse,
} from './cycle-time'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const projectKey = 'BTS'

const filledMetricFixture: MetricResponse = {
  count: 2,
  min: 3600,
  max: 7200,
  avg: 5400,
  p25: 4050,
  p50: 5400,
  p75: 6750,
  p90: 6930,
  samples: [
    { issueKey: 'BTS-1', seconds: 3600 },
    { issueKey: 'BTS-2', seconds: 7200 },
  ],
}

const emptyMetricFixture: MetricResponse = {
  count: 0,
  min: null,
  max: null,
  avg: null,
  p25: null,
  p50: null,
  p75: null,
  p90: null,
  samples: [],
}

const cycleTimeFixture: CycleTimeResponse = {
  projectKey,
  from: '2026-06-01',
  to: '2026-06-30',
  cycleTime: filledMetricFixture,
  leadTime: filledMetricFixture,
}

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 파싱
// ─────────────────────────────────────────────────────────────────────────────

describe('sampleResponseSchema', () => {
  it('유효한 표본을 파싱한다', () => {
    const result = sampleResponseSchema.parse({ issueKey: 'BTS-1', seconds: 3600 })
    expect(result.issueKey).toBe('BTS-1')
    expect(result.seconds).toBe(3600)
  })

  it('seconds 누락 시 파싱에 실패한다', () => {
    expect(() => sampleResponseSchema.parse({ issueKey: 'BTS-1' })).toThrow()
  })
})

describe('metricResponseSchema', () => {
  it('통계가 채워진 정상 응답을 파싱한다', () => {
    const result = metricResponseSchema.parse(filledMetricFixture)
    expect(result.count).toBe(2)
    expect(result.min).toBe(3600)
    expect(result.p50).toBe(5400)
    expect(result.samples).toHaveLength(2)
  })

  it('count=0이고 통계가 모두 null인 응답을 파싱한다', () => {
    const result = metricResponseSchema.parse(emptyMetricFixture)
    expect(result.count).toBe(0)
    expect(result.min).toBeNull()
    expect(result.max).toBeNull()
    expect(result.avg).toBeNull()
    expect(result.p25).toBeNull()
    expect(result.p50).toBeNull()
    expect(result.p75).toBeNull()
    expect(result.p90).toBeNull()
    expect(result.samples).toHaveLength(0)
  })

  it('count 누락 시 파싱에 실패한다', () => {
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    const { count: _count, ...withoutCount } = filledMetricFixture
    expect(() => metricResponseSchema.parse(withoutCount)).toThrow()
  })

  it('samples 누락 시 파싱에 실패한다', () => {
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    const { samples: _samples, ...withoutSamples } = filledMetricFixture
    expect(() => metricResponseSchema.parse(withoutSamples)).toThrow()
  })
})

describe('cycleTimeResponseSchema', () => {
  it('유효한 응답을 파싱한다', () => {
    const result = cycleTimeResponseSchema.parse(cycleTimeFixture)
    expect(result.projectKey).toBe(projectKey)
    expect(result.from).toBe('2026-06-01')
    expect(result.to).toBe('2026-06-30')
    expect(result.cycleTime.count).toBe(2)
    expect(result.leadTime.count).toBe(2)
  })

  it('count=0 + 통계 null인 cycleTime/leadTime을 파싱한다', () => {
    const emptyFixture: CycleTimeResponse = {
      ...cycleTimeFixture,
      cycleTime: emptyMetricFixture,
      leadTime: emptyMetricFixture,
    }
    const result = cycleTimeResponseSchema.parse(emptyFixture)
    expect(result.cycleTime.count).toBe(0)
    expect(result.cycleTime.avg).toBeNull()
    expect(result.leadTime.count).toBe(0)
    expect(result.leadTime.avg).toBeNull()
  })

  it('projectKey 누락 시 파싱에 실패한다', () => {
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    const { projectKey: _projectKey, ...withoutProjectKey } = cycleTimeFixture
    expect(() => cycleTimeResponseSchema.parse(withoutProjectKey)).toThrow()
  })

  it('cycleTime 누락 시 파싱에 실패한다', () => {
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    const { cycleTime: _cycleTime, ...withoutCycleTime } = cycleTimeFixture
    expect(() => cycleTimeResponseSchema.parse(withoutCycleTime)).toThrow()
  })

  it('leadTime 누락 시 파싱에 실패한다', () => {
    // eslint-disable-next-line @typescript-eslint/no-unused-vars
    const { leadTime: _leadTime, ...withoutLeadTime } = cycleTimeFixture
    expect(() => cycleTimeResponseSchema.parse(withoutLeadTime)).toThrow()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// isCycleTimeEmpty
// ─────────────────────────────────────────────────────────────────────────────

describe('isCycleTimeEmpty', () => {
  it('cycleTime과 leadTime의 count가 모두 0이면 true를 반환한다', () => {
    const emptyResponse: CycleTimeResponse = {
      ...cycleTimeFixture,
      cycleTime: emptyMetricFixture,
      leadTime: emptyMetricFixture,
    }
    expect(isCycleTimeEmpty(emptyResponse)).toBe(true)
  })

  it('cycleTime만 count가 있으면 false를 반환한다', () => {
    const partialResponse: CycleTimeResponse = {
      ...cycleTimeFixture,
      cycleTime: filledMetricFixture,
      leadTime: emptyMetricFixture,
    }
    expect(isCycleTimeEmpty(partialResponse)).toBe(false)
  })

  it('leadTime만 count가 있으면 false를 반환한다', () => {
    const partialResponse: CycleTimeResponse = {
      ...cycleTimeFixture,
      cycleTime: emptyMetricFixture,
      leadTime: filledMetricFixture,
    }
    expect(isCycleTimeEmpty(partialResponse)).toBe(false)
  })

  it('둘 다 count가 있으면 false를 반환한다', () => {
    expect(isCycleTimeEmpty(cycleTimeFixture)).toBe(false)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// fetchProjectCycleTime
// ─────────────────────────────────────────────────────────────────────────────

describe('fetchProjectCycleTime', () => {
  it('올바른 URL을 GET 호출하고 200 응답의 data를 반환한다', async () => {
    let capturedUrl = ''
    let capturedMethod = ''
    server.use(
      http.get(`/api/v1/projects/${projectKey}/cycle-time`, ({ request }) => {
        capturedUrl = request.url
        capturedMethod = request.method
        return HttpResponse.json({ data: cycleTimeFixture })
      }),
    )
    const result = await fetchProjectCycleTime(projectKey)
    expect(capturedUrl).toContain(`/api/v1/projects/${projectKey}/cycle-time`)
    expect(capturedMethod).toBe('GET')
    expect(result.projectKey).toBe(projectKey)
    expect(result.cycleTime.count).toBe(2)
    expect(result.leadTime.count).toBe(2)
  })

  it('401 응답 시 ApiError(401)를 throw한다', async () => {
    server.use(
      http.get(`/api/v1/projects/${projectKey}/cycle-time`, () =>
        HttpResponse.json({ errorCode: 'UNAUTHORIZED' }, { status: 401 }),
      ),
    )
    await expect(fetchProjectCycleTime(projectKey)).rejects.toBeInstanceOf(ApiError)
    await expect(fetchProjectCycleTime(projectKey)).rejects.toMatchObject({ status: 401 })
  })

  it('403 응답 시 ApiError(403)를 throw한다', async () => {
    server.use(
      http.get(`/api/v1/projects/${projectKey}/cycle-time`, () =>
        HttpResponse.json({ errorCode: 'FORBIDDEN' }, { status: 403 }),
      ),
    )
    await expect(fetchProjectCycleTime(projectKey)).rejects.toMatchObject({ status: 403 })
  })
})
