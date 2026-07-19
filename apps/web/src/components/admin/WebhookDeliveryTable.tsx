// 아웃바운드 webhook 구독 발송 이력 테이블 컴포넌트 — presentational (FR-API-03 PR4)
import type { JSX } from 'react'
import type { WebhookDeliveryResponse } from '@/api/webhooks'
import { labelForEvent, labelForStatus } from '@/i18n/webhook-labels'
import { useDateFormat } from '@/hooks/use-date-format'

// ─────────────────────────────────────────────────────────────────────────────
// 디자인 토큰 상수 — 매직 클래스 금지, status 배지 색상 매핑
// SUCCEEDED=초록/FAILED=빨강 2종만 색을 갖는다. 그 외 미지 값(백엔드 enum 확장 등)은
// STATUS_BADGE_NEUTRAL로 폴백한다 (전방호환).
// ─────────────────────────────────────────────────────────────────────────────

/** status wireValue → 배지 색상 Tailwind 클래스 (SUCCEEDED/FAILED 2종만 등록) */
const STATUS_BADGE_CLASS: Record<string, string> = {
  SUCCEEDED: 'bg-success/10 text-success-text',
  FAILED: 'bg-danger/10 text-danger-text',
}

/** 미지 status 값에 적용하는 중립 배지 색상 — 색 없이도 라벨 텍스트로 구분 가능 */
const STATUS_BADGE_NEUTRAL = 'bg-muted text-muted-foreground'

// ─────────────────────────────────────────────────────────────────────────────
// 내부 유틸
// ─────────────────────────────────────────────────────────────────────────────

/**
 * status 배지 색상 클래스를 반환한다.
 *
 * STATUS_BADGE_CLASS에 등록된 SUCCEEDED/FAILED만 색을 갖고, 그 외 미지 값은
 * STATUS_BADGE_NEUTRAL로 폴백한다. 색만으로 구분하지 않고 labelForStatus 텍스트를
 * 배지 안에 항상 함께 표시하므로 색맹 사용자도 상태를 텍스트로 구분할 수 있다.
 *
 * @param status 발송 상태 wireValue (예: "SUCCEEDED", "FAILED")
 * @returns 배지에 적용할 Tailwind 클래스
 */
function badgeClassForStatus(status: string): string {
  return STATUS_BADGE_CLASS[status] ?? STATUS_BADGE_NEUTRAL
}

/**
 * 발송 이력 시각을 결정한다. deliveredAt 우선, 없으면 createdAt, 둘 다 없으면 "—".
 *
 * @param delivery 발송 이력 단건
 * @param formatDateTime 사용자 date_format 프리셋이 바인딩된 포맷 함수 (useDateFormat 훅 반환값)
 * @returns 프리셋 기준 로컬 시각 문자열 또는 "—"
 */
function resolveTimestamp(
  delivery: WebhookDeliveryResponse,
  formatDateTime: (iso: string) => string,
): string {
  const iso = delivery.deliveredAt ?? delivery.createdAt
  if (iso === null || iso === undefined) return '—'
  return formatDateTime(iso)
}

// ─────────────────────────────────────────────────────────────────────────────
// 행 서브컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

interface WebhookDeliveryRowProps {
  readonly delivery: WebhookDeliveryResponse
}

/**
 * 발송 이력 단건 행 컴포넌트.
 */
function WebhookDeliveryRow({ delivery }: WebhookDeliveryRowProps): JSX.Element {
  const { formatDateTime } = useDateFormat()
  return (
    <tr className="border-b text-sm hover:bg-muted/50">
      <td className="px-4 py-2 whitespace-nowrap font-medium">
        {labelForEvent(delivery.eventType)}
      </td>
      <td className="px-4 py-2 whitespace-nowrap">
        <span
          className={`inline-flex items-center rounded-full px-2.5 py-0.5 text-xs font-medium ${badgeClassForStatus(delivery.status)}`}
        >
          {labelForStatus(delivery.status)}
        </span>
      </td>
      <td className="px-4 py-2 whitespace-nowrap tabular-nums text-muted-foreground">
        {delivery.responseCode ?? '—'}
      </td>
      <td className="px-4 py-2 whitespace-nowrap tabular-nums text-muted-foreground">
        {delivery.attemptCount}
      </td>
      <td
        className="px-4 py-2 max-w-xs truncate text-xs text-muted-foreground"
        title={delivery.errorDetail ?? undefined}
      >
        {delivery.errorDetail ?? '—'}
      </td>
      <td className="px-4 py-2 whitespace-nowrap text-muted-foreground">
        {resolveTimestamp(delivery, formatDateTime)}
      </td>
    </tr>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 테이블 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/** 로딩 중 표시할 스켈레톤 행 개수 */
const SKELETON_ROW_COUNT = 3

interface WebhookDeliveryTableProps {
  /** 렌더할 발송 이력 목록 (최신순) */
  readonly deliveries: WebhookDeliveryResponse[]
  /** 로딩 중 여부 — true이면 스켈레톤 행 표시 */
  readonly isLoading: boolean
}

/**
 * 아웃바운드 webhook 구독의 발송 이력 테이블 (presentational).
 *
 * - isLoading=true: 스켈레톤 행 표시 (role="status").
 * - deliveries 빈 배열: 빈 상태 메시지 표시.
 * - status: SUCCEEDED=초록/FAILED=빨강 배지, 미지 값은 중립 — 색+텍스트 동시 표시로 색맹 대비.
 * - responseCode/errorDetail null → "—".
 * - 시각: deliveredAt ?? createdAt, 둘 다 없으면 "—".
 */
export function WebhookDeliveryTable({ deliveries, isLoading }: WebhookDeliveryTableProps): JSX.Element {
  if (isLoading) {
    return (
      <div role="status" aria-label="발송 이력 로딩 중" className="overflow-x-auto rounded-md border">
        <table className="w-full border-collapse text-sm">
          <tbody>
            {Array.from({ length: SKELETON_ROW_COUNT }, (_, index) => (
              <tr key={index} className="border-b">
                <td className="px-4 py-3" colSpan={6}>
                  <div className="h-4 w-full animate-pulse rounded bg-muted" />
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    )
  }

  if (deliveries.length === 0) {
    return (
      <div className="py-8 text-center text-sm text-muted-foreground">
        발송 이력이 없습니다
      </div>
    )
  }

  return (
    <div className="overflow-x-auto rounded-md border">
      <table className="w-full border-collapse text-sm">
        <thead>
          <tr className="border-b bg-muted/50 text-left text-xs font-medium text-muted-foreground">
            <th className="px-4 py-2">이벤트</th>
            <th className="px-4 py-2">상태</th>
            <th className="px-4 py-2">응답 코드</th>
            <th className="px-4 py-2">시도 횟수</th>
            <th className="px-4 py-2">오류 내용</th>
            <th className="px-4 py-2">시각</th>
          </tr>
        </thead>
        <tbody>
          {deliveries.map((delivery) => (
            <WebhookDeliveryRow key={delivery.id} delivery={delivery} />
          ))}
        </tbody>
      </table>
    </div>
  )
}
