// 워크로그 집계 결과를 버킷별 행과 합계 행으로 표시하는 테이블 컴포넌트 (FR-TT-02 D6)
import type { JSX } from 'react'
import type { WorklogAggregateBucket, AggregateDimension } from '@/api/worklog-aggregate'
import { worklogAggregateLabels } from '@/i18n/worklog-aggregate-labels'
import { formatSeconds } from '@/lib/duration'

// ─────────────────────────────────────────────────────────────────────────────
// 행 서브컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

interface WorklogBucketRowProps {
  /** 집계 버킷 단건 */
  readonly bucket: WorklogAggregateBucket
}

/**
 * 워크로그 집계 버킷 단건 행.
 *
 * - label이 빈 문자열이면 unknownDisplayName placeholder를 표시한다.
 * - timeSpentSeconds는 formatSeconds로 변환해 표시한다.
 *
 * @example
 * // label 빈 문자열 → "(알 수 없음)" 표시
 * // timeSpentSeconds=50400 → "14h 0m"
 */
function WorklogBucketRow({ bucket }: WorklogBucketRowProps): JSX.Element {
  const displayLabel =
    bucket.label !== '' ? bucket.label : worklogAggregateLabels.table.unknownDisplayName

  return (
    <tr className="border-b text-sm hover:bg-muted/50">
      <td className="px-4 py-2">{displayLabel}</td>
      <td className="px-4 py-2 tabular-nums">{formatSeconds(bucket.timeSpentSeconds)}</td>
      <td className="px-4 py-2 tabular-nums text-right">{bucket.worklogCount}</td>
    </tr>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 테이블 Props
// ─────────────────────────────────────────────────────────────────────────────

interface WorklogAggregateTableProps {
  /** 집계 버킷 배열 — 빈 배열 허용 */
  readonly buckets: WorklogAggregateBucket[]
  /** 전체 소요 시간 합계 (초) */
  readonly total: number
  /** 집계 차원 — 열 헤더 컨텍스트에 활용 */
  readonly dimension: AggregateDimension
}

// ─────────────────────────────────────────────────────────────────────────────
// WorklogAggregateTable
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 워크로그 집계 결과 테이블.
 *
 * - thead: 레이블 / 소요 시간 / 건수 헤더 3개.
 * - tbody: 버킷별 행 (WorklogBucketRow 위임).
 * - tfoot: 합계 행 — total을 formatSeconds로 표시.
 * - buckets가 빈 배열이어도 throw 없이 빈 tbody를 렌더한다.
 * - label 빈 문자열 → unknownDisplayName placeholder. UUID key는 화면에 노출하지 않는다.
 *
 * @param buckets 집계 버킷 배열
 * @param total 전체 소요 시간 합계 (초)
 * @param dimension 집계 차원 (현재 헤더 표시에는 직접 사용하지 않으나 확장 여지)
 */
export function WorklogAggregateTable({
  buckets,
  total,
}: WorklogAggregateTableProps): JSX.Element {
  return (
    <div className="overflow-x-auto rounded-md border">
      <table className="w-full border-collapse text-sm">
        <thead>
          <tr className="border-b bg-muted/50 text-left text-xs font-medium text-muted-foreground">
            <th className="px-4 py-2">{worklogAggregateLabels.table.headerLabel}</th>
            <th className="px-4 py-2">{worklogAggregateLabels.table.headerTimeSpent}</th>
            <th className="px-4 py-2 text-right">{worklogAggregateLabels.table.headerCount}</th>
          </tr>
        </thead>
        <tbody>
          {buckets.map((bucket) => (
            <WorklogBucketRow key={bucket.key} bucket={bucket} />
          ))}
        </tbody>
        <tfoot>
          <tr className="border-t bg-muted/30 text-sm font-medium">
            <td className="px-4 py-2">{worklogAggregateLabels.table.totalRow}</td>
            <td className="px-4 py-2 tabular-nums">{formatSeconds(total)}</td>
            <td className="px-4 py-2" />
          </tr>
        </tfoot>
      </table>
    </div>
  )
}
