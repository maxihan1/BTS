// 스프린트 완료 판정 순수 함수 단위 테스트 — 보드 맨 오른쪽 열 = 완료 (결정 D3 · 갭 JCB-5 · FR-UX-13 F15 FR-7)
import { describe, it, expect } from 'vitest'
import type { BoardColumn } from '@/api/boards'
import { buildDoneColumnMap, isIssueIncomplete } from './backlog-completion'

/** 판정은 `displayOrder` · `name` · `states[].key` 만 읽는다. 나머지는 스키마를 채우는 값이다 */
const column = (displayOrder: number, name: string, stateKeys: string[]): BoardColumn => ({
  columnId: `00000000-0000-4000-8000-00000000000${displayOrder}`,
  name,
  category: 'TODO',
  displayOrder,
  states: stateKeys.map((key) => ({ key, name: key, category: 'DONE' })),
  cards: [],
  wipLimit: null,
  wipExceeded: false,
})

const issueWithState = (currentStateKey: string) => ({ currentStateKey })

/** Jira 관찰 그대로 — Done 뒤에 검수 열을 하나 더 둔 보드 */
const REVIEW_LAST = [
  column(1, '할 일', ['open']),
  column(2, '진행 중', ['in_progress']),
  column(3, '완료', ['done']),
  column(4, '검수', ['verified', 'closed']),
]

describe('buildDoneColumnMap', () => {
  it('displayOrder 가 가장 큰 열의 상태 키만 완료로 모은다', () => {
    const map = buildDoneColumnMap(REVIEW_LAST)

    expect(map.unavailable).toBe(false)
    expect(map.lastColumnName).toBe('검수')
    expect([...map.doneStateKeys].sort()).toEqual(['closed', 'verified'])
  })

  it('응답 순서가 아니라 displayOrder 로 맨 오른쪽을 고른다', () => {
    const map = buildDoneColumnMap([...REVIEW_LAST].reverse())
    expect(map.lastColumnName).toBe('검수')
  })

  it('보드를 아직 못 받았으면 unavailable', () => {
    const map = buildDoneColumnMap(undefined)
    expect(map.unavailable).toBe(true)
    expect(map.doneStateKeys.size).toBe(0)
  })

  it('열이 하나도 없으면 완료 상태가 없다', () => {
    const map = buildDoneColumnMap([])
    expect(map.unavailable).toBe(false)
    expect(map.lastColumnName).toBeNull()
    expect(map.doneStateKeys.size).toBe(0)
  })
})

describe('isIssueIncomplete', () => {
  const map = buildDoneColumnMap(REVIEW_LAST)

  it('맨 오른쪽 열에 매핑된 상태면 완료', () => {
    expect(isIssueIncomplete(issueWithState('verified'), map)).toBe(false)
    expect(isIssueIncomplete(issueWithState('closed'), map)).toBe(false)
  })

  it('★ D3 — 상태 카테고리가 DONE 이어도 맨 오른쪽 열이 아니면 미완료', () => {
    expect(isIssueIncomplete(issueWithState('done'), map)).toBe(true)
  })

  it('어느 열에도 없는 상태는 미완료', () => {
    expect(isIssueIncomplete(issueWithState('unmapped'), map)).toBe(true)
  })

  it('보드를 모르면 맨 오른쪽 열 상태라도 미완료 (안전측)', () => {
    const unknown = { ...map, unavailable: true }
    expect(isIssueIncomplete(issueWithState('verified'), unknown)).toBe(true)
  })
})
