// 칸반 보드 컬럼 컴포넌트 — 드롭 영역 + 카드 목록 + 빈 컬럼 placeholder + WIP 경고
import { memo } from 'react'
import { useDroppable } from '@dnd-kit/core'
import { cn } from '@/lib/utils'
import type { BoardColumn as BoardColumnType } from '@/api/boards'
import { boardLabels } from '@/i18n/board-labels'
import { BoardCard } from './BoardCard'
import type { CardAssigneeDisplay } from './BoardCard'
import { WipCountBadge } from './WipCountBadge'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** BoardColumn 컴포넌트 Props */
export interface BoardColumnProps {
  /** 컬럼 데이터 (카드 목록 포함) */
  column: BoardColumnType
  /**
   * 이슈 키 → 담당자 표시 상태 맵.
   * 페이지가 3-상태(unassigned/named/unknown)로 해석해 주입한다.
   * 맵에 없는 키는 unassigned로 fallback한다.
   */
  assigneeNames: Map<string, CardAssigneeDisplay>
  /** 드래그 카드가 이 컬럼 위에 있는지 여부. 하이라이트에 사용 */
  isOver?: boolean
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 구현 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

const UNASSIGNED: CardAssigneeDisplay = { state: 'unassigned' }

function BoardColumnInner({ column, assigneeNames, isOver = false }: BoardColumnProps) {
  const { setNodeRef } = useDroppable({
    id: column.columnId,
    data: { category: column.category },
  })

  const cardCount = column.cards.length

  return (
    <div
      className="flex min-w-72 w-72 flex-col gap-2"
      role="group"
      aria-label={boardLabels.column.ariaLabel(column.name, cardCount)}
    >
      {/* sticky 헤더 — 이름 + 카테고리 배지 + 카드 수(WIP 포함) */}
      <div className="sticky top-0 z-10 flex items-center gap-2 rounded-t-lg bg-muted px-3 py-2">
        <span className="flex-1 text-sm font-semibold text-foreground">{column.name}</span>
        <span
          className="rounded-sm bg-background px-1.5 py-0.5 text-xs text-muted-foreground"
          aria-label={boardLabels.column.categoryAriaLabel(column.category)}
        >
          {column.category}
        </span>
        <WipCountBadge
          count={cardCount}
          wipLimit={column.wipLimit}
          wipExceeded={column.wipExceeded}
        />
      </div>

      {/* 드롭 영역 + 카드 목록 — 빈 컬럼에도 드롭 가능하도록 ref 유지 */}
      <div
        ref={setNodeRef}
        data-col-id={column.columnId}
        className={cn(
          'flex flex-1 flex-col gap-2 rounded-b-lg border border-border p-2 transition-colors',
          isOver && 'bg-accent ring-2 ring-primary',
        )}
      >
        {column.cards.length === 0 ? (
          <div
            className="flex flex-1 items-center justify-center rounded-md py-8 text-xs text-muted-foreground"
            aria-label="카드 없음"
          >
            카드 없음
          </div>
        ) : (
          column.cards.map((card) => (
            <BoardCard
              key={card.issueKey}
              card={card}
              columnId={column.columnId}
              assignee={assigneeNames.get(card.issueKey) ?? UNASSIGNED}
            />
          ))
        )}
      </div>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// memo 래핑 — Map 참조가 안정적일 때 재렌더 스킵
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 칸반 보드의 개별 컬럼.
 *
 * - sticky 헤더에 이름·카테고리 배지·카드 수(WIP 제한 포함)를 표시한다.
 * - wipLimit이 있으면 "{count}/{limit}" 형식으로 표기한다.
 * - wipExceeded=true이면 amber 경고 톤 + aria-label "WIP 초과"를 적용한다.
 * - `useDroppable`로 드롭 영역을 제공한다. 빈 컬럼에도 드롭 가능.
 * - 카드가 없으면 흐린 "카드 없음" placeholder를 표시한다.
 * - `isOver=true`이면 ring-2 하이라이트를 적용한다.
 * - `memo`로 래핑되어 column·assigneeNames·isOver가 변하지 않으면 재렌더하지 않는다.
 */
export const BoardColumn = memo(BoardColumnInner)
