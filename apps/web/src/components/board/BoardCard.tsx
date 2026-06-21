// 칸반 보드 카드 컴포넌트 — 이슈 요약 표시 + @dnd-kit 드래그 핸들
import { Link } from '@tanstack/react-router'
import { useDraggable } from '@dnd-kit/core'
import { CSS } from '@dnd-kit/utilities'
import { cn } from '@/lib/utils'
import type { BoardCard as BoardCardType } from '@/api/boards'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** BoardCard 컴포넌트 Props */
export interface BoardCardProps {
  /** 카드에 표시할 이슈 데이터 */
  card: BoardCardType
  /** 카드가 속한 컬럼 UUID */
  columnId: string
  /**
   * 담당자 표시 이름 (페이지가 userId → displayName 해석 후 주입).
   * null이면 "미배정" 표시.
   */
  assigneeName: string | null
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 칸반 보드의 개별 이슈 카드.
 *
 * - summary를 주 정보로, issueKey를 보조 정보로 표시한다.
 * - assigneeName이 있으면 이니셜 아바타를, 없으면 "미배정"을 표시한다.
 * - @dnd-kit `useDraggable`로 드래그 핸들을 제공한다.
 * - 카드 클릭 시 이슈 상세(`/issues/:key`)로 이동한다.
 */
export const BoardCard = ({ card, columnId, assigneeName }: BoardCardProps) => {
  const { attributes, listeners, setNodeRef, transform, isDragging } = useDraggable({
    id: card.issueKey,
    data: { fromColumnId: columnId },
  })

  const style = transform
    ? { transform: CSS.Translate.toString(transform) }
    : undefined

  const initial = assigneeName != null ? assigneeName[0] ?? '' : null

  return (
    <div
      ref={setNodeRef}
      style={style}
      {...listeners}
      {...attributes}
      aria-roledescription="draggable card"
      aria-label={`${card.issueKey} — ${card.summary}`}
      className={cn(
        'flex flex-col gap-2 rounded-lg border border-border bg-card p-3 shadow-sm',
        'cursor-grab active:cursor-grabbing',
        isDragging && 'opacity-50',
      )}
    >
      {/* 주 정보: summary (2줄 truncate) */}
      <Link
        to="/issues/$key"
        params={{ key: card.issueKey }}
        className="line-clamp-2 text-sm font-medium leading-snug text-foreground hover:underline"
        onClick={(e) => {
          if (isDragging) {
            e.preventDefault()
          }
        }}
      >
        {card.summary}
      </Link>

      {/* 하단 행: issueKey(보조) + 담당자 아바타 */}
      <div className="flex items-center justify-between">
        <span className="text-xs text-muted-foreground">{card.issueKey}</span>

        {initial != null ? (
          // 담당자 이니셜 아바타
          <span
            title={assigneeName ?? ''}
            className={cn(
              'flex h-6 w-6 items-center justify-center rounded-full',
              'bg-primary text-xs font-medium text-primary-foreground',
            )}
          >
            {initial}
          </span>
        ) : (
          // 미배정
          <span className="text-xs text-muted-foreground">미배정</span>
        )}
      </div>
    </div>
  )
}
