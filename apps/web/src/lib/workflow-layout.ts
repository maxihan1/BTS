// 워크플로우 다이어그램 노드 좌표를 계산하는 순수 함수 (FR-WF-07 D8)
import type { StateCategory } from '@/api/workflows-admin.types'

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
    placed.set(state, { key: state.key, x: CATEGORY_COLUMN[state.category] * 260, y: row * 120 })
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
