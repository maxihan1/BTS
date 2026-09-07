// 분포 차트 가젯 테스트 — 4종 그룹 기준 전수 · 두 마크 · 빈/오류 상태
//
// recharts ResponsiveContainer 는 jsdom 에서 width/height=0 이라 내부가 렌더되지 않는다.
// BurndownChart.test.tsx 선례대로 (1) 순수 함수 단위 테스트 (2) recharts 를 목한 smoke 로 나눈다.
//
// ★목이 prop 을 삼키면 「전달 누락」이 유닛에서 안 보인다는 것이 이 저장소의 기실측 함정이다.
//   그래서 두 가지를 목 밖에서 잰다 —
//   ① `useProjectSummary` 의 **호출 인자**(projectKey 가 실제로 넘어갔나)
//   ② `Cell` 의 **fill 값**(색 토큰이 실제로 붙었나). 목이 fill 을 data-속성으로 되뱉는다.
import { describe, it, expect, beforeEach, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import type { ReactNode } from 'react'
import type { ProjectSummary } from '@/api/project-summary'

vi.mock('recharts', () => ({
  ResponsiveContainer: ({ children }: { children: ReactNode }) => (
    <div data-testid="recharts-responsive-container">{children}</div>
  ),
  PieChart: ({ children }: { children: ReactNode }) => (
    <div data-testid="recharts-pie-chart">{children}</div>
  ),
  BarChart: ({ children }: { children: ReactNode }) => (
    <div data-testid="recharts-bar-chart">{children}</div>
  ),
  Pie: ({ children }: { children: ReactNode }) => <div data-testid="recharts-pie">{children}</div>,
  Bar: ({ children }: { children: ReactNode }) => <div data-testid="recharts-bar">{children}</div>,
  // ★fill 을 되뱉는다 — 목이 삼키면 색 토큰 누락이 안 보인다.
  Cell: (props: { fill?: string }) => (
    <div data-testid="recharts-cell" data-fill={props.fill ?? ''} />
  ),
  XAxis: () => <div data-testid="recharts-xaxis" />,
  YAxis: () => <div data-testid="recharts-yaxis" />,
  Tooltip: () => <div data-testid="recharts-tooltip" />,
  Legend: () => <div data-testid="recharts-legend" />,
}))

vi.mock('@/hooks/use-project-summary', () => ({
  useProjectSummary: vi.fn(),
}))

const { useProjectSummary } = await import('@/hooks/use-project-summary')
const { DistributionChartGadget } = await import('./DistributionChartGadget')
const { rowsForField } = await import('./distribution-chart-model')
const { distributionRowsOf } = await import('@/components/project/summary/summary-view-model')
const { gadgetStateLabels } = await import('@/i18n/dashboard-labels')

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처 — 4종 분포가 서로 **다른 건수**를 갖는다.
// 같은 값을 쓰면 「엉뚱한 분포를 읽어도 통과」하는 가짜 그린이 된다.
// ─────────────────────────────────────────────────────────────────────────────

const summaryFixture = {
  projectKey: 'BTS',
  recent: {
    windowDays: 7,
    completed: { current: 1, previous: 0 },
    updated: { current: 2, previous: 0 },
    created: { current: 3, previous: 0 },
  },
  upcoming: { windowDays: 7, due: 1, overdue: 0 },
  statusOverview: [
    { statusKey: 'todo', statusName: '할 일', category: 'TODO', count: 11 },
    { statusKey: 'done', statusName: '완료', category: 'DONE', count: 12 },
  ],
  priorityBreakdown: [{ priority: 3, priorityName: '보통', count: 21 }],
  typesOfWork: [{ typeKey: 'task', typeName: '작업', count: 31 }],
  teamWorkload: [{ assigneeId: 'u1', assigneeName: '홍길동', count: 41 }],
} as unknown as ProjectSummary

type SummaryHookResult = ReturnType<typeof useProjectSummary>

function mockSummary(over: Partial<{ data: unknown; isLoading: boolean; isError: boolean }>): void {
  vi.mocked(useProjectSummary).mockReturnValue({
    data: summaryFixture,
    isLoading: false,
    isError: false,
    ...over,
  } as unknown as SummaryHookResult)
}

beforeEach(() => {
  vi.clearAllMocks()
})

// ─────────────────────────────────────────────────────────────────────────────
// 순수 함수 — 목과 무관하게 4종 전수를 잰다
// ─────────────────────────────────────────────────────────────────────────────

describe('rowsForField — 그룹 기준 4종 전수', () => {
  const rows = distributionRowsOf(summaryFixture)

  it.each([
    ['status', 11],
    ['priority', 21],
    ['issueType', 31],
    ['assignee', 41],
  ])('field=%s 는 그 분포를 읽는다 (첫 행 count=%i)', (field, expected) => {
    const picked = rowsForField(rows, field)
    expect(picked.length).toBeGreaterThan(0)
    expect(picked[0]?.count).toBe(expected)
  })

  it('알 수 없는 field 는 빈 배열이다 — 저장된 JSON 에 다른 값이 와도 화면이 안 죽는다', () => {
    expect(rowsForField(rows, 'labels')).toEqual([])
    expect(rowsForField(rows, undefined)).toEqual([])
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트 smoke
// ─────────────────────────────────────────────────────────────────────────────

describe('DistributionChartGadget', () => {
  it('★projectKey 를 useProjectSummary 에 그대로 넘긴다 (목이 삼키는 것 방지)', () => {
    mockSummary({})
    render(<DistributionChartGadget variant="pie" config={{ projectKey: 'BTS', field: 'status' }} />)
    expect(useProjectSummary).toHaveBeenCalledWith('BTS')
  })

  it('variant=pie 는 파이를, bar 는 막대를 그린다 — 데이터 경로는 같다', () => {
    mockSummary({})
    const { unmount } = render(
      <DistributionChartGadget variant="pie" config={{ projectKey: 'BTS', field: 'status' }} />,
    )
    expect(screen.getByTestId('recharts-pie-chart')).toBeInTheDocument()
    expect(screen.queryByTestId('recharts-bar-chart')).not.toBeInTheDocument()
    unmount()

    mockSummary({})
    render(<DistributionChartGadget variant="bar" config={{ projectKey: 'BTS', field: 'status' }} />)
    expect(screen.getByTestId('recharts-bar-chart')).toBeInTheDocument()
    expect(screen.queryByTestId('recharts-pie-chart')).not.toBeInTheDocument()
  })

  it('★조각마다 --chart-* 토큰이 붙는다 (하드코딩 색 0)', () => {
    mockSummary({})
    render(<DistributionChartGadget variant="pie" config={{ projectKey: 'BTS', field: 'status' }} />)

    const cells = screen.getAllByTestId('recharts-cell')
    expect(cells).toHaveLength(2) // statusOverview 2건
    for (const cell of cells) {
      expect(cell.getAttribute('data-fill')).toMatch(/^var\(--chart-[1-5]\)$/)
    }
  })

  it('★파이에 범례가 있다 — 색-단독 구분 금지(WCAG)', () => {
    // 파이는 막대와 달리 **축 라벨이 없다**. 범례가 없으면 조각의 뜻을 알 방법이
    // 색뿐이고, 그것이 이 저장소가 기존 차트 3종(Burndown·Velocity·CFD)에 이미
    // 「색-단독 구분 금지 → Legend/Tooltip 텍스트 병행」으로 못박아 둔 규율에 걸린다.
    // 눈확인(A9)에서 실제로 「어느 색이 어느 상태인지 못 읽는다」로 적발됐다.
    mockSummary({})
    render(<DistributionChartGadget variant="pie" config={{ projectKey: 'BTS', field: 'status' }} />)
    expect(screen.getByTestId('recharts-legend')).toBeInTheDocument()
  })

  it('막대에는 범례를 중복해 붙이지 않는다 — X축 라벨이 이미 이름을 낸다', () => {
    // 같은 이름을 축과 범례가 두 번 내면 좁은 타일에서 자리만 먹는다.
    mockSummary({})
    render(<DistributionChartGadget variant="bar" config={{ projectKey: 'BTS', field: 'status' }} />)
    expect(screen.getByTestId('recharts-xaxis')).toBeInTheDocument()
    expect(screen.queryByTestId('recharts-legend')).not.toBeInTheDocument()
  })

  it('이슈가 0건이면 빈 파이를 그리지 않고 빈 상태를 낸다 (E3)', () => {
    mockSummary({
      data: { ...summaryFixture, statusOverview: [] },
    })
    render(<DistributionChartGadget variant="pie" config={{ projectKey: 'BTS', field: 'status' }} />)

    expect(screen.getByText(gadgetStateLabels.noDistribution)).toBeInTheDocument()
    expect(screen.queryByTestId('recharts-pie-chart')).not.toBeInTheDocument()
  })

  it('조회 실패(404 포함)는 빈 상태로 흡수한다 — 대시보드 전체가 죽지 않는다 (E4)', () => {
    mockSummary({ data: undefined, isError: true })
    render(<DistributionChartGadget variant="bar" config={{ projectKey: 'GONE', field: 'status' }} />)

    expect(screen.getByText(gadgetStateLabels.loadFailed)).toBeInTheDocument()
  })

  it('projectKey 가 없으면 설정 안내를 낸다', () => {
    mockSummary({})
    render(<DistributionChartGadget variant="pie" config={{ field: 'status' }} />)

    expect(screen.getByText(gadgetStateLabels.notConfigured)).toBeInTheDocument()
  })

  it('로딩 중에는 차트를 그리지 않는다', () => {
    mockSummary({ data: undefined, isLoading: true })
    render(<DistributionChartGadget variant="pie" config={{ projectKey: 'BTS', field: 'status' }} />)

    expect(screen.getByText(gadgetStateLabels.loading)).toBeInTheDocument()
    expect(screen.queryByTestId('recharts-pie-chart')).not.toBeInTheDocument()
  })
})
