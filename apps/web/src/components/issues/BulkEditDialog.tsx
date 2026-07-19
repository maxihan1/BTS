// 일괄 편집 Dialog — priority(1~5)/impact(1~3) merge-patch 변경 (FR-IS-05)
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
import { useSubmitBulkOperation } from '@/hooks/use-bulk-operation'

// ─────────────────────────────────────────────────────────────────────────────
// 라벨 상수
// ─────────────────────────────────────────────────────────────────────────────

/** priority 1~5 표시 라벨 — 1=Highest, 2=High, 3=Medium, 4=Low, 5=Lowest */
const PRIORITY_LABELS: ReadonlyArray<{ readonly value: number; readonly label: string }> = [
  { value: 1, label: 'Highest' },
  { value: 2, label: 'High' },
  { value: 3, label: 'Medium' },
  { value: 4, label: 'Low' },
  { value: 5, label: 'Lowest' },
]

/** impact 1~3 표시 라벨 — 1=High, 2=Medium, 3=Low */
const IMPACT_LABELS: ReadonlyArray<{ readonly value: number; readonly label: string }> = [
  { value: 1, label: 'High' },
  { value: 2, label: 'Medium' },
  { value: 3, label: 'Low' },
]

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

interface BulkEditDialogProps {
  /** 일괄 변경 대상 이슈 키 목록 */
  readonly issueKeys: string[]
  /** Dialog 열림 여부 */
  readonly open: boolean
  /** Dialog 열림 상태 변경 핸들러 */
  readonly onOpenChange: (o: boolean) => void
  /** 작업 접수 성공 시 bulkOperationId를 전달하는 콜백 */
  readonly onSubmitted: (bulkOperationId: string) => void
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 일괄 편집 Dialog.
 *
 * - priority(1~5), impact(1~3) 네이티브 select로 변경 값을 입력받는다.
 * - 둘 다 미선택(무변경)이면 적용 버튼이 비활성화된다 (EC2).
 * - 적용 시 editPayload를 구성해 useSubmitBulkOperation.mutateAsync를 호출한다.
 *   미선택 필드는 null(무변경)로 전달된다.
 * - 성공 시 onSubmitted(bulkOperationId)와 onOpenChange(false)를 호출한다.
 *
 * @param issueKeys 일괄 변경 대상 이슈 키 목록
 * @param open Dialog 열림 여부
 * @param onOpenChange Dialog 열림 상태 변경 핸들러
 * @param onSubmitted 접수 성공 콜백 — bulkOperationId 전달
 */
export function BulkEditDialog({
  issueKeys,
  open,
  onOpenChange,
  onSubmitted,
}: BulkEditDialogProps): JSX.Element {
  const [priority, setPriority] = useState<number | null>(null)
  const [impact, setImpact] = useState<number | null>(null)

  const submitBulkOperation = useSubmitBulkOperation()

  /** 적용 버튼 활성 조건 — priority 또는 impact 중 하나 이상 선택 */
  const canSubmit = priority !== null || impact !== null

  function handleOpenChange(next: boolean): void {
    if (!next) {
      setPriority(null)
      setImpact(null)
    }
    onOpenChange(next)
  }

  function handlePriorityChange(e: React.ChangeEvent<HTMLSelectElement>): void {
    const val = e.target.value
    setPriority(val === '' ? null : parseInt(val, 10))
  }

  function handleImpactChange(e: React.ChangeEvent<HTMLSelectElement>): void {
    const val = e.target.value
    setImpact(val === '' ? null : parseInt(val, 10))
  }

  async function handleApply(): Promise<void> {
    const result = await submitBulkOperation.mutateAsync({
      operationType: 'BULK_EDIT',
      issueKeys,
      editPayload: { priority, impact },
      transitionPayload: null,
    })
    onSubmitted(result.bulkOperationId)
    onOpenChange(false)
  }

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="max-w-md" aria-describedby={undefined}>
        <DialogHeader>
          <DialogTitle>일괄 편집</DialogTitle>
        </DialogHeader>

        <div className="space-y-4">
          {/* Priority 선택 */}
          <div>
            <label htmlFor="bulk-edit-priority" className="text-sm font-medium mb-1 block">
              Priority
            </label>
            <select
              id="bulk-edit-priority"
              aria-label="priority"
              value={priority !== null ? String(priority) : ''}
              onChange={handlePriorityChange}
              className="w-full rounded-md border border-input bg-transparent px-3 py-2 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
            >
              <option value="">무변경</option>
              {PRIORITY_LABELS.map(({ value, label }) => (
                <option key={value} value={String(value)}>
                  {label}
                </option>
              ))}
            </select>
          </div>

          {/* Impact 선택 */}
          <div>
            <label htmlFor="bulk-edit-impact" className="text-sm font-medium mb-1 block">
              Impact
            </label>
            <select
              id="bulk-edit-impact"
              aria-label="impact"
              value={impact !== null ? String(impact) : ''}
              onChange={handleImpactChange}
              className="w-full rounded-md border border-input bg-transparent px-3 py-2 text-sm outline-none focus:border-ring focus:ring-2 focus:ring-ring/20"
            >
              <option value="">무변경</option>
              {IMPACT_LABELS.map(({ value, label }) => (
                <option key={value} value={String(value)}>
                  {label}
                </option>
              ))}
            </select>
          </div>
        </div>

        {/* 액션 버튼 */}
        <DialogFooter>
          <DialogClose asChild>
            <Button variant="outline" size="sm">
              취소
            </Button>
          </DialogClose>
          <Button
            size="sm"
            disabled={!canSubmit || submitBulkOperation.isPending}
            onClick={() => { void handleApply() }}
          >
            적용
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
