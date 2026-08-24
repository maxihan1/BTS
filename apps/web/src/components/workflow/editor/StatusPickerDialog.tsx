// 워크플로우에 편성할 상태를 카탈로그에서 고르는 다이얼로그
import * as React from 'react'
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription, DialogFooter } from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import { Combobox } from '@/components/ui/combobox'
import { workflowEditorLabels as labels } from '@/i18n/workflow-editor-labels'
import type { StatusCatalogEntry } from '@/api/workflows-admin'

interface StatusPickerDialogProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  /** 전역 카탈로그 전체 */
  catalog: StatusCatalogEntry[]
  /** 이미 편성된 상태 키 — 목록에서 뺀다 */
  usedKeys: string[]
  /** 고른 상태 id 로 편성 요청 */
  onAdd: (statusId: string) => void
  /** 처리 중 */
  adding?: boolean
}

/**
 * 카탈로그에서 하나를 골라 편성한다.
 *
 * **이미 편성된 상태는 후보에서 뺀다.** 남겨 두면 고를 수 있는데 서버가 순서만 갱신해
 * 「추가했는데 안 늘어난다」로 보인다 — 조작과 결과가 어긋나는 자리다.
 */
function StatusPickerDialog({
  open,
  onOpenChange,
  catalog,
  usedKeys,
  onAdd,
  adding = false,
}: StatusPickerDialogProps): React.JSX.Element {
  const [selected, setSelected] = React.useState<string | null>(null)
  // popover 를 다이얼로그 **안쪽**에 그린다 — body 로 나가면 다이얼로그의 aria-hidden 덮개에
  // 걸려 옵션이 접근성 트리에서 사라진다.
  const [portalHost, setPortalHost] = React.useState<HTMLElement | null>(null)
  const candidates = catalog.filter((s) => !usedKeys.includes(s.key))

  // 열 때마다 고른 값을 비운다. 안 비우면 지난번 선택이 남아 「고르지 않았는데 추가되는」
  // 자리가 생긴다.
  React.useEffect(() => {
    if (open) {
      setSelected(null)
    }
  }, [open])

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-md" ref={setPortalHost}>
        <DialogHeader>
          <DialogTitle>{labels.dialog.statusPicker}</DialogTitle>
          <DialogDescription>{labels.nav.description}</DialogDescription>
        </DialogHeader>
        <Combobox
          options={candidates.map((s) => ({
            value: s.id,
            label: s.name,
            ...(s.description !== null ? { description: s.description } : {}),
          }))}
          value={selected}
          onChange={setSelected}
          ariaLabel={labels.dialog.statusPicker}
          placeholder={labels.statusSearchPlaceholder}
          emptyText={labels.statusPanel.empty}
          triggerPlaceholder={labels.statusSearchPlaceholder}
          container={portalHost}
        />
        <DialogFooter>
          <Button variant="ghost" onClick={() => onOpenChange(false)}>
            {labels.dialog.cancel}
          </Button>
          <Button
            disabled={selected === null || adding}
            onClick={() => {
              if (selected !== null) {
                onAdd(selected)
                onOpenChange(false)
              }
            }}
          >
            {labels.statusPanel.add}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

export { StatusPickerDialog }
export type { StatusPickerDialogProps }
