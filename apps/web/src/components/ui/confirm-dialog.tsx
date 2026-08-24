// 확인 다이얼로그 프리미티브 — 되돌리기 어려운 조작 앞에 한 번 묻는 공용 껍데기
import * as React from 'react'
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription, DialogFooter } from './dialog'
import { Button } from './button'

interface ConfirmDialogProps {
  /** 열림 상태 — 제어 컴포넌트다 */
  open: boolean
  /** 열림 상태 변경 요청 */
  onOpenChange: (open: boolean) => void
  /**
   * 제목이자 **dialog 의 접근성 이름**이다.
   *
   * ★ 화면 전체에서 고유해야 한다. 같은 이름의 dialog 가 둘이면 Playwright
   * `getByRole('dialog', { name })` 가 strict mode 로 즉사한다(§2 즉사 계약).
   *
   * ★★ 별도 `aria-label` 프롭을 두지 않는다. Radix `DialogContent` 가 `DialogTitle` 을
   * `aria-labelledby` 로 자동 연결하고 그것이 `aria-label` 을 **이긴다** — 실측으로
   * 확인했다. 두 경로를 두면 「지정한 이름과 실제 이름이 다른」 자리가 생긴다
   * (`CreateIssueDialog.tsx:59` 가 세운 관례와 같다).
   */
  title: string
  /** 설명 — 무엇이 일어나는지 한 줄 */
  description?: string
  /** 확인 버튼 문구 */
  confirmLabel: string
  /** 취소 버튼 문구 */
  cancelLabel: string
  /** 확인을 눌렀을 때 */
  onConfirm: () => void
  /** 처리 중 — 확인 버튼을 잠가 이중 제출을 막는다 */
  confirming?: boolean
  /** 파괴적 조작이면 확인 버튼을 경고 색으로 */
  destructive?: boolean
}

/**
 * 되돌리기 어려운 조작을 한 번 묻는다.
 *
 * 화면마다 제각각 만들던 것을 하나로 모은 것이다 — 문구만 주입하고 구조·포커스·닫힘
 * 동작은 이 프리미티브가 고정한다.
 */
function ConfirmDialog({
  open,
  onOpenChange,
  title,
  description,
  confirmLabel,
  cancelLabel,
  onConfirm,
  confirming = false,
  destructive = false,
}: ConfirmDialogProps): React.JSX.Element {
  const handleConfirm = (): void => {
    onConfirm()
    onOpenChange(false)
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle>{title}</DialogTitle>
          {description !== undefined ? <DialogDescription>{description}</DialogDescription> : null}
        </DialogHeader>
        <DialogFooter>
          <Button variant="ghost" onClick={() => onOpenChange(false)}>
            {cancelLabel}
          </Button>
          <Button
            variant={destructive ? 'destructive' : 'default'}
            disabled={confirming}
            onClick={handleConfirm}
          >
            {confirmLabel}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

export { ConfirmDialog }
export type { ConfirmDialogProps }
