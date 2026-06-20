// 알림 정책 생성 폼 컴포넌트 — select 3종(이벤트/수신자/채널) + 제출 (FR-NT-01 Task 6)
import type { JSX } from 'react'
import { useState } from 'react'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { Button } from '@/components/ui/button'
import type { PolicyCatalog } from '@/api/notification-policies'
import { UNSUPPORTED_RECIPIENT_ROLES } from '@/api/notification-policies'
import {
  eventTypeLabels,
  recipientRoleLabels,
  recipientRoleDescriptions,
  channelLabels,
  labelFor,
  notificationPolicyLabels,
} from '@/i18n/notification-policy-labels'

/** 미지원 역할 Set — 모듈 수준 상수(매 렌더마다 재생성 방지). */
const UNSUPPORTED_ROLE_SET = new Set<string>(UNSUPPORTED_RECIPIENT_ROLES)

// ─────────────────────────────────────────────────────────────────────────────
// 타입
// ─────────────────────────────────────────────────────────────────────────────

/** 폼 제출 페이로드 — enabled는 항상 true(기본값), 토글은 Table에서 별도 처리 */
interface PolicyFormPayload {
  /** 이벤트 타입 wireValue (예: "issue.created") */
  eventType: string
  /** 수신자 역할 enum NAME (예: "REPORTER") */
  recipientRole: string
  /** 채널 enum NAME (예: "EMAIL") */
  channel: string
}

interface NotificationPolicyFormProps {
  /** 서버 enum을 진실 출처로 하는 선택 가능 옵션 카탈로그 */
  readonly catalog: PolicyCatalog
  /**
   * 추가 버튼 클릭 시 호출되는 콜백.
   * enabled는 기본 true — 부모(페이지)가 createPolicy 호출 시 주입한다.
   */
  readonly onSubmit: (payload: PolicyFormPayload) => void
  /**
   * 부모가 내려주는 에러 메시지.
   * 예: 409 중복(NOTIF_POLICY_DUPLICATE) 발생 시 부모가 i18n 변환 후 전달.
   * 폼은 표시만 담당(소유구조 dead-path 회피 — memory: dialog-submiterror-ownership-dead-path).
   */
  readonly submitError?: string
  /** 제출 진행 중 여부 — true이면 추가 버튼 비활성 */
  readonly isSubmitting: boolean
}

// ─────────────────────────────────────────────────────────────────────────────
// 헬퍼 — 카탈로그 → SelectItem 배열
// ─────────────────────────────────────────────────────────────────────────────

/** 이벤트 타입 카탈로그 → SelectItem 배열. 미지 값은 원문 fallback. */
function buildEventTypeOptions(eventTypes: PolicyCatalog['eventTypes']): JSX.Element[] {
  return eventTypes.map((item) => (
    <SelectItem key={item.value} value={item.value}>
      {labelFor(eventTypeLabels, item.value)}
    </SelectItem>
  ))
}

/** 수신자 역할 카탈로그 → SelectItem 배열. 미지원 역할은 disabled + 접미사. */
function buildRecipientRoleOptions(recipientRoles: PolicyCatalog['recipientRoles']): JSX.Element[] {
  return recipientRoles.map((role) => {
    const isUnsupported = UNSUPPORTED_ROLE_SET.has(role)
    const label = isUnsupported
      ? `${labelFor(recipientRoleLabels, role)} ${notificationPolicyLabels.form.recipientUnsupportedSuffix}`
      : labelFor(recipientRoleLabels, role)
    return (
      <SelectItem key={role} value={role} disabled={isUnsupported}>
        {label}
      </SelectItem>
    )
  })
}

/** 채널 카탈로그 → SelectItem 배열. 미지 값은 원문 fallback. */
function buildChannelOptions(channels: PolicyCatalog['channels']): JSX.Element[] {
  return channels.map((ch) => (
    <SelectItem key={ch} value={ch}>
      {labelFor(channelLabels, ch)}
    </SelectItem>
  ))
}

// ─────────────────────────────────────────────────────────────────────────────
// 메인 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 알림 정책 생성 폼.
 *
 * - presentational(표현 전용) — 모든 상태는 props 주도.
 * - select 3종(이벤트/수신자/채널)으로 조합을 선택 후 "정책 추가" 버튼으로 제출.
 * - enabled는 항상 true로 고정 — 생성 후 토글은 NotificationPolicyTable에서 처리.
 * - submitError: 부모(T7 페이지)가 409 중복 오류를 i18n 변환 후 prop으로 전달.
 * - isSubmitting: 제출 중 버튼 비활성.
 * - UNSUPPORTED_RECIPIENT_ROLES 역할은 select 옵션 disabled + "(미지원)" 접미사.
 * - 수신자 역할 선택 시 동적 헬퍼(한 줄 설명) 표시. 상시 안내 문구 항상 표시.
 */
export function NotificationPolicyForm({
  catalog,
  onSubmit,
  submitError,
  isSubmitting,
}: NotificationPolicyFormProps): JSX.Element {
  const [eventType, setEventType] = useState('')
  const [recipientRole, setRecipientRole] = useState('')
  const [channel, setChannel] = useState('')

  const handleSubmit = () => {
    onSubmit({ eventType, recipientRole, channel })
  }

  return (
    <div className="flex flex-wrap items-end gap-3 rounded-lg border bg-muted/30 p-4">
      {/* 이벤트 유형 select */}
      <div className="flex min-w-[180px] flex-col gap-1">
        <label
          id="notif-form-event-type-label"
          className="text-xs font-medium text-muted-foreground"
        >
          {notificationPolicyLabels.form.eventType}
        </label>
        <Select
          value={eventType}
          onValueChange={setEventType}
          aria-labelledby="notif-form-event-type-label"
        >
          <SelectTrigger aria-label={notificationPolicyLabels.form.eventType}>
            <SelectValue placeholder={notificationPolicyLabels.form.eventType} />
          </SelectTrigger>
          <SelectContent>
            {buildEventTypeOptions(catalog.eventTypes)}
          </SelectContent>
        </Select>
      </div>

      {/* 수신자 역할 select */}
      <div className="flex min-w-[160px] flex-col gap-1">
        <label
          id="notif-form-recipient-label"
          className="text-xs font-medium text-muted-foreground"
        >
          {notificationPolicyLabels.form.recipientRole}
        </label>
        <Select
          value={recipientRole}
          onValueChange={setRecipientRole}
          aria-labelledby="notif-form-recipient-label"
        >
          <SelectTrigger aria-label={notificationPolicyLabels.form.recipientRole}>
            <SelectValue placeholder={notificationPolicyLabels.form.recipientRole} />
          </SelectTrigger>
          <SelectContent>
            {buildRecipientRoleOptions(catalog.recipientRoles)}
          </SelectContent>
        </Select>
        {/* 동적 헬퍼 — 선택한 역할의 한 줄 설명. 미선택이면 미표시. */}
        {recipientRole !== '' && (
          <p className="text-xs text-muted-foreground">
            {labelFor(recipientRoleDescriptions, recipientRole)}
          </p>
        )}
        {/* 상시 안내 — 미지원 역할 사유. 항상 표시. */}
        <p className="text-xs text-muted-foreground">
          {notificationPolicyLabels.form.recipientUnsupportedHint}
        </p>
      </div>

      {/* 채널 select */}
      <div className="flex min-w-[140px] flex-col gap-1">
        <label
          id="notif-form-channel-label"
          className="text-xs font-medium text-muted-foreground"
        >
          {notificationPolicyLabels.form.channel}
        </label>
        <Select
          value={channel}
          onValueChange={setChannel}
          aria-labelledby="notif-form-channel-label"
        >
          <SelectTrigger aria-label={notificationPolicyLabels.form.channel}>
            <SelectValue placeholder={notificationPolicyLabels.form.channel} />
          </SelectTrigger>
          <SelectContent>
            {buildChannelOptions(catalog.channels)}
          </SelectContent>
        </Select>
      </div>

      {/* 추가 버튼 */}
      <Button
        type="button"
        onClick={handleSubmit}
        disabled={isSubmitting}
      >
        {notificationPolicyLabels.form.addButton}
      </Button>

      {/* submitError 메시지 — 부모가 전달할 때만 표시 */}
      {submitError !== undefined && (
        <p role="alert" className="w-full text-sm text-destructive">
          {submitError}
        </p>
      )}
    </div>
  )
}
