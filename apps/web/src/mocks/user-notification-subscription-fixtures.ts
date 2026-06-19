// 사용자 알림 구독 설정 MSW fixture — 20셀 (10 eventType × 2 channel) 시드 데이터
import type { SubscriptionEntry } from '@/api/user-notification-subscriptions'

// ─────────────────────────────────────────────────────────────────────────────
// 지원 eventType 10종 — 백엔드 NotificationEventType enum 1:1 정합
// ─────────────────────────────────────────────────────────────────────────────

/** 구독 설정 eventType wireValue 10종 */
export const SUBSCRIPTION_EVENT_TYPES = [
  'issue.created',
  'issue.assigned',
  'issue.transitioned',
  'issue.commented',
  'issue.due_soon',
  'issue.overdue',
  'sprint.started',
  'sprint.ended',
  'automation.failed',
  'issue.mentioned',
] as const

/** 구독 설정 채널 2종 */
export const SUBSCRIPTION_CHANNELS = ['IN_APP', 'EMAIL'] as const

// ─────────────────────────────────────────────────────────────────────────────
// 시드 데이터 — 20셀 (10 eventType × 2 channel), 기본값 enabled=true
// ─────────────────────────────────────────────────────────────────────────────

/** 구독 설정 20셀 시드 데이터 — enabled 전부 true */
export const subscriptionSeedData: SubscriptionEntry[] = SUBSCRIPTION_EVENT_TYPES.flatMap(
  (eventType) =>
    SUBSCRIPTION_CHANNELS.map((channel) => ({
      eventType,
      channel,
      enabled: true,
    })),
)
