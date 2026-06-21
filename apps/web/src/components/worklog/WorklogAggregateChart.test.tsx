// WorklogAggregateChart 데이터 변환 순수 함수 + 컴포넌트 smoke 테스트 + C2 절단 안내 렌더 검증
/**
 * 실 렌더(시각 검증)는 e2e/worklog-aggregate.spec.ts 에 위임한다.
 * recharts ResponsiveContainer 가 jsdom 에서 width/height=0 이라 차트 내부가 렌더되지 않으므로
 * 여기서는 (1) toChartData 순수 함수 단위 테스트, (2) vi.mock('recharts') 를 이용한 컴포넌트 smoke 테스트만 수행한다.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, screen } from '@testing-library/react'
import type { WorklogAggregateBucket, AggregateDimension } from '@/api/worklog-aggregate'

// ─────────────────────────────────────────────────────────────────────────────
// recharts stub — jsdom 에서 ResponsiveContainer 가 차트를 렌더하지 않으므로
// 경량 stub 으로 대체한다.
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
const { toChartData, WorklogAggregateChart } = await import('./WorklogAggregateChart')

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 픽스처
// ─────────────────────────────────────────────────────────────────────────────

function makeBucket(overrides?: Partial<WorklogAggregateBucket>): WorklogAggregateBucket {
  return {
    key: 'KEY-1',
    label: 'Issue One',
    timeSpentSeconds: 3600,
    worklogCount: 1,
    ...overrides,
  }
}

function makeBuckets(count: number): WorklogAggregateBucket[] {
  return Array.from({ length: count }, (_, i) =>
    makeBucket({ key: `KEY-${i + 1}`, label: `Issue ${i + 1}`, timeSpentSeconds: (i + 1) * 600 }),
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// toChartData — 순수 함수 단위 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('toChartData', () => {
  it('정상 buckets → {label, seconds} 배열로 변환한다', () => {
    const buckets: WorklogAggregateBucket[] = [
      makeBucket({ key: 'KEY-1', label: 'Issue One', timeSpentSeconds: 3600 }),
      makeBucket({ key: 'KEY-2', label: 'Issue Two', timeSpentSeconds: 1800 }),
    ]
    const result = toChartData(buckets, 'issue')
    expect(result).toHaveLength(2)
    expect(result[0]).toEqual({ label: 'Issue One', seconds: 3600 })
    expect(result[1]).toEqual({ label: 'Issue Two', seconds: 1800 })
  })

  it('21개 buckets → 상위 20개만 반환한다(절단)', () => {
    const buckets = makeBuckets(21)
    const result = toChartData(buckets, 'issue')
    expect(result).toHaveLength(20)
  })

  it('빈 배열 → 빈 배열을 반환한다', () => {
    const result = toChartData([], 'issue')
    expect(result).toHaveLength(0)
  })

  it('label 빈 문자열 → "(알 수 없음)" placeholder 로 변환한다', () => {
    const buckets: WorklogAggregateBucket[] = [
      makeBucket({ key: 'user-uuid', label: '', timeSpentSeconds: 1800 }),
    ]
    const result = toChartData(buckets, 'user')
    expect(result[0]?.label).toBe('(알 수 없음)')
  })

  it('period 차원 → 백엔드 순서(ASC) 그대로 유지한다', () => {
    const buckets: WorklogAggregateBucket[] = [
      makeBucket({ key: '2024-01', label: '2024-01', timeSpentSeconds: 1000 }),
      makeBucket({ key: '2024-02', label: '2024-02', timeSpentSeconds: 2000 }),
      makeBucket({ key: '2024-03', label: '2024-03', timeSpentSeconds: 500 }),
    ]
    const result = toChartData(buckets, 'period')
    expect(result.map((d) => d.label)).toEqual(['2024-01', '2024-02', '2024-03'])
  })

  it('issue/user 차원 → 백엔드 순서(DESC) 그대로 유지한다', () => {
    const buckets: WorklogAggregateBucket[] = [
      makeBucket({ key: 'KEY-3', label: 'Big issue', timeSpentSeconds: 9000 }),
      makeBucket({ key: 'KEY-1', label: 'Small issue', timeSpentSeconds: 1000 }),
    ]
    const result = toChartData(buckets, 'user')
    expect(result.map((d) => d.label)).toEqual(['Big issue', 'Small issue'])
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// WorklogAggregateChart — 컴포넌트 smoke 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('WorklogAggregateChart (smoke)', () => {
  const DIMENSIONS: AggregateDimension[] = ['issue', 'user', 'period']

  beforeEach(() => {
    vi.clearAllMocks()
  })

  it.each(DIMENSIONS)('dimension="%s", 빈 배열 → throw 없이 마운트된다', (dimension) => {
    expect(() => render(<WorklogAggregateChart buckets={[]} dimension={dimension} />)).not.toThrow()
  })

  it.each(DIMENSIONS)(
    'dimension="%s", 정상 buckets → throw 없이 마운트되고 차트 컨테이너가 렌더된다',
    (dimension) => {
      const buckets = makeBuckets(5)
      expect(() =>
        render(<WorklogAggregateChart buckets={buckets} dimension={dimension} />),
      ).not.toThrow()
      expect(screen.getAllByTestId('recharts-responsive-container').length).toBeGreaterThan(0)
    },
  )

  it('빈 배열이면 컴포넌트가 null 을 반환해 아무것도 렌더하지 않는다', () => {
    const { container } = render(<WorklogAggregateChart buckets={[]} dimension="issue" />)
    expect(container.firstChild).toBeNull()
  })

  it('차트 컨테이너에 aria-label 이 존재한다', () => {
    const buckets = makeBuckets(3)
    render(<WorklogAggregateChart buckets={buckets} dimension="issue" />)
    const el = screen.getByRole('img', { hidden: true })
    expect(el).toBeDefined()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// C2 — 상위 N 절단 안내 렌더 검증 (codereview fix)
// ─────────────────────────────────────────────────────────────────────────────

describe('WorklogAggregateChart — C2 절단 안내(topNHint) 렌더', () => {
  it('buckets 21개(CHART_TOP_N+1)이면 절단 안내 문구가 노출된다', () => {
    const buckets = makeBuckets(21)
    render(<WorklogAggregateChart buckets={buckets} dimension="issue" />)
    // topNHint의 {count}를 CHART_TOP_N(20)으로 치환한 결과가 렌더되어야 한다
    expect(screen.getByText('상위 20개 항목만 표시됩니다.')).toBeInTheDocument()
  })

  it('buckets 20개(CHART_TOP_N)이면 절단 안내 문구가 노출되지 않는다', () => {
    const buckets = makeBuckets(20)
    render(<WorklogAggregateChart buckets={buckets} dimension="issue" />)
    expect(screen.queryByText('상위 20개 항목만 표시됩니다.')).not.toBeInTheDocument()
  })

  it('buckets 1개이면 절단 안내 문구가 노출되지 않는다', () => {
    const buckets = makeBuckets(1)
    render(<WorklogAggregateChart buckets={buckets} dimension="issue" />)
    expect(screen.queryByText('상위 20개 항목만 표시됩니다.')).not.toBeInTheDocument()
  })
})
