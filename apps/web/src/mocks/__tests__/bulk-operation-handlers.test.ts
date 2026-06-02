// 일괄 작업 MSW 핸들러 단위 테스트 — POST 접수 + GET 폴링 stateful 진행 검증
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, beforeEach, describe, expect, it } from 'vitest'
import { bulkOperationHandlers, resetBulkOperationState } from '../bulk-operation-handlers'
import {
  bulkAcceptedSchema,
  bulkOperationResponseSchema,
} from '@/api/bulk-operations'

const server = setupServer(...bulkOperationHandlers)

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => {
  server.resetHandlers()
  resetBulkOperationState()
  globalThis.localStorage?.clear?.()
})
afterAll(() => server.close())

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

async function postBulkUpdate(body: Record<string, unknown>): Promise<Response> {
  return fetch('/api/v1/issues/bulk-update', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

async function getBulkOperation(id: string): Promise<Response> {
  return fetch(`/api/v1/bulk-operations/${id}`)
}

const validBulkEditBody = {
  operationType: 'BULK_EDIT',
  issueKeys: ['ATLAS-1', 'ATLAS-2', 'ATLAS-3'],
  editPayload: { priority: 1, impact: 2 },
  transitionPayload: null,
}

const validBulkTransitionBody = {
  operationType: 'BULK_TRANSITION',
  issueKeys: ['ATLAS-1', 'ATLAS-2'],
  editPayload: null,
  transitionPayload: { toStateKey: 'in_progress' },
}

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/issues/bulk-update — 정상 202 접수
// ─────────────────────────────────────────────────────────────────────────────

describe('POST /api/v1/issues/bulk-update — 정상 202 접수', () => {
  it('BULK_EDIT → 202 + bulkAcceptedSchema.parse 통과 + bulkOperationId 유효 UUID', async () => {
    const res = await postBulkUpdate(validBulkEditBody)
    expect(res.status).toBe(202)
    const json = await res.json() as { data: unknown }
    const parsed = bulkAcceptedSchema.parse(json.data)
    expect(parsed.status).toBe('PENDING')
    expect(parsed.totalCount).toBe(3)
    // RFC4122 v4 UUID 형식 검증 (Zod uuid()가 통과했으므로 형식 보증)
    expect(parsed.bulkOperationId).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i,
    )
  })

  it('BULK_TRANSITION → 202 + status PENDING + totalCount=2', async () => {
    const res = await postBulkUpdate(validBulkTransitionBody)
    expect(res.status).toBe(202)
    const json = await res.json() as { data: unknown }
    const parsed = bulkAcceptedSchema.parse(json.data)
    expect(parsed.status).toBe('PENDING')
    expect(parsed.totalCount).toBe(2)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// POST → GET 폴링 stateful 진행
// ─────────────────────────────────────────────────────────────────────────────

describe('POST 후 GET 연속 폴링 — RUNNING→COMPLETED 진행', () => {
  it('2회 폴링 이내에 COMPLETED 도달 + bulkOperationResponseSchema.parse 통과', async () => {
    const postRes = await postBulkUpdate(validBulkEditBody)
    const postJson = await postRes.json() as { data: { bulkOperationId: string } }
    const { bulkOperationId } = postJson.data

    // 1차 폴링
    const poll1 = await getBulkOperation(bulkOperationId)
    expect(poll1.status).toBe(200)
    const body1 = await poll1.json() as { data: unknown }
    const parsed1 = bulkOperationResponseSchema.parse(body1.data)
    expect(parsed1.processedCount).toBeGreaterThan(0)

    // 2차 폴링 — 반드시 COMPLETED
    const poll2 = await getBulkOperation(bulkOperationId)
    expect(poll2.status).toBe(200)
    const body2 = await poll2.json() as { data: unknown }
    const parsed2 = bulkOperationResponseSchema.parse(body2.data)
    expect(parsed2.status).toBe('COMPLETED')
    expect(parsed2.processedCount).toBe(parsed2.totalCount)
    expect(parsed2.succeededCount).toBe(3)
    expect(parsed2.failedCount).toBe(0)
  })

  it('BULK_EDIT payload가 응답에 포함됨', async () => {
    const postRes = await postBulkUpdate(validBulkEditBody)
    const postJson = await postRes.json() as { data: { bulkOperationId: string } }
    const id = postJson.data.bulkOperationId

    // 2차 폴링까지 소비해 COMPLETED 도달
    await getBulkOperation(id)
    const poll2 = await getBulkOperation(id)
    const body = await poll2.json() as { data: unknown }
    const parsed = bulkOperationResponseSchema.parse(body.data)
    expect(parsed.operationType).toBe('BULK_EDIT')
    // payload가 editPayload 형태
    expect(parsed.payload).toMatchObject({ priority: 1, impact: 2 })
  })

  // bulkOperationPayloadSchema는 z.union([edit, transition])이고 union은 첫 매칭을 쓴다.
  // BULK_TRANSITION payload({toStateKey})가 edit 스키마(priority/impact required)에 잘못
  // 매칭되어 toStateKey가 유실되지 않는지 봉인한다 (edit 스키마가 optional로 약화되면 회귀).
  it('BULK_TRANSITION payload가 응답에 toStateKey로 보존됨', async () => {
    const postRes = await postBulkUpdate(validBulkTransitionBody)
    const postJson = await postRes.json() as { data: { bulkOperationId: string } }
    const id = postJson.data.bulkOperationId

    await getBulkOperation(id)
    const poll2 = await getBulkOperation(id)
    const body = await poll2.json() as { data: unknown }
    const parsed = bulkOperationResponseSchema.parse(body.data)
    expect(parsed.operationType).toBe('BULK_TRANSITION')
    expect(parsed.payload).toMatchObject({ toStateKey: 'in_progress' })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// partial-fail 시나리오 — 마지막 issueKey FAILED
// ─────────────────────────────────────────────────────────────────────────────

describe('partial-fail — localStorage __bts_e2e_bulk_partial_fail=true', () => {
  beforeEach(() => {
    globalThis.localStorage?.setItem('__bts_e2e_bulk_partial_fail', 'true')
  })

  it('마지막 key FAILED(VERSION_CONFLICT), 나머지 SUCCEEDED, failedCount=1', async () => {
    const postRes = await postBulkUpdate(validBulkEditBody)
    const postJson = await postRes.json() as { data: { bulkOperationId: string } }
    const id = postJson.data.bulkOperationId

    // 2회 폴링으로 COMPLETED 도달
    await getBulkOperation(id)
    const poll2 = await getBulkOperation(id)
    const body = await poll2.json() as { data: unknown }
    const parsed = bulkOperationResponseSchema.parse(body.data)
    expect(parsed.status).toBe('COMPLETED')
    expect(parsed.failedCount).toBe(1)
    expect(parsed.succeededCount).toBe(2)

    const failedItem = parsed.items.find(i => i.status === 'FAILED')
    expect(failedItem).toBeDefined()
    expect(failedItem?.issueKey).toBe('ATLAS-3') // 마지막 key
    expect(failedItem?.failureReasonCode).toBe('VERSION_CONFLICT')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 에러 분기 — reject localStorage 플래그
// ─────────────────────────────────────────────────────────────────────────────

describe('POST 에러 분기 — localStorage __bts_e2e_bulk_reject 플래그', () => {
  it("reject='validation' → 400 + body.detail 존재", async () => {
    globalThis.localStorage?.setItem('__bts_e2e_bulk_reject', 'validation')
    const res = await postBulkUpdate(validBulkEditBody)
    expect(res.status).toBe(400)
    const body = await res.json() as { errorCode: string; detail: string }
    expect(body.errorCode).toBe('ISSUE_BULK_VALIDATION_FAILED')
    expect(typeof body.detail).toBe('string')
    expect(body.detail.length).toBeGreaterThan(0)
  })

  it("reject='forbidden' → 403 + body.detail 존재", async () => {
    globalThis.localStorage?.setItem('__bts_e2e_bulk_reject', 'forbidden')
    const res = await postBulkUpdate(validBulkEditBody)
    expect(res.status).toBe(403)
    const body = await res.json() as { errorCode: string; detail: string }
    expect(body.errorCode).toBe('ISSUE_BULK_FORBIDDEN')
    expect(typeof body.detail).toBe('string')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 유효성 검증 — issueKeys 길이 위반
// ─────────────────────────────────────────────────────────────────────────────

describe('POST 유효성 검증 — issueKeys 길이 위반', () => {
  it('issueKeys 빈 배열 → 400 ISSUE_BULK_VALIDATION_FAILED', async () => {
    const res = await postBulkUpdate({ ...validBulkEditBody, issueKeys: [] })
    expect(res.status).toBe(400)
    const body = await res.json() as { errorCode: string }
    expect(body.errorCode).toBe('ISSUE_BULK_VALIDATION_FAILED')
  })

  it('issueKeys 1001개 → 400 ISSUE_BULK_VALIDATION_FAILED', async () => {
    const keys = Array.from({ length: 1001 }, (_, i) => `ATLAS-${i + 1}`)
    const res = await postBulkUpdate({ ...validBulkEditBody, issueKeys: keys })
    expect(res.status).toBe(400)
    const body = await res.json() as { errorCode: string }
    expect(body.errorCode).toBe('ISSUE_BULK_VALIDATION_FAILED')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// GET — 없는 id
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /api/v1/bulk-operations/:id — 없는 id', () => {
  it('존재하지 않는 id → 404 + errorCode ISSUE_BULK_NOT_FOUND', async () => {
    const res = await getBulkOperation('0a7f1e2c-3b4d-4e5f-8a9b-0c1d2e3f4a5b')
    expect(res.status).toBe(404)
    const body = await res.json() as { errorCode: string }
    expect(body.errorCode).toBe('ISSUE_BULK_NOT_FOUND')
  })
})
