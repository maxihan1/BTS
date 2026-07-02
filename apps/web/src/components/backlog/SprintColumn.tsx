// 스프린트 칸 컴포넌트 — 헤더(name+status)·드롭 영역·시작/완료 버튼 슬롯·번다운 링크 (FR-BL-02 D6/D7, FR-RP-01 D6/D7)
import { memo } from 'react'
import { useDroppable } from '@dnd-kit/core'
import { Link } from '@tanstack/react-router'
import { cn } from '@/lib/utils'
import { backlogLabels } from '@/i18n/backlog-labels'
import { burndownLabels } from '@/i18n/burndown-labels'
import type { BacklogIssue, SprintMeta } from '@/api/backlog'
import { BacklogCard } from './BacklogCard'

// ─────────────────────────────────────────────────────────────────────────────
// Props
// ─────────────────────────────────────────────────────────────────────────────

/** SprintColumn 컴포넌트 Props */
export interface SprintColumnProps {
  /** 소속 프로젝트 키 — 번다운 링크 경로 params에 사용 */
  projectKey: string
  /** 스프린트 메타 정보 */
  sprint: SprintMeta
  /** rank 순으로 정렬된 스프린트 이슈 목록 (부모가 정렬해 전달) */
  issues: BacklogIssue[]
  /**
   * 이슈 키 → 담당자 표시 이름 맵.
   * 맵에 없는 키는 assigneeId 유무에 따라 미확인/미배정으로 fallback한다.
   */
  assigneeNames: Map<string, string>
  /** 드래그 카드가 이 칸 위에 있는지 여부. 하이라이트에 사용 */
  isOver?: boolean
  /**
   * PLANNED 상태 시 표시하는 "스프린트 시작" 버튼 콜백.
   * undefined이면 버튼이 렌더되지 않는 게 아니라 클릭 시 아무 동작도 하지 않는다.
   * 버튼 자체는 Task 8 부모가 구체 동작을 주입한다.
   */
  onStart?: () => void
  /**
   * ACTIVE 상태 시 표시하는 "스프린트 완료" 버튼 콜백.
   * Task 8 부모가 주입한다.
   */
  onComplete?: () => void
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 — 상태 배지
// ─────────────────────────────────────────────────────────────────────────────

/** 스프린트 상태에 따른 배지 색상 클래스를 반환한다. */
function statusBadgeClass(status: string): string {
  switch (status) {
    case 'ACTIVE':
      return 'bg-green-100 text-green-800 dark:bg-green-900 dark:text-green-200'
    case 'COMPLETED':
      return 'bg-muted text-muted-foreground'
    default:
      // PLANNED
      return 'bg-blue-100 text-blue-800 dark:bg-blue-900 dark:text-blue-200'
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 내부 구현 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

function SprintColumnInner({
  projectKey,
  sprint,
  issues,
  assigneeNames,
  isOver = false,
  onStart,
  onComplete,
}: SprintColumnProps) {
  const isCompleted = sprint.status === 'COMPLETED'
  const droppableId = `sprint-${sprint.sprintId}`
  const orderedKeys = issues.map((i) => i.key)

  const { setNodeRef } = useDroppable({
    id: droppableId,
    disabled: isCompleted,
    data: { context: 'sprint', sprintId: sprint.sprintId, orderedKeys },
  })

  const issueCount = issues.length

  return (
    <div
      className="flex min-w-72 w-72 flex-col gap-2"
      role="region"
      aria-label={backlogLabels.columnAriaLabel(sprint.name, issueCount)}
    >
      {/* 헤더 — 스프린트 이름 + 상태 배지 + 카드 수 + 액션 버튼 */}
      <div className="sticky top-0 z-10 flex flex-col gap-1 rounded-t-lg bg-muted px-3 py-2">
        <div className="flex items-center gap-2">
          <span className="flex-1 text-sm font-semibold text-foreground">{sprint.name}</span>
          <span
            className={cn(
              'rounded-sm px-1.5 py-0.5 text-xs font-medium',
              statusBadgeClass(sprint.status),
            )}
            aria-label={`스프린트 상태: ${sprint.status}`}
          >
            {sprint.status}
          </span>
          <span
            className="rounded-sm bg-background px-1.5 py-0.5 text-xs text-muted-foreground"
            aria-label={`이슈 ${issueCount}개`}
          >
            {issueCount}
          </span>
        </div>

        {/* 버튼 슬롯 — PLANNED이면 시작, ACTIVE이면 완료, COMPLETED이면 없음 */}
        {sprint.status === 'PLANNED' && (
          <button
            type="button"
            onClick={onStart}
            className={cn(
              'self-start rounded px-2 py-1 text-xs font-medium',
              'bg-primary text-primary-foreground hover:bg-primary/90',
              'transition-colors',
            )}
            aria-label={backlogLabels.startSprint}
          >
            {backlogLabels.startSprint}
          </button>
        )}
        {sprint.status === 'ACTIVE' && (
          <button
            type="button"
            onClick={onComplete}
            className={cn(
              'self-start rounded px-2 py-1 text-xs font-medium',
              'bg-green-600 text-white hover:bg-green-700',
              'transition-colors',
            )}
            aria-label={backlogLabels.completeSprint}
          >
            {backlogLabels.completeSprint}
          </button>
        )}

        {/* 번다운 진입 링크 — 스프린트 상태와 무관하게 항상 표시 (FR-RP-01 D6/D7) */}
        <Link
          to="/projects/$projectKey/sprints/$sprintId/burndown"
          params={{ projectKey, sprintId: sprint.sprintId }}
          className={cn(
            'self-start rounded px-2 py-1 text-xs font-medium',
            'bg-secondary text-secondary-foreground hover:bg-secondary/80',
            'transition-colors',
          )}
          aria-label={`${sprint.name} ${burndownLabels.toggle.burndown} 보기`}
        >
          {burndownLabels.toggle.burndown}
        </Link>
      </div>

      {/* 드롭 영역 + 카드 목록 */}
      <div
        ref={setNodeRef}
        data-droppable={isCompleted ? undefined : droppableId}
        data-droppable-disabled={isCompleted ? 'true' : undefined}
        data-ordered-keys={isCompleted ? undefined : orderedKeys.join(',')}
        className={cn(
          'flex flex-1 flex-col gap-2 rounded-b-lg border border-border p-2 transition-colors',
          isOver && !isCompleted && 'bg-accent ring-2 ring-primary',
          isCompleted && 'bg-muted/50 opacity-75',
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
              context="sprint"
              sprintId={sprint.sprintId}
              assigneeName={assigneeNames.get(issue.key)}
            />
          ))
        )}
      </div>
    </div>
  )
}

// ─────────────────────────────────────────────────────────────────────────────
// memo 래핑
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 스프린트 이슈 칸.
 *
 * - 헤더에 스프린트 이름·상태 배지·이슈 수를 표시한다.
 * - PLANNED이면 "스프린트 시작" 버튼 슬롯, ACTIVE이면 "스프린트 완료" 버튼 슬롯을 표시한다.
 *   버튼 동작은 props 콜백으로 주입된다 (Task 8 부모 담당).
 * - 상태와 무관하게 "번다운" 링크를 항상 표시한다.
 *   `/projects/{projectKey}/sprints/{sprintId}/burndown`로 이동한다 (FR-RP-01 D6/D7).
 * - `useDroppable`로 droppable id=`sprint-{sprintId}` 영역을 제공한다.
 * - COMPLETED 상태이면 `useDroppable`의 disabled=true로 드롭을 거부하고 시각적으로 비활성 처리한다.
 * - `isOver=true`이면 ring-2 하이라이트를 적용한다 (COMPLETED이면 무시).
 * - `memo`로 래핑되어 props가 변하지 않으면 재렌더하지 않는다.
 */
export const SprintColumn = memo(SprintColumnInner)
