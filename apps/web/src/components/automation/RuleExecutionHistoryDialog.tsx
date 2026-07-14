// 자동화 룰 실행 이력 조회 Dialog — issueKey 필터·커서 무한스크롤(더 보기)·재실행 결과 토스트+자동펼침 통합 (FR-AT-05 D6/D7)
import type { FormEvent, JSX } from 'react'
import { useState } from 'react'
import { Dialog as DialogPrimitive } from 'radix-ui'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { useRuleExecutions } from '@/api/useAutomationExecutions'
import { extractAutomationExecutionErrorCode } from '@/api/automation-executions'
import { RuleExecutionTraceRow } from './RuleExecutionTraceRow'
import type { RuleExecutionDetail } from '@/api/automation-executions.types'

// ─────────────────────────────────────────────────────────────────────────────
// 문구 — BC 내 고정 한국어 (AutomationRuleList.tsx/RuleConflictWarningModal.tsx 선례,
// 별도 i18n 파일 미도입 BC 관례)
// ─────────────────────────────────────────────────────────────────────────────

const labels = {
  titleSuffix: ' 실행 이력',
  fallbackRuleName: '자동화 룰',
  issueKeyFilterLabel: '이슈 키 필터',
  issueKeyFilterPlaceholder: '예: ATLAS-100',
  filterApplyButton: '적용',
  loadingStatus: '실행 이력 로딩 중',
  emptyMessage: '실행 이력이 없습니다.',
  accessDenied: '권한이 없습니다.',
  genericError: '실행 이력을 불러오지 못했습니다.',
  loadMoreButton: '더 보기',
  close: '닫기',
  replaySuccessToast: '재실행이 완료되었습니다.',
  replayUnavailableToast: '재실행 대상 자동화 룰을 더 이상 사용할 수 없습니다',
  replayGenericErrorToast: '재실행에 실패했습니다.',
} as const

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** RuleExecutionHistoryDialog props */
export interface RuleExecutionHistoryDialogProps {
  /** Dialog 열림 여부 (controlled) */
  readonly open: boolean
  /** 열림 상태 변경 콜백 — 바깥 클릭·Esc·닫기 버튼에서 호출된다 */
  readonly onOpenChange: (open: boolean) => void
  /** 프로젝트 키 */
  readonly projectKey: string
  /** 조회 대상 자동화 룰 UUID. null이면 선택된 룰이 없어 Dialog를 렌더하지 않는다 */
  readonly ruleId: string | null
  /** Dialog 제목에 표시할 룰 이름. null이면 대체 문구를 사용한다 */
  readonly ruleName: string | null
}

// ─────────────────────────────────────────────────────────────────────────────
// RuleExecutionHistoryDialogBody — 내부 상태(필터·자동펼침) + 목록 조회/렌더
// ─────────────────────────────────────────────────────────────────────────────

interface RuleExecutionHistoryDialogBodyProps {
  readonly projectKey: string
  readonly ruleId: string
  readonly ruleName: string | null
}

/**
 * Dialog 본문 — 실제 상태(issueKey 필터 입력/적용값·자동펼침 대상 id)와 `useRuleExecutions` 조회를
 * 소유한다. 호출부({@link RuleExecutionHistoryDialog})가 `open`/`ruleId` 조합을 key로 이 컴포넌트를
 * 재마운트하므로, 이 컴포넌트 자신은 별도 리셋 로직 없이 useState 초기값만으로 항상 깨끗한 상태로
 * 시작한다(react-usestate-stale-key-prop 교훈, AutomationRuleFormDialog.tsx FormBody 선례 동형).
 */
function RuleExecutionHistoryDialogBody({ projectKey, ruleId, ruleName }: RuleExecutionHistoryDialogBodyProps): JSX.Element {
  const [issueKeyInput, setIssueKeyInput] = useState('')
  const [appliedIssueKey, setAppliedIssueKey] = useState<string | undefined>(undefined)
  const [autoExpandId, setAutoExpandId] = useState<string | null>(null)

  const { executions, fetchNextPage, hasNextPage, isFetchingNextPage, isLoading, error } = useRuleExecutions(
    projectKey,
    ruleId,
    { issueKey: appliedIssueKey },
  )

  function handleFilterSubmit(event: FormEvent<HTMLFormElement>): void {
    event.preventDefault()
    const trimmed = issueKeyInput.trim()
    setAppliedIssueKey(trimmed === '' ? undefined : trimmed)
  }

  function handleReplaySuccess(newDetail: RuleExecutionDetail): void {
    toast.success(labels.replaySuccessToast)
    setAutoExpandId(newDetail.id)
  }

  function handleReplayError(replayError: unknown): void {
    if (extractAutomationExecutionErrorCode(replayError) === 'AUTOMATION_RULE_UNAVAILABLE') {
      toast.error(labels.replayUnavailableToast)
      return
    }
    toast.error(labels.replayGenericErrorToast)
  }

  const accessDenied = extractAutomationExecutionErrorCode(error) === 'AUTOMATION_ACCESS_DENIED'

  return (
    <>
      <DialogPrimitive.Title className="text-lg font-semibold mb-1">
        {(ruleName ?? labels.fallbackRuleName) + labels.titleSuffix}
      </DialogPrimitive.Title>

      <form onSubmit={handleFilterSubmit} className="mt-4 flex gap-2">
        <Input
          aria-label={labels.issueKeyFilterLabel}
          placeholder={labels.issueKeyFilterPlaceholder}
          value={issueKeyInput}
          onChange={(event) => setIssueKeyInput(event.target.value)}
        />
        <Button type="submit" variant="outline" size="sm">
          {labels.filterApplyButton}
        </Button>
      </form>

      <div className="mt-4 max-h-[60vh] space-y-2 overflow-y-auto">
        {isLoading && (
          <p role="status" className="py-8 text-center text-sm text-muted-foreground">
            {labels.loadingStatus}
          </p>
        )}

        {!isLoading && error !== null && (
          <p className="text-sm text-destructive">{accessDenied ? labels.accessDenied : labels.genericError}</p>
        )}

        {!isLoading && error === null && executions.length === 0 && (
          <p className="py-8 text-center text-sm text-muted-foreground">{labels.emptyMessage}</p>
        )}

        {!isLoading && error === null && executions.length > 0 && (
          <ul className="space-y-2">
            {executions.map((execution) => (
              <RuleExecutionTraceRow
                key={execution.id}
                execution={execution}
                projectKey={projectKey}
                ruleId={ruleId}
                defaultExpanded={execution.id === autoExpandId}
                onReplaySuccess={handleReplaySuccess}
                onReplayError={handleReplayError}
              />
            ))}
          </ul>
        )}

        {hasNextPage && (
          <div className="flex justify-center pt-2">
            <Button
              variant="outline"
              size="sm"
              disabled={isFetchingNextPage}
              onClick={() => {
                void fetchNextPage()
              }}
            >
              {labels.loadMoreButton}
            </Button>
          </div>
        )}
      </div>

      <div className="mt-6 flex justify-end">
        <DialogPrimitive.Close asChild>
          <Button variant="outline" size="sm">
            {labels.close}
          </Button>
        </DialogPrimitive.Close>
      </div>
    </>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// RuleExecutionHistoryDialog
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 자동화 룰 실행 이력 조회 Dialog.
 *
 * - `ruleId`가 null이면 선택된 룰이 없다는 뜻이므로 아무것도 렌더하지 않는다(RuleConflictWarningModal.tsx/
 *   WebhookTokenModal.tsx의 "식별값 null이면 미표시" 관례 동형).
 * - issueKey 필터는 exact match라 입력마다 재조회하지 않고, `<form onSubmit>`으로 Enter/"적용" 클릭
 *   시점에만 `useRuleExecutions`에 전달한다 — 적용값이 바뀌면 쿼리키가 바뀌어 페이지 누적이 자동 리셋된다.
 * - 목록은 `RuleExecutionTraceRow`(펼침 상세·인라인 재실행)에 위임한다. 로딩·에러(403은 권한 메시지로
 *   별도 분기)·빈 상태·목록 4분기는 AutomationRuleList.tsx 관례를 그대로 미러한다.
 * - `hasNextPage`면 "더 보기" 버튼으로 `fetchNextPage`를 호출한다(`isFetchingNextPage` 중 disabled).
 * - 재실행 성공(`onReplaySuccess`)이면 토스트 + `autoExpandId`를 새 실행 id로 설정해 해당 행을
 *   `defaultExpanded`로 자동 펼친다(그 행은 이번에 처음 마운트되는 새 `key`라 초기값이 바로 반영된다).
 *   실패(`onReplayError`)면 409(AUTOMATION_RULE_UNAVAILABLE) 전용 문구, 그 외는 일반 실패 토스트.
 * - `open`/`ruleId` 조합을 key로 {@link RuleExecutionHistoryDialogBody}를 재마운트해 필터 입력·자동펼침
 *   상태가 Dialog가 닫히거나 대상 룰이 바뀔 때 잔존하지 않게 한다(react-usestate-stale-key-prop 교훈,
 *   AutomationRuleFormDialog.tsx `formKey` 선례 동형).
 *
 * @param open Dialog 열림 여부
 * @param onOpenChange 열림 상태 변경 콜백
 * @param projectKey 프로젝트 키
 * @param ruleId 조회 대상 자동화 룰 UUID (null이면 미렌더)
 * @param ruleName Dialog 제목에 표시할 룰 이름
 */
export function RuleExecutionHistoryDialog({
  open,
  onOpenChange,
  projectKey,
  ruleId,
  ruleName,
}: RuleExecutionHistoryDialogProps): JSX.Element | null {
  if (ruleId === null) return null

  const bodyKey = `${open ? 'open' : 'closed'}:${ruleId}`

  return (
    <DialogPrimitive.Root open={open} onOpenChange={onOpenChange}>
      <DialogPrimitive.Portal>
        <DialogPrimitive.Overlay className="fixed inset-0 z-50 bg-black/40 data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0" />

        <DialogPrimitive.Content
          data-testid="rule-execution-history-dialog"
          className="fixed left-1/2 top-1/2 z-50 w-full max-w-2xl -translate-x-1/2 -translate-y-1/2 rounded-xl bg-background p-6 shadow-xl data-[state=open]:animate-in data-[state=closed]:animate-out data-[state=closed]:fade-out-0 data-[state=open]:fade-in-0 data-[state=closed]:zoom-out-95 data-[state=open]:zoom-in-95"
        >
          <RuleExecutionHistoryDialogBody key={bodyKey} projectKey={projectKey} ruleId={ruleId} ruleName={ruleName} />
        </DialogPrimitive.Content>
      </DialogPrimitive.Portal>
    </DialogPrimitive.Root>
  )
}
