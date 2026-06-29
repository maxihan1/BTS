// GanttChart 컴포넌트 단위 테스트 — 그룹 렌더·접기/펼치기·마일스톤·콜백·담당자 표시·deps 오버레이·zoomLevel 배선 (FR-TL-01 D6, FR-TL-02 D6, FR-TL-03)
import { describe, it, expect, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import type { TimelineItem } from '@/api/timeline'
import type { DependencyEdge } from '@/lib/timeline-layout'
import {
  DAY_WIDTH_PX,
  daysBetweenUtc,
  parseIsoDateUtc,
  assembleEpicGroups,
  flattenVisibleRows,
} from '@/lib/timeline-layout'
import type { ZoomLevel } from '@/lib/timeline-zoom'
import { ROW_HEIGHT_PX } from './TimelineRow'
import { TIMELINE_AXIS_HEIGHT_PX } from './TimelineAxis'
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
  deps?: DependencyEdge[]
  zoomLevel?: ZoomLevel
}) {
  return render(
    <GanttChart
      items={overrides?.items ?? defaultItems}
      assigneeNames={overrides?.assigneeNames ?? defaultAssigneeNames}
      onSelectIssue={overrides?.onSelectIssue ?? vi.fn()}
      deps={overrides?.deps}
      zoomLevel={overrides?.zoomLevel}
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

// ─────────────────────────────────────────────────────────────────────────────
// S7. deps 오버레이 통합 (FR-TL-02 D6)
// ─────────────────────────────────────────────────────────────────────────────

describe('GanttChart — S7 deps 오버레이 (FR-TL-02 D6)', () => {
  /**
   * S7a. positive control: deps 주입 → DependencyOverlay 라인 path가 렌더된다.
   * vacuous 차단: negative control(S7b)과 공존.
   */
  it('S7a: deps 주입 시 DependencyOverlay 라인 path가 DOM에 존재한다 (positive control)', () => {
    // ATLAS-1 blocks ATLAS-2 — 두 행 모두 visible
    renderChart({
      deps: [{ blockerKey: 'ATLAS-1', blockedKey: 'ATLAS-2' }],
    })
    // DependencyOverlay가 렌더한 visible path(aria-label 있음)가 DOM에 있어야 한다
    expect(
      document.querySelector('path[aria-label="ATLAS-1가 ATLAS-2을 차단"]'),
    ).toBeInTheDocument()
  })

  /**
   * S7b. negative control: 에픽 그룹 접기 → 접힌 자식으로의 라인이 제외된다.
   * flattenVisibleRows 단일 출처 검증 (S4/EC1).
   */
  it('S7b: 에픽 그룹 접기 시 접힌 자식으로의 라인이 제외된다 (negative — flattenVisibleRows 단일 출처)', () => {
    // ATLAS-3 → ATLAS-2 (ATLAS-2는 ATLAS-1 에픽 그룹의 자식)
    renderChart({
      deps: [{ blockerKey: 'ATLAS-3', blockedKey: 'ATLAS-2' }],
    })

    // 접기 전: ATLAS-2 visible → 라인 존재
    expect(
      document.querySelector('path[aria-label="ATLAS-3가 ATLAS-2을 차단"]'),
    ).toBeInTheDocument()

    // 에픽 그룹(ATLAS-1) 접기 → ATLAS-2 비가시
    const toggleBtn = screen.getByRole('button', { name: /접기/ })
    fireEvent.click(toggleBtn)

    // 접힌 후: 라인 제외 (flattenVisibleRows 단일 출처 검증)
    expect(
      document.querySelector('path[aria-label="ATLAS-3가 ATLAS-2을 차단"]'),
    ).not.toBeInTheDocument()
  })

  /** S7c. deps 미주입 → 라인 0개 (무회귀) */
  it('S7c: deps 미주입 → 라인 0개 (무회귀)', () => {
    renderChart()
    expect(document.querySelector('path[aria-label*="을 차단"]')).not.toBeInTheDocument()
  })

  /** S7d. deps 빈 배열 → 라인 0개 (무회귀) */
  it('S7d: deps 빈 배열 → 라인 0개 (무회귀)', () => {
    renderChart({ deps: [] })
    expect(document.querySelector('path[aria-label*="을 차단"]')).not.toBeInTheDocument()
  })

  /**
   * S7e. deps 주입 시 막대 클릭이 여전히 onSelectIssue를 호출한다.
   * jsdom은 pointer-events CSS를 강제하지 않으므로 단위 테스트에서 통과.
   * 실 브라우저 동작은 E2E(T6) qa-engineer가 검증.
   */
  it('S7e: deps 주입 시 막대 클릭이 여전히 onSelectIssue를 호출한다', () => {
    const onSelectIssue = vi.fn()
    renderChart({
      onSelectIssue,
      deps: [{ blockerKey: 'ATLAS-1', blockedKey: 'ATLAS-2' }],
    })
    // epicItem 막대 버튼 — TimelineRow가 우측 영역에 렌더 (aria-label: "KEY start ~ due")
    const barBtn = screen.getByRole('button', { name: 'ATLAS-1 2026-07-01 ~ 2026-07-31' })
    fireEvent.click(barBtn)
    expect(onSelectIssue).toHaveBeenCalledWith('ATLAS-1')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// C-1. axisOffset 단일출처 회귀 보장 (TIMELINE_AXIS_HEIGHT_PX)
// ─────────────────────────────────────────────────────────────────────────────

describe('GanttChart — C-1 axisOffset 단일출처 (TIMELINE_AXIS_HEIGHT_PX)', () => {
  /**
   * DependencyOverlay에 전달하는 axisOffset이 TimelineAxis 실제 높이와 동일해야 한다.
   *
   * 검증 방법:
   * - ATLAS-1(rowIndex=0) blocks ATLAS-2 의존 라인 path `d` 속성의 SVG y 좌표를 파싱.
   * - SVG y = startY(막대 영역 로컬) + axisOffset.
   * - ATLAS-1 rowIndex=0 → startY(bar-relative) = ROW_HEIGHT_PX/2.
   * - 따라서 SVG startY = ROW_HEIGHT_PX/2 + TIMELINE_AXIS_HEIGHT_PX 이어야 한다.
   *
   * GanttChart가 TIMELINE_AXIS_HEIGHT_PX 대신 다른 상수를 axisOffset으로 전달하면
   * 이 계산이 어긋나 테스트가 실패한다 — 양쪽 체인 사이 drift를 빌드 타임에 감지.
   */
  it('C-1: path d의 SVG y 좌표가 ROW_HEIGHT_PX/2 + TIMELINE_AXIS_HEIGHT_PX와 일치한다', () => {
    renderChart({ deps: [{ blockerKey: 'ATLAS-1', blockedKey: 'ATLAS-2' }] })

    const path = document.querySelector('path[aria-label="ATLAS-1가 ATLAS-2을 차단"]')
    expect(path).toBeInTheDocument()
    const d = path?.getAttribute('d') ?? ''

    // ATLAS-1: rowIndex=0, blocker startY(bar-relative) = 0*ROW_HEIGHT_PX + ROW_HEIGHT_PX/2 = 16
    // ATLAS-2: rowIndex=1, blocked endY(bar-relative) = 1*ROW_HEIGHT_PX + ROW_HEIGHT_PX/2 = 48
    // SVG y = bar-relative y + axisOffset (axisOffset 반드시 === TIMELINE_AXIS_HEIGHT_PX)
    const startYInSvg = ROW_HEIGHT_PX / 2 + TIMELINE_AXIS_HEIGHT_PX       // 16 + 48 = 64
    const endYInSvg = ROW_HEIGHT_PX + ROW_HEIGHT_PX / 2 + TIMELINE_AXIS_HEIGHT_PX  // 48 + 48 = 96
    // path d format: "M startX,{startYInSvg} H midX V {endYInSvg} H endX"
    expect(d).toContain(`,${startYInSvg} `)
    expect(d).toContain(`V ${endYInSvg} `)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// C-3. DOM 막대 행↔flattenVisibleRows 정합 회귀 (완전 재작성 금지)
// ─────────────────────────────────────────────────────────────────────────────

describe('GanttChart — C-3 DOM 막대 행↔flattenVisibleRows 정합', () => {
  /**
   * 막대 버튼(role="button", aria-label "KEY start ~ due")에서 이슈 키를 순서대로 추출한다.
   * 토글 버튼(접기/펼치기)는 "~"를 포함하지 않으므로 필터로 제외한다.
   */
  function getBarKeys(): string[] {
    return screen
      .getAllByRole('button')
      .filter((btn) => /~/.test(btn.getAttribute('aria-label') ?? ''))
      .map((btn) => (btn.getAttribute('aria-label') ?? '').split(' ')[0] ?? '')
      .filter((k) => k.length > 0)
  }

  /**
   * C-3a: 펼친 상태에서 DOM 막대 행 순서가 flattenVisibleRows와 일치한다.
   *
   * GanttChart 우측 렌더 루프와 flattenVisibleRows 가 평행 구현이므로
   * 향후 한쪽만 수정하면 이 테스트가 실패해 drift를 감지한다.
   */
  it('C-3a: 펼친 상태 DOM 막대 행 이슈 키 순서가 flattenVisibleRows와 일치한다', () => {
    renderChart()
    const domKeys = getBarKeys()
    const groups = assembleEpicGroups(defaultItems)
    const expectedKeys = flattenVisibleRows(groups, new Set()).map((r) => r.key)
    expect(domKeys).toEqual(expectedKeys)
  })

  /**
   * C-3b: 에픽 그룹 접기 후에도 DOM 막대 행 순서가 flattenVisibleRows(collapsed)와 일치한다.
   * collapsed 케이스 포함 — 접기/펼치기 상태 반영 여부 검증.
   */
  it('C-3b: 에픽 그룹 접기 후 DOM 막대 행 순서가 flattenVisibleRows(collapsed)와 일치한다', () => {
    renderChart()
    const collapseBtn = screen.getByRole('button', { name: /접기/ })
    fireEvent.click(collapseBtn)  // ATLAS-1 그룹 접기

    const domKeys = getBarKeys()
    const groups = assembleEpicGroups(defaultItems)
    // collapsedGroups = {'ATLAS-1'}
    const expectedKeys = flattenVisibleRows(groups, new Set(['ATLAS-1'])).map((r) => r.key)
    expect(domKeys).toEqual(expectedKeys)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// C-2. overlayWidth +1일 클리핑 보정 (CONCERN-2 fix)
// ─────────────────────────────────────────────────────────────────────────────

describe('GanttChart — C-2 overlayWidth +1일 클리핑 보정', () => {
  /**
   * C-2: overlayWidth가 최우측 막대 우끝을 커버해야 한다.
   *
   * computeBarGeometry의 barWidth = (due-start+1)일 × DAY_WIDTH_PX (당일 포함 +1).
   * overlayWidth가 (days)×DAY_WIDTH_PX에 머물면 range 마지막 날로 끝나는 막대의
   * 우끝(+1일분)이 SVG overflow:hidden에 의해 잘린다.
   * overlayWidth = (days+1)×DAY_WIDTH_PX 로 보정해야 한다.
   *
   * RED: 현재 overlayWidth = 30×20 = 600 < ATLAS-1 barRight 31×20 = 620 → 실패.
   * GREEN: +1 보정 후 overlayWidth = 620 ≥ 620 → 통과.
   */
  it('C-2: overlayWidth이 최우측 막대 우끝(+1일 보정)을 커버한다', () => {
    renderChart({ deps: [{ blockerKey: 'ATLAS-1', blockedKey: 'ATLAS-2' }] })

    const svg = document.querySelector('svg')
    expect(svg).toBeInTheDocument()
    const svgWidth = Number(svg?.getAttribute('width'))

    // defaultItems 날짜 범위: 2026-07-01 ~ 2026-07-31 = 30일
    // ATLAS-1: barX=0, barWidth=(30+1)×DAY_WIDTH_PX=620, barRight=620
    // overlayWidth 은 (totalDays+1)×DAY_WIDTH_PX 이상이어야 한다
    const rangeStartMs = parseIsoDateUtc('2026-07-01')
    const rangeEndMs = parseIsoDateUtc('2026-07-31')
    const totalDays = daysBetweenUtc(rangeStartMs, rangeEndMs)  // 30
    const rightmostBarRight = (totalDays + 1) * DAY_WIDTH_PX    // 620
    expect(svgWidth).toBeGreaterThanOrEqual(rightmostBarRight)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// C5. 의존선 줌 정합 회귀 가드 (FR-TL-03 C5)
// ─────────────────────────────────────────────────────────────────────────────

describe('GanttChart — C5 의존선 줌 정합 회귀 가드', () => {
  /**
   * 줌 레벨 변경 시 의존선 X좌표가 새 dayWidth에 비례하는지 수치로 단언한다.
   *
   * 구조적으로는 안전(GanttChart가 단일 dayWidth 공유)하지만,
   * 향후 GanttChart에서 dayWidth 전달 경로 한쪽이 누락되면 이 테스트가 실패한다.
   *
   * 검증 방법:
   * - ATLAS-1 blocks ATLAS-2 의존 라인의 path `d` 속성에서 endX를 파싱.
   *   path format: "M startX,startY H midX V endY H endX"
   * - endX = ATLAS-2 barX = daysBetweenUtc(rangeStart, ATLAS-2.startDate) × dayWidth
   *   = 4일 × dayWidth (ATLAS-2.startDate='2026-07-05', range='2026-07-01')
   * - month(dayWidth=20): endX = 4 × 20 = 80
   * - quarter(dayWidth=6):  endX = 4 × 6  = 24
   * - 24 < 80 → vacuous 방지: 실제 수치로 quarter < month 단언.
   */
  it('C5: quarter 줌의 의존선 endX가 month 줌보다 작다 (dayWidth 비례 정합)', () => {
    const deps: DependencyEdge[] = [{ blockerKey: 'ATLAS-1', blockedKey: 'ATLAS-2' }]

    /** path `d` 속성에서 마지막 H 명령의 X값을 추출한다. */
    function parseEndX(d: string): number {
      const match = /H (\d+(?:\.\d+)?)$/.exec(d)
      return match?.[1] !== undefined ? parseFloat(match[1]) : -1
    }

    // month 줌 렌더 → endX 추출
    const { unmount } = renderChart({ zoomLevel: 'month', deps })
    const monthPath = document.querySelector('path[aria-label="ATLAS-1가 ATLAS-2을 차단"]')
    expect(monthPath).toBeInTheDocument()
    const monthEndX = parseEndX(monthPath?.getAttribute('d') ?? '')
    unmount()

    // quarter 줌 렌더 → endX 추출
    renderChart({ zoomLevel: 'quarter', deps })
    const quarterPath = document.querySelector('path[aria-label="ATLAS-1가 ATLAS-2을 차단"]')
    expect(quarterPath).toBeInTheDocument()
    const quarterEndX = parseEndX(quarterPath?.getAttribute('d') ?? '')

    // 두 값 모두 유효해야 한다 (parse 실패 시 -1 → 여기서 잡힘)
    expect(monthEndX).toBeGreaterThan(0)
    expect(quarterEndX).toBeGreaterThan(0)
    // quarter는 dayWidth가 작으므로 endX도 작아야 한다
    expect(quarterEndX).toBeLessThan(monthEndX)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S8. Task 6 — zoomLevel prop 배선 (FR-TL-03)
// ─────────────────────────────────────────────────────────────────────────────

describe('GanttChart — S8 zoomLevel prop 배선 (FR-TL-03)', () => {
  /**
   * S8a. quarter 줌 시 TimelineAxis 상단에 분기 눈금 Q 텍스트가 렌더된다.
   *
   * RED 실패 예상: GanttChart가 zoomLevel을 무시 → TimelineAxis 기본 month 줌 사용
   * → top='month' 눈금만("2026.07") → Q 텍스트 없음.
   *
   * 검증 근거: defaultItems 날짜 범위 2026-07-01 ~ 2026-07-31.
   * July = UTC month index 6, 6 % 3 === 0 → computeQuarterTicks가 "2026 Q3" 추가.
   */
  it('S8a: zoomLevel="quarter" 시 TimelineAxis 상단에 분기 Q 눈금이 렌더된다', () => {
    renderChart({ zoomLevel: 'quarter' })
    expect(screen.getByText(/Q\d/)).toBeInTheDocument()
  })

  /**
   * S8b. week 줌 시 TimelineAxis 하단에 일(DD) 눈금이 렌더된다.
   *
   * RED 실패 예상: GanttChart가 zoomLevel을 무시 → TimelineAxis 기본 month 줌 사용
   * → bottom='week' 눈금(MM/DD 형식)만 존재 → 단독 "01" 텍스트 없음.
   *
   * 검증 근거: week 줌 → bottom='day' → computeDayTicks → "01"(2026-07-01 첫날).
   */
  it('S8b: zoomLevel="week" 시 TimelineAxis 하단에 일(DD) 눈금이 렌더된다', () => {
    renderChart({ zoomLevel: 'week' })
    // week: bottom='day' → "01" 눈금(2026-07-01)
    expect(screen.queryAllByText('01')).not.toHaveLength(0)
  })

  /**
   * S8c. zoomLevel 변경 시 overlayWidth(SVG width)이 dayWidth에 비례한다.
   *
   * RED 실패 예상: DAY_WIDTH_PX=20 하드코딩 → quarter/month 모두 width 동일(620).
   *
   * 검증 근거: quarter.dayWidth=6, month.dayWidth=20 → (30+1)*6=186 < (30+1)*20=620.
   */
  it('S8c: zoomLevel="quarter" 시 overlayWidth이 month 줌보다 좁다', () => {
    const deps: DependencyEdge[] = [{ blockerKey: 'ATLAS-1', blockedKey: 'ATLAS-2' }]

    const { unmount } = renderChart({ zoomLevel: 'quarter', deps })
    const quarterWidth = Number(document.querySelector('svg')?.getAttribute('width') ?? '0')
    unmount()

    renderChart({ zoomLevel: 'month', deps })
    const monthWidth = Number(document.querySelector('svg')?.getAttribute('width') ?? '0')

    expect(quarterWidth).toBeGreaterThan(0)
    expect(quarterWidth).toBeLessThan(monthWidth)
  })

  /**
   * S8d. zoomLevel 미지정(기본 month) 시 주(MM/DD) 눈금이 렌더된다.
   *
   * 기존 동작 무회귀 검증 — GREEN 전후 모두 통과해야 한다.
   * 2026-07-06이 월요일이므로 computeWeekTicks가 "07/06" 생성.
   */
  it('S8d: zoomLevel 미지정(기본 month) 시 주 눈금 07/06이 렌더된다 (무회귀)', () => {
    renderChart()
    expect(screen.getByText('07/06')).toBeInTheDocument()
  })
})
