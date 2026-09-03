// 워크플로우 다이어그램 자동 배치 순수 함수 단위 테스트 (FR-WF-07 D8)
import { describe, it, expect } from 'vitest'
import { autoLayout, edgeRoutes, START_NODE_ID } from './workflow-layout'
import type {
  EdgeRouteResult,
  LayoutInputState,
  LayoutInputTransition,
  PlacedNode,
  RoutedEdge,
} from './workflow-layout'

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
    expect(draftStates.every((state) => state.layoutX === null && state.layoutY === null)).toBe(
      true,
    )

    // 입력 배열 자체도 건드리지 않는다 — 제자리 정렬은 초안의 상태 순서를 뒤집는다
    expect(draftStates.map((state) => state.key)).toEqual(['done', 'todo', 'doing'])
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// edgeRoutes
// ─────────────────────────────────────────────────────────────────────────────

function makeTransition(
  overrides: Partial<LayoutInputTransition> & { to: string },
): LayoutInputTransition {
  return {
    from: null,
    name: '전환',
    kind: 'NORMAL',
    ...overrides,
  }
}

/**
 * 경로 계산 결과에서 입력 위치로 간선을 집는다.
 *
 * `nodeOf` 와 같은 이유로 `?.` 를 쓰지 않는다 — 간선이 통째로 빠져도 판정이 조용히 통과한다.
 */
function edgeAt(result: EdgeRouteResult, transitionIndex: number): RoutedEdge {
  const found = result.edges.find((edge) => edge.transitionIndex === transitionIndex)
  if (found === undefined) throw new Error(`경로 결과에 입력 ${transitionIndex} 번 간선이 없다`)
  return found
}

/**
 * 좌표를 빼고 경로만 재는 호출.
 *
 * `offset` · `selfLoop` · 시작 노드 판정은 노드가 어디 놓였는지와 무관하다. 배치를 빈 배열로
 * 주면 라벨 겹침 해소만 쉬고(겹치는지 알 수 없으니 밀지 않는다) 나머지 계산은 그대로다.
 * 좌표가 실제로 필요한 판정은 「라벨 겹침 해소」 뿐이라 그쪽만 `autoLayout` 결과를 넘긴다.
 */
function routesOf(transitions: readonly LayoutInputTransition[]): EdgeRouteResult {
  return edgeRoutes(transitions, [])
}

describe('edgeRoutes', () => {
  it('self-loop 은 곡선 offset 을 받는다', () => {
    const result = routesOf([
      makeTransition({ from: 'doing', to: 'doing', name: '재작업' }),
      makeTransition({ from: 'doing', to: 'doing', name: '담당자 변경' }),
      makeTransition({ from: 'todo', to: 'doing', name: '시작' }),
    ])

    // 직선(offset 0)이면 노드에 완전히 가려 사용자가 self-loop 이 있는지조차 모른다
    expect(edgeAt(result, 0).selfLoop).toBe(true)
    expect(edgeAt(result, 0).offset).not.toBe(0)
    expect(edgeAt(result, 1).offset).not.toBe(0)

    // self-loop 이 여럿이면 서로 다른 크기로 겹겹이 벌어진다
    expect(edgeAt(result, 0).offset).not.toBe(edgeAt(result, 1).offset)

    // 굽힐 이유가 없는 홑간선까지 휘게 만들지는 않는다
    expect(edgeAt(result, 2).selfLoop).toBe(false)
    expect(edgeAt(result, 2).offset).toBe(0)
  })

  it('self-loop 곡선이 아무리 늘어도 이웃 행까지 뻗지 않는다 (부채 170)', () => {
    // 옛 기본값 60 은 지름 120 으로 행 간격과 정확히 같아, 고리가 아래 행 노드에 걸쳤다.
    const result = routesOf([
      makeTransition({ from: 'doing', to: 'doing', name: '재작업' }),
      makeTransition({ from: 'doing', to: 'doing', name: '담당자 변경' }),
      makeTransition({ from: 'doing', to: 'doing', name: '반려' }),
      makeTransition({ from: 'doing', to: 'doing', name: '보류' }),
      makeTransition({ from: 'doing', to: 'doing', name: '재개' }),
    ])

    for (const index of [0, 1, 2, 3, 4]) {
      const { offset } = edgeAt(result, index)
      // 노드 높이(min-h-11 = 44px)가 상한이다. 그보다 크면 이웃 행을 삼킨다
      expect(offset).toBeGreaterThan(0)
      expect(offset).toBeLessThanOrEqual(44)
    }
  })

  it('같은 상태쌍에 전환이 여럿이면 서로 다른 offset 을 받는다', () => {
    // FR-WF-05 가 같은 쌍의 다중 전환을 허용한다 — 겹쳐 그리면 어느 것을 고르는지 알 수 없다
    const result = routesOf([
      makeTransition({ from: 'todo', to: 'doing', name: '시작' }),
      makeTransition({ from: 'todo', to: 'doing', name: '급행 시작' }),
      makeTransition({ from: 'todo', to: 'doing', name: '이관 시작' }),
      makeTransition({ from: 'doing', to: 'done', name: '완료' }),
    ])

    const offsets = [0, 1, 2].map((index) => edgeAt(result, index).offset)
    expect(new Set(offsets).size).toBe(3)

    // 벌림은 쌍 단위다 — 쌍이 하나뿐인 전환은 그대로 직선이다
    expect(edgeAt(result, 3).offset).toBe(0)
  })

  it('INITIAL 전환은 시작 노드에서 출발한다', () => {
    const result = routesOf([
      makeTransition({ from: null, to: 'todo', name: '생성', kind: 'INITIAL' }),
    ])

    // mermaid 의 [*] 에 대응하는 가상 시작 노드. 출발지가 없다는 것이 INITIAL 의 정의다
    expect(edgeAt(result, 0).source).toBe(START_NODE_ID)
    expect(edgeAt(result, 0).target).toBe('todo')
    expect(result.hasStartNode).toBe(true)

    // INITIAL 이 없으면 시작 노드를 그리지 않는다 — 늘 참이면 빈 원이 떠다닌다
    const withoutInitial = routesOf([makeTransition({ from: 'todo', to: 'doing', name: '시작' })])
    expect(withoutInitial.hasStartNode).toBe(false)
  })

  it('GLOBAL 전환은 간선을 만들지 않는다', () => {
    const result = routesOf([
      makeTransition({ from: null, to: 'done', name: '강제 종료', kind: 'GLOBAL' }),
      makeTransition({ from: 'todo', to: 'doing', name: '시작' }),
    ])

    // 모든 노드에서 선을 뽑으면 화면을 읽을 수 없다. 대신 「모든 상태에서」 패널 목록으로 돌려준다
    expect(result.edges).toHaveLength(1)
    expect(edgeAt(result, 1).source).toBe('todo')
    expect(result.globals).toEqual([{ transitionIndex: 0, to: 'done', name: '강제 종료' }])

    // GLOBAL 은 시작 노드도 부르지 않는다 — 시작 진입과 전역 전환은 다른 것이다
    expect(result.hasStartNode).toBe(false)
  })

  it('from 이 null 이어도 이름이 null 인 노드를 만들지 않는다', () => {
    const result = routesOf([
      makeTransition({ from: null, to: 'todo', name: '생성', kind: 'INITIAL' }),
      makeTransition({ from: null, to: 'done', name: '강제 종료', kind: 'GLOBAL' }),
      // 스키마상 NORMAL 의 from 도 nullable 이다 — 계약 위반 데이터가 들어와도 노드를 만들지 않는다
      makeTransition({ from: null, to: 'doing', name: '유입 불명', kind: 'NORMAL' }),
    ])

    // ★ learnings.md:223 실측 사고. mermaid 에서 `${from} --> ${to}` 보간이 'null' 이라는 이름의
    //   상태 노드를 만들어 노드 수 단언이 어긋났다. 같은 함정이 xyflow 식별자에도 있다.
    const identifiers = result.edges.flatMap((edge) => [edge.id, edge.source, edge.target])
    expect(identifiers.length).toBeGreaterThan(0)
    expect(identifiers.filter((id) => id.includes('null'))).toEqual([])

    // 출발지 없는 전환이 통째로 사라져서도 안 된다 — 하나는 패널로, 둘은 시작 노드에서 간선으로
    expect(result.edges).toHaveLength(2)
    expect(result.globals).toHaveLength(1)
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 라벨 겹침 (부채 168)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * `software-default` 픽스처와 같은 형태의 상태 5개.
 *
 * 카테고리 열 배치가 `open(0,0)` · `in_progress(260,0)` · `in_review(260,120)` ·
 * `done(520,0)` · `closed(520,120)` 을 만든다. **`520 = 2 × 260` 이라** 아래 세 전환의
 * 중점이 대수적으로 같은 한 점이 된다 — 우연이 아니라 이 배치의 항등이다.
 */
function softwareDefaultStates(): LayoutInputState[] {
  return [
    makeState({ key: 'open', category: 'TODO', displayOrder: 1 }),
    makeState({ key: 'in_progress', category: 'IN_PROGRESS', displayOrder: 2 }),
    makeState({ key: 'in_review', category: 'IN_PROGRESS', displayOrder: 3 }),
    makeState({ key: 'done', category: 'DONE', displayOrder: 4 }),
    makeState({ key: 'closed', category: 'DONE', displayOrder: 5 }),
  ]
}

describe('라벨 겹침 해소', () => {
  it('중점이 한 점에 모이는 간선들의 라벨이 서로 다른 방향으로 밀린다', () => {
    // FR-WF-07 D8 눈확인 ②에서 `Requ|Cancel|nges` 로 뭉개져 나온 그 세 전환이다.
    // 간선 자체는 X 자로 벌어진다(눈확인 ④ 통과) — 겹치는 것은 라벨뿐이다.
    const placed = autoLayout(softwareDefaultStates())
    const result = edgeRoutes(
      [
        makeTransition({ from: 'in_progress', to: 'in_review', name: 'Submit for Review' }),
        makeTransition({ from: 'in_review', to: 'in_progress', name: 'Request Changes' }),
        makeTransition({ from: 'open', to: 'closed', name: 'Cancel' }),
      ],
      placed,
    )

    const shifted = [0, 1, 2].map((index) => {
      const { x, y } = edgeAt(result, index).labelOffset
      return `${x},${y}`
    })

    // 세 간선의 중점이 같은 점이므로, 오프셋이 서로 달라야만 라벨 셋이 따로 읽힌다
    expect(new Set(shifted).size).toBe(3)
  })

  it('중점이 겹치지 않는 라벨은 밀지 않는다', () => {
    // 굽힐 이유가 없는 라벨까지 밀면 선과 이름이 멀어져 어느 간선의 이름인지 알 수 없어진다
    const placed = autoLayout(softwareDefaultStates())
    const result = edgeRoutes(
      [
        makeTransition({ from: 'open', to: 'in_progress', name: 'Start Work' }),
        makeTransition({ from: 'in_review', to: 'done', name: 'Approve' }),
      ],
      placed,
    )

    expect(edgeAt(result, 0).labelOffset).toEqual({ x: 0, y: 0 })
    expect(edgeAt(result, 1).labelOffset).toEqual({ x: 0, y: 0 })
  })
})
