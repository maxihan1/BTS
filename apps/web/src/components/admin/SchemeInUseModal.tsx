// 워크플로우 스킴 삭제 차단 모달 — SCHEME_IN_USE 409 응답 시 표시
import type { JSX } from 'react'
import { AlertDialog } from 'radix-ui'
import { Button } from '@/components/ui/button'
import { cn } from '@/lib/utils'
import { workflowSchemeLabels } from '@/i18n/workflow-scheme-labels'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** 모달 설명 요소 ID — aria-describedby 연결용 */
const MODAL_DESCRIPTION_ID = 'scheme-in-use-modal-description'

// ─────────────────────────────────────────────────────────────────────────────
// Props 인터페이스
// ─────────────────────────────────────────────────────────────────────────────

/** SchemeInUseModal 컴포넌트 props */
export interface SchemeInUseModalProps {
  /** 모달 열림 여부 */
  isOpen: boolean
  /** 모달 닫기 콜백 */
  onClose: () => void
  /** 사용 중인 프로젝트 수 */
  usedByProjectsCount: number
}

// ─────────────────────────────────────────────────────────────────────────────
// 메인 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 워크플로우 스킴 삭제 차단 모달.
 *
 * - 스킴이 프로젝트에서 사용 중인 경우 DELETE 시도 → 409 SCHEME_IN_USE → 이 모달 표시.
 * - role=alertdialog + aria-describedby 적용.
 * - 「확인」 버튼으로 닫음.
 * - 후속 PR에서 프로젝트 목록 링크 제공 예정.
 */
export function SchemeInUseModal({
  isOpen,
  onClose,
  usedByProjectsCount,
}: SchemeInUseModalProps): JSX.Element {
  return (
    <AlertDialog.Root open={isOpen} onOpenChange={(open) => { if (!open) onClose() }}>
      <AlertDialog.Portal>
        <AlertDialog.Overlay
          className={cn(
            'fixed inset-0 z-50 bg-black/50',
            'data-[state=open]:animate-in data-[state=closed]:animate-out',
            'data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0',
          )}
        />
        {/* Radix AlertDialog.Content는 기본적으로 role="alertdialog"를 제공한다 */}
        <AlertDialog.Content
          aria-describedby={MODAL_DESCRIPTION_ID}
          className={cn(
            'fixed left-1/2 top-1/2 z-50 w-full max-w-md -translate-x-1/2 -translate-y-1/2',
            'rounded-xl border border-border bg-background p-6 shadow-lg',
            'data-[state=open]:animate-in data-[state=closed]:animate-out',
            'data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0',
            'data-[state=closed]:zoom-out-95 data-[state=open]:zoom-in-95',
          )}
        >
          {/* 헤더 */}
          <AlertDialog.Title className="flex items-center gap-2 text-base font-semibold text-foreground">
            <span className="flex h-8 w-8 shrink-0 items-center justify-center rounded-full bg-destructive/10 text-destructive">
              <svg
                className="h-4 w-4"
                fill="none"
                viewBox="0 0 24 24"
                stroke="currentColor"
                aria-hidden="true"
              >
                <path
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  strokeWidth={2}
                  d="M12 9v4m0 4h.01M10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z"
                />
              </svg>
            </span>
            {workflowSchemeLabels.inUseModal.title}
          </AlertDialog.Title>

          {/* 본문 설명 */}
          <AlertDialog.Description
            id={MODAL_DESCRIPTION_ID}
            className="mt-3 text-sm text-muted-foreground"
          >
            {workflowSchemeLabels.inUseModal.description}
          </AlertDialog.Description>

          {/* 사용 중인 프로젝트 수 */}
          <div className="mt-3 rounded-lg bg-muted px-4 py-3 text-sm">
            <span className="font-medium">{workflowSchemeLabels.inUseModal.usedByProjectsLabel}</span>
            <span className="ml-2 text-muted-foreground">
              {usedByProjectsCount}개 (프로젝트 목록은 후속 버전에서 제공 예정)
            </span>
          </div>

          {/* 액션 버튼 */}
          <div className="mt-5 flex justify-end">
            <AlertDialog.Action asChild>
              <Button onClick={onClose}>{workflowSchemeLabels.inUseModal.confirmButton}</Button>
            </AlertDialog.Action>
          </div>
        </AlertDialog.Content>
      </AlertDialog.Portal>
    </AlertDialog.Root>
  )
}
