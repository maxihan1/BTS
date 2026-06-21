// 칸반 보드 컬럼 컴포넌트 — 드롭 영역 + 카드 목록 + 빈 컬럼 placeholder
import { useDroppable } from '@dnd-kit/core'
import { cn } from '@/lib/utils'
import type { BoardColumn as BoardColumnType } from '@/api/boards'
import { BoardCard } from './BoardCard'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** BoardColumn 컴포넌트 Props */
export interface BoardColumnProps {
  /** 컬럼 데이터 (카드 목록 포함) */
  column: BoardColumnType
  /**
   * 이슈 키 → 담당자 표시 이름 맵.
   * 페이지가 해석해 주입한다. 값이 없거나 null이면 "미배정".
   */
  assigneeNames: Map<string, string | null>
  /** 드래그 카드가 이 컬럼 위에 있는지 여부. 하이라이트에 사용 */
  isOver?: boolean
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 칸반 보드의 개별 컬럼.
 *
 * - sticky 헤더에 이름·카테고리 배지·카드 수를 표시한다.
 * - `useDroppable`로 드롭 영역을 제공한다. 빈 컬럼에도 드롭 가능.
 * - 카드가 없으면 흐린 "카드 없음" placeholder를 표시한다.
 * - `isOver=true`이면 ring 하이라이트를 적용한다.
 */
export const BoardColumn = ({ column, assigneeNames, isOver = false }: BoardColumnProps) => {
  const { setNodeRef } = useDroppable({
    id: column.columnId,
    data: { category: column.category },
  })

  return (
    <div className="flex min-w-72 w-72 flex-col gap-2">
      {/* 헤더 */}
      <div className="sticky top-0 z-10 flex items-center gap-2 rounded-t-lg bg-muted px-3 py-2">
        <span className="flex-1 text-sm font-semibold text-foreground">{column.name}</span>
        <span className="rounded-sm bg-background px-1.5 py-0.5 text-xs text-muted-foreground">
          {column.category}
        </span>
        <span className="rounded-full bg-primary px-1.5 py-0.5 text-xs font-medium text-primary-foreground">
          {column.cards.length}
        </span>
      </div>

      {/* 드롭 영역 + 카드 목록 */}
      <div
        ref={setNodeRef}
        data-col-id={column.columnId}
        className={cn(
          'flex flex-1 flex-col gap-2 rounded-b-lg border border-border p-2 transition-colors',
          isOver && 'bg-accent ring-2 ring-primary',
        )}
      >
        {column.cards.length === 0 ? (
          <div className="flex flex-1 items-center justify-center rounded-md py-8 text-xs text-muted-foreground">
            카드 없음
          </div>
        ) : (
          column.cards.map((card) => (
            <BoardCard
              key={card.issueKey}
              card={card}
              columnId={column.columnId}
              assigneeName={assigneeNames.get(card.issueKey) ?? null}
            />
          ))
        )}
      </div>
    </div>
  )
}
