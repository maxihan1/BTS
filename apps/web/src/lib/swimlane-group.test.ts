// 스윔레인 그룹화 헬퍼 단위 테스트 — groupCardsBySwimlane 순수 함수
import { describe, it, expect } from 'vitest'
import type { BoardCard } from '@/api/boards'
import type { CardAssigneeDisplay } from '@/components/board/BoardCard'
import { groupCardsBySwimlane } from './swimlane-group'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const makeCard = (issueKey: string, priority: number, assigneeId: string | null = null): BoardCard => ({
  issueKey,
  summary: `이슈 ${issueKey}`,
  assigneeId,
  version: 1,
  priority,
})

const assigneeNames: Map<string, CardAssigneeDisplay> = new Map([
  ['ATLAS-1', { state: 'named', name: '박지현' }],
  ['ATLAS-2', { state: 'named', name: '김민준' }],
  ['ATLAS-3', { state: 'unassigned' }],
  ['ATLAS-4', { state: 'unknown' }],
])

// ─────────────────────────────────────────────────────────────────────────────
// S1. NONE — 단일 그룹, 라벨 없음
// ─────────────────────────────────────────────────────────────────────────────

describe('groupCardsBySwimlane — S1 NONE', () => {
  it('S1a: NONE이면 단일 그룹을 반환하고 라벨이 비어 있다', () => {
    const cards = [makeCard('ATLAS-1', 1), makeCard('ATLAS-2', 2)]
    const result = groupCardsBySwimlane(cards, 'NONE', assigneeNames)
    expect(result).toHaveLength(1)
    expect(result[0]?.label).toBe('')
    expect(result[0]?.cards).toHaveLength(2)
  })

  it('S1b: NONE에서 카드가 없으면 빈 배열을 반환한다', () => {
    const result = groupCardsBySwimlane([], 'NONE', assigneeNames)
    expect(result).toHaveLength(0)
  })

  it('S1c: NONE은 카드 순서를 유지한다', () => {
    const cards = [makeCard('ATLAS-2', 2), makeCard('ATLAS-1', 1)]
    const result = groupCardsBySwimlane(cards, 'NONE', assigneeNames)
    expect(result[0]?.cards.map((c) => c.issueKey)).toEqual(['ATLAS-2', 'ATLAS-1'])
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S2. ASSIGNEE — 담당자별 그룹
// ─────────────────────────────────────────────────────────────────────────────

describe('groupCardsBySwimlane — S2 ASSIGNEE', () => {
  it('S2a: 이름이 있는 담당자는 각자 별도 그룹으로 분리된다', () => {
    const cards = [
      makeCard('ATLAS-1', 1, 'u1'), // 박지현
      makeCard('ATLAS-2', 2, 'u2'), // 김민준
    ]
    const result = groupCardsBySwimlane(cards, 'ASSIGNEE', assigneeNames)
    // 이름순(가나다) 정렬: 김민준 < 박지현
    const labels = result.map((g) => g.label)
    expect(labels).toContain('김민준')
    expect(labels).toContain('박지현')
    const kimIdx = labels.indexOf('김민준')
    const parkIdx = labels.indexOf('박지현')
    expect(kimIdx).toBeLessThan(parkIdx)
  })

  it('S2b: 미배정(unassigned) 그룹은 마지막에 위치한다', () => {
    const cards = [
      makeCard('ATLAS-1', 1, 'u1'), // 박지현
      makeCard('ATLAS-3', 2, null), // unassigned
    ]
    const result = groupCardsBySwimlane(cards, 'ASSIGNEE', assigneeNames)
    const labels = result.map((g) => g.label)
    expect(labels[labels.length - 1]).toBe('미배정')
  })

  it('S2c: unknown 담당자(이름 미확인)는 "이름 미확인" 그룹으로 표시된다', () => {
    const cards = [makeCard('ATLAS-4', 1, 'u4')] // unknown
    const result = groupCardsBySwimlane(cards, 'ASSIGNEE', assigneeNames)
    const labels = result.map((g) => g.label)
    expect(labels).toContain('이름 미확인')
  })

  it('S2d: 빈 그룹은 결과에 포함되지 않는다', () => {
    const cards = [makeCard('ATLAS-1', 1, 'u1')] // 박지현만
    const result = groupCardsBySwimlane(cards, 'ASSIGNEE', assigneeNames)
    // 미배정 그룹이 없어야 함
    expect(result.find((g) => g.label === '미배정')).toBeUndefined()
  })

  it('S2e: 담당자가 모두 같으면 그룹 1개만 반환한다', () => {
    const cards = [
      makeCard('ATLAS-1', 1, 'u1'),
      makeCard('ATLAS-5', 2, 'u1'), // 같은 담당자, 이름 맵 없음
    ]
    const localNames: Map<string, CardAssigneeDisplay> = new Map([
      ['ATLAS-1', { state: 'named', name: '박지현' }],
      ['ATLAS-5', { state: 'named', name: '박지현' }],
    ])
    const result = groupCardsBySwimlane(cards, 'ASSIGNEE', localNames)
    expect(result).toHaveLength(1)
    expect(result[0]?.label).toBe('박지현')
    expect(result[0]?.cards).toHaveLength(2)
  })

  it('S2f: assigneeNames에 없는 카드는 unassigned fallback으로 미배정 그룹에 들어간다', () => {
    const cards = [makeCard('ATLAS-99', 1, null)] // 맵에 없음
    const result = groupCardsBySwimlane(cards, 'ASSIGNEE', new Map())
    expect(result[0]?.label).toBe('미배정')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S3. PRIORITY — 우선순위 오름차순 그룹
// ─────────────────────────────────────────────────────────────────────────────

describe('groupCardsBySwimlane — S3 PRIORITY', () => {
  it('S3a: 우선순위 숫자 오름차순으로 그룹이 배치된다(낮을수록 위)', () => {
    const cards = [
      makeCard('ATLAS-1', 3),
      makeCard('ATLAS-2', 1),
      makeCard('ATLAS-3', 2),
    ]
    const result = groupCardsBySwimlane(cards, 'PRIORITY', new Map())
    const keys = result.map((g) => g.key)
    expect(keys).toEqual(['priority-1', 'priority-2', 'priority-3'])
  })

  it('S3b: 우선순위 그룹 라벨은 "우선순위 N" 형식이다', () => {
    const cards = [makeCard('ATLAS-1', 2), makeCard('ATLAS-2', 1)]
    const result = groupCardsBySwimlane(cards, 'PRIORITY', new Map())
    const labels = result.map((g) => g.label)
    expect(labels).toContain('우선순위 1')
    expect(labels).toContain('우선순위 2')
  })

  it('S3c: 같은 우선순위 카드들은 같은 그룹에 묶인다', () => {
    const cards = [
      makeCard('ATLAS-1', 1),
      makeCard('ATLAS-2', 1),
      makeCard('ATLAS-3', 2),
    ]
    const result = groupCardsBySwimlane(cards, 'PRIORITY', new Map())
    expect(result).toHaveLength(2)
    expect(result[0]?.cards).toHaveLength(2)
    expect(result[1]?.cards).toHaveLength(1)
  })

  it('S3d: 카드가 없는 우선순위 그룹은 생성되지 않는다', () => {
    const cards = [makeCard('ATLAS-1', 1), makeCard('ATLAS-2', 3)]
    const result = groupCardsBySwimlane(cards, 'PRIORITY', new Map())
    // priority 2 그룹은 없어야 함
    expect(result.find((g) => g.label === '우선순위 2')).toBeUndefined()
    expect(result).toHaveLength(2)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// S4. 그룹 key 고유성
// ─────────────────────────────────────────────────────────────────────────────

describe('groupCardsBySwimlane — S4 그룹 key', () => {
  it('S4a: 반환된 모든 그룹의 key가 유일하다', () => {
    const cards = [
      makeCard('ATLAS-1', 1, 'u1'),
      makeCard('ATLAS-2', 2, null),
      makeCard('ATLAS-3', 3, 'u4'),
    ]
    const result = groupCardsBySwimlane(cards, 'ASSIGNEE', assigneeNames)
    const keys = result.map((g) => g.key)
    const unique = new Set(keys)
    expect(unique.size).toBe(keys.length)
  })
})
