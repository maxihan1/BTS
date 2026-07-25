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

// ─────────────────────────────────────────────────────────────────────────────
// 필드변경 announcement 문구 (FR-7, FR-UX-06 PR21b Task 4)
// ─────────────────────────────────────────────────────────────────────────────

/** field-change DropAction 변형만 추출한 타입 별칭 — describeXxxFieldChange 헬퍼 시그니처에 재사용 */
type FieldChangeAction = Extract<DropAction, { type: 'field-change' }>

/** 필드변경 announcement 시제 — onDragOver(예고형 "~변경합니다")·onDragEnd(완료형 "~변경했습니다") 구분 */
type FieldChangeTense = 'preview' | 'done'

/** 담당자 이름을 조회하지 못했을 때(unknown·그룹 미확인) 대체 표시 텍스트 — issue-columns.ts assigneeName 관례와 동일 */
const ASSIGNEE_NAME_FALLBACK = '미배정'

/** 우선순위 값을 읽지 못했을 때(방어적) 대체 표시 텍스트 — findColumnName 방어적 fallback 관례와 동일 */
const PRIORITY_VALUE_FALLBACK = '알 수 없음'

/**
 * 시제(tense)에 따라 예고형/완료형 동사 문구 중 하나를 고른다.
 * describeXxxFieldChange 3곳에서 반복되던 `tense === 'preview' ? ... : ...` 분기를 한 곳에 모은다.
 */
function pickByTense(tense: FieldChangeTense, preview: string, done: string): string {
  return tense === 'preview' ? preview : done
}

/**
 * assigneeId(UUID)를 가진 카드를 board에서 찾아 assigneeNames 표시 이름을 조회한다.
 * 그룹 key(이름 기반)가 아니라 카드 자신의 실제 assigneeId로 조회하므로
 * 이름 기반 그룹 오분류(스펙 G1: unknown/동명이인 혼재)에 영향받지 않는다.
 *
 * @returns named 상태의 이름. 카드를 못 찾거나 named 상태가 아니면 undefined
 */
function findAssigneeDisplayName(
  board: BoardDetail,
  assigneeNames: Map<string, CardAssigneeDisplay>,
  assigneeId: string,
): string | undefined {
  for (const column of board.columns) {
    const card = column.cards.find((c) => c.assigneeId === assigneeId)
    if (card === undefined) continue
    const display = assigneeNames.get(card.issueKey)
    if (display?.state === 'named') return display.name
  }
  return undefined
}

/** ASSIGNEE 필드변경 announcement 문구 — 담당자 재할당/해제 */
function describeAssigneeFieldChange(
  action: FieldChangeAction,
  board: BoardDetail,
  assigneeNames: Map<string, CardAssigneeDisplay>,
  tense: FieldChangeTense,
): string {
  if (action.toAssigneeId === null || action.toAssigneeId === undefined) {
    return `${action.issueKey}의 담당자를 ${pickByTense(tense, '해제합니다', '해제했습니다')}.`
  }
  const name = findAssigneeDisplayName(board, assigneeNames, action.toAssigneeId) ?? ASSIGNEE_NAME_FALLBACK
  const changeVerb = pickByTense(tense, '변경합니다', '변경했습니다')
  return `${action.issueKey}을(를) 담당자 ${name}(으)로 ${changeVerb}`
}

/** PRIORITY 필드변경 announcement 문구 */
function describePriorityFieldChange(action: FieldChangeAction, tense: FieldChangeTense): string {
  const priorityLabel = action.toPriority !== undefined ? String(action.toPriority) : PRIORITY_VALUE_FALLBACK
  const changeVerb = pickByTense(tense, '변경합니다', '변경했습니다')
  return `${action.issueKey}의 우선순위를 ${priorityLabel}로 ${changeVerb}`
}

/** EPIC 필드변경 announcement 문구 — 에픽 재배치/해제 */
function describeEpicFieldChange(action: FieldChangeAction, tense: FieldChangeTense): string {
  if (action.toEpicKey === null || action.toEpicKey === undefined) {
    return `${action.issueKey}의 에픽 연결을 ${pickByTense(tense, '해제합니다', '해제했습니다')}.`
  }
  const moveVerb = pickByTense(tense, '이동합니다', '이동했습니다')
  return `${action.issueKey}을(를) 에픽 ${action.toEpicKey}로 ${moveVerb}`
}

/**
 * field-change 액션을 시제(tense)에 맞춘 한국어 announcement 문구로 변환한다 (FR-7).
 * 필드별 문구는 describeAssigneeFieldChange/describePriorityFieldChange/describeEpicFieldChange에 위임한다.
 */
function describeFieldChangeAction(
  action: FieldChangeAction,
  board: BoardDetail,
  assigneeNames: Map<string, CardAssigneeDisplay>,
  tense: FieldChangeTense,
): string {
  switch (action.field) {
    case 'assignee':
      return describeAssigneeFieldChange(action, board, assigneeNames, tense)
    case 'priority':
      return describePriorityFieldChange(action, tense)
    case 'epic':
      return describeEpicFieldChange(action, tense)
    default: {
      const exhaustiveCheck: never = action.field
      return exhaustiveCheck
    }
  }
}

/**
 * DropAction을 드래그 진행 중(present) 공지 문구로 변환한다 — onDragOver announcement용.
 * 아직 확정되지 않은 위치를 안내한다.
 */
function describeDragOverAction(
  action: DropAction,
  board: BoardDetail,
  assigneeNames: Map<string, CardAssigneeDisplay>,
): string {
  switch (action.type) {
    case 'move':
    case 'needs-resolution':
      return `${findColumnName(board, action.toColumnId)} 컬럼 위에 있습니다.`
    case 'reorder':
      return `${findColumnName(board, action.columnId)} 안에서 순서를 조정하고 있습니다.`
    case 'field-change':
      return describeFieldChangeAction(action, board, assigneeNames, 'preview')
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
function describeDragEndAction(
  action: DropAction,
  board: BoardDetail,
  assigneeNames: Map<string, CardAssigneeDisplay>,
): string {
  switch (action.type) {
    case 'move':
    case 'needs-resolution':
      return `${findColumnName(board, action.toColumnId)} 컬럼으로 이동했습니다.`
    case 'reorder':
      return '순서를 변경했습니다.'
    case 'field-change':
      return describeFieldChangeAction(action, board, assigneeNames, 'done')
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
      return describeDragOverAction(action, board, assigneeNames)
    },
    onDragEnd({ active, over }) {
      const action = resolveDropAction(board, active as DragActiveMin, over as DragOverMin | null, assigneeNames)
      return describeDragEndAction(action, board, assigneeNames)
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

  /** dnd-kit onDragStart — 드래그 중인 카드의 출발 컬럼·셀(스윔레인 그룹) key를 기록한다. */
  function handleDragStart(event: DragStartEvent): void {
    setActiveId(String(event.active.id))
    const current = event.active.data.current as { fromColumnId?: string; swimlaneGroupKey?: string } | undefined
    setActiveFromColumnId(current?.fromColumnId ?? null)
    setActiveSwimlaneGroupKey(current?.swimlaneGroupKey)
  }

  /**
   * dnd-kit onDragOver — 하이라이트할 컬럼 id를 계산한다.
   *
   * DR3(최소 구현) — 같은 컬럼 내에서 활성 카드와 다른 스윔레인 그룹(셀) 위로 드래그 중이면
   * 이 PR에서는 noop으로 처리되므로(필드변경은 PR21b), 착시를 막기 위해 컬럼 하이라이트를
   * 억제한다(over 대상이 없는 것처럼 취급).
   */
  function handleDragOver(event: DragOverEvent): void {
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

  /**
   * dnd-kit onDragEnd — 드래그 상태를 초기화하고 resolveDropAction 판정 결과를
   * dispatchDropAction에 위임한다.
   */
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

    dispatchDropAction(action)
  }

  /**
   * resolveDropAction 판정 결과(DropAction)에 따라 실제 부수효과를 실행한다.
   *
   * - `noop` → 아무 것도 하지 않는다.
   * - `reorder` → executeReorder(useReorderCard.mutate 즉시 호출) — 셀 내 순서변경.
   * - `needs-resolution` → pendingMove를 채워 ResolutionPickerModal을 연다(확인 시 executeMutate).
   * - `move` → executeMutate(useMoveCard.mutate 즉시 호출) — 다른 컬럼(non-DONE)으로 이동.
   *
   * @param action resolveDropAction이 반환한 판정 결과
   */
  function dispatchDropAction(action: DropAction): void {
    switch (action.type) {
      case 'noop':
        return
      case 'reorder':
        executeReorder(action)
        return
      case 'needs-resolution':
        setPendingMove({
          issueKey: action.issueKey,
          fromColumnId: action.fromColumnId,
          toColumnId: action.toColumnId,
          expectedVersion: action.expectedVersion,
        })
        return
      case 'move':
        executeMutate({
          issueKey: action.issueKey,
          fromColumnId: action.fromColumnId,
          toColumnId: action.toColumnId,
          expectedVersion: action.expectedVersion,
        })
        return
      default: {
        const exhaustiveCheck: never = action
        return exhaustiveCheck
      }
    }
  }

  /** 카드를 다른 컬럼으로 이동한다(useMoveCard.mutate). 409 등 에러는 toast로 안내한다. */
  function executeMutate(vars: MoveCardVars): void {
    moveCard.mutate(vars, {
      onError: (err: unknown) => {
        // 409 OCC 충돌 등 에러 — 사용자에게 안내 (롤백+invalidate는 useMoveCard 내부 처리)
        void err
        toast.error('다른 변경과 충돌이 발생했습니다. 다시 시도해 주세요.')
      },
    })
  }

  /**
   * 셀(컬럼 × 스윔레인 그룹) 내에서 카드 순서를 변경한다(useReorderCard.mutate).
   * 409 충돌 등 에러 toast는 useReorderCard 내부에서 처리한다(중복 안내 방지).
   */
  function executeReorder(action: Extract<DropAction, { type: 'reorder' }>): void {
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
