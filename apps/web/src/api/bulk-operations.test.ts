// bulk-operations API 클라이언트 단위 테스트 — MSW로 HTTP 가로채기 + Zod 스키마 검증
import { describe, it, expect, beforeEach } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import {
  bulkOperationResponseSchema,
  submitBulkOperation,
  fetchBulkOperation,
} from './bulk-operations'

// ─────────────────────────────────────────────────────────────────────────────
// Fixture — BulkOperationResponse (BULK_EDIT 케이스)
// ─────────────────────────────────────────────────────────────────────────────
const bulkEditResponseFixture = {
  id: '11111111-1111-1111-1111-111111111111',
  operationType: 'BULK_EDIT' as const,
  status: 'COMPLETED' as const,
  payload: { priority: 3, impact: null },
  totalCount: 2,
  processedCount: 2,
  succeededCount: 2,
  failedCount: 0,
  items: [
    { issueKey: 'ATLAS-1', status: 'SUCCEEDED' as const, failureReasonCode: null },
    { issueKey: 'ATLAS-2', status: 'SUCCEEDED' as const, failureReasonCode: null },
  ],
}

// ─────────────────────────────────────────────────────────────────────────────
// Fixture — BulkOperationResponse (BULK_TRANSITION 케이스)
// ─────────────────────────────────────────────────────────────────────────────
const bulkTransitionResponseFixture = {
  id: '22222222-2222-2222-2222-222222222222',
  operationType: 'BULK_TRANSITION' as const,
  status: 'RUNNING' as const,
  payload: { toStateKey: 'done' },
  totalCount: 3,
  processedCount: 1,
  succeededCount: 1,
  failedCount: 0,
  items: [
    { issueKey: 'ATLAS-3', status: 'SUCCEEDED' as const, failureReasonCode: null },
    { issueKey: 'ATLAS-4', status: 'PENDING' as const, failureReasonCode: null },
    {
      issueKey: 'ATLAS-5',
      status: 'FAILED' as const,
      failureReasonCode: 'TRANSITION_NOT_ALLOWED',
    },
  ],
}

// ─────────────────────────────────────────────────────────────────────────────
// Fixture — 접수(accepted) 응답
// ─────────────────────────────────────────────────────────────────────────────
const bulkAcceptedFixture = {
  bulkOperationId: '33333333-3333-3333-3333-333333333333',
  status: 'PENDING' as const,
  totalCount: 5,
}

beforeEach(() => {
  server.use(
    // POST /api/v1/issues/bulk-update — 202 접수
    http.post('/api/v1/issues/bulk-update', () =>
      HttpResponse.json({ data: bulkAcceptedFixture }, { status: 202 }),
    ),
    // GET /api/v1/bulk-operations/:id — BULK_EDIT 케이스
    http.get('/api/v1/bulk-operations/11111111-1111-1111-1111-111111111111', () =>
      HttpResponse.json({ data: bulkEditResponseFixture }, { status: 200 }),
    ),
    // GET /api/v1/bulk-operations/:id — BULK_TRANSITION 케이스
    http.get('/api/v1/bulk-operations/22222222-2222-2222-2222-222222222222', () =>
      HttpResponse.json({ data: bulkTransitionResponseFixture }, { status: 200 }),
    ),
  )
})

// ─────────────────────────────────────────────────────────────────────────────
// (a) Zod 스키마 파싱 검증
// ─────────────────────────────────────────────────────────────────────────────
describe('bulkOperationResponseSchema', () => {
  it('BULK_EDIT 응답(payload {priority:3,impact:null})을 파싱한다', () => {
    const result = bulkOperationResponseSchema.parse(bulkEditResponseFixture)
    expect(result.operationType).toBe('BULK_EDIT')
    expect(result.payload).toEqual({ priority: 3, impact: null })
    expect(result.status).toBe('COMPLETED')
  })

  it('BULK_TRANSITION 응답(payload {toStateKey:"done"})을 파싱한다', () => {
    const result = bulkOperationResponseSchema.parse(bulkTransitionResponseFixture)
    expect(result.operationType).toBe('BULK_TRANSITION')
    expect(result.payload).toEqual({ toStateKey: 'done' })
    expect(result.status).toBe('RUNNING')
  })

  it('status enum 이외의 값을 거부한다', () => {
    const invalid = { ...bulkEditResponseFixture, status: 'INVALID_STATUS' }
    expect(() => bulkOperationResponseSchema.parse(invalid)).toThrow()
  })

  it('items[].failureReasonCode enum 이외의 값을 거부한다', () => {
    const invalid = {
      ...bulkEditResponseFixture,
      items: [
        {
          issueKey: 'ATLAS-1',
          status: 'FAILED' as const,
          failureReasonCode: 'INVALID_CODE',
        },
      ],
    }
    expect(() => bulkOperationResponseSchema.parse(invalid)).toThrow()
  })

  it('failureReasonCode가 null인 경우를 허용한다', () => {
    const result = bulkOperationResponseSchema.parse(bulkEditResponseFixture)
    const firstItem = result.items[0]
    expect(firstItem?.failureReasonCode).toBeNull()
  })

  it('failureReasonCode 7종 enum 값을 모두 허용한다', () => {
    const codes = [
      'NOT_FOUND',
      'FORBIDDEN',
      'TRANSITION_NOT_ALLOWED',
      'VERSION_CONFLICT',
      'WORKFLOW_NOT_CONFIGURED',
      'TYPE_NOT_FOUND',
      'UNKNOWN',
    ] as const
    for (const code of codes) {
      const fixture = {
        ...bulkEditResponseFixture,
        items: [{ issueKey: 'ATLAS-1', status: 'FAILED' as const, failureReasonCode: code }],
      }
      expect(() => bulkOperationResponseSchema.parse(fixture)).not.toThrow()
    }
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// (b) submitBulkOperation — 202 응답 언래핑
// ─────────────────────────────────────────────────────────────────────────────
describe('submitBulkOperation', () => {
  it('202 응답 data를 언래핑해 {bulkOperationId,status,totalCount}를 반환한다', async () => {
    const result = await submitBulkOperation({
      operationType: 'BULK_EDIT',
      issueKeys: ['ATLAS-1', 'ATLAS-2'],
      editPayload: { priority: 3, impact: null },
      transitionPayload: null,
    })
    expect(result.bulkOperationId).toBe('33333333-3333-3333-3333-333333333333')
    expect(result.status).toBe('PENDING')
    expect(result.totalCount).toBe(5)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// (c) fetchBulkOperation — 200 응답 언래핑
// ─────────────────────────────────────────────────────────────────────────────
describe('fetchBulkOperation', () => {
  it('BULK_EDIT 작업을 조회해 BulkOperationResponse를 반환한다', async () => {
    const result = await fetchBulkOperation('11111111-1111-1111-1111-111111111111')
    expect(result.id).toBe('11111111-1111-1111-1111-111111111111')
    expect(result.operationType).toBe('BULK_EDIT')
    expect(result.payload).toEqual({ priority: 3, impact: null })
  })

  it('BULK_TRANSITION 작업을 조회해 BulkOperationResponse를 반환한다', async () => {
    const result = await fetchBulkOperation('22222222-2222-2222-2222-222222222222')
    expect(result.id).toBe('22222222-2222-2222-2222-222222222222')
    expect(result.operationType).toBe('BULK_TRANSITION')
    expect(result.payload).toEqual({ toStateKey: 'done' })
    expect(result.items).toHaveLength(3)
  })
})
