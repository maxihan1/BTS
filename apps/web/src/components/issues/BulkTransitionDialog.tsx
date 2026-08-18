// 일괄 상태 전환 Dialog — 선택 이슈 가용 전환 교집합 노출 + BULK_TRANSITION 접수 (FR-IS-05, B14)
import type { JSX } from 'react'
import { useState, useEffect } from 'react'
import {
  Dialog,
  DialogClose,
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
import { fetchBulkAvailableTransitions } from '@/api/issues'
import type { IssueTransition } from '@/api/issues'
import { useSubmitBulkOperation } from '@/hooks/use-bulk-operation'
import { useResolutions } from '@/hooks/use-resolutions'
import { bulkOperationLabels } from '@/i18n/bulk-operation-labels'
import { resolutionLabels } from '@/i18n/resolution-labels'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

interface BulkTransitionDialogProps {
  /** 일괄 전환 대상 이슈 키 목록 */
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
 * 일괄 상태 전환 Dialog.
 *
 * 1. open 시 `fetchBulkAvailableTransitions(issueKeys)` 단일 호출로 서버가 계산한 교집합 전환을 받는다.
 * 2. transitions를 드롭다운에 노출한다.
 * 3. unresolvedIssueKeys가 1건 이상이면 경고 메시지를 표시한다.
 * 4. transitions가 0건이고 unresolvedIssueKeys도 없으면 교집합 없음 안내를 표시한다.
 * 5. transitions가 0건이고 unresolvedIssueKeys === issueKeys 전량이면 전량 실패 에러를 표시한다.
 * 6. 전환 선택 후 적용 시 issueKeys 전체를 백엔드에 전송한다.
 *    — 백엔드 best-effort가 개별 처리하므로 전체 목록을 그대로 보낸다.
 * 7. 선택한 전환의 toCategory가 'DONE'이면 resolution 드롭다운을 표시한다 (B14).
 *    — resolution 미선택 시 적용 버튼 비활성. 선택 시 transitionPayload.resolutionId 포함.
 * 8. 성공 시 onSubmitted(bulkOperationId)와 onOpenChange(false)를 호출한다.
 *
 * @param issueKeys 일괄 전환 대상 이슈 키 목록
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
  /** 서버가 계산한 공통 전환 목록 */
  const [transitions, setTransitions] = useState<IssueTransition[]>([])
  /** 조회 실패한 issueKey가 1건 이상이면 true */
  const [hasPartialFailure, setHasPartialFailure] = useState(false)
  /** 모든 issueKey 조회가 실패했으면 true (전량 실패) */
  const [hasTotalFailure, setHasTotalFailure] = useState(false)
  /** 선택된 toStateKey */
  const [selectedStateKey, setSelectedStateKey] = useState<string>('')
  /** DONE 전환 시 선택된 결의안 ID */
  const [selectedResolutionId, setSelectedResolutionId] = useState<string>('')

  const submitBulkOperation = useSubmitBulkOperation()
  const { data: resolutions = [] } = useResolutions()

  /** 현재 선택된 전환이 DONE 카테고리인지 여부 */
  const selectedTransition = transitions.find((t) => t.toStateKey === selectedStateKey)
  const isDoneTransition = selectedTransition?.toCategory === 'DONE'

  /**
   * Dialog가 열릴 때 fetchBulkAvailableTransitions를 단일 호출한다.
   * 서버가 교집합을 계산해 transitions + unresolvedIssueKeys를 반환한다.
   * cleanup 플래그로 stale in-flight 결과가 새 상태를 덮지 않도록 방어한다.
   */
  useEffect(() => {
    if (!open || issueKeys.length === 0) return

    let cancelled = false

    setTransitions([])
    setHasPartialFailure(false)
    setHasTotalFailure(false)
    setSelectedStateKey('')
    setSelectedResolutionId('')

    void fetchBulkAvailableTransitions(issueKeys).then((result) => {
      if (cancelled) return

      setTransitions(result.transitions)
      setHasPartialFailure(result.unresolvedIssueKeys.length > 0)
      setHasTotalFailure(
        result.transitions.length === 0 &&
        result.unresolvedIssueKeys.length === issueKeys.length,
      )
    })

    return () => {
      cancelled = true
    }
  }, [open, issueKeys])

  /** 교집합이 0건이고 전량 실패도 아닌 경우 — 공통 전환 없음 안내 */
  const hasNoCommonTransitions = !hasTotalFailure && transitions.length === 0 && !hasPartialFailure

  /**
   * 적용 버튼 활성 조건.
   * - 전량 실패 없음
   * - 전환이 있고 선택됨
   * - DONE 전환이면 resolution도 선택됨
   */
  const canSubmit =
    !hasTotalFailure &&
    selectedStateKey !== '' &&
    transitions.length > 0 &&
    (!isDoneTransition || selectedResolutionId !== '')

  function handleOpenChange(next: boolean): void {
    if (!next) {
      setSelectedStateKey('')
      setSelectedResolutionId('')
      setTransitions([])
      setHasPartialFailure(false)
      setHasTotalFailure(false)
    }
    onOpenChange(next)
  }

  function handleTransitionChange(value: string): void {
    setSelectedStateKey(value)
    // 전환 변경 시 resolution 선택 초기화
    setSelectedResolutionId('')
  }

  async function handleApply(): Promise<void> {
    const transitionPayload: { toStateKey: string; resolutionId?: string } = {
      toStateKey: selectedStateKey,
    }
    if (isDoneTransition && selectedResolutionId !== '') {
      transitionPayload.resolutionId = selectedResolutionId
    }
    try {
      const result = await submitBulkOperation.mutateAsync({
        operationType: 'BULK_TRANSITION',
        issueKeys,
        editPayload: null,
        transitionPayload,
      })
      onSubmitted(result.bulkOperationId)
      onOpenChange(false)
    } catch {
      // 에러 안내는 useSubmitBulkOperation.onError 의 toast 가 담당한다.
      // 잡지 않으면 호출부의 `void handleApply()` 를 통해 프로덕션 unhandled rejection 이 된다.
      // ★토스트를 여기에 추가하지 말 것 — 같은 실패 1회에 토스트가 2건 뜬다.
      // 정본 패턴. CloneIssueDialog.tsx 의 handleSubmit.
    }
  }

  return (
    <Dialog open={open} onOpenChange={handleOpenChange}>
      <DialogContent className="max-w-md" aria-describedby={undefined}>
        <DialogHeader>
          <DialogTitle>일괄 상태 전환</DialogTitle>
        </DialogHeader>

        <div className="space-y-4">
          {/* 전량 조회 실패 에러 — Select 숨김 + 적용 비활성 */}
          {hasTotalFailure ? (
            <p className="text-sm text-destructive bg-destructive/10 rounded-md px-3 py-2">
              전환 정보를 불러오지 못했습니다. 잠시 후 다시 시도해 주세요.
            </p>
          ) : (
            <>
              {/* 일부 조회 실패 경고 */}
              {hasPartialFailure && (
                <p className="text-sm text-warning-text bg-warning/10 rounded-md px-3 py-2">
                  일부 이슈의 전환 정보를 불러오지 못했습니다. 조회에 성공한 이슈 기준으로 공통 전환을 표시합니다.
                </p>
              )}

              {/* 교집합 0건 안내 */}
              {hasNoCommonTransitions ? (
                <p className="text-sm text-muted-foreground">
                  선택한 이슈들이 공통으로 이동할 수 있는 상태가 없습니다.
                </p>
              ) : (
                <div className="space-y-3">
                  <div>
                    <label htmlFor="bulk-transition-state" className="text-sm font-medium mb-1 block">
                      전환 상태
                    </label>
                    <Select value={selectedStateKey} onValueChange={handleTransitionChange}>
                      <SelectTrigger
                        id="bulk-transition-state"
                        className="w-full"
                        aria-label="전환 상태"
                      >
                        <SelectValue placeholder={bulkOperationLabels.statusSelectPlaceholder} />
                      </SelectTrigger>
                      <SelectContent>
                        {transitions.map((transition) => (
                          <SelectItem key={transition.toStateKey} value={transition.toStateKey}>
                            {transition.name}
                          </SelectItem>
                        ))}
                      </SelectContent>
                    </Select>
                  </div>

                  {/* DONE 전환 선택 시 resolution 드롭다운 (B14) */}
                  {isDoneTransition && (
                    <div>
                      <label htmlFor="bulk-transition-resolution" className="text-sm font-medium mb-1 block">
                        결의안
                      </label>
                      <Select value={selectedResolutionId} onValueChange={setSelectedResolutionId}>
                        <SelectTrigger
                          id="bulk-transition-resolution"
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
                  )}
                </div>
              )}
            </>
          )}
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
