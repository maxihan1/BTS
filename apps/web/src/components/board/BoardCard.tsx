// 칸반 보드 카드 컴포넌트 — 이슈 요약 표시 + @dnd-kit 드래그 핸들
import { memo } from 'react'
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
// 내부 구현 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

function BoardCardInner({ card, columnId, assigneeName }: BoardCardProps) {
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
      {/* 주 정보: summary (2줄 truncate). 드래그 중 클릭 방지 */}
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
            aria-label={`담당자: ${assigneeName ?? ''}`}
            className={cn(
              'flex h-6 w-6 items-center justify-center rounded-full',
              'bg-primary text-xs font-medium text-primary-foreground',
            )}
          >
            {initial}
          </span>
        ) : (
          // 미배정
          <span className="text-xs text-muted-foreground" aria-label="담당자 미배정">
            미배정
          </span>
        )}
      </div>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// memo 래핑 — 보드 전체 재렌더 시 props 변화 없는 카드 렌더 스킵
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 칸반 보드의 개별 이슈 카드.
 *
 * - summary를 주 정보로(2줄 truncate), issueKey를 보조 정보로 표시한다.
 * - assigneeName이 있으면 이니셜 아바타를, 없으면 "미배정"을 표시한다.
 * - `useDraggable`로 드래그 핸들을 제공한다.
 * - 카드 클릭 시 이슈 상세(`/issues/:key`)로 이동하며, 드래그 중엔 네비게이션이 막힌다.
 * - `memo`로 래핑되어 props가 변하지 않으면 재렌더하지 않는다.
 */
export const BoardCard = memo(BoardCardInner)
