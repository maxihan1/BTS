// 알림 정책 목록 테이블 — 토글·인라인 삭제 확인 포함 presentational 컴포넌트
import type { JSX } from 'react'
import { useState } from 'react'
import type { NotificationPolicy } from '@/api/notification-policies'
import {
  eventTypeLabels,
  recipientRoleLabels,
  channelLabels,
  labelFor,
  notificationPolicyLabels,
} from '@/i18n/notification-policy-labels'
import { Button } from '@/components/ui/button'

// ─────────────────────────────────────────────────────────────────────────────
// 행 서브컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

interface NotificationPolicyRowProps {
  /** 렌더할 알림 정책 단건 */
  readonly policy: NotificationPolicy
  /** 토글 버튼 클릭 핸들러 — (id, nextEnabled) */
  readonly onToggle: (id: string, nextEnabled: boolean) => void
  /** 삭제 확인 핸들러 — 인라인 확인 후 호출 */
  readonly onDelete: (id: string) => void
  /** 변이 진행 중 여부 — true이면 액션 버튼 비활성화 */
  readonly isMutating: boolean
}

/**
 * 알림 정책 단건 행 컴포넌트.
 *
 * - 토글 버튼: enabled 상태에 따라 "비활성화" / "활성화" 텍스트.
 * - 삭제 버튼: 클릭 시 인라인 확인(useState)으로 "확인"/"취소" 전환. 모달 라이브러리 없음 (#121 패턴).
 * - 텍스트 중복 버튼: aria-label로 행 컨텍스트 명시해 E2E 셀렉터 안전성 확보.
 * - isMutating=true: 토글·확인 버튼 disabled.
 */
function NotificationPolicyRow({
  policy,
  onToggle,
  onDelete,
  isMutating,
}: NotificationPolicyRowProps): JSX.Element {
  const [confirmingDelete, setConfirmingDelete] = useState(false)

  const eventLabel = labelFor(eventTypeLabels, policy.eventType)
  const roleLabel = labelFor(recipientRoleLabels, policy.recipientRole)
  const channelLabel = labelFor(channelLabels, policy.channel)

  const { actions } = notificationPolicyLabels

  function handleToggle(): void {
    onToggle(policy.id, !policy.enabled)
  }

  function handleDeleteClick(): void {
    setConfirmingDelete(true)
  }

  function handleConfirmDelete(): void {
    setConfirmingDelete(false)
    onDelete(policy.id)
  }

  function handleCancelDelete(): void {
    setConfirmingDelete(false)
  }

  const toggleAriaLabel = policy.enabled
    ? `${eventLabel} 정책 비활성화`
    : `${eventLabel} 정책 활성화`

  return (
    <tr
      className="border-b text-sm hover:bg-muted/50"
      aria-label={eventLabel}
    >
      {/* 이벤트 유형 */}
      <td className="px-4 py-2 whitespace-nowrap font-medium">
        {eventLabel}
      </td>

      {/* 수신자 역할 */}
      <td className="px-4 py-2 whitespace-nowrap">
        {roleLabel}
      </td>

      {/* 채널 */}
      <td className="px-4 py-2 whitespace-nowrap">
        {channelLabel}
      </td>

      {/* 활성 여부 */}
      <td className="px-4 py-2 whitespace-nowrap">
        <span
          className={
            policy.enabled
              ? 'text-sm font-medium text-green-700 dark:text-green-400'
              : 'text-sm text-muted-foreground'
          }
        >
          {policy.enabled ? '활성' : '비활성'}
        </span>
      </td>

      {/* 액션 */}
      <td className="px-4 py-2 whitespace-nowrap">
        <div className="flex items-center gap-2">
          {/* 토글 버튼 */}
          <Button
            variant="outline"
            size="sm"
            disabled={isMutating}
            aria-label={toggleAriaLabel}
            onClick={handleToggle}
          >
            {policy.enabled ? actions.toggleDisable : actions.toggleEnable}
          </Button>

          {/* 인라인 삭제 확인 흐름 */}
          {confirmingDelete ? (
            <>
              <Button
                variant="destructive"
                size="sm"
                disabled={isMutating}
                aria-label={`${eventLabel} 정책 삭제 확인`}
                onClick={handleConfirmDelete}
              >
                {actions.confirmButton}
              </Button>
              <Button
                variant="outline"
                size="sm"
                aria-label={`${eventLabel} 정책 삭제 취소`}
                onClick={handleCancelDelete}
              >
                {actions.cancelButton}
              </Button>
            </>
          ) : (
            <Button
              variant="destructive"
              size="sm"
              aria-label={`${eventLabel} 정책 삭제`}
              onClick={handleDeleteClick}
            >
              {actions.deleteButton}
            </Button>
          )}
        </div>
      </td>
    </tr>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 테이블 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

interface NotificationPolicyTableProps {
  /** 렌더할 알림 정책 목록 */
  readonly policies: readonly NotificationPolicy[]
  /** 토글 버튼 클릭 핸들러 — (id, nextEnabled) */
  readonly onToggle: (id: string, nextEnabled: boolean) => void
  /** 삭제 확인 핸들러 */
  readonly onDelete: (id: string) => void
  /** 변이 진행 중 여부 — true이면 액션 버튼 비활성화 */
  readonly isMutating: boolean
}

/**
 * 알림 정책 관리자 목록 테이블 (presentational).
 *
 * - policies 빈 배열: 빈 상태 문구 표시.
 * - 이벤트/수신자/채널: i18n 라벨(미지 값은 원문 fallback).
 * - 토글: enabled 토글 버튼 → onToggle(id, !enabled).
 * - 삭제: 인라인 확인(useState, 모달 없음) → 확인 시 onDelete(id).
 * - isMutating=true: 액션 버튼 비활성화.
 * - 행마다 aria-label로 텍스트 중복 버튼 E2E 견고성 확보 (memory: playwright-getbyrole).
 */
export function NotificationPolicyTable({
  policies,
  onToggle,
  onDelete,
  isMutating,
}: NotificationPolicyTableProps): JSX.Element {
  const { table, empty } = notificationPolicyLabels

  if (policies.length === 0) {
    return (
      <div className="py-8 text-center text-sm text-muted-foreground">
        {empty.noResults}
      </div>
    )
  }

  return (
    <div className="overflow-x-auto rounded-md border">
      <table className="w-full border-collapse text-sm">
        <thead>
          <tr className="border-b bg-muted/50 text-left text-xs font-medium text-muted-foreground">
            <th className="px-4 py-2">{table.eventType}</th>
            <th className="px-4 py-2">{table.recipientRole}</th>
            <th className="px-4 py-2">{table.channel}</th>
            <th className="px-4 py-2">{table.enabled}</th>
            <th className="px-4 py-2">{table.actions}</th>
          </tr>
        </thead>
        <tbody>
          {policies.map((policy) => (
            <NotificationPolicyRow
              key={policy.id}
              policy={policy}
              onToggle={onToggle}
              onDelete={onDelete}
              isMutating={isMutating}
            />
          ))}
        </tbody>
      </table>
    </div>
  )
}
