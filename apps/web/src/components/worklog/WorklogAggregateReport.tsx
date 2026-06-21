// 워크로그 집계 보고 메인 컴포넌트 — 필터 바 + 4가지 렌더 상태 조립 (FR-TT-02 Task-6)
import { useState } from 'react'
import type { JSX } from 'react'
import { useWorklogAggregate } from '@/hooks/use-worklog-aggregate'
import type { AggregateDimension, AggregateGranularity } from '@/api/worklog-aggregate'
import { ApiError } from '@/api/client'
import { worklogAggregateLabels } from '@/i18n/worklog-aggregate-labels'
import { formatSeconds } from '@/lib/duration'
import { WorklogAggregateChart } from './WorklogAggregateChart'
import { WorklogAggregateTable } from './WorklogAggregateTable'
import { ProjectNotFoundScreen } from '@/routes/projects.$projectKey.settings.members'

// ─────────────────────────────────────────────────────────────────────────────
// 필터 바 서브컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/** WorklogFilterBar — 필터 상태와 setter */
interface WorklogFilterBarProps {
  readonly by: AggregateDimension
  readonly onByChange: (value: AggregateDimension) => void
  readonly granularity: AggregateGranularity
  readonly onGranularityChange: (value: AggregateGranularity) => void
  /** yyyy-MM-dd, 빈 문자열 = 미설정 */
  readonly from: string
  readonly onFromChange: (value: string) => void
  /** yyyy-MM-dd, 빈 문자열 = 미설정 */
  readonly to: string
  readonly onToChange: (value: string) => void
  /** from>to 오류 여부 */
  readonly isDateRangeInvalid: boolean
}

/**
 * 워크로그 집계 필터 바 — 차원/단위/기간 입력, from>to 오류 안내 포함.
 * @param props 필터 상태와 핸들러
 */
export function WorklogFilterBar({
  by,
  onByChange,
  granularity,
  onGranularityChange,
  from,
  onFromChange,
  to,
  onToChange,
  isDateRangeInvalid,
}: WorklogFilterBarProps): JSX.Element {
  const { filter } = worklogAggregateLabels

  return (
    <div className="flex flex-wrap items-end gap-4">
      <div className="flex flex-col gap-1">
        <label htmlFor="worklog-by" className="text-xs font-medium text-muted-foreground">
          {filter.dimensionLabel}
        </label>
        <select
          id="worklog-by"
          aria-label={filter.dimensionLabel}
          value={by}
          onChange={(e) => onByChange(e.target.value as AggregateDimension)}
          className="rounded border px-2 py-1 text-sm"
        >
          <option value="issue">{filter.dimensionIssue}</option>
          <option value="user">{filter.dimensionUser}</option>
          <option value="period">{filter.dimensionPeriod}</option>
        </select>
      </div>
      {by === 'period' && (
        <div className="flex flex-col gap-1">
          <label htmlFor="worklog-granularity" className="text-xs font-medium text-muted-foreground">
            {filter.granularityLabel}
          </label>
          <select
            id="worklog-granularity"
            aria-label={filter.granularityLabel}
            value={granularity}
            onChange={(e) => onGranularityChange(e.target.value as AggregateGranularity)}
            className="rounded border px-2 py-1 text-sm"
          >
            <option value="day">{filter.granularityDay}</option>
            <option value="week">{filter.granularityWeek}</option>
            <option value="month">{filter.granularityMonth}</option>
          </select>
        </div>
      )}
      <div className="flex flex-col gap-1">
        <label htmlFor="worklog-from" className="text-xs font-medium text-muted-foreground">
          {filter.fromLabel}
        </label>
        <input
          id="worklog-from"
          type="date"
          aria-label={filter.fromLabel}
          value={from}
          onChange={(e) => onFromChange(e.target.value)}
          className="rounded border px-2 py-1 text-sm"
        />
      </div>
      <div className="flex flex-col gap-1">
        <label htmlFor="worklog-to" className="text-xs font-medium text-muted-foreground">
          {filter.toLabel}
        </label>
        <input
          id="worklog-to"
          type="date"
          aria-label={filter.toLabel}
          value={to}
          onChange={(e) => onToChange(e.target.value)}
          className="rounded border px-2 py-1 text-sm"
        />
      </div>
      {isDateRangeInvalid && (
        <p role="alert" className="text-sm text-destructive">
          {filter.dateRangeError}
        </p>
      )}
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 메인 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/** WorklogAggregateReport props */
interface WorklogAggregateReportProps {
  /** URL에서 추출한 프로젝트 키 */
  readonly projectKey: string
}

/**
 * 워크로그 집계 보고 메인 컴포넌트.
 *
 * 상태 분기 순서: 403 → 일반에러 → 로딩 → 빈버킷 → 정상(요약+차트+테이블).
 * from > to 클라이언트 방어: params에서 from/to 제외 + role="alert" 안내 표시.
 *
 * @param projectKey 프로젝트 키
 */
export function WorklogAggregateReport({ projectKey }: WorklogAggregateReportProps): JSX.Element {
  const [by, setBy] = useState<AggregateDimension>('issue')
  const [granularity, setGranularity] = useState<AggregateGranularity>('day')
  const [from, setFrom] = useState('')
  const [to, setTo] = useState('')

  // from>to 클라이언트 방어
  const isDateRangeInvalid = from !== '' && to !== '' && from > to

  const effectiveFrom = isDateRangeInvalid ? undefined : from || undefined
  const effectiveTo = isDateRangeInvalid ? undefined : to || undefined

  const { data, isPending, isError, error } = useWorklogAggregate(projectKey, {
    by,
    granularity: by === 'period' ? granularity : undefined,
    from: effectiveFrom,
    to: effectiveTo,
  })

  // 상태 분기 1: 403 권한 오류
  if (isError && error instanceof ApiError && error.status === 403) {
    return <ProjectNotFoundScreen />
  }

  // 상태 분기 2: 일반 에러
  if (isError) {
    return (
      <div className="p-8 text-center text-sm text-destructive">
        {worklogAggregateLabels.error.generalMessage}
      </div>
    )
  }

  // 상태 분기 3: 로딩
  if (isPending) {
    return (
      <div className="p-8 flex justify-center" role="status">
        <span className="text-sm text-muted-foreground">{worklogAggregateLabels.loading.message}</span>
      </div>
    )
  }

  return (
    <div className="space-y-6">
      {/* 필터 바 */}
      <WorklogFilterBar
        by={by}
        onByChange={setBy}
        granularity={granularity}
        onGranularityChange={setGranularity}
        from={from}
        onFromChange={setFrom}
        to={to}
        onToChange={setTo}
        isDateRangeInvalid={isDateRangeInvalid}
      />

      {/* 상태 분기 4: 빈 버킷 */}
      {data.buckets.length === 0 ? (
        <div className="rounded-md border p-8 text-center text-sm text-muted-foreground">
          {worklogAggregateLabels.empty.message}
        </div>
      ) : (
        <>
          {/* 요약 */}
          <div className="flex items-center gap-2 rounded-md border px-4 py-3">
            <span className="text-sm text-muted-foreground">
              {worklogAggregateLabels.summary.totalTimeLabel}
            </span>
            <span className="text-lg font-semibold tabular-nums">
              {formatSeconds(data.totalTimeSpentSeconds)}
            </span>
          </div>

          {/* 차트 */}
          <WorklogAggregateChart buckets={data.buckets} dimension={by} />

          {/* 테이블 */}
          <WorklogAggregateTable
            buckets={data.buckets}
            total={data.totalTimeSpentSeconds}
            dimension={by}
          />
        </>
      )}
    </div>
  )
}
