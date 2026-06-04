// 이슈 클론 옵션 Dialog — includeAssignee 체크박스 + summaryOverride 입력 + 클론 실행
import type { JSX } from 'react'
import { useState, useEffect } from 'react'
import { Dialog as DialogPrimitive } from 'radix-ui'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { useCloneIssue } from '@/api/useCloneIssue'
import { issueDetailStrings } from '@/i18n/ko'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

interface CloneIssueDialogProps {
  /** 클론할 원본 이슈 식별 키 */
  readonly issueKey: string
  /** Dialog 열림 여부 */
  readonly open: boolean
  /** Dialog 열림 상태 변경 핸들러 */
  readonly onOpenChange: (o: boolean) => void
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 클론 옵션 Dialog.
 *
 * - includeAssignee 체크박스 (기본 체크) — 담당자를 클론본에 포함할지 선택
 * - summaryOverride 텍스트 입력 (maxLength 255, 선택) — 클론본의 새 제목
 * - 클론 생성 버튼 클릭 시 useCloneIssue.mutate 호출
 * - 성공 시 Dialog 자동 닫힘 (useCloneIssue.onSuccess에서 navigate + toast)
 * - 에러는 useCloneIssue.onError toast로 처리 — Dialog는 오픈 유지 (재시도 가능)
 * - Dialog 닫히면 상태(includeAssignee/summaryOverride) 초기화
 *
 * Dialog가 mutation을 직접 소유하므로 submitError prop(dead-path) 패턴 사용 금지.
 */
export function CloneIssueDialog({
  issueKey,
  open,
  onOpenChange,
}: CloneIssueDialogProps): JSX.Element {
  const [includeAssignee, setIncludeAssignee] = useState(true)
  const [summaryOverride, setSummaryOverride] = useState('')

  const cloneMutation = useCloneIssue()

  // open이 false로 바뀔 때 상태 초기화
  useEffect(() => {
    if (!open) {
      setIncludeAssignee(true)
      setSummaryOverride('')
    }
  }, [open])

  function handleOpenChange(next: boolean): void {
    onOpenChange(next)
  }

  async function handleSubmit(): Promise<void> {
    const input: { includeAssignee?: boolean; summaryOverride?: string } = {
      includeAssignee,
    }
    if (summaryOverride.trim() !== '') {
      input.summaryOverride = summaryOverride.trim()
    }
    try {
      await cloneMutation.mutateAsync({ key: issueKey, input })
      onOpenChange(false)
    } catch {
      // 에러 처리는 useCloneIssue.onError의 toast가 담당.
      // mutateAsync는 onError 호출 후에도 reject를 re-throw하므로 여기서 catch해 unhandled rejection을 방지.
    }
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
            {issueDetailStrings.cloneDialogTitle}
          </DialogPrimitive.Title>

          <div className="space-y-4">
            {/* 담당자 포함 체크박스 */}
            <div className="flex items-center gap-2">
              <input
                type="checkbox"
                id="clone-include-assignee"
                aria-label={issueDetailStrings.cloneIncludeAssigneeLabel}
                checked={includeAssignee}
                onChange={(e) => setIncludeAssignee(e.target.checked)}
                className="size-4 rounded border-input accent-primary"
              />
              <label
                htmlFor="clone-include-assignee"
                className="text-sm font-medium select-none cursor-pointer"
              >
                {issueDetailStrings.cloneIncludeAssigneeLabel}
              </label>
            </div>

            {/* 제목 재정의 입력 */}
            <div>
              <label
                htmlFor="clone-summary-override"
                className="text-sm font-medium mb-1 block"
              >
                {issueDetailStrings.cloneSummaryOverrideLabel}
              </label>
              <Input
                id="clone-summary-override"
                aria-label={issueDetailStrings.cloneSummaryOverrideLabel}
                value={summaryOverride}
                onChange={(e) => setSummaryOverride(e.target.value)}
                maxLength={255}
                placeholder={issueDetailStrings.cloneSummaryOverridePlaceholder}
                className="w-full"
              />
            </div>
          </div>

          {/* 액션 버튼 */}
          <div className="flex justify-end gap-2 mt-6">
            <DialogPrimitive.Close asChild>
              <Button variant="outline" size="sm">
                {issueDetailStrings.cloneCancelButton}
              </Button>
            </DialogPrimitive.Close>
            <Button
              size="sm"
              disabled={cloneMutation.isPending}
              onClick={() => { void handleSubmit() }}
            >
              {issueDetailStrings.cloneSubmitButton}
            </Button>
          </div>
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  )
}
