// 알림 정책 API 클라이언트 + Zod 스키마 + enum 미러
import { z } from 'zod'
import { apiFetch, apiGet, ApiError } from './client'
import { readXsrfToken } from './sessions'

// ─────────────────────────────────────────────────────────────────────────────
// 백엔드 enum 미러 (as const) — 백엔드 NotificationEventType.kt / RecipientRole.kt / NotificationChannel.kt 와 동기화
// enum 추가 시 이 배열 + notification-policy-labels.ts 라벨 맵도 함께 갱신할 것
//
// ⚠️ 에러 계약 주의:
//   notification BC는 RFC 7807 ProblemDetail에 대문자 errorCode를 사용한다
//   (예: 'NOTIF_POLICY_DUPLICATE'). mfa.ts 등 다른 BC의 소문자 코드 패턴과 다르다.
//   409 판정: ApiError.status===409 또는 ApiError.body.errorCode==='NOTIF_POLICY_DUPLICATE'
//   소문자 패턴(body.error) 복붙 금지 — error-key drift 가짜그린 방지 (learnings 2026-04-29)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 백엔드 NotificationEventType enum의 프론트 미러 (wireValue 형식).
 * 서버에서 "issue.created" 형태로 내려옴 — enum NAME이 아닌 wireValue.
 * 라벨 키 출처 + 카탈로그 select 옵션으로 사용한다.
 */
export const NOTIFICATION_EVENT_TYPES = [
  'issue.created',
  'issue.assigned',
  'issue.transitioned',
  'issue.commented',
  'issue.due_soon',
  'issue.overdue',
  'sprint.started',
  'sprint.ended',
  'automation.failed',
] as const

/** 알려진 이벤트 타입 유니온 */
export type NotificationEventType = (typeof NOTIFICATION_EVENT_TYPES)[number]

/**
 * 백엔드 RecipientRole enum의 프론트 미러 (enum NAME 형식).
 * 서버에서 "REPORTER" 형태로 내려옴.
 */
export const RECIPIENT_ROLES = [
  'REPORTER',
  'ASSIGNEE',
  'PREVIOUS_ASSIGNEE',
  'WATCHER',
  'COMPONENT_LEAD',
  'MENTIONED',
  'PROJECT_MEMBER',
  'RULE_OWNER',
  'PROJECT_ADMIN',
] as const

/** 알려진 수신자 역할 유니온 */
export type RecipientRole = (typeof RECIPIENT_ROLES)[number]

/**
 * 백엔드 NotificationChannel enum의 프론트 미러 (enum NAME 형식).
 * 서버에서 "EMAIL" 형태로 내려옴.
 */
export const CHANNELS = [
  'EMAIL',
  'IN_APP',
  'SLACK',
  'TEAMS',
  'WEBHOOK',
] as const

/** 알려진 채널 유니온 */
export type NotificationChannel = (typeof CHANNELS)[number]

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 — 백엔드 NotificationPolicyResponse DTO 1:1 정합
// @JsonInclude(NON_NULL) 적용 — null 필드는 JSON에서 키 자체가 생략됨
// → projectKey는 반드시 .nullish() (optional + nullable)
//   .nullable()만 쓰면 키 부재 시 파싱 실패 (audit-logs.ts 동일 패턴)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 알림 정책 단건 응답 Zod 스키마.
 *
 * - projectKey는 .nullish() 필수.
 *   @JsonInclude(NON_NULL)로 null이면 키 자체가 응답에 없으므로 optional도 포함해야 함.
 * - eventType/recipientRole/channel은 z.string() — 백엔드 enum 추가 시 파싱 무파손.
 */
export const notificationPolicySchema = z.object({
  /** 정책 ID (UUID) */
  id: z.string().uuid(),
  /**
   * 프로젝트 키 — null이면 전역 정책.
   * @JsonInclude(NON_NULL)로 null 시 키 자체 생략됨 → .nullish() 필수.
   */
  projectKey: z.string().nullish(),
  /** 이벤트 타입 wireValue (예: "issue.created") — z.string() 전방호환 */
  eventType: z.string(),
  /** 수신자 역할 enum NAME (예: "REPORTER") — z.string() 전방호환 */
  recipientRole: z.string(),
  /** 채널 enum NAME (예: "EMAIL") — z.string() 전방호환 */
  channel: z.string(),
  /** 활성 여부 */
  enabled: z.boolean(),
  /** 생성 시각 (ISO 8601) */
  createdAt: z.string(),
  /** 수정 시각 (ISO 8601) */
  updatedAt: z.string(),
})

/** 알림 정책 단건 타입 — z.infer 자동 추론 */
export type NotificationPolicy = z.infer<typeof notificationPolicySchema>

/**
 * 이벤트 타입 카탈로그 항목 Zod 스키마.
 * publishable=true이면 외부 채널(EMAIL/SLACK 등) 발송 가능.
 */
export const eventTypeCatalogItemSchema = z.object({
  /** wireValue (예: "issue.created") */
  value: z.string(),
  /** 외부 채널 발송 가능 여부 */
  publishable: z.boolean(),
})

/**
 * 정책 카탈로그 응답 Zod 스키마.
 * 서버 enum이 진실 출처 — 프론트 미러와 달라지면 카탈로그 우선.
 */
export const policyCatalogSchema = z.object({
  eventTypes: z.array(eventTypeCatalogItemSchema),
  recipientRoles: z.array(z.string()),
  channels: z.array(z.string()),
})

/** 정책 카탈로그 타입 */
export type PolicyCatalog = z.infer<typeof policyCatalogSchema>

/** DataResponse 래퍼 파싱 헬퍼 — `{ data: T }` 형태를 unwrap */
function dataWrapper<T>(schema: z.ZodSchema<T>) {
  return z.object({ data: schema })
}

// ─────────────────────────────────────────────────────────────────────────────
// 요청 인터페이스
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 알림 정책 생성 요청 바디.
 * D1 = 전역만 → projectKey 생략 가능 (undefined이면 서버가 null=전역으로 처리).
 */
export interface CreatePolicyRequest {
  /** 이벤트 타입 wireValue (NotBlank) */
  eventType: string
  /** 수신자 역할 enum NAME (NotBlank) */
  recipientRole: string
  /** 채널 enum NAME (NotBlank) */
  channel: string
  /** 활성 여부 (기본 true) */
  enabled: boolean
  /** 프로젝트 키 — 생략 시 전역 정책 (D1=전역만) */
  projectKey?: string
}

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 알림 정책 카탈로그를 조회한다 — 선택 가능한 eventType/recipientRole/channel 목록.
 *
 * `GET /api/v1/notification-policies/catalog`
 * - 읽기 요청이므로 CSRF 헤더 불요 — apiGet 사용.
 * - 인증만 필요 (비-admin도 OK).
 *
 * @returns PolicyCatalog (eventTypes, recipientRoles, channels)
 * @throws ApiError(401) 미인증
 */
export async function fetchCatalog(): Promise<PolicyCatalog> {
  const wrapper = dataWrapper(policyCatalogSchema)
  const res = await apiGet('/api/v1/notification-policies/catalog', wrapper)
  return res.data
}

/**
 * 전역 알림 정책 목록을 조회한다.
 *
 * `GET /api/v1/notification-policies`
 * - 읽기 요청이므로 CSRF 헤더 불요 — apiGet 사용.
 * - D1 = 전역만 → projectKey 파라미터 없이 호출.
 * - SYSTEM_ADMIN 전용.
 *
 * @returns NotificationPolicy[] 전역 정책 배열
 * @throws ApiError(403) 비관리자
 * @throws ApiError(401) 미인증
 */
export async function fetchPolicies(): Promise<NotificationPolicy[]> {
  const wrapper = dataWrapper(z.array(notificationPolicySchema))
  const res = await apiGet('/api/v1/notification-policies', wrapper)
  return res.data
}

/**
 * 알림 정책을 생성한다.
 *
 * `POST /api/v1/notification-policies`
 * - 상태 변경 요청이므로 X-XSRF-TOKEN 헤더를 포함한다 (double submit cookie 패턴).
 * - 201 Created 응답을 파싱해 반환.
 * - SYSTEM_ADMIN 전용.
 *
 * @param body 생성 요청 바디 (eventType/recipientRole/channel/enabled[/projectKey])
 * @returns NotificationPolicy 생성된 정책
 * @throws ApiError(409) 동일 조합 중복 — errorCode: 'NOTIF_POLICY_DUPLICATE' (대문자)
 * @throws ApiError(400) 유효성 실패 — errorCode: 'NOTIF_VALIDATION_FAILED' 또는 'NOTIF_INVALID_ENUM'
 * @throws ApiError(403) 비관리자
 * @throws ApiError(401) 미인증
 */
export async function createPolicy(body: CreatePolicyRequest): Promise<NotificationPolicy> {
  const res = await apiFetch('/api/v1/notification-policies', {
    method: 'POST',
    body,
    headers: {
      'X-XSRF-TOKEN': readXsrfToken(),
    },
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const parsed: unknown = await res.json()
  return dataWrapper(notificationPolicySchema).parse(parsed).data
}

/**
 * 알림 정책의 활성/비활성을 토글한다.
 *
 * `PATCH /api/v1/notification-policies/{id}`
 * - 상태 변경 요청이므로 X-XSRF-TOKEN 헤더를 포함한다.
 * - 204 No Content 성공 — Zod parse 없이 반환.
 * - ⚠️ enabled만 변경 가능. 조합(eventType/recipientRole/channel) 수정은 삭제 후 재생성.
 * - SYSTEM_ADMIN 전용.
 *
 * @param id 정책 UUID
 * @param enabled 변경할 활성 여부
 * @returns void
 * @throws ApiError(404) 정책 미존재 — errorCode: 'NOTIF_POLICY_NOT_FOUND'
 * @throws ApiError(403) 비관리자
 * @throws ApiError(401) 미인증
 */
export async function togglePolicy(id: string, enabled: boolean): Promise<void> {
  const res = await apiFetch(`/api/v1/notification-policies/${id}`, {
    method: 'PATCH',
    body: { enabled },
    headers: {
      'X-XSRF-TOKEN': readXsrfToken(),
    },
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
}

/**
 * 알림 정책을 삭제한다.
 *
 * `DELETE /api/v1/notification-policies/{id}`
 * - 상태 변경 요청이므로 X-XSRF-TOKEN 헤더를 포함한다.
 * - 204 No Content 성공 — Zod parse 없이 반환.
 * - SYSTEM_ADMIN 전용.
 *
 * @param id 정책 UUID
 * @returns void
 * @throws ApiError(404) 정책 미존재 — errorCode: 'NOTIF_POLICY_NOT_FOUND'
 * @throws ApiError(403) 비관리자
 * @throws ApiError(401) 미인증
 */
export async function deletePolicy(id: string): Promise<void> {
  const res = await apiFetch(`/api/v1/notification-policies/${id}`, {
    method: 'DELETE',
    headers: {
      'X-XSRF-TOKEN': readXsrfToken(),
    },
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
}
