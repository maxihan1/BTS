// 사용자 알림 구독 설정 MSW fixture — eventType 미러 × 설정 가능 채널로 파생시킨 매트릭스 시드 데이터
import type { SubscriptionEntry } from '@/api/user-notification-subscriptions'
import { NOTIFICATION_EVENT_TYPES, type NotificationChannel } from '@/api/notification-policies'

// ─────────────────────────────────────────────────────────────────────────────
// eventType — NOTIFICATION_EVENT_TYPES 미러에서 파생 (하드코딩 목록 금지)
//
// 예전에는 이 파일이 wireValue 목록을 따로 들고 있었고, 백엔드에 enum 이 추가돼도
// 아무도 알려주지 않아 목이 조용히 뒤처졌다 (목이 목과만 맞는 상태).
// 미러를 그대로 재사용하면 그 갈라짐 자체가 성립하지 않는다.
// 백엔드 정본은 NotificationEventType.kt 이고, 미러 ↔ 정본 정합은
// api/notification-policies.test.ts 가 지킨다.
// ─────────────────────────────────────────────────────────────────────────────

/** 구독 설정 eventType wireValue — NOTIFICATION_EVENT_TYPES 미러와 항상 동일 */
export const SUBSCRIPTION_EVENT_TYPES = NOTIFICATION_EVENT_TYPES

// ─────────────────────────────────────────────────────────────────────────────
// channel — 미러 전체가 아니라 "설정 가능" 부분집합
//
// ⚠️ CHANNELS 미러는 5종(EMAIL/IN_APP/SLACK/TEAMS/WEBHOOK)이지만 구독 매트릭스가 쓰는
//   화이트리스트는 UserSubscription.CONFIGURABLE_CHANNELS = setOf(IN_APP, EMAIL) 2종뿐이다.
//   전체 미러에서 파생시키면 셀 수가 부풀어 실제 응답과 어긋난다 — 부분집합이 맞다.
//   satisfies 로 각 원소가 실재하는 채널 이름인지는 타입 검사에 맡긴다.
//   순서는 UserSubscriptionService.ORDERED_CONFIGURABLE_CHANNELS 와 동일하게 (IN_APP, EMAIL).
// ─────────────────────────────────────────────────────────────────────────────

/** 구독 설정 가능 채널 2종 — 백엔드 CONFIGURABLE_CHANNELS 부분집합 */
export const SUBSCRIPTION_CHANNELS = [
  'IN_APP',
  'EMAIL',
] as const satisfies readonly NotificationChannel[]

// ─────────────────────────────────────────────────────────────────────────────
// 시드 데이터 — eventType × channel 전수 곱, 기본값 enabled=true (opt-out 기본 원칙)
// 순서는 백엔드 getMatrix 와 동일하게 eventType 선언 순서 × (IN_APP, EMAIL).
// ─────────────────────────────────────────────────────────────────────────────

/** 구독 설정 시드 데이터 — eventType × channel 전 조합, enabled 전부 true */
export const subscriptionSeedData: SubscriptionEntry[] = SUBSCRIPTION_EVENT_TYPES.flatMap(
  (eventType) =>
    SUBSCRIPTION_CHANNELS.map((channel) => ({
      eventType,
      channel,
      enabled: true,
    })),
)
