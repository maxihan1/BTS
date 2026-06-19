// 사용자 알림 구독 설정 매트릭스 컴포넌트 — data-driven 행/열, 셀 단위 토글 (FR-NT-04)
import type { JSX } from 'react'
import {
  useUserNotificationSubscriptions,
  useUpdateUserNotificationSubscriptions,
} from '@/api/useUserNotificationSubscriptions'
import type { SubscriptionEntry } from '@/api/user-notification-subscriptions'
import {
  eventTypeLabels,
  channelLabels,
  labelFor,
} from '@/i18n/notification-policy-labels'
import { notificationSubscriptionStrings } from '@/i18n/ko'

// ─────────────────────────────────────────────────────────────────────────────
// 내부 헬퍼 — 매트릭스 도출
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 구독 설정 배열에서 distinct 이벤트 타입 목록을 안정 정렬로 도출한다.
 * 응답 순서가 안정적임을 전제(백엔드가 정렬 보장). 순서 보존을 위해 Set + filter 사용.
 */
function deriveEventTypes(subscriptions: readonly SubscriptionEntry[]): string[] {
  const seen = new Set<string>()
  const result: string[] = []
  for (const entry of subscriptions) {
    if (!seen.has(entry.eventType)) {
      seen.add(entry.eventType)
      result.push(entry.eventType)
    }
  }
  return result
}

/**
 * 구독 설정 배열에서 distinct 채널 목록을 안정 정렬로 도출한다.
 * 응답 순서가 안정적임을 전제.
 */
function deriveChannels(subscriptions: readonly SubscriptionEntry[]): string[] {
  const seen = new Set<string>()
  const result: string[] = []
  for (const entry of subscriptions) {
    if (!seen.has(entry.channel)) {
      seen.add(entry.channel)
      result.push(entry.channel)
    }
  }
  return result
}

/**
 * (eventType, channel) 조합의 enabled 값을 빠르게 조회하기 위한 Map 생성.
 */
function buildLookup(subscriptions: readonly SubscriptionEntry[]): Map<string, boolean> {
  const map = new Map<string, boolean>()
  for (const entry of subscriptions) {
    map.set(`${entry.eventType}|${entry.channel}`, entry.enabled)
  }
  return map
}

// ─────────────────────────────────────────────────────────────────────────────
// 셀 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

interface SubscriptionCellProps {
  /** 이벤트 타입 wireValue */
  readonly eventType: string
  /** 채널 enum NAME */
  readonly channel: string
  /** 현재 활성 여부 */
  readonly enabled: boolean
  /** mutation 진행 중 여부 — true이면 비활성화 */
  readonly isMutating: boolean
  /** 토글 핸들러 */
  readonly onToggle: (eventType: string, channel: string, nextEnabled: boolean) => void
}

/**
 * 매트릭스 단일 셀 — 체크박스 토글.
 * aria-label에 이벤트+채널 컨텍스트를 포함해 a11y 견고성 확보.
 */
function SubscriptionCell({
  eventType,
  channel,
  enabled,
  isMutating,
  onToggle,
}: SubscriptionCellProps): JSX.Element {
  const eventLabel = labelFor(eventTypeLabels, eventType)
  const channelLabel = labelFor(channelLabels, channel)
  // issue.mentioned는 notification-policy-labels에 없으므로 ko.ts 추가 라벨로 보완
  const resolvedEventLabel =
    eventType === 'issue.mentioned'
      ? notificationSubscriptionStrings.eventIssueMentioned
      : eventLabel
  const ariaLabel = `${resolvedEventLabel} ${channelLabel} 알림 ${enabled ? '켜짐' : '꺼짐'}`

  return (
    <td className="px-4 py-2 text-center">
      <input
        type="checkbox"
        role="checkbox"
        checked={enabled}
        disabled={isMutating}
        aria-label={ariaLabel}
        className="h-4 w-4 cursor-pointer accent-primary"
        onChange={() => {
          onToggle(eventType, channel, !enabled)
        }}
      />
    </td>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 매트릭스 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 사용자 알림 구독 설정 매트릭스.
 *
 * - GET 응답에서 distinct 이벤트(행)·채널(열)을 도출해 data-driven 렌더 (C1 반영).
 * - 채널 하드코딩 없음 — 응답 셀에서 추출. 백엔드 채널 변경에 자동 적응.
 * - 셀 토글 시 해당 셀 1건만 PATCH (mutation).
 * - 로딩/에러 상태 처리. data !== undefined 가드 (ui-permission-gating 로딩윈도우 교훈).
 */
export function NotificationSubscriptionMatrix(): JSX.Element {
  const { data, isLoading, isError } = useUserNotificationSubscriptions()
  const { mutate, isPending } = useUpdateUserNotificationSubscriptions()

  if (isLoading) {
    return (
      <p className="text-sm text-muted-foreground">
        {notificationSubscriptionStrings.loading}
      </p>
    )
  }

  if (isError || data === undefined) {
    return (
      <p className="text-sm text-destructive">
        {notificationSubscriptionStrings.errorGeneric}
      </p>
    )
  }

  const { subscriptions } = data
  const eventTypes = deriveEventTypes(subscriptions)
  const channels = deriveChannels(subscriptions)
  const lookup = buildLookup(subscriptions)

  function handleToggle(eventType: string, channel: string, nextEnabled: boolean): void {
    mutate([{ eventType, channel, enabled: nextEnabled }])
  }

  return (
    <div className="overflow-x-auto rounded-md border">
      <table className="w-full border-collapse text-sm" role="table">
        <thead>
          <tr className="border-b bg-muted/50 text-left text-xs font-medium text-muted-foreground">
            <th className="px-4 py-2 text-left">
              {notificationSubscriptionStrings.columnEvent}
            </th>
            {channels.map((channel) => (
              <th key={channel} className="px-4 py-2 text-center">
                {labelFor(channelLabels, channel)}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {eventTypes.map((eventType) => {
            const eventLabel =
              eventType === 'issue.mentioned'
                ? notificationSubscriptionStrings.eventIssueMentioned
                : labelFor(eventTypeLabels, eventType)

            return (
              <tr
                key={eventType}
                className="border-b text-sm hover:bg-muted/50"
              >
                <td className="px-4 py-2 font-medium whitespace-nowrap">
                  {eventLabel}
                </td>
                {channels.map((channel) => {
                  const enabled = lookup.get(`${eventType}|${channel}`) ?? false

                  return (
                    <SubscriptionCell
                      key={channel}
                      eventType={eventType}
                      channel={channel}
                      enabled={enabled}
                      isMutating={isPending}
                      onToggle={handleToggle}
                    />
                  )
                })}
              </tr>
            )
          })}
        </tbody>
      </table>
    </div>
  )
}
