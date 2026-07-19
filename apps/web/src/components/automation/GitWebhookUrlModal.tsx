// Git 웹훅 등록 직후 원문 URL 을 1회만 노출하는 모달 — 닫기 3경로 모두 2단계 확인 (FR-AT-07 PR-D Task 4)
import type { JSX } from 'react'
import { useState } from 'react'
import { Dialog as DialogPrimitive } from 'radix-ui'
import { Button } from '@/components/ui/button'

// ─────────────────────────────────────────────────────────────────────────────
// 문구 — BC 내 고정 한국어 (WebhookTokenModal.tsx 선례)
// ─────────────────────────────────────────────────────────────────────────────

const labels = {
  title: 'Git 웹훅 URL이 발급되었습니다',
  warning: '이 URL은 지금 한 번만 표시됩니다. 창을 닫으면 다시 확인할 수 없습니다.',
  copyButton: '복사',
  copiedLabel: '복사됨',
  copyFailed: '복사에 실패했습니다. 직접 선택해 복사해 주세요.',
  close: '닫기',
  closeConfirm: 'URL은 다시 볼 수 없습니다. 닫을까요?',
  confirmButton: '확정',
  cancelButton: '취소',
} as const

// ─────────────────────────────────────────────────────────────────────────────
// CloseConfirmPrompt — 닫기 2단계 확인(FR8). file-local 복제(AutomationYamlImportDialog.tsx
// CloseConfirmPrompt 구조 동형, 재사용 import 금지 — 이 컴포넌트만의 문구/testid를 갖는다).
// ─────────────────────────────────────────────────────────────────────────────

interface CloseConfirmPromptProps {
  readonly onConfirm: () => void
  readonly onCancel: () => void
}

function CloseConfirmPrompt({ onConfirm, onCancel }: CloseConfirmPromptProps): JSX.Element {
  return (
    <div className="mt-4 space-y-2 rounded-md border border-destructive/20 bg-destructive/5 p-3">
      <p className="text-sm">{labels.closeConfirm}</p>
      <div className="flex gap-2">
        <Button variant="destructive" size="sm" data-testid="git-webhook-url-close-confirm" onClick={onConfirm}>
          {labels.confirmButton}
        </Button>
        <Button variant="outline" size="sm" data-testid="git-webhook-url-close-cancel" onClick={onCancel}>
          {labels.cancelButton}
        </Button>
      </div>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** GitWebhookUrlModal props */
export interface GitWebhookUrlModalProps {
  /** Git 웹훅 등록 응답에 1회 동봉되는 인바운드 URL 경로(origin 미포함). null이면 모달을 렌더하지 않는다 */
  readonly webhookUrl: string | null
  /**
   * 닫힘 콜백 — 2단계 확인에서 "확정"을 눌렀을 때만 호출된다(FR8). 호출부는 이 시점에
   * `webhookUrl` state를 null로 되돌린다. 그래야 원문 URL(토큰 포함)이 더 이상 어떤 React
   * state에도 남지 않는다(§1.18 — storage 저장 절대 금지).
   */
  readonly onClose: () => void
}

// ─────────────────────────────────────────────────────────────────────────────
// GitWebhookUrlModal
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Git 웹훅 등록 직후 발급된 인바운드 URL을 1회 노출하는 순수 표시 모달.
 *
 * - 토큰은 SHA-256 해시로만 저장되고 재발급 엔드포인트가 없어(§배경), 이 URL을 분실하면
 *   삭제 후 재등록(=새 URL, provider 재설정)만이 유일한 복구 경로다. 그래서 닫기 시도
 *   3경로(X 버튼·ESC·오버레이 pointer-down) 전부를 가로채 2단계 확인을 요구한다(FR8).
 *   Root의 `onOpenChange`가 3경로가 도달하는 깔때기이되, ESC/오버레이는 Content의
 *   `onEscapeKeyDown`/`onPointerDownOutside`에서 `preventDefault`로 먼저 가로채고(그래야
 *   Radix 기본 닫기가 발생하지 않는다), X 버튼은 별도 가로채기 지점이 없어 `onOpenChange`
 *   에서 직접 잡는다.
 * - `webhookUrl` prop이 있는 동안 전체가 분실 위험 구간이다(in-flight 개념 없음 — 이
 *   컴포넌트는 mutation을 소유하지 않는 순수 표시 컴포넌트, mutation은 Task 6 소유).
 * - `window.location.origin`을 접두해 완전 URL로 표시·복사한다(FR9) — 서버가 주는 경로만으론
 *   provider가 그대로 등록할 수 없다.
 * - raw URL은 이 컴포넌트 내부에서도 로컬 state(복사 여부 등)로만 다루고
 *   localStorage/sessionStorage/URL/로그에 절대 저장하지 않는다.
 */
export function GitWebhookUrlModal({ webhookUrl, onClose }: GitWebhookUrlModalProps): JSX.Element | null {
  const [copied, setCopied] = useState(false)
  const [copyError, setCopyError] = useState<string | null>(null)
  const [closeConfirming, setCloseConfirming] = useState(false)

  if (webhookUrl === null) return null

  // 중첩 함수(handleCopy)에는 null narrowing이 전파되지 않으므로 별도 const로 캡처한다.
  const rawUrl = webhookUrl
  const fullUrl = `${window.location.origin}${rawUrl}`

  async function handleCopy(): Promise<void> {
    try {
      await navigator.clipboard.writeText(fullUrl)
      setCopied(true)
      setCopyError(null)
    } catch {
      setCopyError(labels.copyFailed)
      setCopied(false)
    }
  }

  /** Root의 onOpenChange — 닫기 시도(X 버튼 포함)를 가로채 2단계 확인을 요구한다. */
  function handleOpenChangeAttempt(next: boolean): void {
    if (!next) {
      setCloseConfirming(true)
    }
  }

  return (
    <DialogPrimitive.Root open onOpenChange={handleOpenChangeAttempt}>
      <DialogPrimitive.Portal>
        <DialogPrimitive.Overlay
          data-testid="git-webhook-url-overlay"
          className="fixed inset-0 z-50 bg-black/40 data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0"
        />

        <DialogPrimitive.Content
          data-testid="git-webhook-url-dialog"
          onEscapeKeyDown={(event) => {
            event.preventDefault()
            setCloseConfirming(true)
          }}
          onPointerDownOutside={(event) => {
            event.preventDefault()
            setCloseConfirming(true)
          }}
          className="fixed left-1/2 top-1/2 z-50 w-full max-w-md -translate-x-1/2 -translate-y-1/2 rounded-xl bg-background p-6 shadow-xl data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0 data-[state=closed]:zoom-out-95 data-[state=open]:zoom-in-95"
        >
          <DialogPrimitive.Title className="text-lg font-semibold mb-1">
            {labels.title}
          </DialogPrimitive.Title>

          <DialogPrimitive.Description
            role="alert"
            className="mt-2 text-sm text-warning-text"
          >
            {labels.warning}
          </DialogPrimitive.Description>

          <code className="mt-4 block break-all rounded bg-muted px-3 py-2 text-sm font-mono select-all">
            {fullUrl}
          </code>

          {copyError !== null && (
            <p role="alert" className="mt-2 text-xs text-destructive">{copyError}</p>
          )}

          {closeConfirming && (
            <CloseConfirmPrompt
              onConfirm={onClose}
              onCancel={() => { setCloseConfirming(false) }}
            />
          )}

          <div className="mt-6 flex justify-end gap-2">
            <Button
              variant="outline"
              size="sm"
              data-testid="git-webhook-url-copy-button"
              onClick={() => { void handleCopy() }}
            >
              {copied ? labels.copiedLabel : labels.copyButton}
            </Button>
            <DialogPrimitive.Close asChild>
              <Button size="sm" data-testid="git-webhook-url-close-button">
                {labels.close}
              </Button>
            </DialogPrimitive.Close>
          </div>
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  )
}
