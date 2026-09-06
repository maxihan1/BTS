// BurndownChart 데이터 변환 순수 함수 toBurndownSeries 단위 테스트 + 컴포넌트 smoke 테스트
/**
 * recharts ResponsiveContainer 가 jsdom 에서 width/height=0 이라 차트 내부가 렌더되지 않으므로
 * (1) toBurndownSeries 순수 함수 단위 테스트, (2) vi.mock('recharts') 를 이용한 컴포넌트 smoke 테스트만 수행한다.
 * 실 렌더(시각 검증)는 e2e/sprint-burndown.spec.ts 에 위임한다 (FR-TT-02 D6/D7 선례).
 */
import { describe, it, expect, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import type { BurndownResponse } from '@/api/burndown'
import { burndownLabels } from '@/i18n/burndown-labels'

// ─────────────────────────────────────────────────────────────────────────────
// recharts stub — Line 은 dataKey 별로 구분 가능한 testid 를 부여해
// 뷰(burndown/burnup)에 따라 어떤 라인이 렌더되는지 검증한다.
// ─────────────────────────────────────────────────────────────────────────────

vi.mock('recharts', () => ({
  ResponsiveContainer: ({ children }: { children: React.ReactNode }) => (
    <div data-testid="recharts-responsive-container">{children}</div>
  ),
  LineChart: ({ children }: { children: React.ReactNode }) => (
    <div data-testid="recharts-line-chart">{children}</div>
  ),
  Line: (props: { dataKey?: string }) => (
    <div data-testid={`recharts-line-${String(props.dataKey ?? '')}`} />
  ),
  XAxis: () => <div data-testid="recharts-xaxis" />,
  YAxis: () => <div data-testid="recharts-yaxis" />,
  Tooltip: () => <div data-testid="recharts-tooltip" />,
  Legend: () => <div data-testid="recharts-legend" />,
  CartesianGrid: () => <div data-testid="recharts-cartesian-grid" />,
}))

// mock 선언 이후 import — vi.mock 호이스팅 때문에 동적 import 방식 사용
const { toBurndownSeries, BurndownChart } = await import('./BurndownChart')

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const burndownFixture: BurndownResponse = {
  sprintId: '11111111-1111-4111-8111-111111111111',
  projectKey: 'BTS',
  status: 'ACTIVE',
  startDate: '2026-07-01',
  endDate: '2026-07-10',
  totalScopeSeconds: 36000,
  // 백엔드 `BurndownResponse.unit` 은 non-null 이라 스키마도 필수다(부채 177 task-38).
  unit: 'SECONDS',
  points: [
    {
      date: '2026-07-01',
      remainingSeconds: 28800,
      idealSeconds: 25920,
      completedSeconds: 7200,
      scopeSeconds: 36000,
    },
    {
      date: '2026-07-10',
      remainingSeconds: null,
      idealSeconds: 0,
      completedSeconds: null,
      scopeSeconds: 36000,
    },
  ],
}

// ─────────────────────────────────────────────────────────────────────────────
// toBurndownSeries — 순수 함수 단위 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('toBurndownSeries', () => {
  it("view='burndown' → {date, remaining, ideal, scope} 배열로 변환한다 (remaining null 보존)", () => {
    const result = toBurndownSeries(burndownFixture, 'burndown')
    expect(result).toHaveLength(2)
    expect(result[0]).toEqual({ date: '2026-07-01', remaining: 28800, ideal: 25920, scope: 36000 })
    expect(result[1]).toEqual({ date: '2026-07-10', remaining: null, ideal: 0, scope: 36000 })
  })

  it("view='burnup' → {date, completed, scope} 배열로 변환한다 (completed null 보존)", () => {
    const result = toBurndownSeries(burndownFixture, 'burnup')
    expect(result).toHaveLength(2)
    expect(result[0]).toEqual({ date: '2026-07-01', completed: 7200, scope: 36000 })
    expect(result[1]).toEqual({ date: '2026-07-10', completed: null, scope: 36000 })
  })

  it('points가 빈 배열이면 빈 배열을 반환한다', () => {
    const empty: BurndownResponse = { ...burndownFixture, points: [] }
    expect(toBurndownSeries(empty, 'burndown')).toHaveLength(0)
    expect(toBurndownSeries(empty, 'burnup')).toHaveLength(0)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// BurndownChart — 컴포넌트 smoke 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('BurndownChart (smoke)', () => {
  it('view=burndown → throw 없이 마운트되고 scope/ideal/remaining 라인을 렌더한다', () => {
    expect(() =>
      render(<BurndownChart response={burndownFixture} view="burndown" />),
    ).not.toThrow()
    expect(screen.getByTestId('recharts-line-scope')).toBeInTheDocument()
    expect(screen.getByTestId('recharts-line-ideal')).toBeInTheDocument()
    expect(screen.getByTestId('recharts-line-remaining')).toBeInTheDocument()
    expect(screen.queryByTestId('recharts-line-completed')).not.toBeInTheDocument()
  })

  it('view=burnup → scope/completed 라인만 렌더한다 (ideal/remaining 없음)', () => {
    render(<BurndownChart response={burndownFixture} view="burnup" />)
    expect(screen.getByTestId('recharts-line-scope')).toBeInTheDocument()
    expect(screen.getByTestId('recharts-line-completed')).toBeInTheDocument()
    expect(screen.queryByTestId('recharts-line-ideal')).not.toBeInTheDocument()
    expect(screen.queryByTestId('recharts-line-remaining')).not.toBeInTheDocument()
  })

  it('다중 라인 가독성을 위해 Legend를 렌더한다', () => {
    render(<BurndownChart response={burndownFixture} view="burndown" />)
    expect(screen.getByTestId('recharts-legend')).toBeInTheDocument()
  })

  it('차트 컨테이너에 role=img + aria-label이 존재한다', () => {
    render(<BurndownChart response={burndownFixture} view="burndown" />)
    const el = screen.getByRole('img')
    expect(el).toHaveAttribute('aria-label', burndownLabels.chart.ariaLabel)
  })
})
