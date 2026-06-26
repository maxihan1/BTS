// GanttChart 컴포넌트 단위 테스트 — 그룹 렌더·접기/펼치기·마일스톤·콜백·담당자 표시 (FR-TL-01 D6)
import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import type { TimelineItem } from '@/api/timeline'
import { GanttChart } from './GanttChart'

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const epicItem: TimelineItem = {
  key: 'ATLAS-1',
  summary: '에픽 1',
  issueType: 'epic',
  currentStateKey: 'in-progress',
  assigneeId: 'u-epic-01',
  startDate: '2026-07-01',
  dueDate: '2026-07-31',
  targetDate: null,
  epicKey: null,
}

const childItem: TimelineItem = {
  key: 'ATLAS-2',
  summary: '자식 이슈',
  issueType: 'task',
  currentStateKey: 'todo',
  assigneeId: null,
  startDate: '2026-07-05',
  dueDate: '2026-07-15',
  targetDate: '2026-07-10',
  epicKey: 'ATLAS-1',
}

const unclassifiedItem: TimelineItem = {
  key: 'ATLAS-3',
  summary: '미분류 이슈',
  issueType: 'task',
  currentStateKey: 'todo',
  assigneeId: 'u-other-01',
  startDate: '2026-07-08',
  dueDate: null,
  targetDate: null,
  epicKey: null,
}

const defaultItems: TimelineItem[] = [epicItem, childItem, unclassifiedItem]

/** ATLAS-1: 이름 있음, ATLAS-2: assigneeId=null → 미배정, ATLAS-3: 이름 있음 */
const defaultAssigneeNames: Map<string, string> = new Map([
  ['ATLAS-1', '김에픽'],
  ['ATLAS-3', '이미분류'],
])

function renderChart(overrides?: {
  items?: TimelineItem[]
  assigneeNames?: Map<string, string>
  onSelectIssue?: (key: string) => void
}) {
  return render(
    <GanttChart
      items={overrides?.items ?? defaultItems}
      assigneeNames={overrides?.assigneeNames ?? defaultAssigneeNames}
      onSelectIssue={overrides?.onSelectIssue ?? vi.fn()}
    />,
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// S1. 그룹/행 렌더
// ─────────────────────────────────────────────────────────────────────────────

describe('GanttChart — S1 그룹/행 렌더', () => {
  it('S1a: 에픽 이슈 키가 렌더된다', () => {
    renderChart()
    expect(screen.getByText('ATLAS-1')).toBeInTheDocument()
  })

  it('S1b: 자식 행 이슈 키가 렌더된다', () => {
    renderChart()
    expect(screen.getByText('ATLAS-2')).toBeInTheDocument()
  })

  it('S1c: 미분류 그룹 레이블이 렌더된다', () => {
    renderChart()
    expect(screen.getByText('미분류')).toBeInTheDocument()
  })

  it('S1d: 미분류 아이템 키가 렌더된다', () => {
    renderChart()
    expect(screen.getByText('ATLAS-3')).toBeInTheDocument()
  })

  it('S1e: items가 빈 배열이면 아무 이슈 키도 렌더하지 않는다', () => {
    renderChart({ items: [] })
    expect(screen.queryByText('ATLAS-1')).not.toBeInTheDocument()
    expect(screen.queryByText('미분류')).not.toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S2. 접기/펼치기 토글
// ─────────────────────────────────────────────────────────────────────────────

describe('GanttChart — S2 접기/펼치기 토글', () => {
  it('S2a: 에픽 그룹은 초기 상태에서 aria-expanded=true인 토글 버튼을 가진다', () => {
    renderChart()
    const toggleBtn = screen.getByRole('button', { name: /접기/ })
    expect(toggleBtn).toHaveAttribute('aria-expanded', 'true')
  })

  it('S2b: 토글 버튼 클릭 시 자식 행이 사라진다', () => {
    renderChart()
    const toggleBtn = screen.getByRole('button', { name: /접기/ })

    expect(screen.getByText('ATLAS-2')).toBeInTheDocument()
    fireEvent.click(toggleBtn)
    expect(screen.queryByText('ATLAS-2')).not.toBeInTheDocument()
  })

  it('S2c: 접힌 상태에서 토글 버튼 aria-expanded=false가 된다', () => {
    renderChart()
    const toggleBtn = screen.getByRole('button', { name: /접기/ })
    fireEvent.click(toggleBtn)

    const expandBtn = screen.getByRole('button', { name: /펼치기/ })
    expect(expandBtn).toHaveAttribute('aria-expanded', 'false')
  })

  it('S2d: 접힌 상태에서 다시 클릭하면 자식 행이 나타난다', () => {
    renderChart()
    const collapseBtn = screen.getByRole('button', { name: /접기/ })
    fireEvent.click(collapseBtn)
    expect(screen.queryByText('ATLAS-2')).not.toBeInTheDocument()

    const expandBtn = screen.getByRole('button', { name: /펼치기/ })
    fireEvent.click(expandBtn)
    expect(screen.getByText('ATLAS-2')).toBeInTheDocument()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S3. 마일스톤 표식
// ─────────────────────────────────────────────────────────────────────────────

describe('GanttChart — S3 마일스톤 표식', () => {
  it('S3a: targetDate가 있는 아이템에 ◆ 마일스톤 표식이 렌더된다', () => {
    renderChart()
    // childItem에 targetDate: '2026-07-10'이 있음
    expect(screen.getByText('◆')).toBeInTheDocument()
  })

  it('S3b: targetDate가 없는 아이템만 있으면 ◆가 렌더되지 않는다', () => {
    renderChart({ items: [epicItem, unclassifiedItem] })
    expect(screen.queryByText('◆')).not.toBeInTheDocument()
  })

  it('S3c: targetDate가 있는 아이템이 여럿이면 복수의 ◆가 렌더된다', () => {
    const secondMilestone: TimelineItem = {
      ...unclassifiedItem,
      key: 'ATLAS-4',
      summary: '두 번째 마일스톤',
      targetDate: '2026-07-20',
    }
    renderChart({ items: [childItem, secondMilestone] })
    expect(screen.getAllByText('◆')).toHaveLength(2)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S4. onSelectIssue 콜백
// ─────────────────────────────────────────────────────────────────────────────

describe('GanttChart — S4 onSelectIssue 콜백', () => {
  it('S4a: 에픽 행 레이블 클릭 시 onSelectIssue("ATLAS-1")가 호출된다', () => {
    const onSelectIssue = vi.fn()
    renderChart({ onSelectIssue })

    fireEvent.click(screen.getByText('ATLAS-1'))
    expect(onSelectIssue).toHaveBeenCalledWith('ATLAS-1')
  })

  it('S4b: 자식 행 레이블 클릭 시 onSelectIssue("ATLAS-2")가 호출된다', () => {
    const onSelectIssue = vi.fn()
    renderChart({ onSelectIssue })

    fireEvent.click(screen.getByText('ATLAS-2'))
    expect(onSelectIssue).toHaveBeenCalledWith('ATLAS-2')
  })

  it('S4c: 미분류 행 레이블 클릭 시 onSelectIssue("ATLAS-3")가 호출된다', () => {
    const onSelectIssue = vi.fn()
    renderChart({ onSelectIssue })

    fireEvent.click(screen.getByText('ATLAS-3'))
    expect(onSelectIssue).toHaveBeenCalledWith('ATLAS-3')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S5. G2 — 토글 버튼이 onSelectIssue를 트리거하지 않음
// ─────────────────────────────────────────────────────────────────────────────

describe('GanttChart — S5 토글 버튼 onSelectIssue 차단 (G2)', () => {
  it('S5a: 토글 버튼 클릭이 onSelectIssue를 호출하지 않는다', () => {
    const onSelectIssue = vi.fn()
    renderChart({ onSelectIssue })

    const toggleBtn = screen.getByRole('button', { name: /접기/ })
    fireEvent.click(toggleBtn)

    expect(onSelectIssue).not.toHaveBeenCalled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S6. EC11 — 담당자 표시
// ─────────────────────────────────────────────────────────────────────────────

describe('GanttChart — S6 담당자 표시 (EC11)', () => {
  it('S6a: assigneeNames에 이슈 키가 있으면 displayName을 표시한다', () => {
    renderChart()
    expect(screen.getByText('김에픽')).toBeInTheDocument()
  })

  it('S6b: assigneeId=null이고 map에 없으면 "미배정"을 표시한다', () => {
    // childItem: assigneeId=null, ATLAS-2 map에 없음
    const assigneeNames = new Map<string, string>([
      ['ATLAS-1', '김에픽'],
      ['ATLAS-3', '이미분류'],
    ])
    renderChart({ assigneeNames })
    expect(screen.getByText('미배정')).toBeInTheDocument()
  })

  it('S6c: assigneeId가 있으나 map에 없으면 폴백 텍스트가 표시된다', () => {
    // empty map → epicItem.assigneeId='u-epic-01' → 알 수 없음
    const assigneeNames = new Map<string, string>()
    renderChart({ assigneeNames })
    const fallbacks = screen.getAllByText('알 수 없음')
    expect(fallbacks.length).toBeGreaterThan(0)
  })
})
