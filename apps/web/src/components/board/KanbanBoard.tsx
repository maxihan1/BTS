// 칸반 보드 루트 컴포넌트 — DndContext + 컬럼 배치 + 드래그 이동 오케스트레이션 (FR-BD-01)
import type { JSX } from 'react'
import { useState } from 'react'
import {
  DndContext,
  DragOverlay,
  PointerSensor,
  KeyboardSensor,
  useSensor,
  useSensors,
} from '@dnd-kit/core'
import type { DragEndEvent, DragStartEvent, DragOverEvent } from '@dnd-kit/core'
import { toast } from 'sonner'
import type { BoardDetail } from '@/api/boards'
import { useMoveCard } from '@/hooks/use-move-card'
import type { MoveCardVars } from '@/hooks/use-move-card'
import { BoardColumn } from './BoardColumn'
import { BoardCard } from './BoardCard'
import type { CardAssigneeDisplay } from './BoardCard'
import { ResolutionPickerModal } from './ResolutionPickerModal'

// ─────────────────────────────────────────────────────────────────────────────
// resolveDropAction — 순수 헬퍼 (테스트 가능하도록 export)
// ─────────────────────────────────────────────────────────────────────────────

/** onDragEnd active 인자 최소 타입 — fromColumnId는 BoardCard useDraggable data */
export interface DragActiveMin {
  id: string
  data: { current?: { fromColumnId?: string } }
}

/** onDragEnd over 인자 최소 타입 */
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

/**
 * 드래그 종료 이벤트를 분석해 수행할 동작을 결정하는 순수 헬퍼.
 *
 * - over가 null → noop
 * - 같은 컬럼 → noop (EC1)
 * - 대상 컬럼을 board에서 찾을 수 없음 → noop
 * - 카드(issueKey)를 fromColumn에서 찾을 수 없음 → noop
 * - 대상 컬럼 category === 'DONE' → needs-resolution
 * - 그 외 → move
 *
 * @param board 현재 BoardDetail
 * @param active 드래그 중인 아이템 (최소 타입)
 * @param over 드롭 대상 (최소 타입 또는 null)
 * @returns DropAction union
 */
// eslint-disable-next-line react-refresh/only-export-components
export function resolveDropAction(
  board: BoardDetail,
  active: DragActiveMin,
  over: DragOverMin | null,
): DropAction {
  if (over === null) return { type: 'noop' }

  const fromColumnId = active.data.current?.fromColumnId
  if (fromColumnId === undefined) return { type: 'noop' }

  const toColumnId = String(over.id)

  // 같은 컬럼 drop → EC1
  if (fromColumnId === toColumnId) return { type: 'noop' }

  // 대상 컬럼 탐색
  const toColumn = board.columns.find((c) => c.columnId === toColumnId)
  if (toColumn === undefined) return { type: 'noop' }

  // 이동할 카드 탐색 — expectedVersion 확보
  const issueKey = String(active.id)
  const fromColumn = board.columns.find((c) => c.columnId === fromColumnId)
  const card = fromColumn?.cards.find((c) => c.issueKey === issueKey)
  if (card === undefined) return { type: 'noop' }

  const base = {
    issueKey,
    fromColumnId,
    toColumnId,
    expectedVersion: card.version,
  }

  if (toColumn.category === 'DONE') {
    return { type: 'needs-resolution', ...base }
  }

  return { type: 'move', ...base }
}

// ─────────────────────────────────────────────────────────────────────────────
// KanbanBoard — 대기 이동 정보 타입
// ─────────────────────────────────────────────────────────────────────────────

/** resolution 선택 모달이 열린 동안 보관하는 이동 정보 */
interface PendingMove {
  issueKey: string
  fromColumnId: string
  toColumnId: string
  expectedVersion: number
}

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** KanbanBoard Props */
export interface KanbanBoardProps {
  /** 보드 UUID */
  boardId: string
  /** 보드 상세 데이터 (컬럼 + 카드 포함) */
  board: BoardDetail
  /**
   * 이슈 키 → 담당자 표시 상태 맵 (3-상태 discriminated union).
   * 페이지가 userId → displayName 해석 후 주입한다.
   */
  assigneeNames: Map<string, CardAssigneeDisplay>
}

// ─────────────────────────────────────────────────────────────────────────────
// KanbanBoard 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

const UNASSIGNED: CardAssigneeDisplay = { state: 'unassigned' }

/**
 * 칸반 보드 루트 컴포넌트.
 *
 * - DndContext 안에 컬럼들을 displayOrder asc 정렬로 가로 배치한다.
 * - DragOverlay로 드래그 중 카드 미리보기를 제공한다.
 * - onDragEnd에서 resolveDropAction을 호출해 이동 유형을 판단한다.
 *   - move → useMoveCard.mutate 즉시 호출
 *   - needs-resolution → ResolutionPickerModal 오픈, 확인 시 mutate
 * - 409 충돌 등 에러 시 toast.error를 표시한다.
 * - 센서: PointerSensor(distance:5) + KeyboardSensor — 클릭과 드래그 구분(D-2).
 */
export function KanbanBoard({ boardId, board, assigneeNames }: KanbanBoardProps): JSX.Element {
  const [activeId, setActiveId] = useState<string | null>(null)
  const [activeFromColumnId, setActiveFromColumnId] = useState<string | null>(null)
  const [overColumnId, setOverColumnId] = useState<string | null>(null)
  const [pendingMove, setPendingMove] = useState<PendingMove | null>(null)

  const moveCard = useMoveCard(boardId)

  // PointerSensor: distance 5px 이상 이동해야 드래그 시작 → 카드 Link 클릭 보존 (D-2)
  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 5 } }),
    useSensor(KeyboardSensor),
  )

  // displayOrder asc 정렬 — 원본 board.columns 변경 금지 (immutable)
  const sortedColumns = [...board.columns].sort((a, b) => a.displayOrder - b.displayOrder)

  // 드래그 중 active 카드 데이터
  const activeCard = activeId !== null && activeFromColumnId !== null
    ? board.columns
        .find((c) => c.columnId === activeFromColumnId)
        ?.cards.find((c) => c.issueKey === activeId)
    : undefined

  function handleDragStart(event: DragStartEvent): void {
    setActiveId(String(event.active.id))
    const current = event.active.data.current as { fromColumnId?: string } | undefined
    setActiveFromColumnId(current?.fromColumnId ?? null)
  }

  function handleDragOver(event: DragOverEvent): void {
    setOverColumnId(event.over ? String(event.over.id) : null)
  }

  function handleDragEnd(event: DragEndEvent): void {
    setActiveId(null)
    setActiveFromColumnId(null)
    setOverColumnId(null)

    const action = resolveDropAction(board, event.active as DragActiveMin, event.over as DragOverMin | null)

    if (action.type === 'noop') return

    const { issueKey, fromColumnId, toColumnId, expectedVersion } = action

    if (action.type === 'needs-resolution') {
      setPendingMove({ issueKey, fromColumnId, toColumnId, expectedVersion })
      return
    }

    // type === 'move'
    executeMutate({ issueKey, fromColumnId, toColumnId, expectedVersion })
  }

  function executeMutate(vars: MoveCardVars): void {
    moveCard.mutate(vars, {
      onError: (err: unknown) => {
        // 409 OCC 충돌 등 에러 — 사용자에게 안내 (롤백+invalidate는 useMoveCard 내부 처리)
        void err
        toast.error('다른 변경과 충돌이 발생했습니다. 다시 시도해 주세요.')
      },
    })
  }

  function handleResolutionConfirm(resolutionId: string): void {
    if (pendingMove === null) return
    executeMutate({ ...pendingMove, resolutionId })
    setPendingMove(null)
  }

  function handleResolutionCancel(): void {
    setPendingMove(null)
  }

  return (
    <>
      <DndContext
        sensors={sensors}
        onDragStart={handleDragStart}
        onDragOver={handleDragOver}
        onDragEnd={handleDragEnd}
        accessibility={undefined}
      >
        {/* 가로 스크롤 컨테이너 — 모바일 first, 반응형 가로 스크롤 (D-3) */}
        <div className="flex gap-4 overflow-x-auto pb-4">
          {sortedColumns.map((column) => (
            <BoardColumn
              key={column.columnId}
              column={column}
              assigneeNames={assigneeNames}
              isOver={overColumnId === column.columnId}
            />
          ))}
        </div>

        {/* 드래그 중 카드 미리보기 */}
        <DragOverlay>
          {activeCard !== undefined && activeFromColumnId !== null ? (
            <BoardCard
              card={activeCard}
              columnId={activeFromColumnId}
              assignee={assigneeNames.get(activeCard.issueKey) ?? UNASSIGNED}
            />
          ) : null}
        </DragOverlay>
      </DndContext>

      {/* DONE 컬럼 이동 시 resolution 선택 모달 */}
      <ResolutionPickerModal
        open={pendingMove !== null}
        onConfirm={handleResolutionConfirm}
        onCancel={handleResolutionCancel}
      />
    </>
  )
}
