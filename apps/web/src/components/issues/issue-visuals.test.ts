// 이슈 목록 시각 매핑(상태 카테고리색·우선순위 아이콘색·타입색) 판별식
import { describe, it, expect } from 'vitest'
import { ChevronsUp, ChevronUp, Equal, ChevronDown, ChevronsDown } from 'lucide-react'
import { statusBadgeVariant, priorityVisual, issueTypeColorClass } from './issue-visuals'

describe('statusBadgeVariant — 상태 카테고리 → Badge 색 (Jira: To Do 회색 · In Progress 파랑 · Done 초록)', () => {
  it('T-1: TODO → neutral', () => {
    expect(statusBadgeVariant('TODO')).toBe('neutral')
  })

  it('T-2: IN_PROGRESS → blue', () => {
    expect(statusBadgeVariant('IN_PROGRESS')).toBe('blue')
  })

  it('T-3: DONE → green', () => {
    expect(statusBadgeVariant('DONE')).toBe('green')
  })

  it('T-4: 카테고리 미해석(undefined) → neutral 폴백 — 색을 지어내지 않는다', () => {
    expect(statusBadgeVariant(undefined)).toBe('neutral')
  })
})

describe('priorityVisual — 우선순위 → 아이콘+색', () => {
  /**
   * ★색 밴드는 3개인데 단계는 5개다. 겹화살표(Chevrons*)와 홑화살표(Chevron*)가
   * 같은 밴드 안의 두 단계를 **색 없이도** 가른다 — 색만으로 구분하면 색각 이상
   * 사용자에게 1↔2, 4↔5 가 같은 값으로 보인다.
   */
  it.each([
    [1, ChevronsUp, 'text-danger-text'],
    [2, ChevronUp, 'text-danger-text'],
    [3, Equal, 'text-warning-text'],
    [4, ChevronDown, 'text-info-text'],
    [5, ChevronsDown, 'text-info-text'],
  ])('T-5: priority=%s → 아이콘·색 매핑', (priority, Icon, colorClass) => {
    const visual = priorityVisual(priority as number)
    expect(visual.Icon).toBe(Icon)
    expect(visual.colorClass).toBe(colorClass)
  })

  it('T-6: 같은 색 밴드의 두 단계는 아이콘이 서로 다르다 (색 비의존 구분)', () => {
    expect(priorityVisual(1).Icon).not.toBe(priorityVisual(2).Icon)
    expect(priorityVisual(4).Icon).not.toBe(priorityVisual(5).Icon)
  })

  it('T-7: 정본 범위(1~5) 밖은 보통(3)의 시각으로 폴백한다', () => {
    expect(priorityVisual(0)).toEqual(priorityVisual(3))
    expect(priorityVisual(9)).toEqual(priorityVisual(3))
  })
})

describe('issueTypeColorClass — 이슈 타입 → --type-* 토큰 (TimelineRow 매핑 미러)', () => {
  it.each([
    ['epic', 'text-type-epic'],
    ['story', 'text-type-story'],
    ['task', 'text-type-task'],
    ['bug', 'text-type-bug'],
  ])('T-8: %s → %s', (typeKey, expected) => {
    expect(issueTypeColorClass(typeKey)).toBe(expected)
  })

  it('T-9: 매핑에 없는 타입(subtask 포함)은 중립 폴백', () => {
    expect(issueTypeColorClass('subtask')).toBe('text-type-default')
    expect(issueTypeColorClass('unknown-type')).toBe('text-type-default')
  })
})
