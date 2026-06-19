// 이슈 일정 필드 편집 컴포넌트 — 시작일·마감일·목표일 네이티브 date input, updateIssue OCC 패턴
import type { JSX } from 'react'
import { useState, useEffect, useRef } from 'react'
import { toast } from 'sonner'
import { useQueryClient } from '@tanstack/react-query'
import { updateIssue } from '@/api/issues'
import type { IssueResponse } from '@/api/issues'
import { ApiError } from '@/api/client'
import { issueQueryKey } from '@/api/useUpdateIssueSummary'
import { Button } from '@/components/ui/button'
import { issueDetailStrings } from '@/i18n/ko'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** IssueScheduleFields props */
export interface IssueScheduleFieldsProps {
  /** 렌더할 이슈 데이터 — startDate·dueDate·targetDate 필드를 포함한다 */
  issue: IssueResponse
  /** 수정 권한 — false이면 입력·저장 버튼 disabled (fail-closed) */
  disabled?: boolean
}

// ─────────────────────────────────────────────────────────────────────────────
// 로컬 draft 타입 — 3-state를 로컬 상태로 표현
// - '' (빈 문자열): input이 비어 있는 상태. 저장 시 null(클리어)로 변환
// - 'yyyy-MM-dd': 날짜 설정 상태. 저장 시 그대로 전송
// ─────────────────────────────────────────────────────────────────────────────

/** 날짜 input local draft — '' = 비어있음, 'yyyy-MM-dd' = 설정 */
interface ScheduleDraft {
  startDate: string
  dueDate: string
  targetDate: string
}

/**
 * null 또는 undefined인 날짜 값을 빈 문자열로 변환한다.
 * input[type=date]의 value prop에 사용.
 *
 * @param value - 백엔드 날짜 값 또는 null/undefined
 * @returns input value로 사용할 문자열 — '' 또는 'yyyy-MM-dd'
 */
function toInputValue(value: string | null | undefined): string {
  return value ?? ''
}

/**
 * input value(빈 문자열 또는 'yyyy-MM-dd')를 3-state 전송값으로 변환한다.
 * - '' → null (클리어 — 키가 존재하고 값은 null, C2 요구사항)
 * - 'yyyy-MM-dd' → 해당 문자열 (설정)
 *
 * @param inputVal - input.value 문자열
 * @returns UpdateIssueInput에 포함할 값 — null 또는 'yyyy-MM-dd' 문자열
 */
function toApiValue(inputVal: string): string | null {
  return inputVal === '' ? null : inputVal
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 일정 필드 편집 컴포넌트 (FR-PL-01).
 *
 * - 시작일·마감일·목표일 3개 네이티브 `<input type="date">` 제공 (신규 의존성 0)
 * - 저장 버튼 클릭 시 3필드를 한꺼번에 updateIssue 호출 (OCC version round-trip)
 * - 비우기 시 해당 필드를 null로 명시 전송 (키 생략=무변경 아님 — C2)
 * - issue props가 바뀌면(refetch) 로컬 상태도 동기화 (stale 방지)
 * - disabled=true이면 입력 및 저장 버튼 비활성 (fail-closed)
 * - WCAG AA: min-h-[44px] 버튼, aria-label
 */
export function IssueScheduleFields({
  issue,
  disabled = false,
}: IssueScheduleFieldsProps): JSX.Element {
  const queryClient = useQueryClient()
  const [isPending, setIsPending] = useState(false)

  const [draft, setDraft] = useState<ScheduleDraft>({
    startDate: toInputValue(issue.startDate),
    dueDate: toInputValue(issue.dueDate),
    targetDate: toInputValue(issue.targetDate),
  })

  // issue props가 바뀌면(refetch 후) 로컬 편집 상태를 동기화한다 — stale 방지
  const prevIssueRef = useRef(issue)
  useEffect(() => {
    if (prevIssueRef.current !== issue) {
      prevIssueRef.current = issue
      setDraft({
        startDate: toInputValue(issue.startDate),
        dueDate: toInputValue(issue.dueDate),
        targetDate: toInputValue(issue.targetDate),
      })
    }
  }, [issue])

  /**
   * 저장 핸들러.
   * 3필드를 모두 포함해 updateIssue를 호출한다 (3-state: 빈 문자열 → null).
   * OCC version은 issue.version을 사용한다.
   * 409 버전 충돌 시 toast + invalidate(최신 데이터 재조회 유도).
   */
  async function handleSave(): Promise<void> {
    setIsPending(true)
    try {
      await updateIssue(issue.key, {
        startDate: toApiValue(draft.startDate),
        dueDate: toApiValue(draft.dueDate),
        targetDate: toApiValue(draft.targetDate),
        expectedVersion: issue.version,
      })
      await queryClient.invalidateQueries({ queryKey: issueQueryKey(issue.key) })
    } catch (err: unknown) {
      if (err instanceof ApiError && err.status === 409) {
        toast.error(issueDetailStrings.versionConflictError)
        await queryClient.invalidateQueries({ queryKey: issueQueryKey(issue.key) })
      } else {
        toast.error(issueDetailStrings.scheduleSaveError)
      }
    } finally {
      setIsPending(false)
    }
  }

  const isDisabled = disabled || isPending

  return (
    <div className="flex flex-col gap-3" data-testid="schedule-fields">
      {/* 시작일 */}
      <div className="flex flex-col gap-1">
        <label
          htmlFor="schedule-start-date"
          className="text-xs text-muted-foreground"
        >
          {issueDetailStrings.startDateLabel}
        </label>
        <input
          id="schedule-start-date"
          type="date"
          className="w-full rounded-md border border-input bg-background px-2 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-ring disabled:opacity-40 disabled:cursor-not-allowed"
          value={draft.startDate}
          onChange={(e) => setDraft((prev) => ({ ...prev, startDate: e.target.value }))}
          disabled={isDisabled}
          aria-label={issueDetailStrings.startDateLabel}
        />
      </div>

      {/* 마감일 */}
      <div className="flex flex-col gap-1">
        <label
          htmlFor="schedule-due-date"
          className="text-xs text-muted-foreground"
        >
          {issueDetailStrings.dueDateLabel}
        </label>
        <input
          id="schedule-due-date"
          type="date"
          className="w-full rounded-md border border-input bg-background px-2 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-ring disabled:opacity-40 disabled:cursor-not-allowed"
          value={draft.dueDate}
          onChange={(e) => setDraft((prev) => ({ ...prev, dueDate: e.target.value }))}
          disabled={isDisabled}
          aria-label={issueDetailStrings.dueDateLabel}
        />
      </div>

      {/* 목표일 */}
      <div className="flex flex-col gap-1">
        <label
          htmlFor="schedule-target-date"
          className="text-xs text-muted-foreground"
        >
          {issueDetailStrings.targetDateLabel}
        </label>
        <input
          id="schedule-target-date"
          type="date"
          className="w-full rounded-md border border-input bg-background px-2 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-ring disabled:opacity-40 disabled:cursor-not-allowed"
          value={draft.targetDate}
          onChange={(e) => setDraft((prev) => ({ ...prev, targetDate: e.target.value }))}
          disabled={isDisabled}
          aria-label={issueDetailStrings.targetDateLabel}
        />
      </div>

      {/* 저장 버튼 */}
      <Button
        variant="outline"
        size="sm"
        className="self-end min-h-[44px]"
        onClick={() => { void handleSave() }}
        disabled={isDisabled}
        aria-label={issueDetailStrings.scheduleSaveAriaLabel}
        data-testid="schedule-save"
      >
        {issueDetailStrings.scheduleSaveButton}
      </Button>
    </div>
  )
}
