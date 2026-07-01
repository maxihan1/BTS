// 아웃바운드 Webhook 구독 목록 테이블 — 배지·인라인 삭제 확인 포함 presentational 컴포넌트
import type { JSX } from 'react'
import { useState } from 'react'
import type { WebhookResponse } from '@/api/webhooks'
import { labelForEvent } from '@/i18n/webhook-labels'
import { formatDateTime } from '@/lib/datetime'
import { Button } from '@/components/ui/button'

// ─────────────────────────────────────────────────────────────────────────────
// 행 서브컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

interface WebhookRowProps {
  /** 렌더할 webhook 구독 단건 */
  readonly webhook: WebhookResponse
  /** 편집 버튼 클릭 핸들러 */
  readonly onEdit: (webhook: WebhookResponse) => void
  /** 삭제 확인 핸들러 — 인라인 확인 후 호출 */
  readonly onDelete: (id: string) => void
  /** 발송 이력 조회 핸들러 */
  readonly onViewDeliveries: (id: string) => void
}

interface StatusBadgeProps {
  /** 배지가 나타내는 boolean 상태 */
  readonly active: boolean
  /** active=true일 때 표시할 라벨 */
  readonly activeLabel: string
  /** active=false일 때 표시할 라벨 */
  readonly inactiveLabel: string
}

/**
 * 활성/서명 여부 배지 서브컴포넌트.
 *
 * 색상만으로 상태를 구분하지 않고 라벨 텍스트를 함께 표시해 색각이상 사용자도
 * 상태를 식별할 수 있게 한다 (색+텍스트 동시 표시).
 */
function StatusBadge({ active, activeLabel, inactiveLabel }: StatusBadgeProps): JSX.Element {
  return (
    <span
      className={
        active
          ? 'text-sm font-medium text-green-700 dark:text-green-400'
          : 'text-sm text-muted-foreground'
      }
    >
      {active ? activeLabel : inactiveLabel}
    </span>
  )
}

/**
 * webhook의 구독 이벤트 wireValue 배열을 한국어 라벨로 변환해 쉼표로 조인한다.
 *
 * @param eventFilter 구독 이벤트 wireValue 배열
 * @returns 조인된 한국어 라벨 문자열
 */
function joinEventLabels(eventFilter: readonly string[]): string {
  return eventFilter.map((event) => labelForEvent(event)).join(', ')
}

/**
 * 갱신 시각을 표시용 문자열로 변환한다. null이면 "—"로 방어한다.
 *
 * @param updatedAt ISO 8601 문자열 또는 null
 * @returns 포맷된 날짜/시각 문자열, 또는 "—"
 */
function formatUpdatedAt(updatedAt: string | null | undefined): string {
  return updatedAt == null ? '—' : formatDateTime(updatedAt)
}

/**
 * Webhook 구독 단건 행 컴포넌트.
 *
 * - 삭제 버튼: 클릭 시 인라인 확인(useState)으로 "확인"/"취소" 전환. 모달 라이브러리 없음 (#121 패턴).
 * - aria-label로 행 컨텍스트(name)를 명시해 텍스트 중복 버튼의 E2E 셀렉터 안전성 확보.
 */
function WebhookRow({ webhook, onEdit, onDelete, onViewDeliveries }: WebhookRowProps): JSX.Element {
  const [confirmingDelete, setConfirmingDelete] = useState(false)

  const eventLabels = joinEventLabels(webhook.eventFilter)
  const updatedAtLabel = formatUpdatedAt(webhook.updatedAt)

  return (
    <tr className="border-b text-sm hover:bg-muted/50" aria-label={webhook.name}>
      {/* 이름 — 1차 앵커 */}
      <td className="px-4 py-2 font-medium">{webhook.name}</td>

      {/* URL — 2차, truncate + title 속성으로 전체 값 접근 */}
      <td className="max-w-xs truncate px-4 py-2 text-muted-foreground" title={webhook.url}>
        {webhook.url}
      </td>

      {/* 구독 이벤트 */}
      <td className="px-4 py-2">{eventLabels}</td>

      {/* 프로젝트 키 */}
      <td className="px-4 py-2 whitespace-nowrap">{webhook.projectKey ?? '—'}</td>

      {/* 활성 여부 */}
      <td className="px-4 py-2 whitespace-nowrap">
        <StatusBadge active={webhook.enabled} activeLabel="활성" inactiveLabel="비활성" />
      </td>

      {/* 서명(secret) 설정 여부 */}
      <td className="px-4 py-2 whitespace-nowrap">
        <StatusBadge active={webhook.hasSecret} activeLabel="서명 설정" inactiveLabel="없음" />
      </td>

      {/* 갱신 시각 */}
      <td className="px-4 py-2 whitespace-nowrap text-muted-foreground">{updatedAtLabel}</td>

      {/* 액션 */}
      <td className="px-4 py-2 whitespace-nowrap">
        <div className="flex items-center gap-2">
          <Button
            variant="outline"
            size="sm"
            aria-label={`${webhook.name} 편집`}
            onClick={() => { onEdit(webhook) }}
          >
            편집
          </Button>

          <Button
            variant="outline"
            size="sm"
            aria-label={`${webhook.name} 발송 이력`}
            onClick={() => { onViewDeliveries(webhook.id) }}
          >
            발송 이력
          </Button>

          {/* 인라인 삭제 확인 흐름 */}
          {confirmingDelete ? (
            <>
              <Button
                variant="destructive"
                size="sm"
                aria-label={`${webhook.name} 삭제 확인`}
                onClick={() => { setConfirmingDelete(false); onDelete(webhook.id) }}
              >
                확인
              </Button>
              <Button
                variant="outline"
                size="sm"
                aria-label={`${webhook.name} 삭제 취소`}
                onClick={() => { setConfirmingDelete(false) }}
              >
                취소
              </Button>
            </>
          ) : (
            <Button
              variant="destructive"
              size="sm"
              aria-label={`${webhook.name} 삭제`}
              onClick={() => { setConfirmingDelete(true) }}
            >
              삭제
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

interface WebhookTableProps {
  /** 렌더할 webhook 구독 목록 */
  readonly webhooks: WebhookResponse[]
  /** 로딩 중 여부 — true이면 로딩 상태 표시 */
  readonly isLoading: boolean
  /** 편집 버튼 클릭 핸들러 */
  readonly onEdit: (webhook: WebhookResponse) => void
  /** 삭제 확인 핸들러 — 인라인 확인 후 호출 */
  readonly onDelete: (id: string) => void
  /** 발송 이력 조회 핸들러 */
  readonly onViewDeliveries: (id: string) => void
}

/**
 * 아웃바운드 Webhook 구독 관리자 목록 테이블 (presentational).
 *
 * - isLoading=true: 로딩 상태 표시 (role="status", AuditLogTable 관례).
 * - webhooks 빈 배열: 빈 상태 문구 표시.
 * - 구독 이벤트: labelForEvent로 한국어 라벨 조인.
 * - projectKey/updatedAt=null: "—" 표시.
 * - 활성/서명 배지: 색+텍스트 동시 표시.
 * - 삭제: 인라인 확인(useState, 모달 없음) → 확인 시 onDelete(id).
 * - 행마다 aria-label로 텍스트 중복 버튼 E2E 견고성 확보 (memory: playwright-getbyrole).
 */
export function WebhookTable({
  webhooks,
  isLoading,
  onEdit,
  onDelete,
  onViewDeliveries,
}: WebhookTableProps): JSX.Element {
  if (isLoading) {
    return (
      <div role="status" aria-label="로딩 중" className="py-8 text-center text-sm text-muted-foreground">
        로딩 중...
      </div>
    )
  }

  if (webhooks.length === 0) {
    return (
      <div className="py-8 text-center text-sm text-muted-foreground">
        등록된 Webhook이 없습니다
      </div>
    )
  }

  return (
    <div className="overflow-x-auto rounded-md border">
      <table className="w-full border-collapse text-sm">
        <thead>
          <tr className="border-b bg-muted/50 text-left text-xs font-medium text-muted-foreground">
            <th className="px-4 py-2">이름</th>
            <th className="px-4 py-2">URL</th>
            <th className="px-4 py-2">구독 이벤트</th>
            <th className="px-4 py-2">프로젝트</th>
            <th className="px-4 py-2">활성 여부</th>
            <th className="px-4 py-2">서명</th>
            <th className="px-4 py-2">갱신 시각</th>
            <th className="px-4 py-2">액션</th>
          </tr>
        </thead>
        <tbody>
          {webhooks.map((webhook) => (
            <WebhookRow
              key={webhook.id}
              webhook={webhook}
              onEdit={onEdit}
              onDelete={onDelete}
              onViewDeliveries={onViewDeliveries}
            />
          ))}
        </tbody>
      </table>
    </div>
  )
}
