// CycleTimeHistogram 데이터 변환 순수 함수 toHistogram 단위 테스트 + 컴포넌트 smoke 테스트
/**
 * recharts ResponsiveContainer 가 jsdom 에서 width/height=0 이라 차트 내부가 렌더되지 않으므로
 * (1) toHistogram 순수 함수 단위 테스트, (2) vi.mock('recharts') 를 이용한 컴포넌트 smoke 테스트만 수행한다.
 * 실 렌더(시각 검증)는 e2e 스펙에 위임한다 (FR-RP-01/02/03 D6/D7 선례).
 */
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import type { SampleResponse } from '@/api/cycle-time'
import { formatDurationRange } from '@/lib/format-duration'
import { cycleTimeLabels } from '@/i18n/cycle-time-labels'

// ─────────────────────────────────────────────────────────────────────────────
// recharts stub — BurndownChart/WorklogAggregateChart 선례
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('recharts', () => ({
  ResponsiveContainer: ({ children }: { children: React.ReactNode }) => (
    <div data-testid="recharts-responsive-container">{children}</div>
  ),
  BarChart: ({ children }: { children: React.ReactNode }) => (
    <div data-testid="recharts-bar-chart">{children}</div>
  ),
  Bar: () => <div data-testid="recharts-bar" />,
  XAxis: () => <div data-testid="recharts-xaxis" />,
  YAxis: () => <div data-testid="recharts-yaxis" />,
  Tooltip: () => <div data-testid="recharts-tooltip" />,
  CartesianGrid: () => <div data-testid="recharts-cartesian-grid" />,
}))

// mock 선언 이후 import — vi.mock 호이스팅 때문에 동적 import 방식 사용
const { toHistogram, CycleTimeHistogram } = await import('./CycleTimeHistogram')

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 픽스처
// ─────────────────────────────────────────────────────────────────────────────

function makeSample(issueKey: string, seconds: number): SampleResponse {
  return { issueKey, seconds }
}

/** 0, 10, 20, ..., 100 (초) — 11개 표본, 등간격 검증용 */
const evenlySpreadSamples: SampleResponse[] = Array.from({ length: 11 }, (_, i) =>
  makeSample(`BTS-${i + 1}`, i * 10),
)

// ─────────────────────────────────────────────────────────────────────────────
// toHistogram — 순수 함수 단위 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('toHistogram', () => {
  it('등간격 binCount개 구간을 만들고, 모든 구간 count 합은 samples.length 와 같다', () => {
    const result = toHistogram(evenlySpreadSamples, 10)
    expect(result).toHaveLength(10)
    const totalCount = result.reduce((sum, bin) => sum + bin.count, 0)
    expect(totalCount).toBe(evenlySpreadSamples.length)
  })

  it('모든 seconds가 동일(min===max)하면 단일 구간에 전부 귀속된다', () => {
    const samples: SampleResponse[] = [
      makeSample('BTS-1', 500),
      makeSample('BTS-2', 500),
      makeSample('BTS-3', 500),
    ]
    const result = toHistogram(samples, 10)
    expect(result).toHaveLength(1)
    expect(result[0]).toEqual({
      label: formatDurationRange(500, 500),
      rangeStartSeconds: 500,
      rangeEndSeconds: 500,
      count: 3,
    })
  })

  it('빈 배열이면 빈 결과를 반환한다', () => {
    expect(toHistogram([], 10)).toEqual([])
  })

  it('마지막 구간이 우측 경계(max)를 포함한다', () => {
    const result = toHistogram(evenlySpreadSamples, 10)
    const lastBin = result[9]
    expect(lastBin).toBeDefined()
    expect(lastBin?.rangeEndSeconds).toBe(100)
    // max(100)과 그 직전 값(90)이 모두 마지막 구간에 귀속되어 count=2
    expect(lastBin?.count).toBe(2)
  })

  it('label은 formatDurationRange(rangeStartSeconds, rangeEndSeconds) 결과와 같다', () => {
    const result = toHistogram(evenlySpreadSamples, 10)
    for (const bin of result) {
      expect(bin.label).toBe(formatDurationRange(bin.rangeStartSeconds, bin.rangeEndSeconds))
    }
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// CycleTimeHistogram — 컴포넌트 smoke 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('CycleTimeHistogram (smoke)', () => {
  it('throw 없이 마운트되고 차트 컨테이너가 렌더된다', () => {
    expect(() => render(<CycleTimeHistogram samples={evenlySpreadSamples} />)).not.toThrow()
    expect(screen.getByTestId('recharts-bar-chart')).toBeInTheDocument()
  })

  it('차트 컨테이너에 role=img + aria-label이 존재한다', () => {
    render(<CycleTimeHistogram samples={evenlySpreadSamples} />)
    const el = screen.getByRole('img')
    expect(el).toHaveAttribute('aria-label', cycleTimeLabels.chart.histogramAriaLabel)
  })

  it('samples가 빈 배열이어도 throw 없이 마운트된다', () => {
    expect(() => render(<CycleTimeHistogram samples={[]} />)).not.toThrow()
  })
})
