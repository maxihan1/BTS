// 발급된 PAT raw token 1회 노출 모달 — 복사 버튼 + 재확인 불가 안내 (FR-API-04 Task 7)
import type { JSX } from 'react'
import { useState } from 'react'
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import type { PatIssued } from '@/api/pats'

// ─────────────────────────────────────────────────────────────────────────────
// 문구 — BC 내 고정 한국어 (AddAccountDialog.tsx 관례)
// ─────────────────────────────────────────────────────────────────────────────

const labels = {
  title: '토큰이 발급되었습니다',
  warning: '이 토큰은 지금 한 번만 표시됩니다. 창을 닫으면 다시 확인할 수 없으니 안전한 곳에 보관하세요.',
  copyButton: '복사',
  copiedLabel: '복사됨',
  copyFailed: '복사에 실패했습니다. 직접 선택해 복사해 주세요.',
  close: '닫기',
} as const

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** PatTokenModal props */
export interface PatTokenModalProps {
  /** 발급 직후 응답(raw token 포함). null이면 모달을 렌더하지 않는다 */
  readonly issued: PatIssued | null
  /**
   * 닫힘 콜백 — 호출부(SettingsPatsPage)는 이 시점에 `issued` state를 null로 되돌린다.
   * 그래야 raw token이 더 이상 어떤 React state에도 남지 않는다(§1.18 — storage 저장 절대 금지).
   */
  readonly onClose: () => void
}

// ─────────────────────────────────────────────────────────────────────────────
// PatTokenModal
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 발급된 PAT의 raw token을 1회 노출하는 모달.
 *
 * - raw token은 `issued` prop으로만 전달되며, 이 컴포넌트 내부에서도 로컬 state(복사 여부 등)로만
 *   다루고 localStorage/sessionStorage에 절대 저장하지 않는다.
 * - "닫기" 클릭·바깥 클릭·Esc로 닫히면 `onClose`를 호출한다. 실제 토큰 소멸은 호출부가
 *   `issued` state를 null로 되돌리는 시점에 일어난다 — 그 순간 이 컴포넌트는 `issued === null`
 *   분기로 렌더를 멈추고(언마운트에 준함) `copied`/`copyError` 로컬 state도 함께 사라진다.
 */
export function PatTokenModal({ issued, onClose }: PatTokenModalProps): JSX.Element | null {
  const [copied, setCopied] = useState(false)
  const [copyError, setCopyError] = useState<string | null>(null)

  if (issued === null) return null

  // 중첩 함수(handleCopy)에는 null narrowing이 전파되지 않으므로 token을 별도 const로 캡처한다.
  const token = issued.token

  async function handleCopy(): Promise<void> {
    try {
      await navigator.clipboard.writeText(token)
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
    <Dialog open onOpenChange={handleOpenChange}>
      <DialogContent className="max-w-md" aria-describedby={undefined}>
        <DialogHeader>
          <DialogTitle>{labels.title}</DialogTitle>
        </DialogHeader>

        <p role="alert" className="mt-2 text-sm text-warning-text">
          {labels.warning}
        </p>

        <code className="mt-4 block break-all rounded bg-muted px-3 py-2 text-sm font-mono">
          {issued.token}
        </code>

        {copyError !== null && (
          <p role="alert" className="mt-2 text-xs text-destructive">{copyError}</p>
        )}

        <DialogFooter>
          <Button variant="outline" size="sm" onClick={() => { void handleCopy() }}>
            {copied ? labels.copiedLabel : labels.copyButton}
          </Button>
          <DialogClose asChild>
            <Button size="sm">{labels.close}</Button>
          </DialogClose>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
