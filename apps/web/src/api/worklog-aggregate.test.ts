// 워크로그 집계 API 클라이언트 단위 테스트 — FR-TT-02 D6/D7 Task-1
import { describe, it, expect, beforeEach } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { ApiError } from '@/api/client'
import {
  worklogAggregateBucketSchema,
  worklogAggregateResponseSchema,
  fetchWorklogAggregate,
  type WorklogAggregateResponse,
  type WorklogAggregateBucket,
  type AggregateDimension,
  type AggregateGranularity,
} from './worklog-aggregate'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const bucketFixture: WorklogAggregateBucket = {
  key: 'BTS-12',
  label: 'BTS-12',
  timeSpentSeconds: 50400,
  worklogCount: 7,
}

const aggregateByIssueFixture: WorklogAggregateResponse = {
  by: 'issue',
  buckets: [bucketFixture],
  totalTimeSpentSeconds: 86400,
}

const aggregateByPeriodFixture: WorklogAggregateResponse = {
  by: 'period',
  granularity: 'month',
  buckets: [{ key: '2026-06', label: '2026-06', timeSpentSeconds: 86400, worklogCount: 10 }],
  totalTimeSpentSeconds: 86400,
}

const aggregateWithDatesFixture: WorklogAggregateResponse = {
  by: 'issue',
  from: '2026-01-01',
  to: '2026-06-30',
  buckets: [bucketFixture],
  totalTimeSpentSeconds: 86400,
}

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 파싱
// ─────────────────────────────────────────────────────────────────────────────

describe('worklogAggregateBucketSchema', () => {
  it('정상 픽스처를 파싱한다', () => {
    const result = worklogAggregateBucketSchema.parse(bucketFixture)
    expect(result.key).toBe('BTS-12')
    expect(result.label).toBe('BTS-12')
    expect(result.timeSpentSeconds).toBe(50400)
    expect(result.worklogCount).toBe(7)
  })

  it('필수 필드 누락 시 파싱에 실패한다', () => {
    expect(() =>
      worklogAggregateBucketSchema.parse({ key: 'BTS-12', label: 'BTS-12', worklogCount: 7 }),
    ).toThrow()
  })
})

describe('worklogAggregateResponseSchema', () => {
  it('granularity/from/to 없는 응답(by=issue)을 파싱한다', () => {
    const result = worklogAggregateResponseSchema.parse(aggregateByIssueFixture)
    expect(result.by).toBe('issue')
    expect(result.buckets).toHaveLength(1)
    expect(result.buckets[0]?.key).toBe('BTS-12')
    expect(result.totalTimeSpentSeconds).toBe(86400)
    // NON_NULL이라 키 부재 — optional()이어야 undefined로 파싱됨
    expect(result.granularity).toBeUndefined()
    expect(result.from).toBeUndefined()
    expect(result.to).toBeUndefined()
  })

  it('by=period + granularity=month 응답을 파싱한다', () => {
    const result = worklogAggregateResponseSchema.parse(aggregateByPeriodFixture)
    expect(result.by).toBe('period')
    expect(result.granularity).toBe('month')
    expect(result.buckets).toHaveLength(1)
  })

  it('from/to 있는 응답을 파싱한다', () => {
    const result = worklogAggregateResponseSchema.parse(aggregateWithDatesFixture)
    expect(result.from).toBe('2026-01-01')
    expect(result.to).toBe('2026-06-30')
  })

  it('project 필드가 응답에 있어도 파싱 결과에 포함되지 않는다 (Zod strip)', () => {
    const withProject = { ...aggregateByIssueFixture, project: 'BTS' }
    const result = worklogAggregateResponseSchema.parse(withProject)
    expect(result).not.toHaveProperty('project')
  })

  it('buckets 필드가 없으면 파싱에 실패한다', () => {
    expect(() =>
      worklogAggregateResponseSchema.parse({
        by: 'issue',
        totalTimeSpentSeconds: 0,
      }),
    ).toThrow()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// fetchWorklogAggregate — 응답 파싱
// ─────────────────────────────────────────────────────────────────────────────

describe('fetchWorklogAggregate', () => {
  beforeEach(() => {
    server.use(
      http.get('/api/v1/worklogs/aggregate', () =>
        HttpResponse.json({ data: aggregateByIssueFixture }),
      ),
    )
  })

  it('200 응답을 파싱해 buckets/totalTimeSpentSeconds/by 값을 반환한다', async () => {
    const result = await fetchWorklogAggregate('BTS', { by: 'issue' })
    expect(result.by).toBe('issue')
    expect(result.buckets).toHaveLength(1)
    expect(result.buckets[0]?.key).toBe('BTS-12')
    expect(result.totalTimeSpentSeconds).toBe(86400)
  })

  it('granularity/from/to 부재 응답도 파싱에 성공한다', async () => {
    server.use(
      http.get('/api/v1/worklogs/aggregate', () =>
        HttpResponse.json({ data: aggregateByIssueFixture }),
      ),
    )
    const result = await fetchWorklogAggregate('BTS', { by: 'issue' })
    expect(result.granularity).toBeUndefined()
    expect(result.from).toBeUndefined()
    expect(result.to).toBeUndefined()
  })

  it('by=period + granularity=month 응답을 파싱한다', async () => {
    server.use(
      http.get('/api/v1/worklogs/aggregate', () =>
        HttpResponse.json({ data: aggregateByPeriodFixture }),
      ),
    )
    const result = await fetchWorklogAggregate('BTS', { by: 'period', granularity: 'month' })
    expect(result.by).toBe('period')
    expect(result.granularity).toBe('month')
  })

  it('응답에 project 필드가 있어도 결과 객체에 포함되지 않는다', async () => {
    server.use(
      http.get('/api/v1/worklogs/aggregate', () =>
        HttpResponse.json({ data: { ...aggregateByIssueFixture, project: 'BTS' } }),
      ),
    )
    const result = await fetchWorklogAggregate('BTS', { by: 'issue' })
    expect(result).not.toHaveProperty('project')
  })

  it('403 응답 시 ApiError(403)를 throw한다', async () => {
    server.use(
      http.get('/api/v1/worklogs/aggregate', () =>
        HttpResponse.json({ errorCode: 'FORBIDDEN' }, { status: 403 }),
      ),
    )
    await expect(fetchWorklogAggregate('BTS', { by: 'issue' })).rejects.toBeInstanceOf(ApiError)
    await expect(fetchWorklogAggregate('BTS', { by: 'issue' })).rejects.toMatchObject({ status: 403 })
  })

  it('400 응답 시 ApiError(400)를 throw한다', async () => {
    server.use(
      http.get('/api/v1/worklogs/aggregate', () =>
        HttpResponse.json({ errorCode: 'INVALID_INPUT' }, { status: 400 }),
      ),
    )
    await expect(fetchWorklogAggregate('BTS', { by: 'issue' })).rejects.toMatchObject({ status: 400 })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// fetchWorklogAggregate — 쿼리스트링 조립
// ─────────────────────────────────────────────────────────────────────────────

describe('fetchWorklogAggregate — 쿼리스트링', () => {
  it('project와 by는 항상 URL에 포함된다', async () => {
    let capturedUrl = ''
    server.use(
      http.get('/api/v1/worklogs/aggregate', ({ request }) => {
        capturedUrl = request.url
        return HttpResponse.json({ data: aggregateByIssueFixture })
      }),
    )
    await fetchWorklogAggregate('BTS', { by: 'issue' })
    expect(capturedUrl).toContain('project=BTS')
    expect(capturedUrl).toContain('by=issue')
  })

  it('granularity가 정의된 경우 URL에 포함된다', async () => {
    let capturedUrl = ''
    server.use(
      http.get('/api/v1/worklogs/aggregate', ({ request }) => {
        capturedUrl = request.url
        return HttpResponse.json({ data: aggregateByPeriodFixture })
      }),
    )
    await fetchWorklogAggregate('BTS', { by: 'period', granularity: 'week' })
    expect(capturedUrl).toContain('granularity=week')
  })

  it('granularity가 undefined이면 URL에 포함되지 않는다', async () => {
    let capturedUrl = ''
    server.use(
      http.get('/api/v1/worklogs/aggregate', ({ request }) => {
        capturedUrl = request.url
        return HttpResponse.json({ data: aggregateByIssueFixture })
      }),
    )
    await fetchWorklogAggregate('BTS', { by: 'issue' })
    expect(capturedUrl).not.toContain('granularity')
  })

  it('from/to가 정의된 경우 URL에 포함된다', async () => {
    let capturedUrl = ''
    server.use(
      http.get('/api/v1/worklogs/aggregate', ({ request }) => {
        capturedUrl = request.url
        return HttpResponse.json({ data: aggregateWithDatesFixture })
      }),
    )
    await fetchWorklogAggregate('BTS', { by: 'issue', from: '2026-01-01', to: '2026-06-30' })
    expect(capturedUrl).toContain('from=2026-01-01')
    expect(capturedUrl).toContain('to=2026-06-30')
  })

  it('from/to가 undefined이면 URL에 포함되지 않는다', async () => {
    let capturedUrl = ''
    server.use(
      http.get('/api/v1/worklogs/aggregate', ({ request }) => {
        capturedUrl = request.url
        return HttpResponse.json({ data: aggregateByIssueFixture })
      }),
    )
    await fetchWorklogAggregate('BTS', { by: 'user' })
    expect(capturedUrl).not.toContain('from')
    expect(capturedUrl).not.toContain('to')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 타입 export 확인 (컴파일 타임 — 런타임 이상 없으면 통과)
// ─────────────────────────────────────────────────────────────────────────────

describe('타입 export', () => {
  it('AggregateDimension 유니온 타입이 export된다', () => {
    const dim: AggregateDimension = 'issue'
    expect(['issue', 'user', 'period']).toContain(dim)
  })

  it('AggregateGranularity 유니온 타입이 export된다', () => {
    const gran: AggregateGranularity = 'day'
    expect(['day', 'week', 'month']).toContain(gran)
  })
})
