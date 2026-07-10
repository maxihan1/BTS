// 발급된 automation WEBHOOK 트리거 토큰 1회 노출 모달 — 복사 버튼 + 재확인 불가 안내 (FR-AT-01 D6 Task 7)
import type { JSX } from 'react'
import { useState } from 'react'
import { Dialog as DialogPrimitive } from 'radix-ui'
import { Button } from '@/components/ui/button'

// ─────────────────────────────────────────────────────────────────────────────
// 문구 — BC 내 고정 한국어 (PatTokenModal.tsx 선례)
// ─────────────────────────────────────────────────────────────────────────────

const labels = {
  title: '웹훅 토큰이 발급되었습니다',
  warning: '이 토큰은 지금 한 번만 표시됩니다. 창을 닫으면 다시 확인할 수 없습니다.',
  copyButton: '복사',
  copiedLabel: '복사됨',
  copyFailed: '복사에 실패했습니다. 직접 선택해 복사해 주세요.',
  close: '닫기',
} as const

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** WebhookTokenModal props */
export interface WebhookTokenModalProps {
  /** WEBHOOK 트리거 생성 응답에 1회 동봉되는 토큰 원문. null이면 모달을 렌더하지 않는다 */
  readonly token: string | null
  /**
   * 닫힘 콜백 — 호출부는 이 시점에 `token` state를 null로 되돌린다.
   * 그래야 raw token이 더 이상 어떤 React state에도 남지 않는다(§1.18 — storage 저장 절대 금지).
   */
  readonly onClose: () => void
}

// ─────────────────────────────────────────────────────────────────────────────
// WebhookTokenModal
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 발급된 automation WEBHOOK 트리거 토큰을 1회 노출하는 모달.
 *
 * - raw token은 `token` prop으로만 전달되며, 이 컴포넌트 내부에서도 로컬 state(복사 여부 등)로만
 *   다루고 localStorage/sessionStorage/URL에 절대 저장하지 않는다.
 * - "닫기" 클릭·바깥 클릭·Esc로 닫히면 `onClose`를 호출한다. 실제 토큰 소멸은 호출부가
 *   `token` state를 null로 되돌리는 시점에 일어난다 — 그 순간 이 컴포넌트는 `token === null`
 *   분기로 렌더를 멈추고(언마운트에 준함) `copied`/`copyError` 로컬 state도 함께 사라진다.
 */
export function WebhookTokenModal({ token, onClose }: WebhookTokenModalProps): JSX.Element | null {
  const [copied, setCopied] = useState(false)
  const [copyError, setCopyError] = useState<string | null>(null)

  if (token === null) return null

  // 중첩 함수(handleCopy)에는 null narrowing이 전파되지 않으므로 token을 별도 const로 캡처한다.
  const rawToken = token

  async function handleCopy(): Promise<void> {
    try {
      await navigator.clipboard.writeText(rawToken)
      setCopied(true)
      setCopyError(null)
    } catch {
      setCopyError(labels.copyFailed)
      setCopied(false)
    }
  }

  function handleOpenChange(open: boolean): void {
    if (!open) onClose()
  }

  return (
    <DialogPrimitive.Root open onOpenChange={handleOpenChange}>
      <DialogPrimitive.Portal>
        <DialogPrimitive.Overlay className="fixed inset-0 z-50 bg-black/40 data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0" />

        <DialogPrimitive.Content className="fixed left-1/2 top-1/2 z-50 w-full max-w-md -translate-x-1/2 -translate-y-1/2 rounded-xl bg-background p-6 shadow-xl data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0 data-[state=closed]:zoom-out-95 data-[state=open]:zoom-in-95">
          <DialogPrimitive.Title className="text-lg font-semibold mb-1">
            {labels.title}
          </DialogPrimitive.Title>

          <p role="alert" className="mt-2 text-sm text-amber-800 dark:text-amber-200">
            {labels.warning}
          </p>

          <code className="mt-4 block break-all rounded bg-muted px-3 py-2 text-sm font-mono">
            {token}
          </code>

          {copyError !== null && (
            <p role="alert" className="mt-2 text-xs text-destructive">{copyError}</p>
          )}

          <div className="mt-6 flex justify-end gap-2">
            <Button
              variant="outline"
              size="sm"
              data-testid="webhook-token-copy-button"
              onClick={() => { void handleCopy() }}
            >
              {copied ? labels.copiedLabel : labels.copyButton}
            </Button>
            <DialogPrimitive.Close asChild>
              <Button size="sm" data-testid="webhook-token-close-button">
                {labels.close}
              </Button>
            </DialogPrimitive.Close>
          </div>
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  )
}
