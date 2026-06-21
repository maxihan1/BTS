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

/** WorklogFilterBarProps — 필터 상태와 setter를 그대로 수령한다 */
interface WorklogFilterBarProps {
  /** 현재 집계 차원 */
  readonly by: AggregateDimension
  /** 집계 차원 변경 핸들러 */
  readonly onByChange: (value: AggregateDimension) => void
  /** 현재 집계 단위 */
  readonly granularity: AggregateGranularity
  /** 집계 단위 변경 핸들러 */
  readonly onGranularityChange: (value: AggregateGranularity) => void
  /** 시작일 (yyyy-MM-dd, 빈 문자열 = 미설정) */
  readonly from: string
  /** 시작일 변경 핸들러 */
  readonly onFromChange: (value: string) => void
  /** 종료일 (yyyy-MM-dd, 빈 문자열 = 미설정) */
  readonly to: string
  /** 종료일 변경 핸들러 */
  readonly onToChange: (value: string) => void
  /** from>to 오류 여부 */
  readonly isDateRangeInvalid: boolean
}

/**
 * 워크로그 집계 필터 바.
 *
 * - 집계 차원(by) 셀렉터: 이슈별/사용자별/기간별.
 * - 집계 단위(granularity) 셀렉터: by=period일 때만 렌더.
 * - 시작일/종료일 네이티브 input[type=date].
 * - from>to 오류 시 role="alert" 안내 메시지 노출.
 *
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
      {/* 집계 차원 셀렉터 */}
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

      {/* 집계 단위 셀렉터 — by=period일 때만 노출 */}
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

      {/* 시작일 */}
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

      {/* 종료일 */}
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

      {/* from>to 오류 안내 */}
      {isDateRangeInvalid && (
        <p role="alert" className="text-sm text-destructive">
          시작일이 종료일보다 늦습니다. 기간을 확인해 주세요.
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
 * 필터 상태(by/granularity/from/to)를 관리하고 useWorklogAggregate 훅을 호출한다.
 * 상태 분기 순서.
 * 1. isError + ApiError(403) → ProjectNotFoundScreen (권한 안내)
 * 2. isError (기타) → 일반 에러 메시지
 * 3. isPending → 로딩 인디케이터
 * 4. data.buckets 빈 배열 → 빈 상태 메시지
 * 5. 정상 → 요약 + 차트 + 테이블
 *
 * from > to 일 때 클라이언트 방어: params에서 from/to를 제외하고 role="alert" 안내 표시.
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
        <span className="text-sm text-muted-foreground">불러오는 중...</span>
      </div>
    )
  }

  return (
    <div className="space-y-6">
      {/* 페이지 헤더 */}
      <header className="space-y-1">
        <h1 className="text-2xl font-semibold">{worklogAggregateLabels.page.title}</h1>
        <p className="text-sm text-muted-foreground">{worklogAggregateLabels.page.description}</p>
      </header>

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
