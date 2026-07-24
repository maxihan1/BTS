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
import type { BoardDetail, BoardCardFilterParams } from '@/api/boards'
import { useMoveCard } from '@/hooks/use-move-card'
import type { MoveCardVars } from '@/hooks/use-move-card'
import { BoardColumn } from './BoardColumn'
import { BoardCard } from './BoardCard'
import type { CardAssigneeDisplay } from './BoardCard'
import { ResolutionPickerModal } from './ResolutionPickerModal'
import { resolveDropAction } from './board-drop'
import type { DragActiveMin, DragOverMin } from './board-drop'

// board-drop.ts로 이전된 순수 헬퍼 — 기존 소비자(KanbanBoard.test.tsx) 호환을 위해 재노출
// eslint-disable-next-line react-refresh/only-export-components
export { resolveDropAction } from './board-drop'
export type { DragActiveMin, DragOverMin, DropAction } from './board-drop'

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
  /**
   * 현재 적용된 카드 필터 (FR-BD-02).
   * useMoveCard로 전달해 filter-aware queryKey와 낙관적 업데이트가 정합되도록 한다.
   * 옵셔널 — 없으면 undefined로 전달 (기존 호출 호환).
   */
  filter?: BoardCardFilterParams
  /**
   * 보드 필터 활성 여부 (FR-BD-03 hotfix-p2).
   * true이면 각 BoardColumn의 WIP 초과 경고를 약화 + "(필터됨)" 표시.
   * 이동/판정 로직에는 영향 없음 — 표시만 변경.
   */
  isFilterActive?: boolean
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
export function KanbanBoard({ boardId, board, assigneeNames, filter, isFilterActive = false }: KanbanBoardProps): JSX.Element {
  const [activeId, setActiveId] = useState<string | null>(null)
  const [activeFromColumnId, setActiveFromColumnId] = useState<string | null>(null)
  const [overColumnId, setOverColumnId] = useState<string | null>(null)
  const [pendingMove, setPendingMove] = useState<PendingMove | null>(null)

  // filter-aware useMoveCard — filter와 동일한 queryKey를 공유해 낙관적 업데이트 정합
  const moveCard = useMoveCard(boardId, filter)

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

    const action = resolveDropAction(
      board,
      event.active as DragActiveMin,
      event.over as DragOverMin | null,
      assigneeNames,
    )

    if (action.type === 'noop') return

    if (action.type === 'reorder') {
      // 셀 내 순서변경 실행 배선은 이 컴포넌트의 다른 태스크(sortable 적용) 담당 — 여기선 판정만
      return
    }

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
              swimlaneField={board.swimlaneField}
              isFilterActive={isFilterActive}
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
