// Cycle/Lead Time 지표 섹션 테스트 — 요약 타일 위계(FIX-1) + count 가드 분기 (FR-RP-04 D6/D7 Task-6)
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import type { MetricResponse } from '@/api/cycle-time'

// ─────────────────────────────────────────────────────────────────────────────
// vi.mock — 자식 차트는 렌더 여부만 확인, recharts/SVG geometry는 단언하지 않는다
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('./CycleTimeHistogram', () => ({
  CycleTimeHistogram: () => <div data-testid="mock-histogram" />,
}))
vi.mock('./CycleTimeBoxPlot', () => ({
  CycleTimeBoxPlot: () => <div data-testid="mock-boxplot" />,
}))

import { CycleTimeMetricSection } from './CycleTimeMetricSection'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 표본이 있는 지표 픽스처. formatDuration 결과가 8개 값 모두 서로 다르도록
 * 초 값을 선택해 텍스트 단언 시 모호함이 없게 한다.
 * count=12(그대로), min=60→"1분", p25=120→"2분", p50=3600→"1시간",
 * p75=7200→"2시간", avg=5400→"1시간 30분", p90=8100→"2시간 45분", max=90000→"1일 1시간"
 */
const fullMetric: MetricResponse = {
  count: 12,
  min: 60,
  max: 90000,
  avg: 5400,
  p25: 120,
  p50: 3600,
  p75: 7200,
  p90: 8100,
  samples: [
    { issueKey: 'BTS-1', seconds: 60 },
    { issueKey: 'BTS-2', seconds: 90000 },
  ],
}

/** 표본이 없는 지표 픽스처 — count===0이면 나머지 통계는 전부 null */
const emptyMetric: MetricResponse = {
  count: 0,
  min: null,
  max: null,
  avg: null,
  p25: null,
  p50: null,
  p75: null,
  p90: null,
  samples: [],
}

/** count>0이지만 p50이 null인 방어적 시나리오 픽스처 — 백엔드 계약상 발생하지 않아야 하나 타입은 nullable */
const partiallyNullMetric: MetricResponse = {
  count: 3,
  min: 60,
  max: 7200,
  avg: 1800,
  p25: 120,
  p50: null,
  p75: 3600,
  p90: 5400,
  samples: [{ issueKey: 'BTS-3', seconds: 60 }],
}

const sectionProps = {
  title: 'Cycle Time',
  description: '첫 진행 착수부터 완료까지 걸린 시간입니다.',
  emptyMessage: '진행 중 상태를 거친 완료 이슈가 없습니다.',
}

// ─────────────────────────────────────────────────────────────────────────────
// (a) count>0 — 요약 타일 + 히스토그램 + 박스플롯 렌더
// ─────────────────────────────────────────────────────────────────────────────

describe('CycleTimeMetricSection — count>0', () => {
  it('요약 타일(count/min/max/avg/p25/p50/p75/p90)이 formatDuration 표기로 렌더된다', () => {
    render(<CycleTimeMetricSection metric={fullMetric} {...sectionProps} />)

    expect(screen.getByText('12')).toBeInTheDocument()
    expect(screen.getByText('1분')).toBeInTheDocument()
    expect(screen.getByText('2분')).toBeInTheDocument()
    expect(screen.getByText('1시간')).toBeInTheDocument()
    expect(screen.getByText('2시간')).toBeInTheDocument()
    expect(screen.getByText('1시간 30분')).toBeInTheDocument()
    expect(screen.getByText('2시간 45분')).toBeInTheDocument()
    expect(screen.getByText('1일 1시간')).toBeInTheDocument()
  })

  it('CycleTimeHistogram과 CycleTimeBoxPlot이 렌더된다', () => {
    render(<CycleTimeMetricSection metric={fullMetric} {...sectionProps} />)

    expect(screen.getByTestId('mock-histogram')).toBeInTheDocument()
    expect(screen.getByTestId('mock-boxplot')).toBeInTheDocument()
  })

  it('제목과 설명이 렌더된다', () => {
    render(<CycleTimeMetricSection metric={fullMetric} {...sectionProps} />)

    expect(screen.getByText(sectionProps.title)).toBeInTheDocument()
    expect(screen.getByText(sectionProps.description)).toBeInTheDocument()
  })

  it('emptyMessage는 렌더되지 않는다', () => {
    render(<CycleTimeMetricSection metric={fullMetric} {...sectionProps} />)

    expect(screen.queryByText(sectionProps.emptyMessage)).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// (b) count===0 — emptyMessage만 렌더, 타일/차트 미렌더 (S2 — Cycle 표본 없음)
// ─────────────────────────────────────────────────────────────────────────────

describe('CycleTimeMetricSection — count===0', () => {
  it('emptyMessage가 렌더된다', () => {
    render(<CycleTimeMetricSection metric={emptyMetric} {...sectionProps} />)

    expect(screen.getByText(sectionProps.emptyMessage)).toBeInTheDocument()
  })

  it('요약 타일이 렌더되지 않는다', () => {
    render(<CycleTimeMetricSection metric={emptyMetric} {...sectionProps} />)

    expect(screen.queryByTestId('cycle-time-stat-p50')).not.toBeInTheDocument()
    expect(screen.queryByTestId('cycle-time-stat-count')).not.toBeInTheDocument()
  })

  it('히스토그램과 박스플롯이 렌더되지 않는다', () => {
    render(<CycleTimeMetricSection metric={emptyMetric} {...sectionProps} />)

    expect(screen.queryByTestId('mock-histogram')).not.toBeInTheDocument()
    expect(screen.queryByTestId('mock-boxplot')).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// (c) FIX-1 — 중앙값(p50) 타일이 대표로 강조된다
// ─────────────────────────────────────────────────────────────────────────────

describe('CycleTimeMetricSection — FIX-1 p50 대표 강조', () => {
  it('p50 타일은 text-lg font-semibold로 강조되고, 나머지 타일은 그렇지 않다', () => {
    render(<CycleTimeMetricSection metric={fullMetric} {...sectionProps} />)

    const p50Value = screen.getByTestId('cycle-time-stat-p50')
    expect(p50Value).toHaveTextContent('1시간')
    expect(p50Value.className).toContain('text-lg')
    expect(p50Value.className).toContain('font-semibold')

    const minValue = screen.getByTestId('cycle-time-stat-min')
    expect(minValue.className).not.toContain('text-lg')
    expect(minValue.className).not.toContain('font-semibold')

    const countValue = screen.getByTestId('cycle-time-stat-count')
    expect(countValue.className).not.toContain('text-lg')
    expect(countValue.className).not.toContain('font-semibold')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 방어적 narrowing — count>0인데 p50이 null이면(계약 위반) BoxPlot을 그리지 않는다
// ─────────────────────────────────────────────────────────────────────────────

describe('CycleTimeMetricSection — 방어적 narrowing (p50 null)', () => {
  it('BoxPlot은 렌더되지 않지만 요약 타일은 렌더된다', () => {
    render(<CycleTimeMetricSection metric={partiallyNullMetric} {...sectionProps} />)

    expect(screen.queryByTestId('mock-boxplot')).not.toBeInTheDocument()
    expect(screen.getByTestId('cycle-time-stat-count')).toHaveTextContent('3')
  })

  it('null인 p50 타일은 대시(-)로 표시된다', () => {
    render(<CycleTimeMetricSection metric={partiallyNullMetric} {...sectionProps} />)

    expect(screen.getByTestId('cycle-time-stat-p50')).toHaveTextContent('-')
  })
})
