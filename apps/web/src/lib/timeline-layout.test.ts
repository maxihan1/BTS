// 타임라인 레이아웃 순수 함수 단위 테스트 (FR-TL-01 D6)
import { describe, it, expect } from 'vitest'
import type { TimelineItem } from '@/api/timeline'
import {
  computeDateRange,
  computeBarGeometry,
  assembleEpicGroups,
} from './timeline-layout'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function makeItem(overrides: Partial<TimelineItem> & { key: string }): TimelineItem {
  return {
    summary: `이슈 ${overrides.key}`,
    issueType: 'task',
    currentStateKey: 'TODO',
    assigneeId: null,
    startDate: null,
    dueDate: null,
    targetDate: null,
    epicKey: null,
    ...overrides,
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// computeDateRange
// ─────────────────────────────────────────────────────────────────────────────

describe('computeDateRange', () => {
  it('빈 배열이면 null을 반환한다 (EC4)', () => {
    expect(computeDateRange([])).toBeNull()
  })

  it('모든 날짜가 null이면 null을 반환한다', () => {
    const items = [makeItem({ key: 'BTS-1' }), makeItem({ key: 'BTS-2' })]
    expect(computeDateRange(items)).toBeNull()
  })

  it('startDate·dueDate에서 min·max를 계산한다', () => {
    const items = [
      makeItem({ key: 'BTS-1', startDate: '2026-07-01', dueDate: '2026-07-10' }),
      makeItem({ key: 'BTS-2', startDate: '2026-06-20', dueDate: '2026-07-05' }),
    ]
    const range = computeDateRange(items)
    expect(range).not.toBeNull()
    expect(range!.startMs).toBe(Date.UTC(2026, 5, 20))  // 2026-06-20
    expect(range!.endMs).toBe(Date.UTC(2026, 6, 10))    // 2026-07-10
  })

  it('targetDate가 dueDate보다 늦으면 range에 포함된다 (EC3)', () => {
    const items = [
      makeItem({ key: 'BTS-1', startDate: '2026-07-01', dueDate: '2026-07-10', targetDate: '2026-07-31' }),
    ]
    const range = computeDateRange(items)
    expect(range!.endMs).toBe(Date.UTC(2026, 6, 31))  // 2026-07-31
  })

  it('targetDate가 startDate보다 이르면 range에 포함된다 (EC3 — targetDate < rangeStart)', () => {
    const items = [
      makeItem({ key: 'BTS-1', startDate: '2026-07-10', dueDate: '2026-07-20', targetDate: '2026-07-01' }),
    ]
    const range = computeDateRange(items)
    expect(range!.startMs).toBe(Date.UTC(2026, 6, 1))  // 2026-07-01
  })

  it('rangeStart===rangeEnd일 때 최소 1일 폭을 보장한다 (EC8)', () => {
    const items = [
      makeItem({ key: 'BTS-1', startDate: '2026-07-15', dueDate: '2026-07-15' }),
    ]
    const range = computeDateRange(items)
    expect(range!.startMs).toBe(Date.UTC(2026, 6, 15))
    expect(range!.endMs).toBe(Date.UTC(2026, 6, 16))  // +1일
  })

  it('startDate만 있는 아이템도 range 계산에 포함한다', () => {
    const items = [
      makeItem({ key: 'BTS-1', startDate: '2026-06-01' }),
      makeItem({ key: 'BTS-2', dueDate: '2026-08-31' }),
    ]
    const range = computeDateRange(items)
    expect(range!.startMs).toBe(Date.UTC(2026, 5, 1))   // 2026-06-01
    expect(range!.endMs).toBe(Date.UTC(2026, 7, 31))    // 2026-08-31
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// computeBarGeometry
// ─────────────────────────────────────────────────────────────────────────────

describe('computeBarGeometry', () => {
  const DAY_WIDTH = 20

  // rangeStart = 2026-07-01
  const range = {
    startMs: Date.UTC(2026, 6, 1),
    endMs: Date.UTC(2026, 6, 31),
  }

  it('startDate+dueDate 둘 다 있으면 정상 폭 막대를 반환한다', () => {
    const item = makeItem({ key: 'BTS-1', startDate: '2026-07-05', dueDate: '2026-07-09' })
    const geo = computeBarGeometry(item, range, DAY_WIDTH)
    // offset: 4일 * 20px = 80
    expect(geo.barX).toBe(4 * DAY_WIDTH)
    // 폭: (9-5+1)일 = 5일 * 20px = 100
    expect(geo.barWidth).toBe(5 * DAY_WIDTH)
    expect(geo.milestoneX).toBeNull()
    expect(geo.openStart).toBe(false)
    expect(geo.openEnd).toBe(false)
  })

  it('startDate만 있으면 최소폭 + openEnd (EC1)', () => {
    const item = makeItem({ key: 'BTS-1', startDate: '2026-07-03' })
    const geo = computeBarGeometry(item, range, DAY_WIDTH)
    expect(geo.barX).toBe(2 * DAY_WIDTH)
    expect(geo.barWidth).toBeGreaterThanOrEqual(DAY_WIDTH)  // 최소 1일
    expect(geo.openEnd).toBe(true)
    expect(geo.openStart).toBe(false)
  })

  it('dueDate만 있으면 최소폭 + openStart (EC1)', () => {
    const item = makeItem({ key: 'BTS-1', dueDate: '2026-07-10' })
    const geo = computeBarGeometry(item, range, DAY_WIDTH)
    expect(geo.barX).toBe(9 * DAY_WIDTH)
    expect(geo.barWidth).toBeGreaterThanOrEqual(DAY_WIDTH)
    expect(geo.openStart).toBe(true)
    expect(geo.openEnd).toBe(false)
  })

  it('startDate > dueDate이면 음수폭 클램프 — 최소폭(EC2)', () => {
    const item = makeItem({ key: 'BTS-1', startDate: '2026-07-10', dueDate: '2026-07-05' })
    const geo = computeBarGeometry(item, range, DAY_WIDTH)
    expect(geo.barWidth).toBeGreaterThanOrEqual(DAY_WIDTH)
    expect(geo.barX).toBeGreaterThanOrEqual(0)
  })

  it('targetDate가 있으면 milestoneX를 반환한다 (FR4)', () => {
    const item = makeItem({ key: 'BTS-1', startDate: '2026-07-01', dueDate: '2026-07-10', targetDate: '2026-07-20' })
    const geo = computeBarGeometry(item, range, DAY_WIDTH)
    // milestoneX: (20 - 1) 일 offset = 19 * 20 = 380
    expect(geo.milestoneX).toBe(19 * DAY_WIDTH)
  })

  it('targetDate가 없으면 milestoneX는 null', () => {
    const item = makeItem({ key: 'BTS-1', startDate: '2026-07-01', dueDate: '2026-07-10' })
    const geo = computeBarGeometry(item, range, DAY_WIDTH)
    expect(geo.milestoneX).toBeNull()
  })

  it('날짜가 모두 null이면 barX=0, barWidth=최소폭, open 표식 없음', () => {
    const item = makeItem({ key: 'BTS-1' })
    const geo = computeBarGeometry(item, range, DAY_WIDTH)
    expect(geo.barWidth).toBeGreaterThanOrEqual(DAY_WIDTH)
    expect(geo.openStart).toBe(false)
    expect(geo.openEnd).toBe(false)
  })

  it('UTC 기준 계산 — 로컬 자정 시프트 없음 (NFR4)', () => {
    // 2026-07-20은 UTC 기준 정확히 19일 offset (range start = 2026-07-01)
    const item = makeItem({ key: 'BTS-1', startDate: '2026-07-20', dueDate: '2026-07-20' })
    const geo = computeBarGeometry(item, range, DAY_WIDTH)
    expect(geo.barX).toBe(19 * DAY_WIDTH)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// assembleEpicGroups
// ─────────────────────────────────────────────────────────────────────────────

describe('assembleEpicGroups', () => {
  it('빈 배열이면 빈 그룹 배열을 반환한다', () => {
    expect(assembleEpicGroups([])).toHaveLength(0)
  })

  it('Epic은 그룹 헤더 + 자신의 아이템으로 포함된다', () => {
    const items = [
      makeItem({ key: 'ATLAS-1', issueType: 'epic', epicKey: null }),
      makeItem({ key: 'ATLAS-2', issueType: 'task', epicKey: 'ATLAS-1' }),
    ]
    const groups = assembleEpicGroups(items)
    expect(groups).toHaveLength(1)
    expect(groups[0]?.epicItem?.key).toBe('ATLAS-1')
    expect(groups[0]?.items.map((i) => i.key)).toContain('ATLAS-1')
    expect(groups[0]?.items.map((i) => i.key)).toContain('ATLAS-2')
  })

  it('여러 Epic이 있으면 각각 별도 그룹이다', () => {
    const items = [
      makeItem({ key: 'ATLAS-1', issueType: 'epic', epicKey: null }),
      makeItem({ key: 'ATLAS-2', issueType: 'epic', epicKey: null }),
      makeItem({ key: 'ATLAS-3', issueType: 'task', epicKey: 'ATLAS-1' }),
      makeItem({ key: 'ATLAS-4', issueType: 'story', epicKey: 'ATLAS-2' }),
    ]
    const groups = assembleEpicGroups(items)
    // 미분류 없음 → 2그룹
    const epicGroups = groups.filter((g) => g.epicItem !== null)
    expect(epicGroups).toHaveLength(2)
  })

  it('epicKey가 목록에 없는 Epic과 매칭 실패하면 미분류 그룹에 넣는다 (EC6/EC7)', () => {
    const items = [
      makeItem({ key: 'ATLAS-2', issueType: 'task', epicKey: 'ATLAS-GONE' }),
    ]
    const groups = assembleEpicGroups(items)
    expect(groups).toHaveLength(1)
    expect(groups[0]?.epicItem).toBeNull()
    expect(groups[0]?.items.map((i) => i.key)).toContain('ATLAS-2')
  })

  it('epicKey===null && issueType!==epic이면 미분류 그룹으로 간다 (EC6)', () => {
    const items = [
      makeItem({ key: 'ATLAS-2', issueType: 'task', epicKey: null }),
    ]
    const groups = assembleEpicGroups(items)
    expect(groups).toHaveLength(1)
    expect(groups[0]?.epicItem).toBeNull()
  })

  it('미분류 그룹은 항상 맨 끝이다', () => {
    const items = [
      makeItem({ key: 'ATLAS-1', issueType: 'epic', epicKey: null }),
      makeItem({ key: 'ATLAS-2', issueType: 'task', epicKey: 'ATLAS-1' }),
      makeItem({ key: 'ATLAS-3', issueType: 'task', epicKey: null }),  // 미분류
    ]
    const groups = assembleEpicGroups(items)
    expect(groups[groups.length - 1]?.epicItem).toBeNull()
  })

  it('그룹 내 아이템 순서는 입력(백엔드 정렬) 순서를 유지한다', () => {
    const items = [
      makeItem({ key: 'ATLAS-1', issueType: 'epic', epicKey: null }),
      makeItem({ key: 'ATLAS-3', issueType: 'task', epicKey: 'ATLAS-1' }),
      makeItem({ key: 'ATLAS-2', issueType: 'task', epicKey: 'ATLAS-1' }),
    ]
    const groups = assembleEpicGroups(items)
    const epicGroup = groups[0]
    // Epic 자신이 첫 번째, 이후 자식은 입력 순서 유지
    expect(epicGroup?.items[0]?.key).toBe('ATLAS-1')
    expect(epicGroup?.items[1]?.key).toBe('ATLAS-3')
    expect(epicGroup?.items[2]?.key).toBe('ATLAS-2')
  })

  it('날짜 없는 Epic이 목록에서 빠졌을 때 자식들은 미분류로 간다 (EC7)', () => {
    // Epic ATLAS-10이 날짜 없어 목록에 없음 → ATLAS-5는 epicKey=ATLAS-10이지만 매칭 실패
    const items = [
      makeItem({ key: 'ATLAS-5', issueType: 'task', epicKey: 'ATLAS-10' }),
    ]
    const groups = assembleEpicGroups(items)
    expect(groups[0]?.epicItem).toBeNull()
    expect(groups[0]?.items[0]?.key).toBe('ATLAS-5')
  })

  it('Epic만 있고 자식이 없어도 Epic 그룹이 생긴다', () => {
    const items = [
      makeItem({ key: 'ATLAS-1', issueType: 'epic', epicKey: null }),
    ]
    const groups = assembleEpicGroups(items)
    expect(groups).toHaveLength(1)
    expect(groups[0]?.epicItem?.key).toBe('ATLAS-1')
    expect(groups[0]?.items).toHaveLength(1)  // Epic 자신만
  })
})
