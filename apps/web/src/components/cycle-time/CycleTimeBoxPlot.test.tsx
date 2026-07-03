// Cycle/Lead Time 박스플롯 단위 테스트 — boxPlotScale 순수 함수 + 값 텍스트/접근성 (FR-RP-04 D6/D7 task-5)
import { describe, it, expect } from 'vitest'
import { render, screen } from '@testing-library/react'
import { formatDuration } from '@/lib/format-duration'
import { cycleTimeLabels } from '@/i18n/cycle-time-labels'
import { boxPlotScale, CycleTimeBoxPlot } from './CycleTimeBoxPlot'

// ─────────────────────────────────────────────────────────────────────────────
// boxPlotScale — 순수 함수
// ─────────────────────────────────────────────────────────────────────────────

describe('boxPlotScale', () => {
  it('min을 0으로 매핑한다', () => {
    const scale = boxPlotScale({ min: 100, max: 500 }, 400)
    expect(scale(100)).toBe(0)
  })

  it('max를 width로 매핑한다', () => {
    const scale = boxPlotScale({ min: 100, max: 500 }, 400)
    expect(scale(500)).toBe(400)
  })

  it('중간값을 선형으로 비례 매핑한다', () => {
    const scale = boxPlotScale({ min: 100, max: 500 }, 400)
    // (300-100)/(500-100) * 400 = 200
    expect(scale(300)).toBe(200)
  })

  it('min===max(폭 0)이어도 NaN/Infinity를 반환하지 않는다', () => {
    const scale = boxPlotScale({ min: 100, max: 100 }, 400)
    const result = scale(100)
    expect(Number.isFinite(result)).toBe(true)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// CycleTimeBoxPlot — 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

describe('CycleTimeBoxPlot', () => {
  const stats = { min: 60, p25: 120, p50: 1800, p75: 3600, max: 7200 }

  it('min/p25/p50/p75/max 값 텍스트를 모두 렌더한다 (장식 도형 아님)', () => {
    render(<CycleTimeBoxPlot stats={stats} />)
    expect(screen.getByText(formatDuration(stats.min))).toBeInTheDocument()
    expect(screen.getByText(formatDuration(stats.p25))).toBeInTheDocument()
    expect(screen.getByText(formatDuration(stats.p50))).toBeInTheDocument()
    expect(screen.getByText(formatDuration(stats.p75))).toBeInTheDocument()
    expect(screen.getByText(formatDuration(stats.max))).toBeInTheDocument()
  })

  it('role="img"를 노출하고 aria-label은 정적 안내 문구로 시작한다', () => {
    render(<CycleTimeBoxPlot stats={stats} />)
    const el = screen.getByRole('img')
    expect(el.getAttribute('aria-label')).toContain(cycleTimeLabels.chart.boxPlotAriaLabel)
  })

  it('aria-label에 실제 최소/중앙값/최대값이 포함된다 (review-fix 2 — 비시각 접근성)', () => {
    render(<CycleTimeBoxPlot stats={stats} />)
    const ariaLabel = screen.getByRole('img').getAttribute('aria-label') ?? ''
    expect(ariaLabel).toContain(formatDuration(stats.min))
    expect(ariaLabel).toContain(formatDuration(stats.p50))
    expect(ariaLabel).toContain(formatDuration(stats.max))
  })

  it('min===max(모든 표본이 동일)여도 에러 없이 렌더한다', () => {
    const flatStats = { min: 600, p25: 600, p50: 600, p75: 600, max: 600 }
    render(<CycleTimeBoxPlot stats={flatStats} />)
    expect(screen.getAllByText(formatDuration(600)).length).toBeGreaterThan(0)
  })

  it('min===max(모든 표본이 동일)이면 값 라벨이 5개가 아니라 1개만 렌더된다 (review-fix 3)', () => {
    const flatStats = { min: 600, p25: 600, p50: 600, p75: 600, max: 600 }
    const { container } = render(<CycleTimeBoxPlot stats={flatStats} />)
    const valueTexts = container.querySelectorAll('svg text')
    expect(valueTexts).toHaveLength(1)
  })

  it('min!==max이면 값 라벨이 기존대로 5개(min/p25/p50/p75/max) 렌더된다 (review-fix 3 회귀)', () => {
    const { container } = render(<CycleTimeBoxPlot stats={stats} />)
    const valueTexts = container.querySelectorAll('svg text')
    expect(valueTexts).toHaveLength(5)
  })
})
