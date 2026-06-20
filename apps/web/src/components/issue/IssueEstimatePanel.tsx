// 이슈 추정 시간 편집 패널 — 원 추정·잔여 추정 h/m 입력, 기록 시간 읽기 전용 표시
import type { JSX } from 'react'
import { useState, useEffect, useRef } from 'react'
import { toast } from 'sonner'
import { useQueryClient } from '@tanstack/react-query'
import { updateIssue } from '@/api/issues'
import type { IssueResponse } from '@/api/issues'
import { ApiError } from '@/api/client'
import { issueQueryKey } from '@/api/useUpdateIssueSummary'
import { Button } from '@/components/ui/button'
import { formatSeconds, parseHm } from '@/lib/duration'
import { worklogStrings, issueDetailStrings } from '@/i18n/ko'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** IssueEstimatePanel props */
export interface IssueEstimatePanelProps {
  /** 렌더할 이슈 데이터 — originalEstimateSeconds·timeSpentSeconds·remainingEstimateSeconds 포함 */
  issue: IssueResponse
  /** 수정 권한 — false이면 입력·저장 버튼 disabled (fail-closed) */
  disabled?: boolean
}

// ─────────────────────────────────────────────────────────────────────────────
// 로컬 draft 타입 — h/m 분리 편집 상태
// - '' (빈 문자열): 입력이 비어 있는 상태. 저장 시 null(클리어)로 변환
// - '숫자 문자열': 시간 또는 분 값. 저장 시 parseHm으로 초 변환
// ─────────────────────────────────────────────────────────────────────────────

/** 시간/분 draft 상태 */
interface HmDraft {
  hours: string
  minutes: string
}

/** 추정 편집 draft 상태 */
interface EstimateDraft {
  original: HmDraft
  remaining: HmDraft
}

const SECONDS_PER_HOUR = 3600
const SECONDS_PER_MINUTE = 60

/**
 * 총 초(seconds)를 시간·분 draft 상태로 분해한다.
 * null이면 빈 문자열 반환.
 *
 * @param seconds 총 초 또는 null
 * @returns HmDraft — hours·minutes 문자열
 */
function secondsToHmDraft(seconds: number | null | undefined): HmDraft {
  if (seconds == null) return { hours: '', minutes: '' }
  const s = Math.max(0, seconds)
  const h = Math.floor(s / SECONDS_PER_HOUR)
  const m = Math.floor((s % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE)
  return { hours: String(h), minutes: String(m) }
}

/**
 * HmDraft를 API 전송 값으로 변환한다.
 * - hours·minutes 모두 빈 문자열 → null (클리어)
 * - 하나라도 값 있으면 parseHm으로 초 변환
 *
 * @param draft h/m 입력 draft
 * @returns 초 단위 숫자 또는 null
 */
function hmDraftToApiValue(draft: HmDraft): number | null {
  if (draft.hours === '' && draft.minutes === '') return null
  return parseHm({
    hours: Number(draft.hours) || 0,
    minutes: Number(draft.minutes) || 0,
  })
}

// ─────────────────────────────────────────────────────────────────────────────
// 서브 컴포넌트 — 시간/분 입력 행
// ─────────────────────────────────────────────────────────────────────────────

interface HmInputRowProps {
  /** 필드 레이블 (예: 원 추정) */
  label: string
  /** 시간·분 입력 id prefix — 중복 방지 */
  idPrefix: string
  /** 현재 draft 상태 */
  value: HmDraft
  /** 값 변경 핸들러 */
  onChange: (next: HmDraft) => void
  /** disabled 여부 */
  disabled: boolean
  /** 현재 설정된 원본 초 값 — formatSeconds 요약 표시에 사용 (null이면 표시 안 함) */
  currentSeconds: number | null | undefined
}

/**
 * 시간(h) + 분(m) 숫자 입력 행.
 * 현재 설정값을 formatSeconds로 요약 표시하고, h/m 편집 입력을 제공한다.
 * label htmlFor은 시간 input ID에 연결하고, 분 input은 별도 aria-label로 접근성을 제공한다.
 */
function HmInputRow({ label, idPrefix, value, onChange, disabled, currentSeconds }: HmInputRowProps): JSX.Element {
  const hId = `${idPrefix}-h`
  const mId = `${idPrefix}-m`

  return (
    <div className="flex flex-col gap-1">
      <div className="flex items-center gap-2">
        <span className="text-xs text-muted-foreground">{label}</span>
        {currentSeconds != null && (
          <span className="text-xs text-foreground font-medium">
            {formatSeconds(currentSeconds)}
          </span>
        )}
      </div>
      <div className="flex items-center gap-2">
        <div className="flex flex-col gap-0.5">
          <label htmlFor={hId} className="text-xs text-muted-foreground sr-only">
            {worklogStrings.hoursLabel}
          </label>
          <input
            id={hId}
            type="number"
            min={0}
            className="w-16 rounded-md border border-input bg-background px-2 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-ring disabled:opacity-40 disabled:cursor-not-allowed"
            value={value.hours}
            onChange={(e) => onChange({ ...value, hours: e.target.value })}
            disabled={disabled}
            aria-label={worklogStrings.hoursLabel}
          />
        </div>
        <span className="text-xs text-muted-foreground pt-3">{worklogStrings.hoursLabel}</span>
        <div className="flex flex-col gap-0.5">
          <label htmlFor={mId} className="text-xs text-muted-foreground sr-only">
            {worklogStrings.minutesLabel}
          </label>
          <input
            id={mId}
            type="number"
            min={0}
            max={59}
            className="w-16 rounded-md border border-input bg-background px-2 py-1.5 text-sm focus:outline-none focus:ring-2 focus:ring-ring disabled:opacity-40 disabled:cursor-not-allowed"
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

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이슈 추정 시간 편집 패널 (FR-TT-01 D6).
 *
 * - 원 추정·잔여 추정을 시간(h)/분(m) 숫자 입력으로 편집
 * - 기록 시간(timeSpentSeconds)은 읽기 전용으로 formatSeconds 표시만
 * - 저장 버튼 클릭 시 두 필드를 함께 updateIssue 호출 (OCC version round-trip)
 * - 빈 입력(h·m 모두 빈 문자열) → null 전송 (클리어)
 * - original=null, remaining=null이면 estimateNotSet 안내 표시 (E1)
 * - issue props가 바뀌면(refetch) 로컬 상태도 동기화 (stale 방지)
 * - disabled=true이면 입력 및 저장 버튼 비활성 (fail-closed)
 * - WCAG AA: aria-label, min-h-[44px] 버튼, focus ring, disabled 시각표시
 * - ★design-C1: 카드 wrapper는 그리지 않음 — 입력 행만 렌더 (route가 감싼다)
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

  // issue props가 바뀌면(refetch 후) 로컬 편집 상태를 동기화한다 — stale 방지
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

  /**
   * 저장 핸들러.
   * 두 필드를 함께 포함해 updateIssue를 호출한다.
   * OCC version은 issue.version을 사용한다.
   * 409 버전 충돌 시 versionConflictError toast + invalidate.
   * 그 외 오류 시 estimateSaveError toast.
   */
  async function handleSave(): Promise<void> {
    setIsPending(true)
    try {
      await updateIssue(issue.key, {
        originalEstimateSeconds: hmDraftToApiValue(draft.original),
        remainingEstimateSeconds: hmDraftToApiValue(draft.remaining),
        expectedVersion: issue.version,
      })
      await queryClient.invalidateQueries({ queryKey: issueQueryKey(issue.key) })
    } catch (err: unknown) {
      if (err instanceof ApiError && err.status === 409) {
        toast.error(issueDetailStrings.versionConflictError)
        await queryClient.invalidateQueries({ queryKey: issueQueryKey(issue.key) })
      } else {
        toast.error(worklogStrings.estimateSaveError)
      }
    } finally {
      setIsPending(false)
    }
  }

  const isDisabled = disabled || isPending

  // 추정 미설정 여부 — 원 추정과 잔여 추정 모두 null일 때
  const isEstimateUnset =
    issue.originalEstimateSeconds == null && issue.remainingEstimateSeconds == null

  return (
    <div className="flex flex-col gap-3" data-testid="estimate-fields">
      {/* E1 — 추정 미설정 안내 */}
      {isEstimateUnset && (
        <p className="text-xs text-muted-foreground">{worklogStrings.estimateNotSet}</p>
      )}

      {/* 원 추정 입력 */}
      <HmInputRow
        label={worklogStrings.originalEstimateLabel}
        idPrefix="estimate-original"
        value={draft.original}
        onChange={(next) => setDraft((prev) => ({ ...prev, original: next }))}
        disabled={isDisabled}
        currentSeconds={issue.originalEstimateSeconds}
      />

      {/* 기록 시간 — 읽기 전용 표시 */}
      <div className="flex flex-col gap-1">
        <span className="text-xs text-muted-foreground">{worklogStrings.timeSpentLabel}</span>
        <span className="text-sm px-2 py-1.5 text-foreground">
          {formatSeconds(issue.timeSpentSeconds ?? 0)}
        </span>
      </div>

      {/* 잔여 추정 입력 */}
      <HmInputRow
        label={worklogStrings.remainingEstimateLabel}
        idPrefix="estimate-remaining"
        value={draft.remaining}
        onChange={(next) => setDraft((prev) => ({ ...prev, remaining: next }))}
        disabled={isDisabled}
        currentSeconds={issue.remainingEstimateSeconds}
      />

      {/* 저장 버튼 */}
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
