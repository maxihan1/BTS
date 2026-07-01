// 아웃바운드 webhook 구독/발송이력 MSW stateful 핸들러 6종 — RFC 7807 ProblemDetail (FR-API-03 PR4)
import { http, HttpResponse } from 'msw'
import type { WebhookResponse, WebhookDeliveryResponse } from '@/api/webhooks'
import { webhookStore, deliveryStore, generateUuidV4 } from './webhook-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 에러 응답 헬퍼 — RFC 7807 ProblemDetail 형태 (OutboundWebhookExceptionHandler.kt 정합)
// `message` 필드 절대 금지 — 백엔드는 `detail` 필드를 사용한다
// ─────────────────────────────────────────────────────────────────────────────

interface ProblemDetail {
  type: string
  title: string
  status: number
  detail: string
  errorCode: string
  timestamp: string
}

function problemDetail(
  status: number,
  type: string,
  title: string,
  errorCode: string,
  detail: string,
): HttpResponse<ProblemDetail> {
  return HttpResponse.json<ProblemDetail>(
    {
      type: `https://bts.example.com/problems/${type}`,
      title,
      status,
      detail,
      errorCode,
      timestamp: new Date().toISOString(),
    },
    { status },
  )
}

/** SEARCH_NOT_FOUND — 구독이 존재하지 않거나 소프트 삭제됨 (백엔드 WebhookNotFoundException 404 정합) */
function webhookNotFound(): HttpResponse<ProblemDetail> {
  return problemDetail(
    404,
    'outbound-webhook-not-found',
    'Not Found',
    'SEARCH_NOT_FOUND',
    '아웃바운드 webhook 구독을 찾을 수 없습니다.',
  )
}

/** SEARCH_WEBHOOK_CONFLICT — OCC 충돌(stale version) (백엔드 WebhookConflictException 409 정합) */
function webhookConflict(): HttpResponse<ProblemDetail> {
  return problemDetail(
    409,
    'outbound-webhook-conflict',
    'Conflict',
    'SEARCH_WEBHOOK_CONFLICT',
    '구독이 다른 요청으로 수정되었습니다. 최신 버전을 재조회 후 재시도하세요.',
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 페이지네이션 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** DEFAULT_PAGE_SIZE — 백엔드 OutboundWebhookController와 동일 기본값 */
const DEFAULT_PAGE_SIZE = 20

/** query param에서 page/size를 읽는다. 미지정 시 백엔드 기본값(0/20)을 따른다. */
function parsePaging(url: URL): { page: number; size: number } {
  const page = Number.parseInt(url.searchParams.get('page') ?? '0', 10)
  const size = Number.parseInt(url.searchParams.get('size') ?? `${DEFAULT_PAGE_SIZE}`, 10)
  return { page, size }
}

/** 배열을 page/size로 슬라이스한다 (offset 페이지네이션). */
function paginate<T>(items: T[], page: number, size: number): T[] {
  const offset = page * size
  return items.slice(offset, offset + size)
}

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/webhooks?page=&size= — raw List
// ─────────────────────────────────────────────────────────────────────────────

/**
 * GET /api/v1/webhooks — webhook 구독 목록 조회.
 *
 * store를 삽입 순서 그대로 page/size로 슬라이스한 raw 배열을 반환한다(envelope 아님).
 * 성공 → 200 WebhookResponse[]
 */
const listWebhooksHandler = http.get('/api/v1/webhooks', ({ request }) => {
  const { page, size } = parsePaging(new URL(request.url))
  const items = paginate(Array.from(webhookStore.values()), page, size)
  return HttpResponse.json(items)
})

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/webhooks
// ─────────────────────────────────────────────────────────────────────────────

interface CreateWebhookBody {
  name?: string
  url?: string
  eventFilter?: string[]
  secret?: string
  projectKey?: string
  enabled?: boolean
}

/**
 * POST /api/v1/webhooks — webhook 구독 생성.
 *
 * store에 새 구독을 추가한다 — id 발급, version=0, hasSecret은 secret 유무로 결정,
 * createdAt/updatedAt은 현재 시각으로 설정한다.
 * 성공 → 201 WebhookResponse
 */
const createWebhookHandler = http.post('/api/v1/webhooks', async ({ request }) => {
  const body = (await request.json()) as CreateWebhookBody
  const now = new Date().toISOString()

  const created: WebhookResponse = {
    id: generateUuidV4(),
    name: body.name ?? '',
    url: body.url ?? '',
    eventFilter: body.eventFilter ?? [],
    projectKey: body.projectKey ?? null,
    enabled: body.enabled ?? true,
    hasSecret: (body.secret ?? '') !== '',
    createdAt: now,
    updatedAt: now,
    version: 0,
  }

  webhookStore.set(created.id, created)

  return HttpResponse.json(created, { status: 201 })
})

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/webhooks/:id
// ─────────────────────────────────────────────────────────────────────────────

/**
 * GET /api/v1/webhooks/{id} — webhook 구독 단건 조회.
 * 성공 → 200 WebhookResponse / 미존재 → 404 SEARCH_NOT_FOUND
 */
const getWebhookHandler = http.get('/api/v1/webhooks/:id', ({ params }) => {
  const id = params['id'] as string
  const stored = webhookStore.get(id)
  if (stored === undefined) {
    return webhookNotFound()
  }
  return HttpResponse.json(stored)
})

// ─────────────────────────────────────────────────────────────────────────────
// PUT /api/v1/webhooks/:id
// ─────────────────────────────────────────────────────────────────────────────

interface UpdateWebhookBody {
  name?: string
  url?: string
  eventFilter?: string[]
  version?: number
  secret?: string
  projectKey?: string
  enabled?: boolean
}

/**
 * PUT /api/v1/webhooks/{id} — webhook 구독 전체 교체(OCC).
 *
 * name/url/eventFilter/projectKey/enabled는 전체 교체, secret만 3-state
 * (생략/빈 문자열=기존 hasSecret 유지, 값 있음=hasSecret=true로 교체).
 * version이 store의 현재 version과 다르면 409(OCC 충돌).
 *
 * 성공 → 200 WebhookResponse(version+1) / 미존재 → 404 / OCC 충돌 → 409
 */
const updateWebhookHandler = http.put('/api/v1/webhooks/:id', async ({ params, request }) => {
  const id = params['id'] as string
  const existing = webhookStore.get(id)
  if (existing === undefined) {
    return webhookNotFound()
  }

  const body = (await request.json()) as UpdateWebhookBody
  if (body.version !== existing.version) {
    return webhookConflict()
  }

  const hasNewSecret = (body.secret ?? '') !== ''

  const updated: WebhookResponse = {
    ...existing,
    name: body.name ?? existing.name,
    url: body.url ?? existing.url,
    eventFilter: body.eventFilter ?? existing.eventFilter,
    projectKey: body.projectKey ?? null,
    enabled: body.enabled ?? true,
    hasSecret: hasNewSecret ? true : existing.hasSecret,
    updatedAt: new Date().toISOString(),
    version: existing.version + 1,
  }

  webhookStore.set(id, updated)

  return HttpResponse.json(updated)
})

// ─────────────────────────────────────────────────────────────────────────────
// DELETE /api/v1/webhooks/:id
// ─────────────────────────────────────────────────────────────────────────────

/**
 * DELETE /api/v1/webhooks/{id} — webhook 구독 삭제.
 * 성공 → 204 No Content / 미존재 → 404 SEARCH_NOT_FOUND
 */
const deleteWebhookHandler = http.delete('/api/v1/webhooks/:id', ({ params }) => {
  const id = params['id'] as string
  if (!webhookStore.has(id)) {
    return webhookNotFound()
  }
  webhookStore.delete(id)
  return new HttpResponse(null, { status: 204 })
})

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/webhooks/:id/deliveries?page=&size= — raw List, 최신순
// ─────────────────────────────────────────────────────────────────────────────

/**
 * GET /api/v1/webhooks/{id}/deliveries — webhook 발송 이력 조회(최신순, offset 페이지네이션).
 *
 * deliveryStore에 시드된 배열(호출자가 최신순으로 정렬)을 page/size로 슬라이스한
 * raw 배열로 반환한다(envelope 아님). 이력이 없으면 빈 배열.
 * 성공 → 200 WebhookDeliveryResponse[] / 구독 미존재 → 404 SEARCH_NOT_FOUND
 */
const listDeliveriesHandler = http.get(
  '/api/v1/webhooks/:id/deliveries',
  ({ params, request }) => {
    const id = params['id'] as string
    if (!webhookStore.has(id)) {
      return webhookNotFound()
    }

    const { page, size } = parsePaging(new URL(request.url))
    const deliveries: WebhookDeliveryResponse[] = deliveryStore.get(id) ?? []
    return HttpResponse.json(paginate(deliveries, page, size))
  },
)

// ─────────────────────────────────────────────────────────────────────────────
// export
// ─────────────────────────────────────────────────────────────────────────────

/** 아웃바운드 webhook 구독/발송이력 MSW 핸들러 배열 — handlers.ts에서 spread해 등록한다. */
export const webhookHandlers = [
  listWebhooksHandler,
  createWebhookHandler,
  getWebhookHandler,
  updateWebhookHandler,
  deleteWebhookHandler,
  listDeliveriesHandler,
]
