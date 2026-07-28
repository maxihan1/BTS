// 사용자 알림 구독 MSW 픽스처가 백엔드 구독 매트릭스 계약과 어긋나지 않도록 강제하는 테스트
import { describe, expect, it } from 'vitest'
import { NOTIFICATION_EVENT_TYPES } from '@/api/notification-policies'
import {
  SUBSCRIPTION_CHANNELS,
  SUBSCRIPTION_EVENT_TYPES,
  subscriptionSeedData,
} from '../user-notification-subscription-fixtures'

// ─────────────────────────────────────────────────────────────────────────────
// 백엔드 정본 스냅샷 — 2026-07-28 실측
//
// eventType 11종.
//   backend/modules/notification/src/main/kotlin/com/bts/notification/domain/NotificationEventType.kt
//   (enum 상수의 wireValue, 선언 순서 그대로)
//
// channel 2종.
//   UserSubscription.CONFIGURABLE_CHANNELS = setOf(Channel.IN_APP, Channel.EMAIL)
//   Channel enum 자체는 5종(EMAIL/IN_APP/SLACK/TEAMS/WEBHOOK)이지만 구독 매트릭스가 쓰는
//   화이트리스트는 2종뿐이다. 둘을 혼동하면 셀 수가 어긋난다.
//
// 매트릭스 = UserSubscriptionService.getMatrix 가
//   NotificationEventType.entries × ORDERED_CONFIGURABLE_CHANNELS 로 만든 11 × 2 = 22셀.
// ─────────────────────────────────────────────────────────────────────────────

const BACKEND_EVENT_TYPE_WIRE_VALUES: readonly string[] = [
  'issue.created',
  'issue.assigned',
  'issue.transitioned',
  'issue.commented',
  'issue.comment_deleted',
  'issue.due_soon',
  'issue.overdue',
  'sprint.started',
  'sprint.ended',
  'automation.failed',
  'issue.mentioned',
]

const BACKEND_CONFIGURABLE_CHANNELS: readonly string[] = ['IN_APP', 'EMAIL']

const BACKEND_MATRIX_CELL_COUNT =
  BACKEND_EVENT_TYPE_WIRE_VALUES.length * BACKEND_CONFIGURABLE_CHANNELS.length

/** 차집합 헬퍼 — 어느 쪽이 남는지 실패 메시지에 그대로 드러낸다. */
function difference(a: readonly string[], b: readonly string[]): string[] {
  const bSet = new Set(b)
  return a.filter((value) => !bSet.has(value))
}

// ─────────────────────────────────────────────────────────────────────────────
// F1 — 픽스처 ↔ NOTIFICATION_EVENT_TYPES 미러 양방향 정합
//
// 한쪽 방향만 보면 "부분집합"이 통과해 버린다 (미러 9종 ⊂ 픽스처 10종이 실제로 그랬다).
// 삭제·추가 양쪽을 모두 막으려면 차집합을 양방향으로 판정해야 한다.
// ─────────────────────────────────────────────────────────────────────────────

describe('F1 픽스처 eventType ↔ NOTIFICATION_EVENT_TYPES 미러', () => {
  const mirror: readonly string[] = NOTIFICATION_EVENT_TYPES
  const fixture: readonly string[] = SUBSCRIPTION_EVENT_TYPES

  it('F1a: 미러에 있는데 픽스처에 없는 eventType 이 없다', () => {
    expect(difference(mirror, fixture)).toEqual([])
  })

  it('F1b: 픽스처에 있는데 미러에 없는 eventType 이 없다', () => {
    expect(difference(fixture, mirror)).toEqual([])
  })

  it('F1c: 개수가 같다', () => {
    expect(fixture).toHaveLength(mirror.length)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// F2 — 픽스처 ↔ 백엔드 정본 정합 (실제 서버 응답과 어긋나지 않는지)
// ─────────────────────────────────────────────────────────────────────────────

describe('F2 픽스처 ↔ 백엔드 NotificationEventType 정본', () => {
  const fixture: readonly string[] = SUBSCRIPTION_EVENT_TYPES

  it('F2a: 백엔드 eventType 11종이 픽스처에 모두 있다', () => {
    expect(difference(BACKEND_EVENT_TYPE_WIRE_VALUES, fixture)).toEqual([])
  })

  it('F2b: 백엔드에 없는 eventType 이 픽스처에 없다', () => {
    expect(difference(fixture, BACKEND_EVENT_TYPE_WIRE_VALUES)).toEqual([])
  })

  it('F2c: 채널 화이트리스트가 백엔드 CONFIGURABLE_CHANNELS 와 같다 (순서 포함)', () => {
    expect([...SUBSCRIPTION_CHANNELS]).toEqual([...BACKEND_CONFIGURABLE_CHANNELS])
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// F3 — 시드 매트릭스 구조
// ─────────────────────────────────────────────────────────────────────────────

describe('F3 시드 매트릭스 구조', () => {
  it('F3a: 셀 수가 백엔드 매트릭스와 같다 (11 × 2 = 22)', () => {
    expect(subscriptionSeedData).toHaveLength(BACKEND_MATRIX_CELL_COUNT)
  })

  it('F3b: 셀 수가 eventType × channel 곱과 같다', () => {
    expect(subscriptionSeedData).toHaveLength(
      SUBSCRIPTION_EVENT_TYPES.length * SUBSCRIPTION_CHANNELS.length,
    )
  })

  it('F3c: (eventType, channel) 조합이 중복 없이 전부 한 번씩 나온다', () => {
    const seen = subscriptionSeedData.map((cell) => `${cell.eventType}|${cell.channel}`)
    const expected = BACKEND_EVENT_TYPE_WIRE_VALUES.flatMap((eventType) =>
      BACKEND_CONFIGURABLE_CHANNELS.map((channel) => `${eventType}|${channel}`),
    )

    expect(new Set(seen).size).toBe(seen.length)
    expect([...seen].sort()).toEqual([...expected].sort())
  })

  it('F3d: 시드 기본값은 전부 enabled=true (opt-out 기본 원칙)', () => {
    expect(subscriptionSeedData.every((cell) => cell.enabled)).toBe(true)
  })
})
