// 워크플로우 다이어그램 노드 좌표를 계산하는 순수 함수 (FR-WF-07 D8)
import type { StateCategory, TransitionKind } from '@/api/workflows-admin.types'

/**
 * 배치에 필요한 상태의 최소 모양. **초안 타입에 결합하지 않는다** —
 * `DraftState` 에 필드가 늘 때마다 이 모듈의 픽스처가 깨지지 않게 구조적으로 받는다.
 * 초안의 상태는 이 모양을 구조적으로 만족하므로 호출부는 그대로 넘기면 된다.
 */
export interface LayoutInputState {
  readonly key: string
  readonly category: StateCategory
  readonly displayOrder: number
  readonly layoutX: number | null
  readonly layoutY: number | null
}

/** 캔버스에 놓일 노드 한 개의 좌표. 캔버스 좌표계(px) 기준이다. */
export interface PlacedNode {
  key: string
  x: number
  y: number
}

/** 카테고리 → 열 번호. 왼쪽에서 오른쪽으로 진행 방향을 그린다. */
const CATEGORY_COLUMN: Record<StateCategory, number> = {
  TODO: 0,
  IN_PROGRESS: 1,
  DONE: 2,
}

/** 카테고리 열 사이 가로 간격(px). 노드 폭보다 넉넉해야 열 사이 간선 라벨이 노드를 덮지 않는다. */
const COLUMN_GAP_PX = 260

/** 같은 열 안 행 사이 세로 간격(px). */
const ROW_GAP_PX = 120

/**
 * 좌표가 이미 정해진 상태인지 판정한다.
 *
 * **둘 다** 채워졌을 때만 고정으로 본다. 한쪽만 있으면 노드를 놓을 수 없으므로 자동 배치 대상이다.
 */
function hasFixedPosition(
  state: LayoutInputState,
): state is LayoutInputState & { layoutX: number; layoutY: number } {
  return state.layoutX !== null && state.layoutY !== null
}

/**
 * 상태별 좌표를 계산해 상태 객체를 키로 하는 사상으로 돌려준다.
 *
 * 키를 상태 객체 자체로 잡는 이유. 상태 키 문자열이 중복돼도 결과가 서로를 덮지 않는다.
 */
function placeStates(states: readonly LayoutInputState[]): Map<LayoutInputState, PlacedNode> {
  // ★ 정렬 전에 복사한다. Array.prototype.sort 는 제자리 정렬이라 원본(초안)의 상태 순서를 뒤집는다.
  const byDisplayOrder = [...states].sort((a, b) => a.displayOrder - b.displayOrder)
  const placed = new Map<LayoutInputState, PlacedNode>()
  const filledRows = new Map<StateCategory, number>()

  for (const state of byDisplayOrder) {
    if (hasFixedPosition(state)) {
      placed.set(state, { key: state.key, x: state.layoutX, y: state.layoutY })
      continue
    }
    // 고정 좌표 노드는 행을 차지하지 않는다 — 자동 배치 노드끼리만 열 안에서 쌓인다
    const row = filledRows.get(state.category) ?? 0
    placed.set(state, {
      key: state.key,
      x: CATEGORY_COLUMN[state.category] * COLUMN_GAP_PX,
      y: row * ROW_GAP_PX,
    })
    filledRows.set(state.category, row + 1)
  }

  return placed
}

/**
 * 좌표가 없는 상태에 카테고리 열 기준 초기 좌표를 만들어 준다 (F4).
 *
 * - `layoutX`·`layoutY` 가 둘 다 있는 상태는 그 값을 그대로 통과시킨다.
 * - 나머지는 카테고리별 열(TODO → IN_PROGRESS → DONE)에 놓고, 같은 열 안에서는
 *   `displayOrder` 순으로 아래로 쌓는다.
 * - 결과 배열은 입력 순서를 유지한다.
 *
 * ★ **자동 배치는 표시일 뿐 편집이 아니다 — 이 결과를 초안에 써 넣지 마라.** 좌표를 초안 상태에
 * 되돌려 쓰면 초안이 열리자마자 dirty 가 되어 **화면을 열기만 해도 「저장 안 됨」이 뜬다.**
 * 초안의 `layoutX`/`layoutY` 를 바꾸는 것은 사용자가 노드를 실제로 끌어 옮겼을 때뿐이다.
 * 그래서 이 함수는 입력 배열도, 입력 상태 객체도 변형하지 않는다(`autoLayout` 판정 3번).
 *
 * @param states 배치할 상태 목록
 * @returns 입력과 같은 순서의 노드 좌표 배열
 */
export function autoLayout(states: readonly LayoutInputState[]): PlacedNode[] {
  const placed = placeStates(states)
  const result: PlacedNode[] = []

  for (const state of states) {
    const node = placed.get(state)
    if (node !== undefined) result.push(node)
  }

  return result
}

// ─────────────────────────────────────────────────────────────────────────────
// 간선(엣지) 경로
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 경로 계산에 필요한 전환의 최소 모양. `LayoutInputState` 와 같은 이유로 초안 타입에 결합하지 않는다.
 * 초안의 전환(`DraftTransition`)은 이 모양을 구조적으로 만족하므로 호출부는 그대로 넘기면 된다.
 */
export interface LayoutInputTransition {
  readonly from: string | null
  readonly to: string
  readonly name: string
  readonly kind: TransitionKind
}

/** 캔버스에 그릴 간선 한 개. */
export interface RoutedEdge {
  id: string
  /** 출발 노드 id — 상태 키이거나 `START_NODE_ID` 다. **절대 null 이 아니다.** */
  source: string
  target: string
  label: string
  /**
   * 입력 배열에서의 위치. 초안 전환에는 `id` 가 없어서(아직 발행되지 않아 DB 행이 아니다) 이것이
   * 화면과 초안을 잇는 유일한 identity 다 — 간선을 고르면 이 번호로 전환을 되찾는다.
   */
  transitionIndex: number
  selfLoop: boolean
  /** 곡선을 중심선에서 얼마나 벌릴지(px). 0 이면 직선이다. */
  offset: number
  /**
   * 라벨을 간선 중점에서 추가로 밀어낼 거리(px).
   *
   * **`offset` 과 다른 문제를 푼다.** `offset` 은 *같은 상태쌍* 간선이 서로 겹치는 것을 막고,
   * 이것은 *서로 다른 상태쌍* 간선의 중점이 한 자리에 모여 **라벨끼리** 겹치는 것을 막는다.
   * 겹칠 이웃이 없으면 `{ x: 0, y: 0 }` 이다 — 굽힐 이유가 없는 라벨은 중점에 그대로 둔다.
   */
  labelOffset: LabelOffset
}

/** 라벨을 중점에서 밀어낼 벡터(px). 캔버스 좌표계 기준이다. */
export interface LabelOffset {
  x: number
  y: number
}

/** 간선을 만들지 않고 「모든 상태에서」 패널에 나열할 전역 전환 (F11). */
export interface GlobalTransitionRoute {
  transitionIndex: number
  to: string
  name: string
}

/** `edgeRoutes` 의 결과. */
export interface EdgeRouteResult {
  edges: RoutedEdge[]
  globals: GlobalTransitionRoute[]
  /** 가상 시작 노드를 그릴지. 그것을 출발지로 쓰는 간선이 있을 때만 참이다. */
  hasStartNode: boolean
}

/**
 * 출발지 없는 전환이 출발하는 가상 시작 노드의 id. mermaid 화면의 `[*]` 와 같은 자리다
 * (`WorkflowDiagram.tsx` 의 `transitionSourceNode` — 두 화면의 시작 표기를 같게 맞춘다).
 *
 * 상태 키와 겹치지 않도록 실무에서 쓰지 않는 밑줄 이중 접두 모양으로 잡았다.
 */
export const START_NODE_ID = '__start__'

/** 같은 상태쌍에 몰린 간선을 벌리는 간격(px). 전환 이름 라벨이 서로 겹치지 않을 만큼은 돼야 한다. */
const EDGE_BUNDLE_GAP_PX = 40

/** self-loop 곡선의 기본 크기(px). 노드 밖으로 나올 만큼은 커야 한다. */
const SELF_LOOP_BASE_OFFSET_PX = 60

/**
 * 라벨 앵커를 같은 자리로 볼 격자 크기(px).
 *
 * 정확히 같은 좌표만 묶으면 1px 어긋난 겹침을 놓친다 — 라벨 상자는 수십 px 이라
 * 몇 px 차이는 눈에 겹쳐 보인다. 라벨 한 줄 높이 정도로 잡는다.
 */
const LABEL_CELL_PX = 24

/** 쌍 키 구분자. 상태 키에 섞일 수 없는 문자라야 `a\0b`·`ab\0` 같은 충돌이 안 난다. */
const PAIR_KEY_SEPARATOR = '\u0000'

/**
 * 전환의 출발 노드 id 를 고른다.
 *
 * ★ **여기서 `from` 을 그대로 문자열 보간하면 안 된다.** mermaid 화면이 `${from} --> ${to}` 로
 * 보간했다가 `from` 이 null 인 전환에서 **`null` 이라는 이름의 상태 노드**를 만들어 낸 실사고가
 * 있다(learnings.md:223). 그래서 null 을 여기서 한 번에 걸러 낸다.
 *
 * @param transition 전환
 * @returns 출발 노드 id. `GLOBAL` 은 간선을 만들지 않으므로 null 이다
 */
function sourceNodeId(transition: LayoutInputTransition): string | null {
  // GLOBAL 은 「모든 상태에서」다. 모든 노드에서 선을 뽑으면 화면을 읽을 수 없어 패널로 뺀다
  if (transition.kind === 'GLOBAL') return null
  // INITIAL 은 출발지가 없는 것이 정의다. NORMAL 인데 from 이 비어 있는 계약 위반 데이터도
  // 같은 자리로 떨어뜨린다 — 전환을 통째로 버리는 것보다 시작 노드에 매다는 쪽이 눈에 띈다
  return transition.from ?? START_NODE_ID
}

/** 출발·도착 쌍을 Map 키 하나로 접는다. */
function pairKey(source: string, target: string): string {
  return `${source}${PAIR_KEY_SEPARATOR}${target}`
}

/**
 * 상태쌍마다 간선이 몇 개인지 미리 센다. 벌림 폭이 총수에 달려 있어 한 번 훑고 시작해야 한다.
 *
 * @param transitions 전환 목록
 * @returns 쌍 키 → 간선 총수. 간선을 만들지 않는 `GLOBAL` 은 세지 않는다
 */
function countByPair(transitions: readonly LayoutInputTransition[]): Map<string, number> {
  const totals = new Map<string, number>()

  for (const transition of transitions) {
    const source = sourceNodeId(transition)
    if (source === null) continue
    const key = pairKey(source, transition.to)
    totals.set(key, (totals.get(key) ?? 0) + 1)
  }

  return totals
}

/**
 * 같은 상태쌍에 몰린 간선을 중심선 좌우로 대칭 분산한다 (F12 — FR-WF-05 의 다중 전환).
 *
 * 홑간선(`total === 1`)은 0 이다 — 굽힐 이유가 없다. 총수가 홀수면 가운데 하나가 0 을 받는다.
 *
 * @param index 그 쌍 안에서 몇 번째 간선인지 (0-based)
 * @param total 그 쌍의 간선 총수
 * @returns 중심선에서 벌릴 거리(px). 음수는 반대쪽이다
 */
function bundleOffset(index: number, total: number): number {
  return (index - (total - 1) / 2) * EDGE_BUNDLE_GAP_PX
}

/**
 * self-loop 곡선의 크기를 정한다 (F10 — J5).
 *
 * `bundleOffset` 과 달리 **0 이 나오면 안 된다.** self-loop 은 출발점과 도착점이 같은 자리라
 * 오프셋이 0 이면 노드 뒤에 완전히 숨어 전환이 있는지조차 보이지 않는다. 그래서 좌우 대칭이
 * 아니라 기본 반지름에서 바깥으로 겹겹이 키운다.
 *
 * @param index 그 노드의 몇 번째 self-loop 인지 (0-based)
 * @returns 곡선 크기(px). 항상 0 보다 크다
 */
function selfLoopOffset(index: number): number {
  return SELF_LOOP_BASE_OFFSET_PX + index * EDGE_BUNDLE_GAP_PX
}

/** 밀어낼 이웃이 없는 라벨의 오프셋. 매번 새 객체를 만들지 않는다. */
const NO_LABEL_OFFSET: LabelOffset = { x: 0, y: 0 }

/** 간선 하나를 만드는 데 필요한 자리 정보. */
interface EdgeSlot {
  readonly transition: LayoutInputTransition
  readonly transitionIndex: number
  readonly source: string
  /** 같은 상태쌍 안에서 몇 번째인지 (0-based) */
  readonly index: number
  /** 같은 상태쌍의 간선 총수 */
  readonly total: number
}

/**
 * 자리 정보를 캔버스 간선 하나로 옮긴다.
 *
 * @param slot 전환과 그 전환이 놓일 자리
 * @returns 간선. `id` 는 입력 위치를 달고 있어 같은 쌍의 전환이 여럿이어도 겹치지 않는다
 */
function routedEdge(slot: EdgeSlot): RoutedEdge {
  const { transition, transitionIndex, source, index, total } = slot
  const selfLoop = source === transition.to

  return {
    id: `${source}->${transition.to}#${transitionIndex}`,
    source,
    target: transition.to,
    label: transition.name,
    transitionIndex,
    selfLoop,
    offset: selfLoop ? selfLoopOffset(index) : bundleOffset(index, total),
    labelOffset: NO_LABEL_OFFSET,
  }
}

/**
 * 간선의 라벨이 놓일 자리를 노드 좌표만으로 근사한다.
 *
 * 노드 크기를 모르는 채로 **좌표만** 쓰는 것이 옳다 — 모든 노드가 같은 크기라 크기를 더하면
 * 세 중점에 같은 상수가 얹힐 뿐이고, 겹치는지 여부는 그대로다.
 *
 * @returns 앵커. 두 끝 중 하나라도 배치에 없으면(가상 시작 노드) null 이다
 */
function labelAnchor(
  edge: RoutedEdge,
  byKey: Map<string, PlacedNode>,
): { x: number; y: number } | null {
  const source = byKey.get(edge.source)
  const target = byKey.get(edge.target)
  if (source === undefined || target === undefined) return null

  return { x: (source.x + target.x) / 2, y: (source.y + target.y) / 2 }
}

/**
 * 라벨 앵커가 같은 칸에 떨어지는 간선끼리 라벨을 서로 밀어낸다 (부채 168).
 *
 * **`bundleOffset` 이 못 푸는 문제다.** 그쪽은 같은 상태쌍 안에서만 벌리는데, 카테고리 열 배치는
 * *서로 다른 상태쌍*의 중점을 한 점에 모은다 — `software-default` 에서 `open→closed` 의 중점과
 * `in_progress↔in_review` 의 중점이 `520 = 2 × 260` 때문에 대수적으로 같다.
 *
 * 미는 방향은 **각 간선의 법선**이다. 공통 축(가로나 세로)으로 밀면 방향이 반대인 두 간선이
 * 같은 쪽으로 가 다시 겹칠 수 있지만, 법선은 간선마다 다른 쪽을 가리킨다.
 *
 * self-loop 은 제외한다 — 그쪽 라벨 자리는 고리 경로가 정하고(부채 170), 중점이라는 개념이 없다.
 *
 * @param edges 간선 목록. **제자리에서 바꾸지 않고** 새 배열을 돌려준다
 * @param placed 배치된 노드 좌표
 */
function spreadLabels(edges: readonly RoutedEdge[], placed: readonly PlacedNode[]): RoutedEdge[] {
  const byKey = new Map(placed.map((node) => [node.key, node]))
  const cells = new Map<string, RoutedEdge[]>()

  for (const edge of edges) {
    if (edge.selfLoop) continue
    const anchor = labelAnchor(edge, byKey)
    if (anchor === null) continue
    const cell = `${Math.round(anchor.x / LABEL_CELL_PX)}${PAIR_KEY_SEPARATOR}${Math.round(anchor.y / LABEL_CELL_PX)}`
    const bucket = cells.get(cell)
    if (bucket === undefined) cells.set(cell, [edge])
    else bucket.push(edge)
  }

  const shifts = new Map<string, LabelOffset>()
  for (const bucket of cells.values()) {
    // 이웃이 없으면 밀 이유가 없다. 굽힐 이유 없는 라벨까지 밀면 어느 간선의 이름인지 흐려진다.
    if (bucket.length < 2) continue

    bucket.forEach((edge, index) => {
      const source = byKey.get(edge.source)
      const target = byKey.get(edge.target)
      if (source === undefined || target === undefined) return

      const dx = target.x - source.x
      const dy = target.y - source.y
      const length = Math.hypot(dx, dy) || 1
      const distance = bundleOffset(index, bucket.length)
      shifts.set(edge.id, {
        x: Math.round((-(dy / length)) * distance),
        y: Math.round((dx / length) * distance),
      })
    })
  }

  return edges.map((edge) => {
    const shift = shifts.get(edge.id)
    return shift === undefined ? edge : { ...edge, labelOffset: shift }
  })
}

/**
 * 전환 목록을 캔버스 간선 경로로 옮긴다 (F10 · F11 · F12).
 *
 * - `NORMAL`. 출발 상태 → 도착 상태. 같은 쌍이 여럿이면 서로 벌린다.
 * - `INITIAL`. 가상 시작 노드(`START_NODE_ID`) → 도착 상태. mermaid 의 `[*]` 와 같은 자리다.
 * - `GLOBAL`. **간선을 만들지 않는다.** 「모든 상태에서」 패널이 쓸 목록으로 따로 나간다.
 * - self-loop(출발 == 도착)은 노드에 가리지 않게 곡선 크기를 받는다.
 *
 * `autoLayout` 과 같이 **입력을 변형하지 않는다** — 전환 목록도, 전환 객체도 그대로다.
 *
 * @param transitions 초안의 전환 목록. 배열 순서가 곧 전환의 identity 다(`transitionIndex`)
 * @param placed 배치된 노드 좌표(`autoLayout` 의 결과). 라벨끼리 겹치는지 판정하는 데 쓴다 —
 *   좌표를 모르면 겹침을 알 수 없으므로 그때는 라벨을 밀지 않는다
 * @returns 간선 · 전역 전환 목록 · 시작 노드 표시 여부
 */
export function edgeRoutes(
  transitions: readonly LayoutInputTransition[],
  placed: readonly PlacedNode[],
): EdgeRouteResult {
  const totals = countByPair(transitions)
  const placedPerPair = new Map<string, number>()
  const edges: RoutedEdge[] = []
  const globals: GlobalTransitionRoute[] = []

  transitions.forEach((transition, transitionIndex) => {
    const source = sourceNodeId(transition)
    if (source === null) {
      globals.push({ transitionIndex, to: transition.to, name: transition.name })
      return
    }

    const key = pairKey(source, transition.to)
    const index = placedPerPair.get(key) ?? 0
    placedPerPair.set(key, index + 1)
    const total = totals.get(key) ?? 1
    edges.push(routedEdge({ transition, transitionIndex, source, index, total }))
  })

  // 시작 노드는 그것을 쓰는 간선이 있을 때만 그린다 — 늘 그리면 빈 원이 홀로 떠다닌다
  return {
    edges: spreadLabels(edges, placed),
    globals,
    hasStartNode: edges.some((edge) => edge.source === START_NODE_ID),
  }
}
