// 아웃바운드 webhook 구독/발송이력 API 클라이언트 + Zod 스키마 (FR-API-03 PR4)
import { z } from 'zod'
import { apiFetch, apiGet, ApiError } from './client'
import { readXsrfToken } from './sessions'

// ─────────────────────────────────────────────────────────────────────────────
// 백엔드 상수 미러 — com.bts.search.webhook.domain.WebhookEventCatalog.PUBLISHABLE 와 동기화
// 이벤트 추가 시 이 배열 + i18n/webhook-labels.ts eventLabels 도 함께 갱신할 것
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 아웃바운드 webhook 구독의 `eventFilter`가 선택 가능한 발행 이벤트 wireValue 전체 집합.
 *
 * 백엔드 `com.bts.search.webhook.domain.WebhookEventCatalog.PUBLISHABLE` 미러 —
 * 이벤트 추가 시 이 배열 + `webhook-labels.ts`의 라벨 맵도 동반 갱신할 것.
 */
export const WEBHOOK_PUBLISHABLE_EVENTS = ['issue.created', 'issue.transitioned'] as const

/** 발행 가능한 webhook 이벤트 wireValue 유니온 타입 */
export type WebhookEventType = (typeof WEBHOOK_PUBLISHABLE_EVENTS)[number]

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 — 백엔드 WebhookResponse / WebhookDeliveryResponse DTO 1:1 정합
// (OutboundWebhookDtos.kt) — null 필드는 JSON에서 키 자체가 생략될 수 있음
// → projectKey/createdAt/updatedAt/responseCode/errorDetail/deliveredAt 반드시 .nullish()
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 아웃바운드 webhook 구독 단건 응답 Zod 스키마.
 *
 * - projectKey/createdAt/updatedAt은 .nullish() 필수.
 *   null이면 키 자체가 응답에서 생략될 수 있으므로 optional도 포함해야 함.
 * - secret 원문/암호문은 응답에 없다 — hasSecret(boolean)으로만 설정 여부를 노출.
 */
export const webhookResponseSchema = z.object({
  /** 구독 ID (UUID) */
  id: z.string().uuid(),
  /** 구독 이름 */
  name: z.string(),
  /** 통지 대상 URL */
  url: z.string(),
  /** 관심 이벤트 wireValue 목록 */
  eventFilter: z.array(z.string()),
  /** 필터링할 프로젝트 키 — null/키 부재면 전체 프로젝트 대상 */
  projectKey: z.string().nullish(),
  /** 활성화 여부 */
  enabled: z.boolean(),
  /** 서명용 secret 설정 여부 — 원문/암호문 자체는 노출하지 않음 */
  hasSecret: z.boolean(),
  /** 생성 시각 (ISO 8601) */
  createdAt: z.string().nullish(),
  /** 마지막 변경 시각 (ISO 8601) */
  updatedAt: z.string().nullish(),
  /** 낙관적 동시성 제어(OCC) 버전 */
  version: z.number(),
})

/** webhook 구독 단건 타입 — z.infer 자동 추론 */
export type WebhookResponse = z.infer<typeof webhookResponseSchema>

/**
 * 아웃바운드 webhook 발송 이력 단건 응답 Zod 스키마.
 *
 * - status는 z.string() — 전방호환(값은 SUCCEEDED|FAILED, 백엔드 enum 추가에도 파싱 무파손).
 * - responseCode/errorDetail/deliveredAt은 .nullish() — 발송 자체가 불가능했거나 실패 시 null.
 */
export const webhookDeliveryResponseSchema = z.object({
  /** 발송 이력 레코드 ID (UUID, 내부 감사용) */
  id: z.string().uuid(),
  /** 발송을 트리거한 이벤트 wireValue */
  eventType: z.string(),
  /** 시도 결과 — z.string() 전방호환 (값: SUCCEEDED|FAILED) */
  status: z.string(),
  /** 수신자 HTTP 상태 코드 — 발송 자체가 불가능했으면 null */
  responseCode: z.number().nullish(),
  /** 이 이벤트에 대한 시도 순번 */
  attemptCount: z.number(),
  /** 실패 원인 요약 — 성공 시 null */
  errorDetail: z.string().nullish(),
  /** 시도 시각 (ISO 8601) */
  createdAt: z.string().nullish(),
  /** 발송 성공 시각 — 실패면 null */
  deliveredAt: z.string().nullish(),
})

/** webhook 발송 이력 단건 타입 — z.infer 자동 추론 */
export type WebhookDeliveryResponse = z.infer<typeof webhookDeliveryResponseSchema>

// ─────────────────────────────────────────────────────────────────────────────
// 요청 인터페이스
// ─────────────────────────────────────────────────────────────────────────────

/**
 * webhook 구독 생성 요청 바디.
 * secret 생략(undefined) 시 서버가 미설정으로 처리한다.
 */
export interface CreateWebhookRequest {
  /** 구독 이름 (필수) */
  name: string
  /** 통지 대상 URL (필수, 서버가 SSRF 검증) */
  url: string
  /** 관심 이벤트 wireValue 목록 */
  eventFilter: string[]
  /** 서명용 평문 secret — 생략 시 미설정 */
  secret?: string
  /** 필터링할 프로젝트 키 — 생략 시 전체 프로젝트 대상 */
  projectKey?: string
  /** 활성화 여부 — 생략 시 서버 기본값(true) */
  enabled?: boolean
}

/**
 * webhook 구독 수정 요청 바디 (PUT — 전체 교체).
 *
 * version은 OCC(낙관적 동시성 제어) 키로 필수.
 * secret은 3-state — undefined/빈 문자열이면 요청 바디에서 생략해 기존 암호문을 유지하고,
 * 값이 있으면 포함해 재암호화 교체한다.
 */
export interface UpdateWebhookRequest {
  /** 새 구독 이름 (필수) */
  name: string
  /** 새 통지 대상 URL (필수, 서버가 SSRF 검증) */
  url: string
  /** 새 관심 이벤트 wireValue 목록 */
  eventFilter: string[]
  /** 클라이언트가 보유한 현재 version (OCC 키, 필수) */
  version: number
  /** 새 평문 secret — undefined/빈 문자열이면 기존 암호문 유지 */
  secret?: string
  /** 새 프로젝트 키 — 생략 시 전체 프로젝트 대상 */
  projectKey?: string
  /** 새 활성화 여부 — 생략 시 서버 기본값(true) */
  enabled?: boolean
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * secret 3-state 처리 — undefined/빈 문자열이면 필드 자체를 요청 바디에서 제거해
 * "기존 암호문 유지" 신호를 보낸다. 값이 있으면 그대로 통과시켜 교체 신호를 보낸다.
 *
 * @param body secret을 포함할 수 있는 요청 바디
 * @returns secret이 비어있으면 해당 키가 제거된 사본
 */
function withOmittedEmptySecret<T extends { secret?: string }>(body: T): T {
  if (body.secret === undefined || body.secret === '') {
    const rest = { ...body }
    delete rest.secret
    return rest
  }
  return body
}

/** 비-2xx 응답을 검사하고 아니면 ApiError를 throw하는 공통 헬퍼 */
async function assertOk(res: Response): Promise<void> {
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * webhook 구독 목록을 offset 페이지네이션으로 조회한다.
 *
 * `GET /api/v1/webhooks?page=&size=` — raw List 응답 (envelope 아님).
 * 읽기 요청이므로 CSRF 헤더 불요. SYSTEM_ADMIN 전용.
 *
 * @param page 0-based 페이지 번호
 * @param size 페이지 크기
 * @returns WebhookResponse 배열
 * @throws ApiError(403) 비관리자
 * @throws ApiError(401) 미인증
 */
export async function fetchWebhooks(page: number, size: number): Promise<WebhookResponse[]> {
  return apiGet(`/api/v1/webhooks?page=${page}&size=${size}`, z.array(webhookResponseSchema))
}

/**
 * webhook 구독 단건을 조회한다.
 *
 * `GET /api/v1/webhooks/{id}` — 읽기 요청이므로 CSRF 헤더 불요. SYSTEM_ADMIN 전용.
 *
 * @param id 구독 UUID
 * @returns WebhookResponse
 * @throws ApiError(404) 미존재
 * @throws ApiError(403) 비관리자
 * @throws ApiError(401) 미인증
 */
export async function getWebhook(id: string): Promise<WebhookResponse> {
  return apiGet(`/api/v1/webhooks/${id}`, webhookResponseSchema)
}

/**
 * webhook 구독을 생성한다.
 *
 * `POST /api/v1/webhooks` — 상태 변경 요청이므로 X-XSRF-TOKEN 헤더를 포함한다.
 * 201 Created 응답을 파싱해 반환. SYSTEM_ADMIN 전용.
 *
 * @param body 생성 요청 바디
 * @returns 생성된 WebhookResponse
 * @throws ApiError(400) 유효성 실패 (name/url 누락, eventFilter 미지 이벤트 등)
 * @throws ApiError(403) 비관리자
 * @throws ApiError(401) 미인증
 */
export async function createWebhook(body: CreateWebhookRequest): Promise<WebhookResponse> {
  const res = await apiFetch('/api/v1/webhooks', {
    method: 'POST',
    body: withOmittedEmptySecret(body),
    headers: {
      'X-XSRF-TOKEN': readXsrfToken(),
    },
  })
  await assertOk(res)
  const parsed: unknown = await res.json()
  return webhookResponseSchema.parse(parsed)
}

/**
 * webhook 구독을 전체 교체(PUT)로 수정한다.
 *
 * `PUT /api/v1/webhooks/{id}` — 상태 변경 요청이므로 X-XSRF-TOKEN 헤더를 포함한다.
 * version은 OCC 키로 필수. secret은 3-state(생략/빈 문자열=기존 유지, 값 있음=교체).
 * 200 OK 응답을 파싱해 반환. SYSTEM_ADMIN 전용.
 *
 * @param id 구독 UUID
 * @param body 수정 요청 바디 (version 필수)
 * @returns 수정된 WebhookResponse
 * @throws ApiError(409) OCC 충돌 (version 불일치)
 * @throws ApiError(404) 미존재
 * @throws ApiError(400) 유효성 실패
 * @throws ApiError(403) 비관리자
 * @throws ApiError(401) 미인증
 */
export async function updateWebhook(id: string, body: UpdateWebhookRequest): Promise<WebhookResponse> {
  const res = await apiFetch(`/api/v1/webhooks/${id}`, {
    method: 'PUT',
    body: withOmittedEmptySecret(body),
    headers: {
      'X-XSRF-TOKEN': readXsrfToken(),
    },
  })
  await assertOk(res)
  const parsed: unknown = await res.json()
  return webhookResponseSchema.parse(parsed)
}

/**
 * webhook 구독을 삭제한다.
 *
 * `DELETE /api/v1/webhooks/{id}` — 상태 변경 요청이므로 X-XSRF-TOKEN 헤더를 포함한다.
 * 204 No Content 성공 — Zod parse 없이 반환. SYSTEM_ADMIN 전용.
 *
 * @param id 구독 UUID
 * @returns void
 * @throws ApiError(404) 미존재
 * @throws ApiError(403) 비관리자
 * @throws ApiError(401) 미인증
 */
export async function deleteWebhook(id: string): Promise<void> {
  const res = await apiFetch(`/api/v1/webhooks/${id}`, {
    method: 'DELETE',
    headers: {
      'X-XSRF-TOKEN': readXsrfToken(),
    },
  })
  await assertOk(res)
}

/**
 * webhook 구독의 발송 이력을 offset 페이지네이션으로 조회한다 (최신순).
 *
 * `GET /api/v1/webhooks/{id}/deliveries?page=&size=` — raw List 응답 (envelope 아님).
 * 읽기 요청이므로 CSRF 헤더 불요. SYSTEM_ADMIN 전용.
 *
 * @param id 구독 UUID
 * @param page 0-based 페이지 번호
 * @param size 페이지 크기
 * @returns WebhookDeliveryResponse 배열
 * @throws ApiError(404) 구독 미존재
 * @throws ApiError(403) 비관리자
 * @throws ApiError(401) 미인증
 */
export async function fetchDeliveries(id: string, page: number, size: number): Promise<WebhookDeliveryResponse[]> {
  return apiGet(
    `/api/v1/webhooks/${id}/deliveries?page=${page}&size=${size}`,
    z.array(webhookDeliveryResponseSchema),
  )
}
