/* eslint-disable react-refresh/only-export-components -- toHistogram/HistogramBin 은 테스트용 named export (CfdChart/BurndownChart 선례) */
// Cycle/Lead Time 소요 시간 분포 히스토그램 컴포넌트 — recharts BarChart, 등간격 구간별 이슈 수
import type { JSX } from 'react'
import { ResponsiveContainer, BarChart, Bar, XAxis, YAxis, Tooltip, CartesianGrid } from 'recharts'
import type { SampleResponse } from '@/api/cycle-time'
import { formatDurationRange } from '@/lib/format-duration'
import { cycleTimeLabels } from '@/i18n/cycle-time-labels'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** recharts ResponsiveContainer / wrapper div 의 고정 높이 (px). */
const CHART_HEIGHT = 360

/** BarChart 내부 여백 — 형제 차트(CfdChart/VelocityChart)와 톤 통일. */
const CHART_MARGIN = { top: 8, right: 24, bottom: 8, left: 8 }

/** 히스토그램 등간격 구간(bin) 개수. */
const BIN_COUNT = 10

/** 막대 색상 — 단일 지표(분포)라 CfdChart의 done 계열 색상 하나만 재사용. */
const BAR_COLOR = '#6366f1'

/** 축 tick 공통 스타일. */
const AXIS_TICK_STYLE = { fontSize: 12 }

// ─────────────────────────────────────────────────────────────────────────────
// 데이터 변환 — 순수 함수 (단위 테스트 대상)
// ─────────────────────────────────────────────────────────────────────────────

/** 히스토그램 구간(bin) 단건 */
export interface HistogramBin {
  /** 구간 라벨 (예. "0초 ~ 1시간") */
  label: string
  /** 구간 시작 값 (초, 포함) */
  rangeStartSeconds: number
  /** 구간 끝 값 (초) — 마지막 구간만 포함(closed), 그 외는 다음 구간에 넘김(half-open) */
  rangeEndSeconds: number
  /** 구간에 속한 표본 개수 */
  count: number
}

/**
 * 등간격 구간(bin) 경계 배열을 만든다. 마지막 구간의 끝은 부동소수점 누적 오차를 피하기 위해
 * `max` 값을 그대로 사용한다.
 *
 * @param min 전체 표본의 최소값 (초)
 * @param max 전체 표본의 최대값 (초)
 * @param binCount 구간 개수
 * @returns 구간별 { start, end } 배열
 */
function buildBinRanges(
  min: number,
  max: number,
  binCount: number,
): Array<{ start: number; end: number }> {
  const binWidth = (max - min) / binCount
  return Array.from({ length: binCount }, (_, index) => ({
    start: min + index * binWidth,
    end: index === binCount - 1 ? max : min + (index + 1) * binWidth,
  }))
}

/**
 * 값이 속할 구간 인덱스를 계산한다. `max`와 같은 값은 이론상 `binCount`가 되므로
 * 마지막 구간(`binCount - 1`)으로 clamp 해 우측 경계를 마지막 구간에 포함시킨다.
 *
 * @param value 표본 값 (초)
 * @param min 전체 표본의 최소값 (초)
 * @param binWidth 구간 폭 (초)
 * @param binCount 구간 개수
 * @returns 0 이상 `binCount - 1` 이하의 구간 인덱스
 */
function toBinIndex(value: number, min: number, binWidth: number, binCount: number): number {
  return Math.min(Math.floor((value - min) / binWidth), binCount - 1)
}

/**
 * Cycle/Lead Time 표본(초 단위 소요 시간) 배열을 등간격 히스토그램 구간 배열로 변환한다.
 *
 * - 표본이 없으면 빈 배열
 * - 모든 표본 값이 같으면(min===max) 구간 폭을 나눌 수 없으므로 단일 구간에 전부 귀속
 * - 그 외에는 [min, max] 범위를 binCount개 등간격 구간으로 나누고, 각 값을 half-open
 *   구간([start, end))에 배정하되 마지막 구간만 우측 경계(max)를 포함한다
 *
 * @param samples 소요 시간(초) 개별 이슈 표본 목록
 * @param binCount 구간 개수
 * @returns 구간별 { label, rangeStartSeconds, rangeEndSeconds, count } 배열
 */
export function toHistogram(samples: SampleResponse[], binCount: number): HistogramBin[] {
  if (samples.length === 0) return []

  const seconds = samples.map((sample) => sample.seconds)
  const min = Math.min(...seconds)
  const max = Math.max(...seconds)

  if (min === max) {
    return [
      { label: formatDurationRange(min, max), rangeStartSeconds: min, rangeEndSeconds: max, count: samples.length },
    ]
  }

  const binWidth = (max - min) / binCount
  const counts = new Array<number>(binCount).fill(0)
  for (const value of seconds) {
    const index = toBinIndex(value, min, binWidth, binCount)
    counts[index] = (counts[index] ?? 0) + 1
  }

  return buildBinRanges(min, max, binCount).map((range, index) => ({
    label: formatDurationRange(range.start, range.end),
    rangeStartSeconds: range.start,
    rangeEndSeconds: range.end,
    count: counts[index] ?? 0,
  }))
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/** CycleTimeHistogram props */
interface CycleTimeHistogramProps {
  /** Cycle/Lead Time 지표의 개별 이슈 표본 목록 */
  samples: SampleResponse[]
}

/**
 * Cycle/Lead Time 소요 시간 분포 히스토그램.
 *
 * - 표본(초 단위 소요 시간)을 `BIN_COUNT`개 등간격 구간으로 나눠 구간별 이슈 수를 막대로 표시
 * - 접근성: 컨테이너 `role="img"` + aria-label 로 차트 목적 서술
 *
 * 실 렌더(시각 검증)는 e2e 스펙에 위임한다.
 */
export function CycleTimeHistogram({ samples }: CycleTimeHistogramProps): JSX.Element {
  const data = toHistogram(samples, BIN_COUNT)
  return (
    // w-full: recharts ResponsiveContainer 가 부모 너비를 참조하므로 필수.
    // h-[360px]: 높이는 Tailwind 임의값으로 지정 (CHART_HEIGHT 와 동기화).
    <div role="img" aria-label={cycleTimeLabels.chart.histogramAriaLabel} className="w-full h-[360px]">
      <ResponsiveContainer width="100%" height={CHART_HEIGHT}>
        <BarChart data={data} margin={CHART_MARGIN}>
          <CartesianGrid strokeDasharray="3 3" />
          <XAxis dataKey="label" tick={AXIS_TICK_STYLE} />
          <YAxis allowDecimals={false} tick={AXIS_TICK_STYLE} />
          <Tooltip />
          <Bar dataKey="count" fill={BAR_COLOR} />
        </BarChart>
      </ResponsiveContainer>
    </div>
  )
}
