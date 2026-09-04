// 보드 설정 — 컬럼 추가 다이얼로그 (부채 177 R6 · J23 · 편차 X1)
import type { FormEvent, JSX } from 'react'
import { useState } from 'react'
import { boardLabels } from '@/i18n/board-labels'
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'

/** AddColumnDialog props */
export interface AddColumnDialogProps {
  /** 열림 상태 — 제어 컴포넌트다 */
  open: boolean
  /** 열림 상태 변경 요청 */
  onOpenChange: (open: boolean) => void
  /** 제출. 성공 시 부모가 닫는다 — 이 컴포넌트는 스스로 닫지 않는다. */
  onSubmit: (name: string) => void
  /** 전송 중 — 이중 제출을 막는다 */
  submitting: boolean
  /** 실패 사유. 창 **안**에 싣는다 — 화면 배너로 그리면 오버레이가 가린다. */
  error?: string
}

/**
 * 컬럼을 만드는 다이얼로그 (J23).
 *
 * ### 지라와 다른 점 — 카테고리를 묻지 않는다 (편차 X1)
 * 지라는 *"Enter a name for the new column and select its category"* 로 카테고리를 고르게
 * 한다. BTS 는 담은 상태들의 category 최댓값으로 **파생**하므로 물을 것이 없다 — 물으면
 * 파생값과 선택값이라는 두 번째 진실이 생긴다(#444 ADR D5·D7).
 *
 * ### 만들어지는 것은 상태 0개 컬럼이다
 * 그것이 지라의 「컬럼 먼저, 상태는 드래그로」 흐름(J23→J27)이 요구하는 중간 상태다.
 * 설정 화면이 그 컬럼을 「상태 없음」으로 명시하므로 미완성임이 보인다(E1).
 */
export function AddColumnDialog({
  open,
  onOpenChange,
  onSubmit,
  submitting,
  error,
}: AddColumnDialogProps): JSX.Element {
  const [name, setName] = useState('')

  function handleSubmit(event: FormEvent<HTMLFormElement>): void {
    event.preventDefault()
    // 공백만 있는 이름은 서버도 400 으로 막지만, 왕복 없이 여기서 끝낸다.
    if (name.trim() === '') return
    onSubmit(name.trim())
  }

  return (
    <Dialog
      open={open}
      onOpenChange={(next) => {
        // 닫힐 때 입력을 비운다 — 다음에 연 창에 지난 입력이 남아 있으면 「내가 뭘 쓰던
        // 중이었나」가 되고, 실패로 닫힌 창의 값이 되살아난 것처럼 보인다.
        if (!next) setName('')
        onOpenChange(next)
      }}
    >
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{boardLabels.settings.addColumn}</DialogTitle>
        </DialogHeader>

        <form onSubmit={handleSubmit} className="space-y-4">
          <div className="space-y-2">
            <Label htmlFor="board-settings-new-column-name">
              {boardLabels.settings.columnNameLabel}
            </Label>
            <Input
              id="board-settings-new-column-name"
              value={name}
              onChange={(e) => {
                setName(e.target.value)
              }}
              autoFocus
            />
          </div>

          {error !== undefined && (
            <p className="text-destructive text-sm" role="alert">
              {error}
            </p>
          )}

          <DialogFooter>
            <Button
              type="button"
              variant="ghost"
              onClick={() => {
                onOpenChange(false)
              }}
            >
              {boardLabels.settings.cancel}
            </Button>
            <Button type="submit" disabled={submitting || name.trim() === ''}>
              {boardLabels.settings.addColumnSubmit}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
