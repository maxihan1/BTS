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

function findGroupKey(
  columnCards: BoardCard[],
  swimlaneField: SwimlaneField,
  assigneeNames: Map<string, CardAssigneeDisplay>,
  issueKey: string,
): string | undefined {
  if (swimlaneField === 'NONE') return 'none'
  const groups = groupCardsBySwimlane(columnCards, swimlaneField, assigneeNames)
  return groups.find((g) => g.cards.some((c) => c.issueKey === issueKey))?.key
}

function cardsOfCell(
  columnCards: BoardCard[],
  swimlaneField: SwimlaneField,
  assigneeNames: Map<string, CardAssigneeDisplay>,
  groupKey: string,
): BoardCard[] {
  if (swimlaneField === 'NONE') return columnCards
  const groups = groupCardsBySwimlane(columnCards, swimlaneField, assigneeNames)
  return groups.find((g) => g.key === groupKey)?.cards ?? []
}

function neighborsOf(cell: BoardCard[], issueKey: string): { previousIssueKey?: string; nextIssueKey?: string } {
  const index = cell.findIndex((c) => c.issueKey === issueKey)
  if (index === -1) return {}
  return {
    previousIssueKey: index > 0 ? cell[index - 1]?.issueKey : undefined,
    nextIssueKey: index < cell.length - 1 ? cell[index + 1]?.issueKey : undefined,
  }
}

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

/** over.id를 컬럼(background) 또는 카드로 해석해 대상 컬럼 + overIssueKey를 반환 */
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

/** 같은 컬럼 내 드롭 — 셀(스윔레인 그룹) 판정 후 noop/reorder 결정 */
function resolveSameColumnDrop(
  columnCards: BoardCard[],
  swimlaneField: SwimlaneField,
  assigneeNames: Map<string, CardAssigneeDisplay>,
  activeIssueKey: string,
  overIssueKey: string | undefined,
  columnId: string,
  expectedVersion: number,
): DropAction {
  if (overIssueKey === activeIssueKey) return { type: 'noop' }

  const activeGroupKey = findGroupKey(columnCards, swimlaneField, assigneeNames, activeIssueKey)
  const overGroupKey =
    overIssueKey === undefined ? activeGroupKey : findGroupKey(columnCards, swimlaneField, assigneeNames, overIssueKey)

  if (activeGroupKey === undefined || overGroupKey === undefined || activeGroupKey !== overGroupKey) {
    return { type: 'noop' }
  }

  const cell = cardsOfCell(columnCards, swimlaneField, assigneeNames, activeGroupKey)
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
    board.swimlaneField,
    assigneeNames,
    issueKey,
    overIssueKey,
    toColumn.columnId,
    card.version,
  )
}
