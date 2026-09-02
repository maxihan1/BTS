// 기본값 복원 다이얼로그 — 복원이 초안까지만 간다는 사실을 문구가 직접 말한다
import * as React from 'react'
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogFooter,
} from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { workflowPublishLabels as labels } from '@/i18n/workflow-publish-labels'

interface ResetToDefaultDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  onConfirm: () => void
  confirming: boolean
}

/**
 * YAML 기본값을 초안으로 불러온다.
 *
 * ### 왜 `ConfirmDialog` 를 쓰지 않는가
 * 이 조작의 핵심은 「되돌린다」가 아니라 **「초안까지만 되돌린다」**이고, 그것을 설명에 담아야
 * 관리자가 곧바로 운영에 반영되는 것으로 오해하지 않는다. 되돌림의 주체가 부팅 이벤트에서
 * 사람으로 바뀐 것이 이 기능의 존재 이유다(ADR 2026-08-18 §D4).
 *
 * 이 다이얼로그를 여는 버튼 자체가 서버 판정(`canResetToDefault`)으로 가려지므로, 여기서는
 * 「복원할 수 있는가」를 다시 판정하지 않는다 — 두 곳에서 판정하면 규칙이 갈린다.
 */
function ResetToDefaultDialog({
  open,
  onOpenChange,
  onConfirm,
  confirming,
}: ResetToDefaultDialogProps): React.JSX.Element {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-md">
        <DialogHeader>
          <DialogTitle>{labels.reset.dialogTitle}</DialogTitle>
          <DialogDescription>{labels.reset.dialogDescription}</DialogDescription>
        </DialogHeader>
        <DialogFooter>
          <Button variant="ghost" onClick={() => onOpenChange(false)}>
            {labels.common.cancel}
          </Button>
          <Button disabled={confirming} onClick={onConfirm}>
            {labels.reset.confirm}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

export { ResetToDefaultDialog }
export type { ResetToDefaultDialogProps }
