// 일괄 작업 결과 패널 Dialog — 폴링·진행률·성공/실패 결과 표시
import type { JSX } from 'react'
import { Dialog as DialogPrimitive } from 'radix-ui'
import { useBulkOperationPolling } from '@/hooks/use-bulk-operation'
import { failureReasonLabels, statusLabels, getFailureReasonLabel } from '@/i18n/bulk-operation-labels'
import type { BulkOperationResponse } from '@/api/bulk-operations'

// ─────────────────────────────────────────────────────────────────────────────
// 내부 유틸
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 진행률을 0~100 사이의 정수 퍼센트로 계산한다.
 * totalCount가 0이면 0을 반환해 division by zero를 방지한다.
 *
 * @param processedCount 처리된 이슈 수
 * @param totalCount 전체 이슈 수
 * @returns 0~100 정수 퍼센트
 */
function calcPercent(processedCount: number, totalCount: number): number {
  if (totalCount === 0) return 0
  return Math.round((processedCount / totalCount) * 100)
}

// ─────────────────────────────────────────────────────────────────────────────
// 서브 컴포넌트 — 진행률 표시
// ─────────────────────────────────────────────────────────────────────────────

interface ProgressPanelProps {
  readonly data: BulkOperationResponse
}

/**
 * 진행률 바와 카운트를 aria-live로 표시한다.
 * 종단 상태(COMPLETED/FAILED)에도 최종 카운트를 표시한다.
 */
function ProgressPanel({ data }: ProgressPanelProps): JSX.Element {
  const { status, processedCount, totalCount, succeededCount, failedCount } = data
  const percent = calcPercent(processedCount, totalCount)
  const isTerminal = status === 'COMPLETED' || status === 'FAILED'

  return (
    <div>
      {/* 상태 뱃지 */}
      <p className="text-sm font-medium text-foreground mb-2">
        {statusLabels[status]}
      </p>

      {/* 진행률 aria-live 영역 */}
      <div
        role="status"
        aria-live="polite"
        aria-label={`진행률 ${processedCount}/${totalCount}`}
        className="mb-3"
      >
        <div className="flex items-center justify-between text-xs text-muted-foreground mb-1">
          <span>{processedCount} / {totalCount}</span>
          <span>{percent}%</span>
        </div>
        {/* 진행률 바 */}
        <div className="h-2 rounded-full bg-muted overflow-hidden">
          <div
            className="h-full rounded-full bg-primary transition-all duration-300"
            style={{ width: `${percent}%` }}
            aria-hidden="true"
          />
        </div>
      </div>

      {/* 종단 상태 카운트 요약 */}
      {isTerminal && (
        <div className="flex gap-4 text-sm">
          <span className="text-success-text">
            성공 {succeededCount}
          </span>
          <span className="text-destructive">
            실패 {failedCount}
          </span>
        </div>
      )}
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 서브 컴포넌트 — 실패 이슈 목록
// ─────────────────────────────────────────────────────────────────────────────

interface FailureListProps {
  readonly items: BulkOperationResponse['items']
}

/**
 * 실패 이슈 목록을 issueKey + 한국어 실패사유 형태로 렌더한다.
 * 실패 항목이 없으면 null을 반환한다.
 */
function FailureList({ items }: FailureListProps): JSX.Element | null {
  const failedItems = items.filter((item) => item.status === 'FAILED')
  if (failedItems.length === 0) return null

  return (
    <div className="mt-4">
      <p className="text-sm font-medium text-foreground mb-2">실패 목록</p>
      <ul className="space-y-1 max-h-48 overflow-y-auto">
        {failedItems.map((item) => (
          <li
            key={item.issueKey}
            className="flex items-center justify-between text-sm rounded-md px-2 py-1 bg-muted/50"
          >
            <span className="font-mono text-foreground">{item.issueKey}</span>
            <span className="text-destructive text-xs">
              {item.failureReasonCode !== null
                ? getFailureReasonLabel(item.failureReasonCode)
                : failureReasonLabels['UNKNOWN']}
            </span>
          </li>
        ))}
      </ul>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

interface BulkOperationResultDialogProps {
  /** 폴링할 일괄 작업 UUID. null이면 폴링을 비활성화한다. */
  readonly bulkOperationId: string | null
  /** Dialog 열림 여부. false이면 폴링을 비활성화한다. */
  readonly open: boolean
  /** Dialog 열림 상태 변경 콜백 */
  readonly onOpenChange: (open: boolean) => void
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 일괄 작업 결과 패널 Dialog.
 *
 * - `open=true && bulkOperationId!=null`인 경우에만 `useBulkOperationPolling`을 활성화한다.
 * - PENDING/RUNNING 상태에서 진행률 바와 aria-live 카운트를 표시한다.
 * - COMPLETED/FAILED 도달 시 성공/실패 카운트 + 실패 이슈 목록(issueKey + 한국어 사유)을 표시한다.
 * - 폴링 에러(403/404 등) 시 role="alert" 에러 영역을 표시한다.
 *
 * @param bulkOperationId 폴링할 작업 UUID. null이면 비활성.
 * @param open Dialog 열림 여부
 * @param onOpenChange Dialog 상태 변경 콜백
 */
export function BulkOperationResultDialog({
  bulkOperationId,
  open,
  onOpenChange,
}: BulkOperationResultDialogProps): JSX.Element {
  const { data, isError } = useBulkOperationPolling(bulkOperationId, open)

  return (
    <DialogPrimitive.Root open={open} onOpenChange={onOpenChange}>
      <DialogPrimitive.Portal>
        <DialogPrimitive.Overlay className="fixed inset-0 z-50 bg-black/40 data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0" />

        <DialogPrimitive.Content
          className="fixed left-1/2 top-1/2 z-50 w-full max-w-lg -translate-x-1/2 -translate-y-1/2 rounded-xl bg-background p-6 shadow-xl data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0 data-[state=closed]:zoom-out-95 data-[state=open]:zoom-in-95"
        >
          <DialogPrimitive.Title className="text-lg font-semibold mb-4">
            일괄 작업 결과
          </DialogPrimitive.Title>

          {/* 폴링 에러 */}
          {isError && (
            <div
              role="alert"
              className="rounded-md border border-destructive/50 bg-destructive/10 px-4 py-3 text-sm text-destructive"
            >
              작업 상태를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.
            </div>
          )}

          {/* 진행률 + 결과 */}
          {data !== undefined && !isError && (
            <>
              <ProgressPanel data={data} />
              <FailureList items={data.items} />
            </>
          )}

          {/* 초기 로딩(데이터 없고 에러도 없음) */}
          {data === undefined && !isError && (
            <div
              role="status"
              aria-live="polite"
              aria-label="작업 상태 로딩 중"
              className="text-sm text-muted-foreground"
            >
              작업 상태를 불러오는 중...
            </div>
          )}

          {/* 닫기 버튼 */}
          <div className="flex justify-end mt-6">
            <DialogPrimitive.Close
              className="rounded-md border border-input bg-background px-4 py-2 text-sm font-medium hover:bg-accent transition-colors"
            >
              닫기
            </DialogPrimitive.Close>
          </div>
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  )
}
