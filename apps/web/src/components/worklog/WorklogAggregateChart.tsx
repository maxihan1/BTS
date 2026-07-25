/* eslint-disable react-refresh/only-export-components -- toChartData/CHART_TOP_N/ChartDatum 은 테스트용 named export (spec FR-TT-02 task-4 명시) */
// 워크로그 집계 막대 차트 컴포넌트 — recharts BarChart, 이슈/사용자(가로)/기간(세로) 방향 전환
import type { JSX } from 'react'
import {
  ResponsiveContainer,
  BarChart,
  Bar,
  XAxis,
  YAxis,
  Tooltip,
  CartesianGrid,
} from 'recharts'
import type { WorklogAggregateBucket, AggregateDimension } from '@/api/worklog-aggregate'
import { formatSeconds } from '@/lib/duration'
import { worklogAggregateLabels } from '@/i18n/worklog-aggregate-labels'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** 차트에 표시할 최대 버킷 수. 초과분은 상위 N개로 절단한다. */
export const CHART_TOP_N = 20

/** recharts ResponsiveContainer / wrapper div 의 고정 높이 (px). */
const CHART_HEIGHT = 400

/** 막대 색상 — 단일 지표(집계 시간)라 주 시리즈 토큰 하나만 쓴다(FR-UX-06 PR22 토큰화). */
const BAR_COLOR = 'var(--chart-1)'

/** label 이 빈 문자열일 때 대체 표시 문자열 (by=user displayName 누락 대비). */
const UNKNOWN_LABEL = worklogAggregateLabels.table.unknownDisplayName

// ─────────────────────────────────────────────────────────────────────────────
// 데이터 변환 — 순수 함수 (단위 테스트 대상)
// ─────────────────────────────────────────────────────────────────────────────

/** 차트 데이터 단건 */
export interface ChartDatum {
  label: string
  seconds: number
}

/**
 * 집계 버킷 배열을 recharts 용 데이터 배열로 변환한다.
 *
 * - 상위 CHART_TOP_N 개로 절단한다 (백엔드 내림차순 순서 신뢰).
 * - label 이 빈 문자열이면 UNKNOWN_LABEL 로 대체한다.
 * - by=period 이면 백엔드 시간순(ASC)을 그대로 유지하고,
 *   그 외(issue/user)는 백엔드 DESC 순서를 그대로 유지한다.
 *   정렬 변경 없음 — 백엔드 순서를 신뢰한다.
 *
 * @param buckets 집계 버킷 배열
 * @param dimension 집계 차원
 * @returns recharts 차트 데이터 배열 ({ label, seconds }[])
 */
export function toChartData(
  buckets: WorklogAggregateBucket[],
  dimension: AggregateDimension,
): ChartDatum[] {
  // dimension 파라미터는 호출자가 차원을 명시적으로 전달하는 API 일관성을 위해 유지한다.
  // 현재 구현에서 정렬은 백엔드 응답 순서를 그대로 신뢰하므로 dimension 별 분기가 없다.
  void dimension
  return buckets.slice(0, CHART_TOP_N).map((b) => ({
    label: b.label === '' ? UNKNOWN_LABEL : b.label,
    seconds: b.timeSpentSeconds,
  }))
}

// ─────────────────────────────────────────────────────────────────────────────
// 차트 헬퍼 — tick 포맷터
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 시간 축 tick 포맷터. 초를 시간(h) 단위의 짧은 문자열로 변환한다.
 * recharts tickFormatter 시그니처에 맞춰 unknown 으로 받은 뒤 좁힌다.
 */
function formatTickSeconds(value: unknown): string {
  if (typeof value !== 'number') return ''
  const h = value / 3600
  if (h >= 1) return `${Math.round(h)}h`
  const m = Math.floor(value / 60)
  return `${m}m`
}

/**
 * Tooltip formatter. recharts Formatter 시그니처 반환값에 맞춰 string 을 반환한다.
 * value 가 number 가 아니면 빈 문자열로 처리한다.
 */
function tooltipFormatter(value: unknown): string {
  if (typeof value !== 'number') return ''
  return formatSeconds(value)
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/** WorklogAggregateChart props */
interface WorklogAggregateChartProps {
  /** 집계 버킷 배열 */
  buckets: WorklogAggregateBucket[]
  /** 집계 차원 — 가로(issue/user) 또는 세로(period) 막대 방향 결정 */
  dimension: AggregateDimension
}

/**
 * 워크로그 집계 막대 차트.
 *
 * - dimension=issue|user → 가로 막대 (BarChart layout="vertical")
 *   YAxis=label(category), XAxis=초(number)
 * - dimension=period → 세로 막대 (BarChart layout="horizontal")
 *   XAxis=label(시간 축), YAxis=초(number)
 * - 빈 buckets → null 반환 (부모가 빈 상태 처리)
 * - 접근성: aria-label 로 차트 목적 명시
 *
 * 실 렌더(시각 검증)는 e2e/worklog-aggregate.spec.ts 에 위임한다.
 */
export function WorklogAggregateChart({
  buckets,
  dimension,
}: WorklogAggregateChartProps): JSX.Element | null {
  if (buckets.length === 0) return null

  const data = toChartData(buckets, dimension)
  const isHorizontalBar = dimension === 'issue' || dimension === 'user'
  const isTruncated = buckets.length > CHART_TOP_N

  return (
    <div className="space-y-2">
      {isTruncated && (
        <p className="text-xs text-muted-foreground">
          {worklogAggregateLabels.chart.topNHint.replace('{count}', String(CHART_TOP_N))}
        </p>
      )}
      {/* w-full: recharts ResponsiveContainer 가 부모 너비를 참조하므로 필수.
          h-[400px]: 높이는 Tailwind 임의값으로 지정 (CHART_HEIGHT 와 동기화). */}
      <div
        role="img"
        aria-label={worklogAggregateLabels.chart.ariaLabel}
        className="w-full h-[400px]"
      >
      {isHorizontalBar ? (
        // 가로 막대 — 이슈별·사용자별
        // layout="vertical": BarChart 에서 막대가 가로 방향으로 뻗는다.
        // YAxis=category(레이블), XAxis=number(시간)
        <ResponsiveContainer width="100%" height={CHART_HEIGHT}>
          <BarChart layout="vertical" data={data} margin={{ top: 4, right: 24, bottom: 4, left: 80 }}>
            <CartesianGrid strokeDasharray="3 3" horizontal={false} />
            <YAxis type="category" dataKey="label" width={76} tick={{ fontSize: 12 }} />
            <XAxis type="number" tickFormatter={formatTickSeconds} tick={{ fontSize: 12 }} />
            <Tooltip formatter={tooltipFormatter} />
            <Bar dataKey="seconds" fill={BAR_COLOR} radius={[0, 2, 2, 0]} />
          </BarChart>
        </ResponsiveContainer>
      ) : (
        // 세로 막대 — 기간별
        // XAxis=label(기간), YAxis=number(시간)
        <ResponsiveContainer width="100%" height={CHART_HEIGHT}>
          <BarChart data={data} margin={{ top: 4, right: 16, bottom: 32, left: 16 }}>
            <CartesianGrid strokeDasharray="3 3" vertical={false} />
            <XAxis dataKey="label" tick={{ fontSize: 12 }} angle={-45} textAnchor="end" />
            <YAxis tickFormatter={formatTickSeconds} tick={{ fontSize: 12 }} />
            <Tooltip formatter={tooltipFormatter} />
            <Bar dataKey="seconds" fill={BAR_COLOR} radius={[2, 2, 0, 0]} />
          </BarChart>
        </ResponsiveContainer>
      )}
      </div>
    </div>
  )
}
