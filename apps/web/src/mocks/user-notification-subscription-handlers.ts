// 사용자 알림 구독 설정 MSW stateful 핸들러 — GET/PATCH + E2E reset 헤더
import { http, HttpResponse } from 'msw'
import type { SubscriptionEntry } from '@/api/user-notification-subscriptions'
import { subscriptionSeedData } from './user-notification-subscription-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 모듈 스코프 가변 store — resetUserNotificationSubscriptionStore()로 테스트/E2E 격리
// notification-policy-handlers.ts 동형 패턴
// ─────────────────────────────────────────────────────────────────────────────

function buildSeedStore(): SubscriptionEntry[] {
  return subscriptionSeedData.map((e) => ({ ...e }))
}

let subscriptionStore: SubscriptionEntry[] = buildSeedStore()

/**
 * store를 시드 상태로 초기화한다.
 * Vitest 단위 테스트 전용 — E2E는 GET 헤더 트리거를 사용할 것.
 * (msw-mutation-stateful-refetch, msw-derived-behavior-shared-store)
 */
export function resetUserNotificationSubscriptionStore(): void {
  subscriptionStore = buildSeedStore()
}

// ─────────────────────────────────────────────────────────────────────────────
// RFC 7807 ProblemDetail 헬퍼 — notification BC 규칙 (대문자 errorCode)
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

// ─────────────────────────────────────────────────────────────────────────────
// GET /api/v1/users/me/notifications
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 사용자 알림 구독 설정 조회.
 * X-MSW-Reset-User-Notification-Subscriptions: true 헤더 포함 시 store를 시드로 reset 후 응답.
 * (E2E 격리용 reset 경로 — notification-policy-handlers:listPoliciesHandler 선례)
 *
 * 성공 → 200 { data: { subscriptions: SubscriptionEntry[] } }
 */
const getSubscriptionsHandler = http.get('/api/v1/users/me/notifications', ({ request }) => {
  if (request.headers.get('X-MSW-Reset-User-Notification-Subscriptions') === 'true') {
    resetUserNotificationSubscriptionStore()
  }
  return HttpResponse.json({ data: { subscriptions: [...subscriptionStore] } })
})

// ─────────────────────────────────────────────────────────────────────────────
// PATCH /api/v1/users/me/notifications
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 사용자 알림 구독 설정 일괄 갱신.
 * - CSRF 헤더(X-XSRF-TOKEN) 없으면 → 403 (가짜그린 차단)
 * - body: { subscriptions: SubscriptionEntry[] }
 * - upsert: eventType+channel 조합으로 매칭, 없으면 무시
 * - 성공 → 200 { data: { subscriptions: [...] } } 갱신된 전체 매트릭스
 */
const patchSubscriptionsHandler = http.patch('/api/v1/users/me/notifications', async ({ request }) => {
  // CSRF 검사 — X-XSRF-TOKEN 헤더 없으면 403
  const csrfToken = request.headers.get('X-XSRF-TOKEN')
  if (csrfToken === null || csrfToken === '') {
    return problemDetail(
      403,
      'csrf-token-missing',
      'CSRF Token Missing',
      'CSRF_TOKEN_MISSING',
      'X-XSRF-TOKEN 헤더가 없습니다.',
    )
  }

  const body = (await request.clone().json()) as { subscriptions: SubscriptionEntry[] }
  const entries = body.subscriptions

  // upsert: eventType+channel 조합으로 store 갱신
  for (const entry of entries) {
    const idx = subscriptionStore.findIndex(
      (s) => s.eventType === entry.eventType && s.channel === entry.channel,
    )
    if (idx !== -1) {
      subscriptionStore[idx] = { ...entry }
    }
  }

  return HttpResponse.json({ data: { subscriptions: [...subscriptionStore] } })
})

// ─────────────────────────────────────────────────────────────────────────────
// 핸들러 집합 export
// ─────────────────────────────────────────────────────────────────────────────

export const userNotificationSubscriptionHandlers = [
  getSubscriptionsHandler,
  patchSubscriptionsHandler,
]
