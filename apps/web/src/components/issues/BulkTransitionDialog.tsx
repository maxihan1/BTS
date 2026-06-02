// 일괄 상태 전이 Dialog — 선택 이슈 가용 전이 교집합 노출 + BULK_TRANSITION 접수 (FR-IS-05)
import type { JSX } from 'react'
import { useState, useEffect, useMemo } from 'react'
import { Dialog as DialogPrimitive } from 'radix-ui'
import { Button } from '@/components/ui/button'
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from '@/components/ui/select'
import { fetchIssueTransitions } from '@/api/issues'
import type { IssueTransition } from '@/api/issues'
import { intersectTransitions } from '@/lib/transition-intersection'
import { useSubmitBulkOperation } from '@/hooks/use-bulk-operation'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

interface BulkTransitionDialogProps {
  /** 일괄 전이 대상 이슈 키 목록 */
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
 * 일괄 상태 전이 Dialog.
 *
 * 1. open 시 모든 issueKey에 대해 `fetchIssueTransitions`를 병렬 호출한다.
 *    (`Promise.allSettled`로 부분 실패를 격리한다.)
 * 2. fulfilled 결과만 모아 `intersectTransitions`로 교집합을 계산해 드롭다운에 노출한다.
 * 3. rejected가 1건 이상이면 경고 메시지를 표시한다.
 * 4. 교집합이 0건이면 안내 메시지를 표시하고 적용 버튼을 비활성화한다.
 * 5. 전이 선택 후 적용 시 issueKeys 전체(조회 실패 포함)를 백엔드에 전송한다.
 *    — 백엔드 best-effort가 개별 처리하므로 전체 목록을 그대로 보낸다.
 * 6. 성공 시 onSubmitted(bulkOperationId)와 onOpenChange(false)를 호출한다.
 *
 * @param issueKeys 일괄 전이 대상 이슈 키 목록
 * @param open Dialog 열림 여부
 * @param onOpenChange Dialog 열림 상태 변경 핸들러
 * @param onSubmitted 접수 성공 콜백 — bulkOperationId 전달
 */
export function BulkTransitionDialog({
  issueKeys,
  open,
  onOpenChange,
  onSubmitted,
}: BulkTransitionDialogProps): JSX.Element {
  /** 이슈별 가용 전이 목록 (fulfilled 결과만) */
  const [perIssueTransitions, setPerIssueTransitions] = useState<IssueTransition[][]>([])
  /** 조회 실패한 issueKey가 1건 이상이면 true */
  const [hasPartialFailure, setHasPartialFailure] = useState(false)
  /** 선택된 toStateKey */
  const [selectedStateKey, setSelectedStateKey] = useState<string>('')

  const submitBulkOperation = useSubmitBulkOperation()

  /**
   * Dialog가 열릴 때 모든 issueKey의 가용 전이를 병렬 조회한다.
   * Promise.allSettled로 부분 실패를 격리하고 fulfilled 결과만 수집한다.
   */
  useEffect(() => {
    if (!open || issueKeys.length === 0) return

    setPerIssueTransitions([])
    setHasPartialFailure(false)
    setSelectedStateKey('')

    void Promise.allSettled(issueKeys.map((key) => fetchIssueTransitions(key))).then((results) => {
      const fulfilled: IssueTransition[][] = []
      let failureCount = 0

      for (const result of results) {
        if (result.status === 'fulfilled') {
          fulfilled.push(result.value)
        } else {
          failureCount++
        }
      }

      setPerIssueTransitions(fulfilled)
      setHasPartialFailure(failureCount > 0)
    })
  }, [open, issueKeys])

  /** 교집합 전이 목록 — fulfilled 결과 기반 */
  const intersected = useMemo(() => intersectTransitions(perIssueTransitions), [perIssueTransitions])

  /** 교집합이 0건인지 여부 */
  const hasNoCommonTransitions = perIssueTransitions.length > 0 && intersected.length === 0

  /** 적용 버튼 활성 조건: 교집합이 있고 전이가 선택된 경우 */
  const canSubmit = selectedStateKey !== '' && intersected.length > 0

  function handleOpenChange(next: boolean): void {
    if (!next) {
      setSelectedStateKey('')
      setPerIssueTransitions([])
      setHasPartialFailure(false)
    }
    onOpenChange(next)
  }

  function handleTransitionChange(value: string): void {
    setSelectedStateKey(value)
  }

  async function handleApply(): Promise<void> {
    const result = await submitBulkOperation.mutateAsync({
      operationType: 'BULK_TRANSITION',
      issueKeys,
      editPayload: null,
      transitionPayload: { toStateKey: selectedStateKey },
    })
    onSubmitted(result.bulkOperationId)
    onOpenChange(false)
  }

  return (
    <DialogPrimitive.Root open={open} onOpenChange={handleOpenChange}>
      <DialogPrimitive.Portal>
        <DialogPrimitive.Overlay className="fixed inset-0 z-50 bg-black/40 data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0" />

        <DialogPrimitive.Content
          className="fixed left-1/2 top-1/2 z-50 w-full max-w-md -translate-x-1/2 -translate-y-1/2 rounded-xl bg-background p-6 shadow-xl data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0 data-[state=closed]:zoom-out-95 data-[state=open]:zoom-in-95"
          aria-describedby={undefined}
        >
          <DialogPrimitive.Title className="text-lg font-semibold mb-4">
            일괄 상태 전이
          </DialogPrimitive.Title>

          <div className="space-y-4">
            {/* 일부 조회 실패 경고 */}
            {hasPartialFailure && (
              <p className="text-sm text-amber-600 bg-amber-50 rounded-md px-3 py-2">
                일부 이슈의 전이 정보를 불러오지 못했습니다. 조회에 성공한 이슈 기준으로 공통 전이를 표시합니다.
              </p>
            )}

            {/* 교집합 0건 안내 */}
            {hasNoCommonTransitions ? (
              <p className="text-sm text-muted-foreground">
                선택한 이슈들이 공통으로 이동할 수 있는 상태가 없습니다.
              </p>
            ) : (
              <div>
                <label htmlFor="bulk-transition-state" className="text-sm font-medium mb-1 block">
                  전이 상태
                </label>
                <Select value={selectedStateKey} onValueChange={handleTransitionChange}>
                  <SelectTrigger
                    id="bulk-transition-state"
                    className="w-full"
                    aria-label="전이 상태"
                  >
                    <SelectValue placeholder="상태를 선택하세요" />
                  </SelectTrigger>
                  <SelectContent>
                    {intersected.map((transition) => (
                      <SelectItem key={transition.toStateKey} value={transition.toStateKey}>
                        {transition.name}
                      </SelectItem>
                    ))}
                  </SelectContent>
                </Select>
              </div>
            )}
          </div>

          {/* 액션 버튼 */}
          <div className="flex justify-end gap-2 mt-6">
            <DialogPrimitive.Close asChild>
              <Button variant="outline" size="sm">
                취소
              </Button>
            </DialogPrimitive.Close>
            <Button
              size="sm"
              disabled={!canSubmit || submitBulkOperation.isPending}
              onClick={() => { void handleApply() }}
            >
              적용
            </Button>
          </div>
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  )
}
