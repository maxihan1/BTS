// 분포 차트 가젯 — pie/bar 공용. 프로젝트 요약 집계를 재사용해 백엔드 신규 호출이 0이다
import type { JSX } from 'react'
import {
  Bar,
  BarChart,
  Cell,
  Pie,
  PieChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import { EmptyState } from '@/components/ui/empty-state'
import { useProjectSummary } from '@/hooks/use-project-summary'
import { distributionRowsOf } from '@/components/project/summary/summary-view-model'
import { gadgetStateLabels } from '@/i18n/dashboard-labels'
import { rowsForField } from './distribution-chart-model'
import type { DistributionVariant } from './distribution-chart-model'
import type { GadgetConfig } from './gadget-types'

// ─────────────────────────────────────────────────────────────────────────────
// 타입
// ─────────────────────────────────────────────────────────────────────────────

interface DistributionChartGadgetProps {
  /** 파이냐 막대냐 — 데이터 경로는 같다 */
  readonly variant: DistributionVariant
  /** 가젯 config (projectKey · field) */
  readonly config: GadgetConfig
}

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 조각 색 — `--chart-1`~`5` 를 순환한다.
 *
 * ★하드코딩 hex 를 쓰지 않는다. 라이트/다크 전환이 토큰 위에서만 성립하고,
 * `chart-color-tokens.test.ts` 가 hex 리터럴 0건을 전수로 강제한다.
 */
const SLICE_COLORS = [
  'var(--chart-1)',
  'var(--chart-2)',
  'var(--chart-3)',
  'var(--chart-4)',
  'var(--chart-5)',
] as const

/** 축·툴팁 글자색 — 차트 밖 텍스트와 같은 토큰을 쓴다 */
const AXIS_COLOR = 'var(--text-subtle)'

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 프로젝트의 이슈 분포를 파이 또는 막대로 그린다.
 *
 * 데이터는 `GET /api/v1/projects/{key}/summary` 하나뿐이다 — 프로젝트 요약 화면과 같은
 * 엔드포인트라 TanStack Query 가 **자동으로 dedupe** 한다(같은 projectKey 면 요청 1회).
 *
 * ★스코프가 프로젝트인 것은 의도적 편차다(X-JD-1). Jira 는 space **또는** filter 를 받지만
 * 이 PR 은 프로젝트 스코프만 구현하고 필터 스코프를 이연했다.
 *
 * ★막대는 `DistributionWidget`(요약 화면의 막대 목록)과 **다른 모양**이다. 게이트 1 의
 * M-4 결정이고 그 대가는 plan 에 적혀 있다 — 파이가 recharts 를 어차피 요구하므로
 * 두 마크를 한 컴포넌트의 variant 로 겸하는 쪽을 골랐다.
 */
export function DistributionChartGadget({
  variant,
  config,
}: DistributionChartGadgetProps): JSX.Element {
  const projectKey = config.projectKey ?? ''
  const { data, isLoading, isError } = useProjectSummary(projectKey)

  if (projectKey === '') {
    return <EmptyState title={gadgetStateLabels.notConfigured} />
  }

  if (isLoading) {
    return (
      <div className="text-muted-foreground flex flex-1 items-center justify-center p-4 text-sm">
        {gadgetStateLabels.loading}
      </div>
    )
  }

  // ★404 도 여기로 흡수한다(E4). 프로젝트가 지워지거나 layout JSON 을 손으로 고쳐 없는 키가
  //   들어와도 대시보드 전체가 죽지 않아야 한다.
  if (isError || data === undefined) {
    return <EmptyState title={gadgetStateLabels.loadFailed} />
  }

  const rows = rowsForField(distributionRowsOf(data), config.field)

  // ★빈 파이를 그리지 않는다(E3). 조각이 0개인 원은 「로딩 중」과 시각적으로 구분되지 않는다.
  if (rows.length === 0) {
    return <EmptyState title={gadgetStateLabels.noDistribution} />
  }

  return (
    <div className="flex-1" data-testid={`distribution-chart-${variant}`}>
      <ResponsiveContainer width="100%" height="100%">
        {variant === 'pie' ? (
          <PieChart>
            <Tooltip />
            <Pie data={[...rows]} dataKey="count" nameKey="label" outerRadius="80%">
              {rows.map((row, index) => (
                <Cell key={row.id} fill={SLICE_COLORS[index % SLICE_COLORS.length]} />
              ))}
            </Pie>
          </PieChart>
        ) : (
          <BarChart data={[...rows]} margin={{ top: 8, right: 8, bottom: 8, left: 0 }}>
            <XAxis dataKey="label" tick={{ fill: AXIS_COLOR, fontSize: 11 }} interval={0} />
            <YAxis allowDecimals={false} tick={{ fill: AXIS_COLOR, fontSize: 11 }} width={28} />
            <Tooltip />
            <Bar dataKey="count">
              {rows.map((row, index) => (
                <Cell key={row.id} fill={SLICE_COLORS[index % SLICE_COLORS.length]} />
              ))}
            </Bar>
          </BarChart>
        )}
      </ResponsiveContainer>
    </div>
  )
}
