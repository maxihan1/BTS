// 백로그 미할당 칸 컴포넌트 — 드롭 영역 + 이슈 카드 목록 (FR-BL-01/02 D6/D7)
import { memo } from 'react'
import { useDroppable } from '@dnd-kit/core'
import { cn } from '@/lib/utils'
import { backlogLabels } from '@/i18n/backlog-labels'
import type { BacklogIssue } from '@/api/backlog'
import { BacklogCard } from './BacklogCard'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** 백로그 칸의 고정 droppable id */
const BACKLOG_DROPPABLE_ID = 'backlog'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** BacklogColumn 컴포넌트 Props */
export interface BacklogColumnProps {
  /** rank 순으로 정렬된 미할당 이슈 목록 (부모가 정렬해 전달) */
  issues: BacklogIssue[]
  /**
   * 이슈 키 → 담당자 표시 이름 맵.
   * 맵에 없는 키는 assigneeId 유무에 따라 미확인/미배정으로 fallback한다.
   */
  assigneeNames: Map<string, string>
  /** 드래그 카드가 이 칸 위에 있는지 여부. 하이라이트에 사용 */
  isOver?: boolean
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 구현 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

function BacklogColumnInner({ issues, assigneeNames, isOver = false }: BacklogColumnProps) {
  const orderedKeys = issues.map((i) => i.key)

  const { setNodeRef } = useDroppable({
    id: BACKLOG_DROPPABLE_ID,
    data: { context: 'backlog', orderedKeys },
  })

  const issueCount = issues.length

  return (
    <div
      className="flex min-w-72 w-72 flex-col gap-2"
      role="region"
      aria-label={backlogLabels.columnAriaLabel(backlogLabels.backlogTitle, issueCount)}
    >
      {/* 헤더 — 제목 + 카드 수 */}
      <div className="sticky top-0 z-10 flex items-center gap-2 rounded-t-lg bg-(--bg-neutral-solid) px-3 py-2">
        <span className="flex-1 text-sm font-semibold text-foreground">
          {backlogLabels.backlogTitle}
        </span>
        <span
          className="rounded-sm bg-background px-1.5 py-0.5 text-xs text-muted-foreground"
          aria-label={`이슈 ${issueCount}개`}
        >
          {issueCount}
        </span>
      </div>

      {/* 드롭 영역 + 카드 목록 */}
      <div
        ref={setNodeRef}
        data-droppable={BACKLOG_DROPPABLE_ID}
        data-ordered-keys={orderedKeys.join(',')}
        className={cn(
          'flex flex-1 flex-col gap-2 rounded-b-lg border border-border p-2 transition-colors',
          isOver && 'bg-accent ring-2 ring-primary',
        )}
      >
        {issueCount === 0 ? (
          <div
            className="flex flex-1 items-center justify-center rounded-md py-8 text-xs text-muted-foreground"
            aria-label="이슈 없음"
          >
            {backlogLabels.emptyIssues}
          </div>
        ) : (
          issues.map((issue) => (
            <BacklogCard
              key={issue.key}
              issue={issue}
              context="backlog"
              assigneeName={assigneeNames.get(issue.key)}
            />
          ))
        )}
      </div>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// memo 래핑 — issues·assigneeNames·isOver가 변하지 않으면 재렌더 스킵
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 백로그(스프린트 미할당) 이슈 칸.
 *
 * - 헤더에 "백로그" 제목과 이슈 수를 표시한다.
 * - `useDroppable`로 droppable id="backlog" 영역을 제공한다. 빈 목록에도 드롭 가능.
 * - 이슈가 없으면 "이슈 없음" placeholder를 표시한다.
 * - `isOver=true`이면 ring-2 하이라이트를 적용한다.
 * - `memo`로 래핑되어 props가 변하지 않으면 재렌더하지 않는다.
 */
export const BacklogColumn = memo(BacklogColumnInner)
