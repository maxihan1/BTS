// FR-NT-01 알림 정책 MSW stateful 핸들러 — CRUD + RFC7807 에러 + E2E reset 헤더
import { http, HttpResponse } from 'msw'
import { type NotificationPolicyFixture, notificationPolicySeedData } from './notification-policy-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 카탈로그 상수 — 백엔드 enum과 1:1 (enum 추가 시 이곳도 동반 갱신)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * eventType wireValue 9종 + publishable 여부.
 * publishable=true: 수신자 역할이 있어야 발행 가능한 이벤트.
 */
const CATALOG_EVENT_TYPES: ReadonlyArray<{ value: string; publishable: boolean }> = [
  { value: 'issue.created', publishable: true },
  { value: 'issue.assigned', publishable: false },
  { value: 'issue.transitioned', publishable: true },
  { value: 'issue.commented', publishable: false },
  { value: 'issue.due_soon', publishable: false },
  { value: 'issue.overdue', publishable: false },
  { value: 'sprint.started', publishable: false },
  { value: 'sprint.ended', publishable: false },
  { value: 'automation.failed', publishable: false },
]

/** recipientRole NAME 9종 */
const CATALOG_RECIPIENT_ROLES: ReadonlyArray<string> = [
  'REPORTER',
  'ASSIGNEE',
  'PREVIOUS_ASSIGNEE',
  'WATCHER',
  'COMPONENT_LEAD',
  'MENTIONED',
  'PROJECT_MEMBER',
  'RULE_OWNER',
  'PROJECT_ADMIN',
]

/** channel NAME 5종 */
const CATALOG_CHANNELS: ReadonlyArray<string> = ['EMAIL', 'IN_APP', 'SLACK', 'TEAMS', 'WEBHOOK']

// ─────────────────────────────────────────────────────────────────────────────
// RFC 7807 ProblemDetail 헬퍼
// `message` 필드 절대 금지 — 백엔드는 `detail` 필드를 사용한다
// ─────────────────────────────────────────────────────────────────────────────

interface ProblemDetail {
  type: string
  title: string
  status: number
  detail: string
  /** 대문자 errorCode — notification BC 규칙 (mfa 소문자 패턴과 다름, C3) */
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

// ─────────────────────────────────────────────────────────────────────────────
// UUID v4 생성 헬퍼 (version-handlers.ts 동형)
// ─────────────────────────────────────────────────────────────────────────────

function generateUuidV4(): string {
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
// 모듈 스코프 가변 store — resetNotificationPolicyStore()로 테스트/E2E 격리
// ─────────────────────────────────────────────────────────────────────────────

let policyStore: NotificationPolicyFixture[] = [...notificationPolicySeedData]

/**
 * store를 시드 상태로 초기화한다.
 * Vitest 단위 테스트 전용 — E2E는 GET 헤더 트리거를 사용할 것.
 */
export function resetNotificationPolicyStore(): void {
  policyStore = [...notificationPolicySeedData]
}

// ─────────────────────────────────────────────────────────────────────────────
// 중복 판정 키 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 정책의 유니크 조합 키를 반환한다.
 * eventType + recipientRole + channel + projectKey(null/undefined을 "" 통일).
 */
function policyCompositeKey(
  eventType: string,
  recipientRole: string,
  channel: string,
  projectKey: string | null | undefined,
): string {
  return `${eventType}|${recipientRole}|${channel}|${projectKey ?? ''}`
}

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/notification-policies/catalog
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 카탈로그 조회 — 인증만(비-admin도 OK).
 * 성공 → 200 { data: PolicyCatalogResponse }
 */
const getCatalogHandler = http.get('/api/v1/notification-policies/catalog', () => {
  return HttpResponse.json({
    data: {
      eventTypes: CATALOG_EVENT_TYPES,
      recipientRoles: CATALOG_RECIPIENT_ROLES,
      channels: CATALOG_CHANNELS,
    },
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/notification-policies
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 정책 목록 조회 — SYSTEM_ADMIN 전용.
 * X-MSW-Reset-Notification-Policies: true 헤더 포함 시 store를 시드로 reset 후 응답.
 * (E2E 격리용 reset 경로 — B1 BLOCKER 해소, version-handlers:174 선례)
 *
 * 성공 → 200 { data: NotificationPolicyResponse[] }
 */
const listPoliciesHandler = http.get('/api/v1/notification-policies', ({ request }) => {
  if (request.headers.get('X-MSW-Reset-Notification-Policies') === 'true') {
    resetNotificationPolicyStore()
  }
  return HttpResponse.json({ data: [...policyStore] })
})

// ─────────────────────────────────────────────────────────────────────────────
// POST /api/v1/notification-policies
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 정책 생성 — SYSTEM_ADMIN 전용.
 *
 * 에러 분기 순서 (백엔드와 동일).
 * 1. 동일 조합(eventType+recipientRole+channel+projectKey) 중복 → 409 NOTIF_POLICY_DUPLICATE
 * 성공 → 201 { data: NotificationPolicyResponse }
 */
const createPolicyHandler = http.post('/api/v1/notification-policies', async ({ request }) => {
  const body = (await request.clone().json()) as {
    eventType: string
    recipientRole: string
    channel: string
    projectKey?: string | null
    enabled?: boolean
  }

  // 중복 확인 — 동일 조합이면 409
  const newKey = policyCompositeKey(body.eventType, body.recipientRole, body.channel, body.projectKey)
  const duplicate = policyStore.find(
    (p) => policyCompositeKey(p.eventType, p.recipientRole, p.channel, p.projectKey) === newKey,
  )
  if (duplicate !== undefined) {
    return problemDetail(
      409,
      'notif-policy-duplicate',
      'Notification Policy Duplicate',
      'NOTIF_POLICY_DUPLICATE',
      `동일한 조합의 알림 정책이 이미 존재합니다: ${body.eventType}+${body.recipientRole}+${body.channel}`,
    )
  }

  const now = new Date().toISOString()
  const newPolicy: NotificationPolicyFixture = {
    id: generateUuidV4(),
    eventType: body.eventType,
    recipientRole: body.recipientRole,
    channel: body.channel,
    enabled: body.enabled ?? true,
    createdAt: now,
    updatedAt: now,
  }

  // projectKey는 NON_NULL — null/undefined이면 키 자체 생략
  if (body.projectKey != null) {
    newPolicy.projectKey = body.projectKey
  }

  policyStore.push(newPolicy)

  return HttpResponse.json({ data: newPolicy }, { status: 201 })
})

// ─────────────────────────────────────────────────────────────────────────────
// PATCH /api/v1/notification-policies/:id
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 정책 enabled 토글 — SYSTEM_ADMIN 전용.
 * body: { enabled: boolean } — 조합(event/role/channel) 수정 불가.
 * 성공 → 204 No Content
 */
const togglePolicyHandler = http.patch('/api/v1/notification-policies/:id', async ({ request, params }) => {
  const id = params['id'] as string
  const body = (await request.clone().json()) as { enabled: boolean }

  const index = policyStore.findIndex((p) => p.id === id)
  if (index === -1) {
    return problemDetail(
      404,
      'notif-policy-not-found',
      'Notification Policy Not Found',
      'NOTIF_POLICY_NOT_FOUND',
      `알림 정책을 찾을 수 없습니다: ${id}`,
    )
  }

  const existing = policyStore[index]
  if (existing === undefined) {
    return problemDetail(
      404,
      'notif-policy-not-found',
      'Notification Policy Not Found',
      'NOTIF_POLICY_NOT_FOUND',
      `알림 정책을 찾을 수 없습니다: ${id}`,
    )
  }

  policyStore[index] = {
    ...existing,
    enabled: body.enabled,
    updatedAt: new Date().toISOString(),
  }

  return new HttpResponse(null, { status: 204 })
})

// ─────────────────────────────────────────────────────────────────────────────
// DELETE /api/v1/notification-policies/:id
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 정책 삭제 — SYSTEM_ADMIN 전용.
 * 성공 → 204 No Content
 */
const deletePolicyHandler = http.delete('/api/v1/notification-policies/:id', ({ params }) => {
  const id = params['id'] as string

  const index = policyStore.findIndex((p) => p.id === id)
  if (index === -1) {
    return problemDetail(
      404,
      'notif-policy-not-found',
      'Notification Policy Not Found',
      'NOTIF_POLICY_NOT_FOUND',
      `알림 정책을 찾을 수 없습니다: ${id}`,
    )
  }

  policyStore.splice(index, 1)

  return new HttpResponse(null, { status: 204 })
})

// ─────────────────────────────────────────────────────────────────────────────
// 핸들러 집합 export
// ─────────────────────────────────────────────────────────────────────────────

export const notificationPolicyHandlers = [
  getCatalogHandler,
  listPoliciesHandler,
  createPolicyHandler,
  togglePolicyHandler,
  deletePolicyHandler,
]
