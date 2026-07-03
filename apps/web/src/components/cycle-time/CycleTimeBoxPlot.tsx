// Cycle/Lead Time 분포 박스플롯 — 표준 Tukey 박스플롯(상자 p25~p75, 중앙선 p50, 수염 min~max) 커스텀 SVG (FR-RP-04 D6/D7)
import type { JSX } from 'react'
import { formatDuration } from '@/lib/format-duration'
import { cycleTimeLabels } from '@/i18n/cycle-time-labels'

// ─────────────────────────────────────────────────────────────────────────────
// 상수 — SVG 좌표/치수
// ─────────────────────────────────────────────────────────────────────────────

/** SVG viewBox 내부 좌표계 폭(px) */
const CHART_WIDTH = 320

/** SVG viewBox 내부 좌표계 높이(px) */
const CHART_HEIGHT = 120

/** 좌우 여백(px) — min/max 값 라벨이 viewBox 밖으로 잘리지 않도록 확보 */
const HORIZONTAL_PADDING = 40

/** boxPlotScale에 전달할 실제 값 매핑 폭(px) — 전체 폭에서 좌우 여백 제외 */
const USABLE_WIDTH = CHART_WIDTH - HORIZONTAL_PADDING * 2

/** 상자(p25~p75 rect) 상단 y좌표(px) */
const BOX_TOP_Y = 40

/** 상자 높이(px) */
const BOX_HEIGHT = 36

/** 상자 하단 y좌표(px) — BOX_TOP_Y + BOX_HEIGHT */
const BOX_BOTTOM_Y = BOX_TOP_Y + BOX_HEIGHT

/** 수염(min~max 라인) y좌표(px) — 상자 세로 중앙 */
const WHISKER_Y = BOX_TOP_Y + BOX_HEIGHT / 2

/** 수염 끝 캡(작은 수직 눈금) 반높이(px) */
const WHISKER_CAP_HALF_HEIGHT = 8

/** p25/p75 값 라벨 y좌표(px) — 상자 위쪽 */
const QUARTILE_LABEL_Y = BOX_TOP_Y - 8

/** p50(중앙값) 라벨 y좌표(px) — 사분위 라벨과 겹치지 않도록 한 단 위 */
const MEDIAN_LABEL_Y = QUARTILE_LABEL_Y - 16

/** min/max 값 라벨 y좌표(px) — 수염 아래쪽 */
const EXTREME_LABEL_Y = BOX_BOTTOM_Y + 28

/** 단일 accent 색상(FIX-3) — WorklogAggregateChart/BurndownChart 선례(#6366f1) 톤 계열 */
const ACCENT_COLOR = '#6366f1'

/** 라벨 폰트 크기(px) */
const LABEL_FONT_SIZE = 11

// ─────────────────────────────────────────────────────────────────────────────
// 순수 함수 — 값 → 픽셀 좌표 스케일
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 값 범위(min~max)를 [0, width] 픽셀 구간으로 선형 매핑하는 스케일 함수를 만든다.
 *
 * min은 0으로, max는 width로 매핑되며, 그 사이 값은 선형 비례한다.
 * range.min === range.max(폭 0)인 경우 나눗셈(0/0)으로 NaN이 발생하는 것을 막기 위해
 * 모든 입력값을 폭의 중앙(width/2)으로 매핑하는 상수 함수를 반환한다 — 표본이 전부
 * 동일한 값이면 박스플롯이 시각적으로 폭 0의 세로선으로 수렴하는 것이 자연스럽다.
 *
 * @param range 매핑할 값의 최소/최대
 * @param width 매핑 대상 픽셀 폭
 * @returns 값(number)을 픽셀 좌표(number)로 변환하는 함수
 */
export function boxPlotScale(range: { min: number; max: number }, width: number): (value: number) => number {
  const span = range.max - range.min
  if (span === 0) {
    const center = width / 2
    return () => center
  }
  return (value: number) => ((value - range.min) / span) * width
}

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** CycleTimeBoxPlot 통계 입력 — 상위 섹션이 count>0(표본 존재)일 때만 렌더하므로 non-null 전제 */
export interface CycleTimeBoxPlotStats {
  min: number
  p25: number
  p50: number
  p75: number
  max: number
}

/** CycleTimeBoxPlot Props */
export interface CycleTimeBoxPlotProps {
  /** 최소/25백분위/중앙값/75백분위/최대값 통계(초 단위) */
  stats: CycleTimeBoxPlotStats
}

// ─────────────────────────────────────────────────────────────────────────────
// CycleTimeBoxPlot 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Cycle/Lead Time 분포를 표준 Tukey 박스플롯으로 그리는 커스텀 SVG 컴포넌트.
 *
 * **커스텀 SVG인 이유**: recharts는 박스플롯을 네이티브로 지원하지 않는다. BTS는
 * 이미 Gantt 차트(FR-TL-01, `docs/adr/2026-06-26-gantt-rendering-self-svg.md`)와
 * 의존성 라인 오버레이(FR-TL-02, `DependencyOverlay.tsx`)에서 신규 라이브러리 도입
 * 없이 자체 SVG로 그린 선례가 있다 — 박스플롯도 동일하게 "값→픽셀"의 단순 기하이므로
 * 같은 원칙을 따른다.
 *
 * **레이아웃(표준 Tukey 박스플롯)**:
 * - 수염(whisker) 라인: min~max 전체 구간, 양끝에 작은 수직 캡.
 * - 상자(box) rect: p25~p75 구간, 상자 폭이 IQR(사분위 범위)을 표현.
 * - 중앙선(median line): p50 위치에 상자를 가로지르는 굵은 세로선.
 * - p90은 이 컴포넌트가 그리지 않는다 — 상위 요약 타일이 별도로 표시한다.
 *
 * **값 텍스트 병기**: 장식 도형만으로는 값을 읽을 수 없으므로, min/p25/p50/p75/max
 * 5개 값 모두 `formatDuration`으로 포맷한 텍스트를 SVG 위에 병기한다.
 *
 * **접근성**: 바깥 wrapper `div`에 `role="img"` + aria-label(전체 요약 문구).
 *
 * @param props stats — 상위 섹션이 count>0을 확인한 뒤에만 전달(non-null 값 전제)
 */
export function CycleTimeBoxPlot({ stats }: CycleTimeBoxPlotProps): JSX.Element {
  const scale = boxPlotScale({ min: stats.min, max: stats.max }, USABLE_WIDTH)
  const toX = (value: number): number => HORIZONTAL_PADDING + scale(value)

  const xMin = toX(stats.min)
  const xP25 = toX(stats.p25)
  const xP50 = toX(stats.p50)
  const xP75 = toX(stats.p75)
  const xMax = toX(stats.max)

  const boxLeftX = Math.min(xP25, xP75)
  const boxWidth = Math.abs(xP75 - xP25)

  return (
    <div
      role="img"
      aria-label={cycleTimeLabels.chart.boxPlotAriaLabel}
      className="w-full h-[120px]"
    >
      <svg viewBox={`0 0 ${CHART_WIDTH} ${CHART_HEIGHT}`} width="100%" height="100%" preserveAspectRatio="xMidYMid meet">
        {/* 수염 라인 — min~max */}
        <line x1={xMin} y1={WHISKER_Y} x2={xMax} y2={WHISKER_Y} stroke={ACCENT_COLOR} strokeWidth={1.5} />
        {/* 수염 끝 캡 — min */}
        <line
          x1={xMin}
          y1={WHISKER_Y - WHISKER_CAP_HALF_HEIGHT}
          x2={xMin}
          y2={WHISKER_Y + WHISKER_CAP_HALF_HEIGHT}
          stroke={ACCENT_COLOR}
          strokeWidth={1.5}
        />
        {/* 수염 끝 캡 — max */}
        <line
          x1={xMax}
          y1={WHISKER_Y - WHISKER_CAP_HALF_HEIGHT}
          x2={xMax}
          y2={WHISKER_Y + WHISKER_CAP_HALF_HEIGHT}
          stroke={ACCENT_COLOR}
          strokeWidth={1.5}
        />

        {/* 상자 — p25~p75 */}
        <rect
          x={boxLeftX}
          y={BOX_TOP_Y}
          width={boxWidth}
          height={BOX_HEIGHT}
          fill={ACCENT_COLOR}
          fillOpacity={0.15}
          stroke={ACCENT_COLOR}
          strokeWidth={1.5}
        />

        {/* 중앙선 — p50 */}
        <line x1={xP50} y1={BOX_TOP_Y} x2={xP50} y2={BOX_BOTTOM_Y} stroke={ACCENT_COLOR} strokeWidth={2.5} />

        {/* 값 라벨 — 장식 도형이 아니라 실제 값을 읽을 수 있도록 병기 */}
        <text x={xMin} y={EXTREME_LABEL_Y} textAnchor="middle" fontSize={LABEL_FONT_SIZE} className="fill-muted-foreground">
          {formatDuration(stats.min)}
        </text>
        <text x={xP25} y={QUARTILE_LABEL_Y} textAnchor="middle" fontSize={LABEL_FONT_SIZE} className="fill-muted-foreground">
          {formatDuration(stats.p25)}
        </text>
        <text x={xP50} y={MEDIAN_LABEL_Y} textAnchor="middle" fontSize={LABEL_FONT_SIZE} fontWeight="bold" className="fill-foreground">
          {formatDuration(stats.p50)}
        </text>
        <text x={xP75} y={QUARTILE_LABEL_Y} textAnchor="middle" fontSize={LABEL_FONT_SIZE} className="fill-muted-foreground">
          {formatDuration(stats.p75)}
        </text>
        <text x={xMax} y={EXTREME_LABEL_Y} textAnchor="middle" fontSize={LABEL_FONT_SIZE} className="fill-muted-foreground">
          {formatDuration(stats.max)}
        </text>
      </svg>
    </div>
  )
}
