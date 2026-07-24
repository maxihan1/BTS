// 칸반 보드 드래그 종료 판정 순수 헬퍼 — 컬럼 이동/셀(컬럼×스윔레인) 내 순서변경 4분기 (FR-UX-06 PR21)
import { arrayMove } from '@dnd-kit/sortable'
import type { BoardDetail, BoardColumn, BoardCard, SwimlaneField } from '@/api/boards'
import type { CardAssigneeDisplay } from './BoardCard'
import { groupCardsBySwimlane } from '@/lib/swimlane-group'

// ─────────────────────────────────────────────────────────────────────────────
// 최소 이벤트 타입 — dnd-kit 이벤트 원형 대신 테스트 가능한 최소 인터페이스
// ─────────────────────────────────────────────────────────────────────────────

/** onDragEnd active 인자 최소 타입 — fromColumnId는 BoardCard useDraggable/useSortable data */
export interface DragActiveMin {
  id: string
  data: { current?: { fromColumnId?: string } }
}

/**
 * onDragEnd over 인자 최소 타입.
 * id는 컬럼 droppable(columnId) 또는 카드 sortable item(issueKey) 둘 다일 수 있다.
 */
export interface DragOverMin {
  id: string
}

/** resolveDropAction 반환 union 타입 */
export type DropAction =
  | { type: 'noop' }
  | {
      type: 'move'
      issueKey: string
      fromColumnId: string
      toColumnId: string
      expectedVersion: number
    }
  | {
      type: 'needs-resolution'
      issueKey: string
      fromColumnId: string
      toColumnId: string
      expectedVersion: number
    }
  | {
      type: 'reorder'
      issueKey: string
      columnId: string
      previousIssueKey?: string
      nextIssueKey?: string
      expectedVersion: number
    }

// ─────────────────────────────────────────────────────────────────────────────
// 셀(컬럼 × 스윔레인 그룹) 판정 내부 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** 스윔레인 그룹 판정에 필요한 컨텍스트 — swimlane-group.ts groupCardsBySwimlane 호출 인자 묶음 */
interface CellContext {
  /** 보드 스윔레인 기준 필드 (BoardDetail.swimlaneField) */
  swimlaneField: SwimlaneField
  /** 이슈 키 → 담당자 표시 상태 맵 (ASSIGNEE 그룹 판정용) */
  assigneeNames: Map<string, CardAssigneeDisplay>
}

/** 카드 이웃(previous/next issueKey) 결과 */
interface Neighbors {
  previousIssueKey?: string
  nextIssueKey?: string
}

/**
 * 컬럼 카드 목록 안에서 issueKey가 속한 스윔레인 그룹 key를 반환한다.
 * 셀 식별자 = columnId + 이 함수가 반환하는 groupKey.
 *
 * swimlaneField가 NONE이면 컬럼 전체가 단일 셀이므로 항상 'none'
 * (swimlane-group.ts groupByNone의 key와 동일 상수).
 *
 * @returns groupKey. issueKey를 어떤 그룹에서도 찾지 못하면 undefined
 */
function findGroupKey(columnCards: BoardCard[], ctx: CellContext, issueKey: string): string | undefined {
  if (ctx.swimlaneField === 'NONE') return 'none'
  const groups = groupCardsBySwimlane(columnCards, ctx.swimlaneField, ctx.assigneeNames)
  return groups.find((g) => g.cards.some((c) => c.issueKey === issueKey))?.key
}

/**
 * 컬럼 카드 목록에서 groupKey에 속한 카드만 rank 순서(원본 배열 순서) 그대로 추출한다.
 * 이 배열이 곧 "셀의 카드 배열" — reorder 이웃 계산의 기준이 된다.
 */
function cardsOfCell(columnCards: BoardCard[], ctx: CellContext, groupKey: string): BoardCard[] {
  if (ctx.swimlaneField === 'NONE') return columnCards
  const groups = groupCardsBySwimlane(columnCards, ctx.swimlaneField, ctx.assigneeNames)
  return groups.find((g) => g.key === groupKey)?.cards ?? []
}

/**
 * 셀 카드 배열(rank 순서)에서 issueKey의 이웃을 반환한다.
 * 맨 앞이면 previousIssueKey 없음, 맨 뒤면 nextIssueKey 없음.
 */
function neighborsOf(cell: BoardCard[], issueKey: string): Neighbors {
  const index = cell.findIndex((c) => c.issueKey === issueKey)
  if (index === -1) return {}
  return {
    previousIssueKey: index > 0 ? cell[index - 1]?.issueKey : undefined,
    nextIssueKey: index < cell.length - 1 ? cell[index + 1]?.issueKey : undefined,
  }
}

/**
 * 셀 카드 배열 안에서 active를 over 위치로 옮긴 뒤의 배열을 계산한다.
 *
 * - overIssueKey가 카드면 dnd-kit `arrayMove`와 동일한 방향성으로 삽입한다
 *   (active가 over보다 앞에 있었으면 over 뒤로, 뒤에 있었으면 over 앞으로 — 표준 sortable 동작).
 * - overIssueKey가 undefined면 컬럼 배경(빈 셀 등) 드롭으로 간주해 셀 맨 뒤로 옮긴다.
 *
 * rank 문자열은 계산하지 않는다 — 최종 rank는 이웃 issueKey 사이 값을 서버가 계산한다.
 */
function moveWithinCell(cell: BoardCard[], activeIssueKey: string, overIssueKey: string | undefined): BoardCard[] {
  const activeIndex = cell.findIndex((c) => c.issueKey === activeIssueKey)
  if (activeIndex === -1) return cell

  if (overIssueKey === undefined) {
    const moved = cell[activeIndex]
    if (moved === undefined) return cell
    return [...cell.filter((c) => c.issueKey !== activeIssueKey), moved]
  }

  const overIndex = cell.findIndex((c) => c.issueKey === overIssueKey)
  if (overIndex === -1 || overIndex === activeIndex) return cell
  return arrayMove(cell, activeIndex, overIndex)
}

/**
 * over.id를 board에서 해석해 대상 컬럼 + (카드 위 드롭이면) overIssueKey를 반환한다.
 *
 * - over.id가 컬럼 droppable id와 일치 → 그 컬럼의 배경 드롭(overIssueKey undefined)
 * - over.id가 어느 컬럼 카드의 issueKey와 일치 → 그 카드가 속한 컬럼 위 드롭(overIssueKey = over.id)
 * - 둘 다 아니면(알 수 없는 대상) undefined
 */
function resolveDropTarget(
  board: BoardDetail,
  overId: string,
): { toColumn: BoardColumn; overIssueKey: string | undefined } | undefined {
  const overColumn = board.columns.find((c) => c.columnId === overId)
  if (overColumn !== undefined) return { toColumn: overColumn, overIssueKey: undefined }

  const overOwnerColumn = board.columns.find((c) => c.cards.some((card) => card.issueKey === overId))
  if (overOwnerColumn !== undefined) return { toColumn: overOwnerColumn, overIssueKey: overId }

  return undefined
}

/**
 * 같은 컬럼 내 드롭을 셀(컬럼 × 스윔레인 그룹) 단위로 판정한다.
 *
 * - over가 active 자기 자신 → noop (제자리)
 * - active/over가 서로 다른 스윔레인 그룹(셀이 다름) → noop
 * - 같은 셀이지만 이동 후 이웃(previous/next)이 이전과 동일(위치 변화 없음) → noop
 * - 그 외 → reorder (이웃 issueKey 포함)
 */
function resolveSameColumnDrop(
  columnCards: BoardCard[],
  ctx: CellContext,
  activeIssueKey: string,
  overIssueKey: string | undefined,
  columnId: string,
  expectedVersion: number,
): DropAction {
  if (overIssueKey === activeIssueKey) return { type: 'noop' }

  const activeGroupKey = findGroupKey(columnCards, ctx, activeIssueKey)
  const overGroupKey = overIssueKey === undefined ? activeGroupKey : findGroupKey(columnCards, ctx, overIssueKey)

  if (activeGroupKey === undefined || overGroupKey === undefined || activeGroupKey !== overGroupKey) {
    return { type: 'noop' }
  }

  const cell = cardsOfCell(columnCards, ctx, activeGroupKey)
  const before = neighborsOf(cell, activeIssueKey)
  const after = moveWithinCell(cell, activeIssueKey, overIssueKey)
  const afterNeighbors = neighborsOf(after, activeIssueKey)

  if (afterNeighbors.previousIssueKey === before.previousIssueKey && afterNeighbors.nextIssueKey === before.nextIssueKey) {
    return { type: 'noop' }
  }

  return {
    type: 'reorder',
    issueKey: activeIssueKey,
    columnId,
    previousIssueKey: afterNeighbors.previousIssueKey,
    nextIssueKey: afterNeighbors.nextIssueKey,
    expectedVersion,
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// resolveDropAction — 공개 API
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 드래그 종료 이벤트를 분석해 수행할 동작을 결정하는 순수 헬퍼.
 *
 * - over가 null → noop
 * - active 카드를 board에서 찾을 수 없음(fromColumnId 누락 포함) → noop
 * - over가 카드/컬럼 어느 쪽으로도 board에서 찾을 수 없음 → noop
 * - 대상이 다른 컬럼 → move (대상 컬럼 category === 'DONE'이면 needs-resolution)
 * - 대상이 같은 컬럼·다른 스윔레인 그룹(셀) → noop
 * - 대상이 같은 셀이고 위치가 그대로(제자리) → noop
 * - 대상이 같은 셀이고 위치가 바뀜 → reorder (이웃 issueKey 포함, rank 계산은 서버 위임)
 *
 * @param board 현재 BoardDetail
 * @param active 드래그 중인 아이템 (최소 타입)
 * @param over 드롭 대상 (최소 타입 또는 null)
 * @param assigneeNames 이슈 키 → 담당자 표시 상태 맵 (ASSIGNEE 스윔레인 그룹 판정용). 생략 시 빈 맵.
 * @returns DropAction union
 */
export function resolveDropAction(
  board: BoardDetail,
  active: DragActiveMin,
  over: DragOverMin | null,
  assigneeNames: Map<string, CardAssigneeDisplay> = new Map(),
): DropAction {
  if (over === null) return { type: 'noop' }

  const fromColumnId = active.data.current?.fromColumnId
  if (fromColumnId === undefined) return { type: 'noop' }

  const issueKey = String(active.id)
  const fromColumn = board.columns.find((c) => c.columnId === fromColumnId)
  const card = fromColumn?.cards.find((c) => c.issueKey === issueKey)
  if (fromColumn === undefined || card === undefined) return { type: 'noop' }

  const target = resolveDropTarget(board, String(over.id))
  if (target === undefined) return { type: 'noop' }

  const { toColumn, overIssueKey } = target

  if (toColumn.columnId !== fromColumnId) {
    const base = {
      issueKey,
      fromColumnId,
      toColumnId: toColumn.columnId,
      expectedVersion: card.version,
    }
    return toColumn.category === 'DONE' ? { type: 'needs-resolution', ...base } : { type: 'move', ...base }
  }

  return resolveSameColumnDrop(
    fromColumn.cards,
    { swimlaneField: board.swimlaneField, assigneeNames },
    issueKey,
    overIssueKey,
    toColumn.columnId,
    card.version,
  )
}
