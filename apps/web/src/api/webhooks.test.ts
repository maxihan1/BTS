// 아웃바운드 webhook 구독/발송이력 API 클라이언트 단위 테스트 — MSW + Zod 파싱 + CSRF 헤더 검증
import { describe, it, expect, beforeEach, afterEach } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '@/test/server'
import { ApiError } from './client'
import {
  WEBHOOK_PUBLISHABLE_EVENTS,
  webhookResponseSchema,
  webhookDeliveryResponseSchema,
  fetchWebhooks,
  getWebhook,
  createWebhook,
  updateWebhook,
  deleteWebhook,
  fetchDeliveries,
} from './webhooks'
import { labelForEvent, labelForStatus } from '@/i18n/webhook-labels'

// ─────────────────────────────────────────────────────────────────────────────
// XSRF 쿠키 설정 / 해제 — CSRF 헤더 검증용
// ─────────────────────────────────────────────────────────────────────────────
const XSRF_COOKIE_VALUE = 'test-xsrf-webhook-token'

beforeEach(() => {
  document.cookie = `XSRF-TOKEN=${XSRF_COOKIE_VALUE}; path=/`
})

afterEach(() => {
  document.cookie = 'XSRF-TOKEN=; max-age=0; path=/'
})

// ─────────────────────────────────────────────────────────────────────────────
// Fixture
// ─────────────────────────────────────────────────────────────────────────────

/** @JsonInclude(NON_NULL) 가정 — projectKey/createdAt/updatedAt 키 자체가 생략된 최소 응답 */
const webhookMinimalFixture = {
  id: '11111111-1111-4111-a111-111111111111',
  name: '이슈 생성 알림',
  url: 'https://example.com/hook',
  eventFilter: ['issue.created'],
  enabled: true,
  hasSecret: false,
  version: 0,
}

/** projectKey/createdAt/updatedAt 포함 — 일반 응답 */
const webhookFullFixture = {
  ...webhookMinimalFixture,
  id: '22222222-2222-4222-a222-222222222222',
  projectKey: 'ATLAS',
  createdAt: '2026-06-01T00:00:00Z',
  updatedAt: '2026-06-01T00:00:00Z',
}

/** 발송 이력 — 성공(응답코드 있음) */
const deliverySucceededFixture = {
  id: '33333333-3333-4333-a333-333333333333',
  eventType: 'issue.created',
  status: 'SUCCEEDED',
  responseCode: 200,
  attemptCount: 1,
  errorDetail: null,
  createdAt: '2026-06-01T00:00:00Z',
  deliveredAt: '2026-06-01T00:00:01Z',
}

/** 발송 이력 — 실패(응답코드/시각 없음, EC — 발송 자체 불가능) */
const deliveryFailedFixture = {
  id: '44444444-4444-4444-a444-444444444444',
  eventType: 'issue.transitioned',
  status: 'FAILED',
  responseCode: null,
  attemptCount: 3,
  errorDetail: 'connect timeout',
  createdAt: '2026-06-01T00:00:00Z',
  deliveredAt: null,
}

// ─────────────────────────────────────────────────────────────────────────────
// T-WH-S. Zod 스키마 파싱 검증
// ─────────────────────────────────────────────────────────────────────────────
describe('webhookResponseSchema', () => {
  it('T-WH-S1: projectKey/createdAt/updatedAt 키가 없는 최소 응답을 파싱한다 (.nullish())', () => {
    const result = webhookResponseSchema.parse(webhookMinimalFixture)
    expect(result.id).toBe(webhookMinimalFixture.id)
    expect(result.projectKey).toBeUndefined()
    expect(result.createdAt).toBeUndefined()
    expect(result.updatedAt).toBeUndefined()
  })

  it('T-WH-S2: projectKey/createdAt/updatedAt이 null이어도 파싱된다', () => {
    const result = webhookResponseSchema.parse({
      ...webhookMinimalFixture,
      projectKey: null,
      createdAt: null,
      updatedAt: null,
    })
    expect(result.projectKey).toBeNull()
    expect(result.createdAt).toBeNull()
    expect(result.updatedAt).toBeNull()
  })

  it('T-WH-S3: 값이 채워진 전체 응답을 파싱한다', () => {
    const result = webhookResponseSchema.parse(webhookFullFixture)
    expect(result.projectKey).toBe('ATLAS')
    expect(result.eventFilter).toEqual(['issue.created'])
    expect(result.version).toBe(0)
  })
})

describe('webhookDeliveryResponseSchema', () => {
  it('T-WH-S4: responseCode/deliveredAt이 있는 성공 이력을 파싱한다', () => {
    const result = webhookDeliveryResponseSchema.parse(deliverySucceededFixture)
    expect(result.status).toBe('SUCCEEDED')
    expect(result.responseCode).toBe(200)
    expect(result.deliveredAt).toBe('2026-06-01T00:00:01Z')
    expect(result.errorDetail).toBeNull()
  })

  it('T-WH-S5: responseCode/deliveredAt이 null인 실패 이력을 파싱한다', () => {
    const result = webhookDeliveryResponseSchema.parse(deliveryFailedFixture)
    expect(result.status).toBe('FAILED')
    expect(result.responseCode).toBeNull()
    expect(result.deliveredAt).toBeNull()
    expect(result.errorDetail).toBe('connect timeout')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-WH-E. 상수 미러 검증
// ─────────────────────────────────────────────────────────────────────────────
describe('WEBHOOK_PUBLISHABLE_EVENTS', () => {
  it('T-WH-E1: 정확히 2종 — issue.created, issue.transitioned', () => {
    expect(WEBHOOK_PUBLISHABLE_EVENTS).toHaveLength(2)
    expect(WEBHOOK_PUBLISHABLE_EVENTS).toContain('issue.created')
    expect(WEBHOOK_PUBLISHABLE_EVENTS).toContain('issue.transitioned')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-WH-1. fetchWebhooks — GET /api/v1/webhooks?page=&size= (raw List)
// ─────────────────────────────────────────────────────────────────────────────
describe('fetchWebhooks', () => {
  it('T-WH-1a: raw List(배열)를 파싱해 반환한다 — envelope 아님', async () => {
    server.use(
      http.get('/api/v1/webhooks', () => HttpResponse.json([webhookMinimalFixture, webhookFullFixture])),
    )
    const result = await fetchWebhooks(0, 20)
    expect(result).toHaveLength(2)
    expect(result[0]?.id).toBe(webhookMinimalFixture.id)
  })

  it('T-WH-1b: createdAt/updatedAt/projectKey 키가 없는 항목도 파싱 성공한다 (EC-1)', async () => {
    server.use(
      http.get('/api/v1/webhooks', () => HttpResponse.json([webhookMinimalFixture])),
    )
    const result = await fetchWebhooks(0, 20)
    expect(result[0]?.projectKey).toBeUndefined()
    expect(result[0]?.createdAt).toBeUndefined()
  })

  it('T-WH-1c: page/size 쿼리 파라미터를 요청에 포함한다', async () => {
    let capturedUrl: string | null = null
    server.use(
      http.get('/api/v1/webhooks', ({ request }) => {
        capturedUrl = request.url
        return HttpResponse.json([])
      }),
    )
    await fetchWebhooks(2, 10)
    expect(capturedUrl).toContain('page=2')
    expect(capturedUrl).toContain('size=10')
  })

  it('T-WH-1d: 401 응답 → ApiError(401) throw', async () => {
    server.use(
      http.get('/api/v1/webhooks', () => HttpResponse.json({ type: 'about:blank' }, { status: 401 })),
    )
    let thrown: unknown
    try {
      await fetchWebhooks(0, 20)
    } catch (e) {
      thrown = e
    }
    expect(thrown).toBeInstanceOf(ApiError)
    if (!(thrown instanceof ApiError)) throw new Error('type guard missed')
    expect(thrown.status).toBe(401)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-WH-2. getWebhook — GET /api/v1/webhooks/{id}
// ─────────────────────────────────────────────────────────────────────────────
describe('getWebhook', () => {
  it('T-WH-2a: 단건 응답을 파싱해 반환한다', async () => {
    server.use(
      http.get(`/api/v1/webhooks/${webhookFullFixture.id}`, () => HttpResponse.json(webhookFullFixture)),
    )
    const result = await getWebhook(webhookFullFixture.id)
    expect(result.id).toBe(webhookFullFixture.id)
  })

  it('T-WH-2b: 404 응답 → ApiError(404) throw', async () => {
    server.use(
      http.get(`/api/v1/webhooks/${webhookFullFixture.id}`, () =>
        HttpResponse.json({ type: 'about:blank' }, { status: 404 }),
      ),
    )
    let thrown: unknown
    try {
      await getWebhook(webhookFullFixture.id)
    } catch (e) {
      thrown = e
    }
    expect(thrown).toBeInstanceOf(ApiError)
    if (!(thrown instanceof ApiError)) throw new Error('type guard missed')
    expect(thrown.status).toBe(404)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-WH-3. createWebhook — POST /api/v1/webhooks
// ─────────────────────────────────────────────────────────────────────────────
describe('createWebhook', () => {
  const createBody = {
    name: '이슈 생성 알림',
    url: 'https://example.com/hook',
    eventFilter: ['issue.created'],
  }

  it('T-WH-3a: 201 응답을 파싱해 반환한다', async () => {
    server.use(
      http.post('/api/v1/webhooks', () => HttpResponse.json(webhookMinimalFixture, { status: 201 })),
    )
    const result = await createWebhook(createBody)
    expect(result.id).toBe(webhookMinimalFixture.id)
  })

  it('T-WH-3b: X-XSRF-TOKEN 헤더가 요청에 포함된다 (상태 변경)', async () => {
    let capturedXsrf: string | null = null
    server.use(
      http.post('/api/v1/webhooks', ({ request }) => {
        capturedXsrf = request.headers.get('x-xsrf-token')
        return HttpResponse.json(webhookMinimalFixture, { status: 201 })
      }),
    )
    await createWebhook(createBody)
    expect(capturedXsrf).toBe(XSRF_COOKIE_VALUE)
  })

  it('T-WH-3c: 요청 바디에 name/url/eventFilter가 포함된다', async () => {
    let capturedBody: unknown = null
    server.use(
      http.post('/api/v1/webhooks', async ({ request }) => {
        capturedBody = await request.json()
        return HttpResponse.json(webhookMinimalFixture, { status: 201 })
      }),
    )
    await createWebhook(createBody)
    const body = capturedBody as Record<string, unknown> | null
    expect(body?.name).toBe(createBody.name)
    expect(body?.url).toBe(createBody.url)
    expect(body?.eventFilter).toEqual(createBody.eventFilter)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-WH-4. updateWebhook — PUT /api/v1/webhooks/{id}
// ─────────────────────────────────────────────────────────────────────────────
describe('updateWebhook', () => {
  const webhookId = webhookFullFixture.id
  const updateBody = {
    name: '이슈 생성 알림 (수정)',
    url: 'https://example.com/hook2',
    eventFilter: ['issue.created', 'issue.transitioned'],
    version: 1,
  }

  it('T-WH-4a: 200 응답을 파싱해 반환한다', async () => {
    server.use(
      http.put(`/api/v1/webhooks/${webhookId}`, () => HttpResponse.json(webhookFullFixture)),
    )
    const result = await updateWebhook(webhookId, updateBody)
    expect(result.id).toBe(webhookId)
  })

  it('T-WH-4b: X-XSRF-TOKEN 헤더가 요청에 포함된다', async () => {
    let capturedXsrf: string | null = null
    server.use(
      http.put(`/api/v1/webhooks/${webhookId}`, ({ request }) => {
        capturedXsrf = request.headers.get('x-xsrf-token')
        return HttpResponse.json(webhookFullFixture)
      }),
    )
    await updateWebhook(webhookId, updateBody)
    expect(capturedXsrf).toBe(XSRF_COOKIE_VALUE)
  })

  it('T-WH-4c: 요청 바디에 version이 포함된다 (OCC)', async () => {
    let capturedBody: unknown = null
    server.use(
      http.put(`/api/v1/webhooks/${webhookId}`, async ({ request }) => {
        capturedBody = await request.json()
        return HttpResponse.json(webhookFullFixture)
      }),
    )
    await updateWebhook(webhookId, updateBody)
    const body = capturedBody as Record<string, unknown> | null
    expect(body?.version).toBe(1)
  })

  it('T-WH-4d: secret이 undefined면 요청 바디에서 생략된다 (3-state, 기존 암호문 유지)', async () => {
    let capturedBody: unknown = null
    server.use(
      http.put(`/api/v1/webhooks/${webhookId}`, async ({ request }) => {
        capturedBody = await request.json()
        return HttpResponse.json(webhookFullFixture)
      }),
    )
    await updateWebhook(webhookId, updateBody)
    const body = capturedBody as Record<string, unknown> | null
    expect(Object.hasOwn(body ?? {}, 'secret')).toBe(false)
  })

  it('T-WH-4e: secret이 빈 문자열이면 요청 바디에서 생략된다 (3-state)', async () => {
    let capturedBody: unknown = null
    server.use(
      http.put(`/api/v1/webhooks/${webhookId}`, async ({ request }) => {
        capturedBody = await request.json()
        return HttpResponse.json(webhookFullFixture)
      }),
    )
    await updateWebhook(webhookId, { ...updateBody, secret: '' })
    const body = capturedBody as Record<string, unknown> | null
    expect(Object.hasOwn(body ?? {}, 'secret')).toBe(false)
  })

  it('T-WH-4f: secret에 값이 있으면 요청 바디에 포함된다 (교체)', async () => {
    let capturedBody: unknown = null
    server.use(
      http.put(`/api/v1/webhooks/${webhookId}`, async ({ request }) => {
        capturedBody = await request.json()
        return HttpResponse.json(webhookFullFixture)
      }),
    )
    await updateWebhook(webhookId, { ...updateBody, secret: 'new-secret-value' })
    const body = capturedBody as Record<string, unknown> | null
    expect(body?.secret).toBe('new-secret-value')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-WH-5. deleteWebhook — DELETE /api/v1/webhooks/{id}
// ─────────────────────────────────────────────────────────────────────────────
describe('deleteWebhook', () => {
  const webhookId = webhookFullFixture.id

  it('T-WH-5a: 204 응답 → void 반환 (에러 없음)', async () => {
    server.use(
      http.delete(`/api/v1/webhooks/${webhookId}`, () => new HttpResponse(null, { status: 204 })),
    )
    await expect(deleteWebhook(webhookId)).resolves.toBeUndefined()
  })

  it('T-WH-5b: X-XSRF-TOKEN 헤더가 요청에 포함된다', async () => {
    let capturedXsrf: string | null = null
    server.use(
      http.delete(`/api/v1/webhooks/${webhookId}`, ({ request }) => {
        capturedXsrf = request.headers.get('x-xsrf-token')
        return new HttpResponse(null, { status: 204 })
      }),
    )
    await deleteWebhook(webhookId)
    expect(capturedXsrf).toBe(XSRF_COOKIE_VALUE)
  })

  it('T-WH-5c: 404 → ApiError(404) throw', async () => {
    server.use(
      http.delete(`/api/v1/webhooks/${webhookId}`, () =>
        HttpResponse.json({ type: 'about:blank' }, { status: 404 }),
      ),
    )
    let thrown: unknown
    try {
      await deleteWebhook(webhookId)
    } catch (e) {
      thrown = e
    }
    expect(thrown).toBeInstanceOf(ApiError)
    if (!(thrown instanceof ApiError)) throw new Error('type guard missed')
    expect(thrown.status).toBe(404)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-WH-6. fetchDeliveries — GET /api/v1/webhooks/{id}/deliveries?page=&size=
// ─────────────────────────────────────────────────────────────────────────────
describe('fetchDeliveries', () => {
  const webhookId = webhookFullFixture.id

  it('T-WH-6a: raw List(배열)를 파싱해 반환한다', async () => {
    server.use(
      http.get(`/api/v1/webhooks/${webhookId}/deliveries`, () =>
        HttpResponse.json([deliverySucceededFixture, deliveryFailedFixture]),
      ),
    )
    const result = await fetchDeliveries(webhookId, 0, 20)
    expect(result).toHaveLength(2)
  })

  it('T-WH-6b: responseCode/errorDetail/deliveredAt이 null인 이력도 파싱된다', async () => {
    server.use(
      http.get(`/api/v1/webhooks/${webhookId}/deliveries`, () =>
        HttpResponse.json([deliveryFailedFixture]),
      ),
    )
    const result = await fetchDeliveries(webhookId, 0, 20)
    expect(result[0]?.responseCode).toBeNull()
    expect(result[0]?.deliveredAt).toBeNull()
  })

  it('T-WH-6c: page/size 쿼리 파라미터를 요청에 포함한다', async () => {
    let capturedUrl: string | null = null
    server.use(
      http.get(`/api/v1/webhooks/${webhookId}/deliveries`, ({ request }) => {
        capturedUrl = request.url
        return HttpResponse.json([])
      }),
    )
    await fetchDeliveries(webhookId, 1, 5)
    expect(capturedUrl).toContain('page=1')
    expect(capturedUrl).toContain('size=5')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// T-WH-L. 라벨 헬퍼 — 미지 값 전방호환
// ─────────────────────────────────────────────────────────────────────────────
describe('labelForEvent', () => {
  it('T-WH-L1: issue.created를 "이슈 생성"으로 변환한다', () => {
    expect(labelForEvent('issue.created')).toBe('이슈 생성')
  })

  it('T-WH-L2: issue.transitioned를 "이슈 상태 전환"로 변환한다', () => {
    expect(labelForEvent('issue.transitioned')).toBe('이슈 상태 전환')
  })

  it('T-WH-L3: 미지 값은 원문을 그대로 반환한다', () => {
    expect(labelForEvent('unknown.event')).toBe('unknown.event')
  })
})

describe('labelForStatus', () => {
  it('T-WH-L4: SUCCEEDED를 "성공"으로 변환한다', () => {
    expect(labelForStatus('SUCCEEDED')).toBe('성공')
  })

  it('T-WH-L5: FAILED를 "실패"로 변환한다', () => {
    expect(labelForStatus('FAILED')).toBe('실패')
  })

  it('T-WH-L6: 미지 값은 원문을 그대로 반환한다', () => {
    expect(labelForStatus('UNKNOWN')).toBe('UNKNOWN')
  })
})
