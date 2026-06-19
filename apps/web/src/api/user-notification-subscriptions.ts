// 사용자 알림 구독 설정 API 클라이언트 + Zod 스키마 — GET/PATCH /api/v1/users/me/notifications
import { z } from 'zod'
import { apiFetch, apiGet, ApiError } from './client'
import { readXsrfToken } from './sessions'

// ─────────────────────────────────────────────────────────────────────────────
// Zod 스키마 — 백엔드 UserNotificationSubscriptionResponse DTO 1:1 정합
//
// ⚠️ channel/eventType은 z.string() — enum 강제 안 함.
//   백엔드가 400으로 검증하므로 프론트는 전방호환성을 위해 문자열 그대로 수신한다.
//
// 응답 형태:
//   { "data": { "subscriptions": [ { eventType, channel, enabled }, ... ] } }
//   항상 20개: 10 eventType × { IN_APP, EMAIL }
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 구독 설정 단건 Zod 스키마.
 * 백엔드 UserNotificationSubscriptionEntry DTO 1:1 정합.
 */
export const subscriptionEntrySchema = z.object({
  /** 이벤트 타입 wireValue (예: "issue.commented") — z.string() 전방호환 */
  eventType: z.string(),
  /** 채널 enum NAME (예: "IN_APP" | "EMAIL") — z.string() 전방호환 */
  channel: z.string(),
  /** 구독 활성 여부 */
  enabled: z.boolean(),
})

/**
 * 구독 설정 전체 매트릭스 Zod 스키마.
 * subscriptions 배열: 항상 20개 (10 eventType × 2 channel).
 */
export const subscriptionMatrixSchema = z.object({
  subscriptions: z.array(subscriptionEntrySchema),
})

/** 구독 설정 단건 타입 — z.infer 자동 추론 */
export type SubscriptionEntry = z.infer<typeof subscriptionEntrySchema>

/** 구독 설정 전체 매트릭스 타입 — z.infer 자동 추론 */
export type SubscriptionMatrix = z.infer<typeof subscriptionMatrixSchema>

// ─────────────────────────────────────────────────────────────────────────────
// DataResponse 래퍼 헬퍼 — { data: T } 형태를 unwrap
// notification-policies.ts 동일 패턴 (같은 notification BC)
// ─────────────────────────────────────────────────────────────────────────────

function dataWrapper<T>(schema: z.ZodSchema<T>) {
  return z.object({ data: schema })
}

// ─────────────────────────────────────────────────────────────────────────────
// API 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 현재 인증 사용자의 알림 구독 설정 전체 매트릭스를 조회한다.
 *
 * `GET /api/v1/users/me/notifications`
 * - 읽기 요청이므로 CSRF 헤더 불요 — apiGet 사용.
 * - 응답: { data: { subscriptions: [...] } } — 항상 20개 (10 이벤트 × 2 채널).
 *
 * @returns SubscriptionMatrix (20개 셀)
 * @throws ApiError(401) 미인증
 */
export async function getSubscriptions(): Promise<SubscriptionMatrix> {
  const wrapper = dataWrapper(subscriptionMatrixSchema)
  const res = await apiGet('/api/v1/users/me/notifications', wrapper)
  return res.data
}

/**
 * 현재 인증 사용자의 알림 구독 설정을 일괄 갱신한다.
 *
 * `PATCH /api/v1/users/me/notifications`
 * - 상태 변경 요청이므로 X-XSRF-TOKEN 헤더를 포함한다 (double submit cookie 패턴).
 * - 요청 body: { subscriptions: SubscriptionEntry[] }
 * - 응답: 갱신된 전체 매트릭스 (동일 { data: { subscriptions: [...] } } 형태).
 *
 * @param entries 갱신할 구독 설정 배열 (부분 전달 가능 — 백엔드가 나머지 유지)
 * @returns 갱신 후 전체 SubscriptionMatrix
 * @throws ApiError(400) 미지원 eventType/channel
 * @throws ApiError(401) 미인증
 */
export async function patchSubscriptions(entries: SubscriptionEntry[]): Promise<SubscriptionMatrix> {
  const res = await apiFetch('/api/v1/users/me/notifications', {
    method: 'PATCH',
    body: { subscriptions: entries },
    headers: {
      'X-XSRF-TOKEN': readXsrfToken(),
    },
  })
  if (!res.ok) {
    const errorBody: unknown = await res.json().catch(() => ({}))
    throw new ApiError(res.status, errorBody)
  }
  const parsed: unknown = await res.json()
  return dataWrapper(subscriptionMatrixSchema).parse(parsed).data
}
