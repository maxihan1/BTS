// 워크플로우 다이어그램 자동 배치 순수 함수 단위 테스트 (FR-WF-07 D8)
import { describe, it, expect } from 'vitest'
import { autoLayout } from './workflow-layout'
import type { LayoutInputState, PlacedNode } from './workflow-layout'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

function makeState(overrides: Partial<LayoutInputState> & { key: string }): LayoutInputState {
  return {
    category: 'TODO',
    displayOrder: 0,
    layoutX: null,
    layoutY: null,
    ...overrides,
  }
}

/**
 * 배치 결과에서 키로 노드를 집는다.
 *
 * `noUncheckedIndexedAccess` 아래에서 인덱스 접근은 `undefined` 를 달고 오는데,
 * 그것을 `?.` 로 흘려보내면 노드가 통째로 빠져도 판정이 조용히 통과한다.
 */
function nodeOf(placed: PlacedNode[], key: string): PlacedNode {
  const found = placed.find((node) => node.key === key)
  if (found === undefined) throw new Error(`배치 결과에 '${key}' 가 없다`)
  return found
}

// ─────────────────────────────────────────────────────────────────────────────
// autoLayout
// ─────────────────────────────────────────────────────────────────────────────

describe('autoLayout', () => {
  it('좌표가 없는 상태를 카테고리 열로 배치한다', () => {
    // 입력 순서와 displayOrder 를 일부러 어긋나게 둔다 — 행 누적 기준이 displayOrder 임을 잰다
    const placed = autoLayout([
      makeState({ key: 'selected', category: 'TODO', displayOrder: 1 }),
      makeState({ key: 'backlog', category: 'TODO', displayOrder: 0 }),
      makeState({ key: 'doing', category: 'IN_PROGRESS', displayOrder: 2 }),
      makeState({ key: 'done', category: 'DONE', displayOrder: 3 }),
    ])

    // 결과는 입력 순서를 그대로 유지한다 — 호출부가 상태 목록과 나란히 쓴다
    expect(placed.map((node) => node.key)).toEqual(['selected', 'backlog', 'doing', 'done'])

    // TODO < IN_PROGRESS < DONE 순으로 x 가 커진다
    expect(nodeOf(placed, 'backlog').x).toBeLessThan(nodeOf(placed, 'doing').x)
    expect(nodeOf(placed, 'doing').x).toBeLessThan(nodeOf(placed, 'done').x)

    // 같은 카테고리는 같은 열이다
    expect(nodeOf(placed, 'selected').x).toBe(nodeOf(placed, 'backlog').x)

    // 같은 열 안에서는 displayOrder 순으로 y 가 누적된다 (입력 순서가 아니다)
    expect(nodeOf(placed, 'backlog').y).toBeLessThan(nodeOf(placed, 'selected').y)

    // 열마다 첫 행은 같은 y 에서 시작한다
    expect(nodeOf(placed, 'doing').y).toBe(nodeOf(placed, 'backlog').y)
    expect(nodeOf(placed, 'done').y).toBe(nodeOf(placed, 'backlog').y)
  })

  it('이미 좌표가 있는 상태는 그대로 둔다', () => {
    const placed = autoLayout([
      // 손으로 옮긴 노드. 음수·소수 좌표도 그대로 통과해야 한다
      makeState({ key: 'moved', category: 'TODO', displayOrder: 0, layoutX: 512.5, layoutY: -48 }),
      makeState({ key: 'fresh', category: 'TODO', displayOrder: 1 }),
      makeState({ key: 'doing', category: 'IN_PROGRESS', displayOrder: 2 }),
    ])

    expect(nodeOf(placed, 'moved')).toEqual({ key: 'moved', x: 512.5, y: -48 })

    // 고정 좌표는 자동 열의 행을 차지하지 않는다 — 남은 자동 노드가 첫 행에서 시작한다
    expect(nodeOf(placed, 'fresh').y).toBe(nodeOf(placed, 'doing').y)
  })

  it('자동 배치 결과를 받아도 초안은 pristine 이다', () => {
    const draftStates: LayoutInputState[] = [
      makeState({ key: 'done', category: 'DONE', displayOrder: 2 }),
      makeState({ key: 'todo', category: 'TODO', displayOrder: 0 }),
      makeState({ key: 'doing', category: 'IN_PROGRESS', displayOrder: 1 }),
    ]

    autoLayout(draftStates)

    // ★ 자동 배치는 표시일 뿐 편집이 아니다. 좌표가 초안에 되돌아 실리면
    //   화면을 열기만 해도 「저장 안 됨」이 뜨는 회귀가 된다.
    expect(draftStates[0]?.layoutX).toBeNull()
    expect(draftStates.every((state) => state.layoutX === null && state.layoutY === null)).toBe(true)

    // 입력 배열 자체도 건드리지 않는다 — 제자리 정렬은 초안의 상태 순서를 뒤집는다
    expect(draftStates.map((state) => state.key)).toEqual(['done', 'todo', 'doing'])
  })
})
