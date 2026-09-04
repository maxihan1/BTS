// 상태 매핑 드래그의 드롭 판정 — 순수 함수 (부채 177 R5 · J27 · X1)

import type { BoardColumn } from '@/api/boards'

/** 미매핑 패널의 드롭 대상 id. 컬럼 UUID 와 겹치지 않는 센티널이다. */
export const UNMAPPED_DROP_ID = 'board-settings-unmapped'

/**
 * 한 컬럼에 보낼 상태 집합 교체 요청 1건.
 *
 * `PUT /boards/{id}/columns/{columnId}/states` 한 번에 대응한다.
 */
export interface StateSetChange {
  /** 대상 컬럼 UUID */
  columnId: string
  /** 교체 후 그 컬럼이 담을 상태 키 **전량** */
  stateKeys: string[]
}

/**
 * 드롭 하나가 만들어 내는 서버 호출 계획.
 *
 * ### 왜 배열인가 — 컬럼 사이 이동은 두 번의 교체다
 * 한 상태는 **한 컬럼에만** 속할 수 있다(#444 X1 · DB 복합 FK 가 강제). 그래서 A→B 이동은
 * 「B 에 추가」 하나로 끝나지 않는다 — A 에 남아 있는 채로 B 에 넣으면 서버가 **409** 를 낸다.
 *
 * ★**순서가 계약이다.** 항상 **빼기가 먼저**다. 넣기를 먼저 하면 반드시 409 다.
 */
export interface DropPlan {
  /** 순서대로 보낼 교체 요청. 빈 배열이면 아무 일도 하지 않는다. */
  changes: StateSetChange[]
}

/** 아무 것도 하지 않는 계획. */
const NO_OP: DropPlan = { changes: [] }

/**
 * 상태 하나를 드롭했을 때 서버에 보낼 교체 계획을 만든다.
 *
 * @param stateKey 끌고 있던 워크플로우 상태 키.
 * @param fromColumnId 출발지. 미매핑 패널에서 끌었으면 `null`.
 * @param toDropId 도착지. 컬럼 UUID 또는 [UNMAPPED_DROP_ID].
 * @param columns 현재 보드의 컬럼 전량. 각 컬럼의 현재 상태 집합을 읽는다.
 * @returns 순서대로 실행할 교체 계획. 제자리 드롭·미지목 드롭은 빈 계획이다.
 */
export function planStateDrop(
  stateKey: string,
  fromColumnId: string | null,
  toDropId: string | null,
  columns: readonly BoardColumn[],
): DropPlan {
  // 드롭 대상이 없거나(허공) 제자리면 아무 일도 하지 않는다.
  if (toDropId === null) return NO_OP
  if (toDropId === fromColumnId) return NO_OP
  if (toDropId === UNMAPPED_DROP_ID && fromColumnId === null) return NO_OP

  const removal: StateSetChange[] =
    fromColumnId === null
      ? []
      : [{ columnId: fromColumnId, stateKeys: keysWithout(columns, fromColumnId, stateKey) }]

  if (toDropId === UNMAPPED_DROP_ID) return { changes: removal }

  const target = columns.find((c) => c.columnId === toDropId)
  // 모르는 대상 id 는 조용히 무시한다 — 던지면 드래그 하나가 화면을 통째로 죽인다.
  if (target === undefined) return NO_OP

  // ★빼기가 먼저다. 이 순서를 뒤집으면 X1 위반으로 서버가 409 를 낸다.
  return {
    changes: [
      ...removal,
      { columnId: target.columnId, stateKeys: [...target.states.map((s) => s.key), stateKey] },
    ],
  }
}

/**
 * 컬럼 하나를 다른 컬럼 자리로 옮긴 뒤의 **전체 순서**를 만든다 (R10 · J25).
 *
 * 서버가 부분 이동 명령을 받지 않으므로(`PUT …/columns/order` 는 전 컬럼을 요구한다) 여기서
 * 최종 배열을 만든다. 제자리·미지목·모르는 id 는 `null` 이고, 호출자는 아무 요청도 보내지 않는다.
 *
 * @param draggedColumnId 끌던 컬럼 UUID.
 * @param overColumnId 놓은 자리의 컬럼 UUID. 허공이면 `null`.
 * @param columns 현재 보드의 컬럼 전량(표시 순서).
 * @returns 새 순서의 컬럼 UUID 전량. 바뀌는 것이 없으면 `null`.
 */
export function planColumnReorder(
  draggedColumnId: string,
  overColumnId: string | null,
  columns: readonly { columnId: string }[],
): string[] | null {
  if (overColumnId === null || overColumnId === draggedColumnId) return null

  const ids = columns.map((c) => c.columnId)
  const from = ids.indexOf(draggedColumnId)
  const to = ids.indexOf(overColumnId)
  // 둘 중 하나라도 이 보드 것이 아니면 아무 일도 하지 않는다 — 던지면 드래그가 화면을 죽인다.
  if (from === -1 || to === -1) return null

  const next = [...ids]
  next.splice(from, 1)
  next.splice(to, 0, draggedColumnId)
  return next
}

/** 그 컬럼의 상태 집합에서 `stateKey` 를 뺀 목록. 컬럼이 없으면 빈 목록. */
function keysWithout(
  columns: readonly BoardColumn[],
  columnId: string,
  stateKey: string,
): string[] {
  const column = columns.find((c) => c.columnId === columnId)
  if (column === undefined) return []
  return column.states.map((s) => s.key).filter((k) => k !== stateKey)
}
