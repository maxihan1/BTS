// 칸반 DONE 컬럼 이동 시 결의안(Resolution) 선택 모달 (FR-BD-01)
import type { JSX } from 'react'
import { useState } from 'react'
import {
  Dialog,
  DialogContent,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { Button } from '@/components/ui/button'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { useResolutions } from '@/hooks/use-resolutions'
import { resolutionLabels } from '@/i18n/resolution-labels'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** ResolutionPickerModal Props */
export interface ResolutionPickerModalProps {
  /** 모달 열림 여부 */
  open: boolean
  /**
   * 결의안 선택 후 확인 클릭 시 호출.
   * @param resolutionId 선택된 결의안 UUID
   */
  onConfirm: (resolutionId: string) => void
  /** 취소 또는 닫기 클릭 시 호출 */
  onCancel: () => void
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * DONE 컬럼으로 카드를 이동할 때 결의안을 선택하는 모달.
 *
 * - `useResolutions()`로 결의안 목록을 가져와 Select에 표시한다.
 * - 미선택 상태에서는 확인 버튼이 비활성이다 (BulkTransitionDialog 동일 패턴).
 * - 결의안이 0개이면 "설정된 해결 방안이 없습니다" 안내와 함께 확인이 비활성이다 (G3).
 * - 확인 클릭 → `onConfirm(resolutionId)`.
 * - 취소/닫기 클릭 → `onCancel()`.
 */
export function ResolutionPickerModal({
  open,
  onConfirm,
  onCancel,
}: ResolutionPickerModalProps): JSX.Element {
  const [selectedId, setSelectedId] = useState<string>('')
  const { data: resolutions = [] } = useResolutions()

  const hasResolutions = resolutions.length > 0
  const canConfirm = hasResolutions && selectedId !== ''

  function handleOpenChange(next: boolean): void {
    if (!next) {
      setSelectedId('')
      onCancel()
    }
  }

  function handleConfirm(): void {
    if (!canConfirm) return
    onConfirm(selectedId)
  }

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="max-w-sm" aria-describedby={undefined}>
        <DialogHeader>
          <DialogTitle>해결 방안 선택</DialogTitle>
        </DialogHeader>

        <div className="space-y-3">
          {hasResolutions ? (
            <div>
              <label
                htmlFor="resolution-picker-select"
                className="text-sm font-medium mb-1 block"
              >
                결의안
              </label>
              <Select value={selectedId} onValueChange={setSelectedId}>
                <SelectTrigger
                  id="resolution-picker-select"
                  className="w-full"
                  aria-label="결의안"
                >
                  <SelectValue placeholder={resolutionLabels.selectPlaceholder} />
                </SelectTrigger>
                <SelectContent>
                  {resolutions.map((resolution) => (
                    <SelectItem key={resolution.id} value={resolution.id}>
                      {resolution.name}
                    </SelectItem>
                  ))}
                </SelectContent>
              </Select>
            </div>
          ) : (
            <p className="text-sm text-muted-foreground">
              설정된 해결 방안이 없습니다
            </p>
          )}
        </div>

        <DialogFooter>
          <Button variant="outline" size="sm" onClick={onCancel}>
            취소
          </Button>
          <Button size="sm" disabled={!canConfirm} onClick={handleConfirm}>
            확인
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
