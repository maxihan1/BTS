// 칸반 보드 루트 컴포넌트 — DndContext + 컬럼 배치 + 드래그 이동 오케스트레이션 (FR-BD-01)
import type { JSX } from 'react'
import { useMemo, useState } from 'react'
import {
  DndContext,
  DragOverlay,
  PointerSensor,
  KeyboardSensor,
  useSensor,
  useSensors,
} from '@dnd-kit/core'
import type { Announcements, DragEndEvent, DragStartEvent, DragOverEvent } from '@dnd-kit/core'
import { toast } from 'sonner'
import type { BoardDetail, BoardCardFilterParams } from '@/api/boards'
import { useMoveCard } from '@/hooks/use-move-card'
import type { MoveCardVars } from '@/hooks/use-move-card'
import { useReorderCard } from '@/hooks/use-reorder-card'
import type { ReorderCardVars } from '@/hooks/use-reorder-card'
import { BoardColumn } from './BoardColumn'
import { BoardCard } from './BoardCard'
import type { CardAssigneeDisplay } from './BoardCard'
import { ResolutionPickerModal } from './ResolutionPickerModal'
import { resolveDropAction } from './board-drop'
import type { DragActiveMin, DragOverMin, DropAction } from './board-drop'

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
// 접근성 공지(DR2) — resolveDropAction과 동일 판정을 재사용해 실제 결과와 문구를 일치시킨다
// ─────────────────────────────────────────────────────────────────────────────

/** board에서 issueKey에 해당하는 카드 summary를 찾는다. 없으면 undefined. */
function findCardSummary(board: BoardDetail, issueKey: string): string | undefined {
  for (const column of board.columns) {
    const card = column.cards.find((c) => c.issueKey === issueKey)
    if (card !== undefined) return card.summary
  }
  return undefined
}

/** board에서 columnId에 해당하는 컬럼 이름을 찾는다. 못 찾으면 columnId를 그대로 반환한다(방어적 fallback). */
function findColumnName(board: BoardDetail, columnId: string): string {
  return board.columns.find((c) => c.columnId === columnId)?.name ?? columnId
}

/**
 * DropAction을 드래그 진행 중(present) 공지 문구로 변환한다 — onDragOver announcement용.
 * 아직 확정되지 않은 위치를 안내한다.
 */
function describeDragOverAction(action: DropAction, board: BoardDetail): string {
  switch (action.type) {
    case 'move':
    case 'needs-resolution':
      return `${findColumnName(board, action.toColumnId)} 컬럼 위에 있습니다.`
    case 'reorder':
      return `${findColumnName(board, action.columnId)} 안에서 순서를 조정하고 있습니다.`
    case 'noop':
      return '이동할 수 없는 위치입니다.'
    default: {
      const exhaustiveCheck: never = action
      return exhaustiveCheck
    }
  }
}

/**
 * DropAction을 드래그 완료(past) 공지 문구로 변환한다 — onDragEnd announcement용.
 * 실제로 반영될 변경 결과를 안내한다.
 */
function describeDragEndAction(action: DropAction, board: BoardDetail): string {
  switch (action.type) {
    case 'move':
    case 'needs-resolution':
      return `${findColumnName(board, action.toColumnId)} 컬럼으로 이동했습니다.`
    case 'reorder':
      return '순서를 변경했습니다.'
    case 'noop':
      return '변경 사항이 없습니다.'
    default: {
      const exhaustiveCheck: never = action
      return exhaustiveCheck
    }
  }
}

/**
 * DndContext `accessibility.announcements` — 드래그 상호작용을 한국어로 스크린리더에 공지한다(DR2).
 *
 * resolveDropAction과 동일한 판정 로직을 그대로 재사용해, 실제로 반영되는 동작(move/reorder/noop)과
 * 공지 문구가 어긋나지 않도록 한다.
 *
 * @param board 현재 BoardDetail — 카드 summary/컬럼 이름 조회에 사용
 * @param assigneeNames 스윔레인 그룹 판정용 담당자 표시 맵(resolveDropAction과 동일 인자)
 */
function buildDragAnnouncements(
  board: BoardDetail,
  assigneeNames: Map<string, CardAssigneeDisplay>,
): Announcements {
  return {
    onDragStart({ active }) {
      const label = findCardSummary(board, String(active.id)) ?? String(active.id)
      return `${label} 카드를 집었습니다.`
    },
    onDragOver({ active, over }) {
      if (over === null) return '드롭 가능한 영역을 벗어났습니다.'
      const action = resolveDropAction(board, active as DragActiveMin, over as DragOverMin, assigneeNames)
      return describeDragOverAction(action, board)
    },
    onDragEnd({ active, over }) {
      const action = resolveDropAction(board, active as DragActiveMin, over as DragOverMin | null, assigneeNames)
      return describeDragEndAction(action, board)
    },
    onDragCancel() {
      return '취소했습니다.'
    },
  }
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
 *   - reorder → useReorderCard.mutate 즉시 호출(셀 내 순서변경)
 *   - noop → 아무 동작 없음
 * - 409 충돌 등 에러 시 toast.error를 표시한다(reorder는 useReorderCard 내부에서 처리).
 * - accessibility.announcements로 드래그 상호작용을 한국어로 스크린리더에 공지한다(DR2).
 * - 센서: PointerSensor(distance:5) + KeyboardSensor — 클릭과 드래그 구분(D-2).
 */
export function KanbanBoard({ boardId, board, assigneeNames, filter, isFilterActive = false }: KanbanBoardProps): JSX.Element {
  const [activeId, setActiveId] = useState<string | null>(null)
  const [activeFromColumnId, setActiveFromColumnId] = useState<string | null>(null)
  const [activeSwimlaneGroupKey, setActiveSwimlaneGroupKey] = useState<string | undefined>(undefined)
  const [overColumnId, setOverColumnId] = useState<string | null>(null)
  const [pendingMove, setPendingMove] = useState<PendingMove | null>(null)

  // filter-aware useMoveCard/useReorderCard — filter와 동일한 queryKey를 공유해 낙관적 업데이트 정합
  const moveCard = useMoveCard(boardId, filter)
  const reorderCard = useReorderCard(boardId, filter)

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

  // 접근성 공지(DR2) — board/assigneeNames가 바뀔 때만 재계산(불필요한 재구독 방지)
  const announcements = useMemo(() => buildDragAnnouncements(board, assigneeNames), [board, assigneeNames])

  function handleDragStart(event: DragStartEvent): void {
    setActiveId(String(event.active.id))
    const current = event.active.data.current as { fromColumnId?: string; swimlaneGroupKey?: string } | undefined
    setActiveFromColumnId(current?.fromColumnId ?? null)
    setActiveSwimlaneGroupKey(current?.swimlaneGroupKey)
  }

  function handleDragOver(event: DragOverEvent): void {
    // DR3(최소 구현) — 같은 컬럼 내 다른 스윔레인 그룹(셀) 위 드래그는 이번 PR에서 noop이므로
    // 착시를 막기 위해 컬럼 하이라이트를 억제한다.
    const over = event.over
    if (over === null) {
      setOverColumnId(null)
      return
    }

    const overData = over.data.current as { fromColumnId?: string; swimlaneGroupKey?: string } | undefined
    const overColumnIdResolved = overData?.fromColumnId ?? String(over.id)
    const isCrossGroupWithinSameColumn =
      activeFromColumnId !== null &&
      overColumnIdResolved === activeFromColumnId &&
      activeSwimlaneGroupKey !== undefined &&
      overData?.swimlaneGroupKey !== undefined &&
      overData.swimlaneGroupKey !== activeSwimlaneGroupKey

    setOverColumnId(isCrossGroupWithinSameColumn ? null : overColumnIdResolved)
  }

  function handleDragEnd(event: DragEndEvent): void {
    setActiveId(null)
    setActiveFromColumnId(null)
    setActiveSwimlaneGroupKey(undefined)
    setOverColumnId(null)

    const action = resolveDropAction(
      board,
      event.active as DragActiveMin,
      event.over as DragOverMin | null,
      assigneeNames,
    )

    if (action.type === 'noop') return

    if (action.type === 'reorder') {
      executeReorder(action)
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

  function executeReorder(action: Extract<DropAction, { type: 'reorder' }>): void {
    // 409 충돌 등 에러 toast는 useReorderCard 내부에서 처리(중복 안내 방지)
    const vars: ReorderCardVars = {
      issueKey: action.issueKey,
      columnId: action.columnId,
      previousIssueKey: action.previousIssueKey,
      nextIssueKey: action.nextIssueKey,
    }
    reorderCard.mutate(vars)
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
        accessibility={{ announcements }}
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
