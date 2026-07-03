// Cycle/Lead Time 지표 하나(요약 타일 + 히스토그램 + 박스플롯)를 조립하는 섹션 (FR-RP-04 D6/D7 Task-6)
import type { JSX } from 'react'
import type { MetricResponse } from '@/api/cycle-time'
import { formatDuration } from '@/lib/format-duration'
import { cycleTimeLabels } from '@/i18n/cycle-time-labels'
import { CycleTimeHistogram } from './CycleTimeHistogram'
import { CycleTimeBoxPlot } from './CycleTimeBoxPlot'
import type { CycleTimeBoxPlotStats } from './CycleTimeBoxPlot'

// ─────────────────────────────────────────────────────────────────────────────
// 순수 함수
// ─────────────────────────────────────────────────────────────────────────────

/**
 * nullable 소요 시간(초)을 안전하게 포맷한다.
 *
 * count>0이면 백엔드 계약상 항상 non-null이지만 응답 타입은 nullable로 선언돼 있다.
 * `!!`(null assertion) 없이 명시적으로 null을 체크해, null이면 실제 0초와 구분되는
 * 대시("-")를 반환한다 — 값이 없다는 사실을 "0초"로 오인시키지 않기 위함이다.
 *
 * @param seconds 초 단위 소요 시간 또는 null
 * @returns formatDuration 결과 문자열, null이면 "-"
 */
function formatNullableDuration(seconds: number | null): string {
  if (seconds === null) return '-'
  return formatDuration(seconds)
}

/**
 * 박스플롯에 필요한 min/p25/p50/p75/max 5개 값이 모두 non-null인지 확인해
 * non-null 통계 객체로 narrowing 한다.
 *
 * count>0이면 백엔드 계약상 5개 값 모두 non-null이 보장되지만, 응답 타입은
 * nullable이라 `!!` 없이 명시적으로 각 값을 체크한다. 하나라도 null이면
 * (계약 위반 시에도 화면이 깨지지 않도록) 방어적으로 null을 반환해
 * 박스플롯을 그리지 않는다 — 이 경우에도 요약 타일은 그대로 렌더된다.
 *
 * @param metric Cycle/Lead Time 지표 응답
 * @returns 5개 값이 모두 non-null이면 박스플롯 통계 객체, 하나라도 null이면 null
 */
function toBoxPlotStats(metric: MetricResponse): CycleTimeBoxPlotStats | null {
  const { min, p25, p50, p75, max } = metric
  if (min === null || p25 === null || p50 === null || p75 === null || max === null) {
    return null
  }
  return { min, p25, p50, p75, max }
}

// ─────────────────────────────────────────────────────────────────────────────
// 요약 타일 서브컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/** 요약 타일 시각 강조 등급 — FIX-1: 8개 수치를 평평하게 나열하지 않고 위계를 둔다 */
type StatEmphasis = 'primary' | 'secondary'

/** MetricStatTile props */
interface MetricStatTileProps {
  /** 타일 상단 라벨 (예. "중앙값") */
  label: string
  /** 타일 본문 값 (이미 포맷된 문자열) */
  value: string
  /** 시각 강조 등급 — primary(중앙값 대표) | secondary(나머지, count 포함) */
  emphasis: StatEmphasis
  /** 테스트 식별 + 값 노드 조회용 data-testid */
  testId: string
}

/**
 * 요약 통계 타일 하나.
 *
 * FIX-1: min/max/avg/p25/p50/p75/p90/count 8개 수치를 동등하게 나열하면
 * 어느 값이 대표인지 알기 어렵다. 중앙값(p50)만 `primary`로 크고 굵게
 * 강조하고, count를 포함한 나머지는 `secondary`(muted)로 보조 처리한다.
 *
 * @param props label/value/emphasis/testId
 */
function MetricStatTile({ label, value, emphasis, testId }: MetricStatTileProps): JSX.Element {
  return (
    <div className="flex flex-col gap-1">
      <span className="text-xs text-muted-foreground">{label}</span>
      <span
        data-testid={testId}
        className={emphasis === 'primary' ? 'text-lg font-semibold' : 'text-sm text-muted-foreground'}
      >
        {value}
      </span>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/** CycleTimeMetricSection props */
interface CycleTimeMetricSectionProps {
  /** Cycle 또는 Lead Time 지표 응답 */
  metric: MetricResponse
  /** 섹션 제목 (예. "Cycle Time") */
  title: string
  /** 섹션 설명 문구 */
  description: string
  /** count===0(표본 없음)일 때 노출할 안내 문구 */
  emptyMessage: string
}

/**
 * Cycle 또는 Lead Time 지표 하나를 렌더하는 섹션.
 *
 * - count===0이면 표본이 없다는 뜻이라 emptyMessage만 보여주고, 요약 타일/히스토그램/
 *   박스플롯은 렌더하지 않는다(S2 — 예. Cycle Time에서 IN_PROGRESS 미경유 완료 이슈만 있는 경우).
 * - count>0이면 요약 타일(FIX-1: p50 대표 강조) + 히스토그램을 렌더한다.
 * - 박스플롯은 min/p25/p50/p75/max 5개 값이 모두 non-null일 때만 렌더한다 —
 *   히스토그램은 개별 표본(`samples`) 배열만 있으면 그릴 수 있어 이 narrowing과 무관하다.
 *
 * @param props metric/title/description/emptyMessage
 */
export function CycleTimeMetricSection({
  metric,
  title,
  description,
  emptyMessage,
}: CycleTimeMetricSectionProps): JSX.Element {
  const { stats } = cycleTimeLabels
  const boxPlotStats = toBoxPlotStats(metric)

  return (
    <section className="space-y-4">
      <div>
        <h2 className="text-base font-semibold">{title}</h2>
        <p className="text-sm text-muted-foreground">{description}</p>
      </div>

      {metric.count === 0 ? (
        <p className="text-sm text-muted-foreground">{emptyMessage}</p>
      ) : (
        <>
          <div className="flex flex-wrap gap-4 sm:gap-6">
            <MetricStatTile
              testId="cycle-time-stat-count"
              label={stats.count}
              value={String(metric.count)}
              emphasis="secondary"
            />
            <MetricStatTile
              testId="cycle-time-stat-min"
              label={stats.min}
              value={formatNullableDuration(metric.min)}
              emphasis="secondary"
            />
            <MetricStatTile
              testId="cycle-time-stat-p25"
              label={stats.p25}
              value={formatNullableDuration(metric.p25)}
              emphasis="secondary"
            />
            <MetricStatTile
              testId="cycle-time-stat-p50"
              label={stats.p50}
              value={formatNullableDuration(metric.p50)}
              emphasis="primary"
            />
            <MetricStatTile
              testId="cycle-time-stat-p75"
              label={stats.p75}
              value={formatNullableDuration(metric.p75)}
              emphasis="secondary"
            />
            <MetricStatTile
              testId="cycle-time-stat-max"
              label={stats.max}
              value={formatNullableDuration(metric.max)}
              emphasis="secondary"
            />
            <MetricStatTile
              testId="cycle-time-stat-avg"
              label={stats.avg}
              value={formatNullableDuration(metric.avg)}
              emphasis="secondary"
            />
            <MetricStatTile
              testId="cycle-time-stat-p90"
              label={stats.p90}
              value={formatNullableDuration(metric.p90)}
              emphasis="secondary"
            />
          </div>

          <CycleTimeHistogram samples={metric.samples} />

          {boxPlotStats && <CycleTimeBoxPlot stats={boxPlotStats} />}
        </>
      )}
    </section>
  )
}
