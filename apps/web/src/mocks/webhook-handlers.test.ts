// 아웃바운드 webhook MSW 핸들러 stateful 동작 검증 테스트 (FR-API-03 PR4 Task 3)
import { setupServer } from 'msw/node'
import { afterAll, afterEach, beforeAll, describe, expect, it } from 'vitest'
import { webhookHandlers } from './webhook-handlers'
import {
  resetWebhookStore,
  seedWebhook,
  seedDeliveries,
  DEFAULT_WEBHOOK,
  DEFAULT_WEBHOOK_ID,
  SECOND_WEBHOOK,
  SECOND_WEBHOOK_ID,
  DEFAULT_WEBHOOK_DELIVERIES,
} from './webhook-fixtures'
import type { WebhookResponse, WebhookDeliveryResponse } from '@/api/webhooks'

const server = setupServer(...webhookHandlers)

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }))
afterEach(() => {
  server.resetHandlers()
  resetWebhookStore()
})
afterAll(() => server.close())

// ─────────────────────────────────────────────────────────────────────────────
// 공통 fetch 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

interface ProblemDetail {
  errorCode: string
  detail: string
}

async function getWebhooks(page: number, size: number): Promise<Response> {
  return fetch(`/api/v1/webhooks?page=${page}&size=${size}`)
}

async function getWebhookById(id: string): Promise<Response> {
  return fetch(`/api/v1/webhooks/${id}`)
}

async function postWebhook(body: Record<string, unknown>): Promise<Response> {
  return fetch('/api/v1/webhooks', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

async function putWebhook(id: string, body: Record<string, unknown>): Promise<Response> {
  return fetch(`/api/v1/webhooks/${id}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
}

async function deleteWebhookById(id: string): Promise<Response> {
  return fetch(`/api/v1/webhooks/${id}`, { method: 'DELETE' })
}

async function getDeliveries(id: string, page: number, size: number): Promise<Response> {
  return fetch(`/api/v1/webhooks/${id}/deliveries?page=${page}&size=${size}`)
}

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/webhooks?page=&size= — raw List
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /api/v1/webhooks?page=&size=', () => {
  it('빈 store → 200 빈 배열(raw List, envelope 아님)', async () => {
    const res = await getWebhooks(0, 20)
    expect(res.status).toBe(200)
    const body = (await res.json()) as unknown
    expect(Array.isArray(body)).toBe(true)
    expect(body).toEqual([])
  })

  it('seedWebhook 후 → 목록에 반영 (stateful)', async () => {
    seedWebhook(DEFAULT_WEBHOOK)
    const res = await getWebhooks(0, 20)
    expect(res.status).toBe(200)
    const body = (await res.json()) as WebhookResponse[]
    expect(body).toHaveLength(1)
    expect(body[0]?.id).toBe(DEFAULT_WEBHOOK_ID)
  })

  it('page/size로 슬라이스한다', async () => {
    seedWebhook(DEFAULT_WEBHOOK)
    seedWebhook(SECOND_WEBHOOK)
    const res = await getWebhooks(0, 1)
    expect(res.status).toBe(200)
    const body = (await res.json()) as WebhookResponse[]
    expect(body).toHaveLength(1)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/webhooks — 생성 → 목록 반영 (stateful)
// ─────────────────────────────────────────────────────────────────────────────

describe('POST /api/v1/webhooks', () => {
  const createBody = {
    name: '새 webhook',
    url: 'https://example.com/hook',
    eventFilter: ['issue.created'],
  }

  it('생성 → 201 WebhookResponse(id 발급, version=0, hasSecret=false)', async () => {
    const res = await postWebhook(createBody)
    expect(res.status).toBe(201)
    const body = (await res.json()) as WebhookResponse
    expect(body.id).toBeTruthy()
    expect(body.name).toBe(createBody.name)
    expect(body.version).toBe(0)
    expect(body.hasSecret).toBe(false)
    expect(body.createdAt).toBeTruthy()
    expect(body.updatedAt).toBeTruthy()
  })

  it('secret 포함 생성 → hasSecret=true', async () => {
    const res = await postWebhook({ ...createBody, secret: 'my-secret' })
    const body = (await res.json()) as WebhookResponse
    expect(body.hasSecret).toBe(true)
  })

  it('생성 후 → GET 목록에 반영된다 (stateful)', async () => {
    await postWebhook(createBody)
    const res = await getWebhooks(0, 20)
    const body = (await res.json()) as WebhookResponse[]
    expect(body).toHaveLength(1)
    expect(body[0]?.name).toBe(createBody.name)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/webhooks/:id — 단건
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /api/v1/webhooks/:id', () => {
  it('존재하는 구독 → 200 WebhookResponse', async () => {
    seedWebhook(DEFAULT_WEBHOOK)
    const res = await getWebhookById(DEFAULT_WEBHOOK_ID)
    expect(res.status).toBe(200)
    const body = (await res.json()) as WebhookResponse
    expect(body.id).toBe(DEFAULT_WEBHOOK_ID)
  })

  it('없는 구독 → 404 errorCode SEARCH_NOT_FOUND', async () => {
    const res = await getWebhookById('00000000-0000-4000-8000-000000000099')
    expect(res.status).toBe(404)
    const body = (await res.json()) as ProblemDetail
    expect(body.errorCode).toBe('SEARCH_NOT_FOUND')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// PUT /api/v1/webhooks/:id — version+1 + secret 3-state
// ─────────────────────────────────────────────────────────────────────────────

describe('PUT /api/v1/webhooks/:id', () => {
  const updateBody = {
    name: '수정된 이름',
    url: 'https://example.com/hook-updated',
    eventFilter: ['issue.created', 'issue.transitioned'],
    version: 0,
  }

  it('수정 → 200, version이 1 증가한다', async () => {
    seedWebhook(DEFAULT_WEBHOOK)
    const res = await putWebhook(DEFAULT_WEBHOOK_ID, updateBody)
    expect(res.status).toBe(200)
    const body = (await res.json()) as WebhookResponse
    expect(body.name).toBe(updateBody.name)
    expect(body.version).toBe(1)
  })

  it('secret 생략 → hasSecret이 기존 값 그대로 유지된다 (3-state)', async () => {
    seedWebhook(DEFAULT_WEBHOOK) // hasSecret=true
    const res = await putWebhook(DEFAULT_WEBHOOK_ID, updateBody)
    const body = (await res.json()) as WebhookResponse
    expect(body.hasSecret).toBe(true)
  })

  it('secret 값 포함 → hasSecret=true로 바뀐다 (3-state, 기존 false→true)', async () => {
    seedWebhook(SECOND_WEBHOOK) // hasSecret=false
    const res = await putWebhook(SECOND_WEBHOOK_ID, { ...updateBody, secret: 'new-secret' })
    const body = (await res.json()) as WebhookResponse
    expect(body.hasSecret).toBe(true)
  })

  it('수정 후 → GET 단건에 반영된다 (stateful)', async () => {
    seedWebhook(DEFAULT_WEBHOOK)
    await putWebhook(DEFAULT_WEBHOOK_ID, updateBody)
    const res = await getWebhookById(DEFAULT_WEBHOOK_ID)
    const body = (await res.json()) as WebhookResponse
    expect(body.name).toBe(updateBody.name)
    expect(body.version).toBe(1)
  })

  it('없는 구독 → 404 errorCode SEARCH_NOT_FOUND', async () => {
    const res = await putWebhook('00000000-0000-4000-8000-000000000099', updateBody)
    expect(res.status).toBe(404)
    const body = (await res.json()) as ProblemDetail
    expect(body.errorCode).toBe('SEARCH_NOT_FOUND')
  })

  it('version 불일치 → 409 errorCode SEARCH_WEBHOOK_CONFLICT (OCC)', async () => {
    seedWebhook(DEFAULT_WEBHOOK) // version=0
    const res = await putWebhook(DEFAULT_WEBHOOK_ID, { ...updateBody, version: 5 })
    expect(res.status).toBe(409)
    const body = (await res.json()) as ProblemDetail
    expect(body.errorCode).toBe('SEARCH_WEBHOOK_CONFLICT')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// DELETE /api/v1/webhooks/:id — store에서 제거
// ─────────────────────────────────────────────────────────────────────────────

describe('DELETE /api/v1/webhooks/:id', () => {
  it('삭제 → 204', async () => {
    seedWebhook(DEFAULT_WEBHOOK)
    const res = await deleteWebhookById(DEFAULT_WEBHOOK_ID)
    expect(res.status).toBe(204)
  })

  it('삭제 후 → GET 목록에서 제거된다 (stateful)', async () => {
    seedWebhook(DEFAULT_WEBHOOK)
    await deleteWebhookById(DEFAULT_WEBHOOK_ID)
    const res = await getWebhooks(0, 20)
    const body = (await res.json()) as WebhookResponse[]
    expect(body).toHaveLength(0)
  })

  it('삭제 후 → GET 단건이 404가 된다', async () => {
    seedWebhook(DEFAULT_WEBHOOK)
    await deleteWebhookById(DEFAULT_WEBHOOK_ID)
    const res = await getWebhookById(DEFAULT_WEBHOOK_ID)
    expect(res.status).toBe(404)
  })

  it('없는 구독 삭제 → 404 errorCode SEARCH_NOT_FOUND', async () => {
    const res = await deleteWebhookById('00000000-0000-4000-8000-000000000099')
    expect(res.status).toBe(404)
    const body = (await res.json()) as ProblemDetail
    expect(body.errorCode).toBe('SEARCH_NOT_FOUND')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/webhooks/:id/deliveries — raw List, 최신순, null 필드 케이스
// ─────────────────────────────────────────────────────────────────────────────

describe('GET /api/v1/webhooks/:id/deliveries', () => {
  it('시드된 이력 → 200 raw List(배열, envelope 아님)', async () => {
    seedWebhook(DEFAULT_WEBHOOK)
    seedDeliveries(DEFAULT_WEBHOOK_ID, DEFAULT_WEBHOOK_DELIVERIES)
    const res = await getDeliveries(DEFAULT_WEBHOOK_ID, 0, 20)
    expect(res.status).toBe(200)
    const body = (await res.json()) as WebhookDeliveryResponse[]
    expect(Array.isArray(body)).toBe(true)
    expect(body).toHaveLength(3)
  })

  it('SUCCEEDED/FAILED가 섞여 있고 최신순이다', async () => {
    seedWebhook(DEFAULT_WEBHOOK)
    seedDeliveries(DEFAULT_WEBHOOK_ID, DEFAULT_WEBHOOK_DELIVERIES)
    const res = await getDeliveries(DEFAULT_WEBHOOK_ID, 0, 20)
    const body = (await res.json()) as WebhookDeliveryResponse[]
    expect(body[0]?.status).toBe('FAILED')
    expect(body[2]?.status).toBe('SUCCEEDED')
  })

  it('responseCode/errorDetail/deliveredAt null 케이스가 파싱 가능한 형태로 포함된다', async () => {
    seedWebhook(DEFAULT_WEBHOOK)
    seedDeliveries(DEFAULT_WEBHOOK_ID, DEFAULT_WEBHOOK_DELIVERIES)
    const res = await getDeliveries(DEFAULT_WEBHOOK_ID, 0, 20)
    const body = (await res.json()) as WebhookDeliveryResponse[]
    const connectionFailure = body.find((d) => d.responseCode === null)
    expect(connectionFailure).toBeDefined()
    expect(connectionFailure?.deliveredAt).toBeNull()

    const succeeded = body.find((d) => d.status === 'SUCCEEDED')
    expect(succeeded?.errorDetail).toBeNull()

    const failedWithResponse = body.find((d) => d.status === 'FAILED' && d.responseCode !== null)
    expect(failedWithResponse?.deliveredAt).toBeNull()
  })

  it('이력 없는 구독 → 200 빈 배열', async () => {
    seedWebhook(SECOND_WEBHOOK)
    const res = await getDeliveries(SECOND_WEBHOOK_ID, 0, 20)
    expect(res.status).toBe(200)
    const body = (await res.json()) as WebhookDeliveryResponse[]
    expect(body).toEqual([])
  })

  it('page/size로 슬라이스한다', async () => {
    seedWebhook(DEFAULT_WEBHOOK)
    seedDeliveries(DEFAULT_WEBHOOK_ID, DEFAULT_WEBHOOK_DELIVERIES)
    const res = await getDeliveries(DEFAULT_WEBHOOK_ID, 0, 2)
    const body = (await res.json()) as WebhookDeliveryResponse[]
    expect(body).toHaveLength(2)
  })

  it('없는 구독 → 404 errorCode SEARCH_NOT_FOUND', async () => {
    const res = await getDeliveries('00000000-0000-4000-8000-000000000099', 0, 20)
    expect(res.status).toBe(404)
    const body = (await res.json()) as ProblemDetail
    expect(body.errorCode).toBe('SEARCH_NOT_FOUND')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// resetWebhookStore 헬퍼 동작 검증
// ─────────────────────────────────────────────────────────────────────────────

describe('resetWebhookStore', () => {
  it('seedWebhook → resetWebhookStore → GET 빈 목록', async () => {
    seedWebhook(DEFAULT_WEBHOOK)
    resetWebhookStore()
    const res = await getWebhooks(0, 20)
    const body = (await res.json()) as WebhookResponse[]
    expect(body).toHaveLength(0)
  })

  it('afterEach 자동 초기화 — 이전 테스트 잔여 없음', async () => {
    const res = await getWebhooks(0, 20)
    const body = (await res.json()) as WebhookResponse[]
    expect(body).toHaveLength(0)
  })
})
