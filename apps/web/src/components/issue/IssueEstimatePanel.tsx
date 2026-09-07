// 이슈 추정 시간 편집 패널 — 원 추정·잔여 추정 h/m 입력, 기록 시간 읽기 전용 표시
import type { JSX } from 'react'
import { useState, useEffect, useRef } from 'react'
import { toast } from 'sonner'
import { useQueryClient } from '@tanstack/react-query'
import { updateIssue } from '@/api/issues'
import type { IssueResponse } from '@/api/issues'
import { ApiError } from '@/api/client'
import { Button } from '@/components/ui/button'
import { formatSeconds, parseHm } from '@/lib/duration'
import { worklogStrings, issueDetailStrings } from '@/i18n/ko'
import { invalidateIssueViews } from '@/api/issue-view-invalidation'

// ─── Props ───────────────────────────────────────────────────────────────────

/** IssueEstimatePanel props */
export interface IssueEstimatePanelProps {
  /** 렌더할 이슈 데이터 — originalEstimateSeconds·timeSpentSeconds·remainingEstimateSeconds 포함 */
  issue: IssueResponse
  /** 수정 권한 — false이면 입력·저장 버튼 disabled (fail-closed) */
  disabled?: boolean
}

// ─── Draft 타입 + 헬퍼 ───────────────────────────────────────────────────────

/** h/m 분리 편집 draft — '' = 비어있음(클리어), 숫자 문자열 = 값 */
interface HmDraft { hours: string; minutes: string }

/** 추정 편집 draft */
interface EstimateDraft { original: HmDraft; remaining: HmDraft }

const SECS_PER_HOUR = 3600
const SECS_PER_MIN = 60

/** 초 → HmDraft 변환. null/undefined이면 빈 문자열 반환 */
function secondsToHmDraft(seconds: number | null | undefined): HmDraft {
  if (seconds == null) return { hours: '', minutes: '' }
  const s = Math.max(0, seconds)
  return {
    hours: String(Math.floor(s / SECS_PER_HOUR)),
    minutes: String(Math.floor((s % SECS_PER_HOUR) / SECS_PER_MIN)),
  }
}

/**
 * HmDraft → API 전송 값.
 * h·m 모두 빈 문자열이면 null(클리어), 하나라도 있으면 parseHm으로 초 변환.
 */
function hmDraftToApiValue(draft: HmDraft): number | null {
  if (draft.hours === '' && draft.minutes === '') return null
  return parseHm({ hours: Number(draft.hours) || 0, minutes: Number(draft.minutes) || 0 })
}

// ─── 서브 컴포넌트 — h/m 입력 행 ─────────────────────────────────────────────

interface HmInputRowProps {
  label: string
  idPrefix: string
  value: HmDraft
  onChange: (next: HmDraft) => void
  disabled: boolean
  /** formatSeconds 요약 표시용 원본 초 (null이면 표시 안 함) */
  currentSeconds: number | null | undefined
}

const INPUT_CLASS = 'w-16 rounded-md border border-input bg-background px-2 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-ring disabled:opacity-40 disabled:cursor-not-allowed'

/** 시간(h)/분(m) 숫자 입력 행 — 현재값 formatSeconds 요약 + 편집 input 제공 */
function HmInputRow({ label, idPrefix, value, onChange, disabled, currentSeconds }: HmInputRowProps): JSX.Element {
  const hId = `${idPrefix}-h`
  const mId = `${idPrefix}-m`
  return (
    <div className="flex flex-col gap-1">
      <div className="flex items-center gap-2">
        <span className="text-xs text-muted-foreground">{label}</span>
        {currentSeconds != null && (
          <span className="text-xs text-foreground font-medium">{formatSeconds(currentSeconds)}</span>
        )}
      </div>
      <div className="flex items-center gap-2">
        <div className="flex flex-col gap-0.5">
          <label htmlFor={hId} className="sr-only">{worklogStrings.hoursLabel}</label>
          <input
            id={hId}
            type="number"
            min={0}
            className={INPUT_CLASS}
            value={value.hours}
            onChange={(e) => onChange({ ...value, hours: e.target.value })}
            disabled={disabled}
            aria-label={worklogStrings.hoursLabel}
          />
        </div>
        <span className="text-xs text-muted-foreground pt-3">{worklogStrings.hoursLabel}</span>
        <div className="flex flex-col gap-0.5">
          <label htmlFor={mId} className="sr-only">{worklogStrings.minutesLabel}</label>
          <input
            id={mId}
            type="number"
            min={0}
            max={59}
            className={INPUT_CLASS}
            value={value.minutes}
            onChange={(e) => onChange({ ...value, minutes: e.target.value })}
            disabled={disabled}
            aria-label={worklogStrings.minutesLabel}
          />
        </div>
        <span className="text-xs text-muted-foreground pt-3">{worklogStrings.minutesLabel}</span>
      </div>
    </div>
  )
}

// ─── 메인 컴포넌트 ─────────────────────────────────────────────────────────────

/**
 * 이슈 추정 시간 편집 패널 (FR-TT-01 D6).
 *
 * - 원 추정·잔여 추정 h/m 편집 + 기록 시간 읽기 전용
 * - 저장 시 두 필드 함께 updateIssue (OCC version round-trip)
 * - 빈 입력 → null 전송 (클리어), 409 → versionConflictError toast + invalidate
 * - original/remaining 모두 null이면 estimateNotSet 안내 (E1)
 * - issue 변경 시 draft 재동기화, disabled fail-closed
 * - ★design-C1: 카드 wrapper 없음 — 입력 행만 렌더 (route가 감싼다)
 */
export function IssueEstimatePanel({
  issue,
  disabled = false,
}: IssueEstimatePanelProps): JSX.Element {
  const queryClient = useQueryClient()
  const [isPending, setIsPending] = useState(false)
  const [draft, setDraft] = useState<EstimateDraft>({
    original: secondsToHmDraft(issue.originalEstimateSeconds),
    remaining: secondsToHmDraft(issue.remainingEstimateSeconds),
  })

  // issue props 변경 시 draft 재동기화 — stale 방지
  const prevIssueRef = useRef(issue)
  useEffect(() => {
    if (prevIssueRef.current !== issue) {
      prevIssueRef.current = issue
      setDraft({
        original: secondsToHmDraft(issue.originalEstimateSeconds),
        remaining: secondsToHmDraft(issue.remainingEstimateSeconds),
      })
    }
  }, [issue])

  async function handleSave(): Promise<void> {
    setIsPending(true)
    try {
      await updateIssue(issue.key, {
        originalEstimateSeconds: hmDraftToApiValue(draft.original),
        remainingEstimateSeconds: hmDraftToApiValue(draft.remaining),
        expectedVersion: issue.version,
      })
      await invalidateIssueViews(queryClient, issue.key)
    } catch (err: unknown) {
      if (err instanceof ApiError && err.status === 409) {
        toast.error(issueDetailStrings.versionConflictError)
        await invalidateIssueViews(queryClient, issue.key)
      } else {
        toast.error(worklogStrings.estimateSaveError)
      }
    } finally {
      setIsPending(false)
    }
  }

  const isDisabled = disabled || isPending
  const isEstimateUnset =
    issue.originalEstimateSeconds == null && issue.remainingEstimateSeconds == null

  return (
    <div className="flex flex-col gap-3" data-testid="estimate-fields">
      {isEstimateUnset && (
        <p className="text-xs text-muted-foreground">{worklogStrings.estimateNotSet}</p>
      )}
      <HmInputRow
        label={worklogStrings.originalEstimateLabel}
        idPrefix="estimate-original"
        value={draft.original}
        onChange={(next) => setDraft((prev) => ({ ...prev, original: next }))}
        disabled={isDisabled}
        currentSeconds={issue.originalEstimateSeconds}
      />
      <div className="flex flex-col gap-1">
        <span className="text-xs text-muted-foreground">{worklogStrings.timeSpentLabel}</span>
        <span className="text-sm px-2 py-1.5 text-foreground">
          {formatSeconds(issue.timeSpentSeconds ?? 0)}
        </span>
      </div>
      <HmInputRow
        label={worklogStrings.remainingEstimateLabel}
        idPrefix="estimate-remaining"
        value={draft.remaining}
        onChange={(next) => setDraft((prev) => ({ ...prev, remaining: next }))}
        disabled={isDisabled}
        currentSeconds={issue.remainingEstimateSeconds}
      />
      <Button
        variant="outline"
        size="sm"
        className="self-end min-h-[44px]"
        onClick={() => { void handleSave() }}
        disabled={isDisabled}
        aria-label={worklogStrings.estimateSaveAriaLabel}
        data-testid="estimate-save"
      >
        {worklogStrings.estimateSaveButton}
      </Button>
    </div>
  )
}
