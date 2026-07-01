// 아웃바운드 webhook 구독/발송이력 MSW stateful store + 기본 시드 fixture (FR-API-03 PR4)
import type { WebhookResponse, WebhookDeliveryResponse } from '@/api/webhooks'

// ─────────────────────────────────────────────────────────────────────────────
// UUID v4 생성 헬퍼 — 신규 의존성 금지, crypto.randomUUID 표준 API 사용
// (board-fixtures.ts / custom-field-handlers.ts 동일 패턴 — Zod v4 z.string().uuid() 통과 보장)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * RFC4122 v4 UUID를 생성한다.
 * crypto.randomUUID()가 있으면 사용하고, 없으면 Math.random 기반 폴백.
 */
export function generateUuidV4(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (c) => {
    const r = (Math.random() * 16) | 0
    const v = c === 'x' ? r : (r & 0x3) | 0x8
    return v.toString(16)
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// 공유 stateful store — resetWebhookStore()로 테스트 격리
// ─────────────────────────────────────────────────────────────────────────────

/** webhook 구독 store — id → WebhookResponse. 삽입 순서를 유지한다(Map). */
export let webhookStore: Map<string, WebhookResponse> = new Map()

/** webhook 발송 이력 store — webhookId → WebhookDeliveryResponse[] (호출자가 최신순으로 시드한다). */
export let deliveryStore: Map<string, WebhookDeliveryResponse[]> = new Map()

/**
 * 구독 store와 발송 이력 store를 빈 상태로 초기화한다.
 * 각 테스트 afterEach에서 호출해 테스트 간 격리를 보장한다(msw-derived-behavior-shared-store-e2e 선례).
 */
export function resetWebhookStore(): void {
  webhookStore = new Map()
  deliveryStore = new Map()
}

/**
 * webhook 구독을 store에 시드한다. 동일 id가 이미 있으면 덮어쓴다.
 *
 * @param webhook 시드할 WebhookResponse
 */
export function seedWebhook(webhook: WebhookResponse): void {
  webhookStore.set(webhook.id, webhook)
}

/**
 * 특정 webhook 구독의 발송 이력 배열을 store에 시드한다.
 * 배열은 호출자가 최신순(newest-first)으로 정렬해 전달해야 한다 — 핸들러는 재정렬하지 않는다.
 *
 * @param webhookId 이력을 연결할 구독 id
 * @param deliveries 최신순으로 정렬된 WebhookDeliveryResponse 배열
 */
export function seedDeliveries(webhookId: string, deliveries: WebhookDeliveryResponse[]): void {
  deliveryStore.set(webhookId, deliveries)
}

// ─────────────────────────────────────────────────────────────────────────────
// 기본 시드 픽스처 — 계약(webhooks.ts Zod 스키마) 1:1 정합
// ─────────────────────────────────────────────────────────────────────────────

/** DEFAULT_WEBHOOK 고정 UUID — 테스트가 안정적으로 참조 가능하도록 상수화 */
export const DEFAULT_WEBHOOK_ID = '10000000-0000-4000-8000-000000000001'

/** SECOND_WEBHOOK 고정 UUID — hasSecret=false 3-state 검증용 */
export const SECOND_WEBHOOK_ID = '10000000-0000-4000-8000-000000000002'

/**
 * 기본 webhook 구독 픽스처 — secret 설정됨(hasSecret=true), 전체 프로젝트 대상(projectKey=null).
 */
export const DEFAULT_WEBHOOK: WebhookResponse = {
  id: DEFAULT_WEBHOOK_ID,
  name: 'Slack 알림 webhook',
  url: 'https://hooks.slack.example.com/services/T000/B000/XXXX',
  eventFilter: ['issue.created', 'issue.transitioned'],
  projectKey: null,
  enabled: true,
  hasSecret: true,
  createdAt: '2026-06-01T00:00:00Z',
  updatedAt: '2026-06-01T00:00:00Z',
  version: 0,
}

/**
 * 두 번째 webhook 구독 픽스처 — secret 미설정(hasSecret=false), ATLAS 프로젝트 한정.
 * PUT secret 3-state(생략/값 있음) 검증에 사용한다.
 */
export const SECOND_WEBHOOK: WebhookResponse = {
  id: SECOND_WEBHOOK_ID,
  name: 'ATLAS 전용 알림',
  url: 'https://hooks.example.com/atlas',
  eventFilter: ['issue.created'],
  projectKey: 'ATLAS',
  enabled: true,
  hasSecret: false,
  createdAt: '2026-06-02T00:00:00Z',
  updatedAt: '2026-06-02T00:00:00Z',
  version: 0,
}

/**
 * DEFAULT_WEBHOOK의 발송 이력 픽스처 — 최신순, SUCCEEDED/FAILED 혼합.
 *
 * - 1번(SUCCEEDED): responseCode/deliveredAt 모두 값 있음, errorDetail=null.
 * - 2번(FAILED): 수신자 응답은 왔으나 5xx — responseCode 있음, deliveredAt/errorDetail 있음 아님(성공 아니므로 null).
 * - 3번(FAILED): 연결 자체 실패 — responseCode=null, deliveredAt=null.
 */
export const DEFAULT_WEBHOOK_DELIVERIES: WebhookDeliveryResponse[] = [
  {
    id: '20000000-0000-4000-8000-000000000003',
    eventType: 'issue.transitioned',
    status: 'FAILED',
    responseCode: null,
    attemptCount: 3,
    errorDetail: '연결 시간 초과',
    createdAt: '2026-06-03T00:00:00Z',
    deliveredAt: null,
  },
  {
    id: '20000000-0000-4000-8000-000000000002',
    eventType: 'issue.transitioned',
    status: 'FAILED',
    responseCode: 500,
    attemptCount: 2,
    errorDetail: '수신자 서버 오류 (5xx)',
    createdAt: '2026-06-02T00:00:00Z',
    deliveredAt: null,
  },
  {
    id: '20000000-0000-4000-8000-000000000001',
    eventType: 'issue.created',
    status: 'SUCCEEDED',
    responseCode: 200,
    attemptCount: 1,
    errorDetail: null,
    createdAt: '2026-06-01T00:00:00Z',
    deliveredAt: '2026-06-01T00:00:01Z',
  },
]
