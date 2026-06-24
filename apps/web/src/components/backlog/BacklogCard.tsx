// 백로그·스프린트 카드 컴포넌트 — 이슈 요약 표시 + @dnd-kit 드래그/드롭 핸들
import { memo } from 'react'
import { Link } from '@tanstack/react-router'
import { useDraggable, useDroppable } from '@dnd-kit/core'
import { CSS } from '@dnd-kit/utilities'
import { cn } from '@/lib/utils'
import { backlogLabels } from '@/i18n/backlog-labels'
import { cardDroppableId } from '@/lib/backlog-drag'
import type { BacklogIssue } from '@/api/backlog'

// ─────────────────────────────────────────────────────────────────────────────
// 드래그 컨텍스트 타입
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 카드가 위치한 컨텍스트.
 *
 * - `backlog`: 스프린트 미할당 백로그 칸
 * - `sprint`: 특정 스프린트 칸
 *
 * onDragEnd 핸들러가 드롭 대상과 조합해 이동 방향을 판단하는 데 사용된다.
 */
export type BacklogCardContext = 'backlog' | 'sprint'

/**
 * 드래그 data — `useDraggable`에 넣어 DndContext 이벤트에서 읽는다.
 *
 * - `issueKey`: 이동할 이슈 키
 * - `context`: 카드가 위치한 컨텍스트
 * - `sprintId`: context='sprint'일 때 스프린트 UUID, 'backlog'이면 null
 */
export interface BacklogDragData {
  issueKey: string
  context: BacklogCardContext
  sprintId: string | null
}

/**
 * 카드 droppable data — `useDroppable`에 넣어 onDragEnd에서 over 대상이 카드임을 식별한다.
 *
 * - `type`: 'card' — 칸 droppable과 구분하는 식별자
 * - `key`: 이 카드의 이슈 키
 * - `context`: 카드가 위치한 컨텍스트
 * - `sprintId`: context='sprint'일 때 스프린트 UUID, 'backlog'이면 null
 */
export interface BacklogCardDropData {
  type: 'card'
  key: string
  context: BacklogCardContext
  sprintId: string | null
}


// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** BacklogCard 컴포넌트 Props */
export interface BacklogCardProps {
  /** 카드에 표시할 이슈 데이터 */
  issue: BacklogIssue
  /** 카드가 위치한 컨텍스트 (백로그 또는 스프린트) */
  context: BacklogCardContext
  /**
   * context='sprint'일 때 스프린트 UUID.
   * 백로그 컨텍스트이면 undefined (data에는 null로 저장).
   */
  sprintId?: string
  /**
   * 담당자 표시 이름.
   * - undefined이고 assigneeId=null → 미배정
   * - undefined이고 assigneeId!=null → 이름 미확인 ("?" 아바타)
   * - string → 이니셜 아바타
   */
  assigneeName?: string
}

// ─────────────────────────────────────────────────────────────────────────────
// 담당자 아바타 렌더 헬퍼
// ─────────────────────────────────────────────────────────────────────────────

/** 담당자 표시 상태에 따른 아바타 또는 "미배정" 텍스트를 렌더한다. */
function AssigneeSlot({
  assigneeId,
  assigneeName,
}: {
  assigneeId: string | null
  assigneeName: string | undefined
}): React.ReactElement {
  if (assigneeName !== undefined) {
    const initial = assigneeName[0] ?? ''
    return (
      <span
        title={assigneeName}
        aria-label={`담당자: ${assigneeName}`}
        className={cn(
          'flex h-6 w-6 items-center justify-center rounded-full',
          'bg-primary text-xs font-medium text-primary-foreground',
        )}
      >
        {initial}
      </span>
    )
  }

  if (assigneeId !== null) {
    // assigneeId는 있으나 이름을 못 찾은 경우
    return (
      <span
        title={backlogLabels.unknownAssigneeTitle}
        aria-label={backlogLabels.unknownAssigneeAriaLabel}
        className={cn(
          'flex h-6 w-6 items-center justify-center rounded-full',
          'bg-muted border border-border text-xs font-medium text-muted-foreground',
        )}
      >
        ?
      </span>
    )
  }

  // 미배정
  return (
    <span className="text-xs text-muted-foreground" aria-label="담당자 미배정">
      {backlogLabels.unassigned}
    </span>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 구현 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

function BacklogCardInner({ issue, context, sprintId, assigneeName }: BacklogCardProps) {
  const resolvedSprintId = sprintId ?? null

  const dragData: BacklogDragData = {
    issueKey: issue.key,
    context,
    sprintId: resolvedSprintId,
  }

  const dropData: BacklogCardDropData = {
    type: 'card',
    key: issue.key,
    context,
    sprintId: resolvedSprintId,
  }

  const droppableId = cardDroppableId(context, issue.key)

  const { attributes, listeners, setNodeRef: setDragRef, transform, isDragging } = useDraggable({
    id: `${context}:${issue.key}`,
    data: dragData,
  })

  const { setNodeRef: setDropRef } = useDroppable({
    id: droppableId,
    data: dropData,
  })

  // draggable과 droppable ref를 동일 요소에 합성한다
  function setRef(node: HTMLDivElement | null): void {
    setDragRef(node)
    setDropRef(node)
  }

  const style = transform
    ? { transform: CSS.Translate.toString(transform) }
    : undefined

  return (
    <div
      ref={setRef}
      style={style}
      {...listeners}
      {...attributes}
      data-drag-context={context}
      data-card-droppable={droppableId}
      aria-roledescription={backlogLabels.draggableCard}
      aria-label={backlogLabels.cardAriaLabel(issue.key, issue.summary)}
      className={cn(
        'flex flex-col gap-2 rounded-lg border border-border bg-card p-3 shadow-sm',
        'cursor-grab active:cursor-grabbing',
        isDragging && 'opacity-50',
      )}
    >
      {/* summary — 주 정보. 드래그 중 클릭 방지 */}
      <Link
        to="/issues/$key"
        params={{ key: issue.key }}
        className="line-clamp-2 text-sm font-medium leading-snug text-foreground hover:underline"
        onClick={(e) => {
          if (isDragging) {
            e.preventDefault()
          }
        }}
      >
        {issue.summary}
      </Link>

      {/* 하단 행: issueKey + 우선순위 + 담당자 */}
      <div className="flex items-center justify-between gap-2">
        <span className="text-xs text-muted-foreground">{issue.key}</span>
        <div className="flex items-center gap-1">
          <span
            aria-label={`우선순위 ${issue.priority}`}
            className="rounded px-1 py-0.5 text-xs text-muted-foreground ring-1 ring-border"
          >
            P{issue.priority}
          </span>
          <AssigneeSlot assigneeId={issue.assigneeId} assigneeName={assigneeName} />
        </div>
      </div>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// memo 래핑 — 보드 전체 재렌더 시 props 변화 없는 카드 렌더 스킵
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 백로그·스프린트 칸의 개별 이슈 카드.
 *
 * - summary를 주 정보로(2줄 truncate), issueKey와 우선순위를 보조로 표시한다.
 * - assigneeName 유무·assigneeId 유무에 따라 이니셜/미확인(?)/미배정 세 상태를 표시한다.
 * - `useDraggable`로 드래그 핸들을 제공한다. data에 context·sprintId를 담아 부모가 이동 방향을 계산한다.
 * - 카드 클릭 시 이슈 상세(`/issues/:key`)로 이동하며, 드래그 중엔 네비게이션이 막힌다.
 * - `memo`로 래핑되어 props가 변하지 않으면 재렌더하지 않는다.
 */
export const BacklogCard = memo(BacklogCardInner)
