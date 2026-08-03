// 스프린트 칸 헤더 — 제목 행(이름·상태·개수·생성 진입점) + 액션 슬롯(시작/완료·번다운)
import type { JSX } from 'react'
import { Link } from '@tanstack/react-router'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import { backlogLabels } from '@/i18n/backlog-labels'
import { burndownLabels } from '@/i18n/burndown-labels'
import type { SprintMeta } from '@/api/backlog'
import { CreateIssueEntryButton } from '@/components/issue/CreateIssueEntryButton'

/** 헤더 액션 버튼/링크 공통 베이스 클래스 (시작·완료·번다운 3종이 공유) */
const ACTION_BASE_CLASS = 'self-start rounded px-2 py-1 text-xs font-medium transition-colors'

/** 스프린트 상태에 따른 배지 색상 클래스를 반환한다. */
function statusBadgeClass(status: string): string {
  switch (status) {
    case 'ACTIVE':
      return 'bg-success/10 text-success-text'
    case 'COMPLETED':
      return 'bg-muted text-muted-foreground'
    default:
      // PLANNED
      return 'bg-info/10 text-info-text'
  }
}

/** SprintColumnHeader Props — 의미는 [SprintColumn] 의 같은 이름 prop 과 동일하다. */
export interface SprintColumnHeaderProps {
  projectKey: string
  sprint: SprintMeta
  issueCount: number
  onStart?: () => void
  onComplete?: () => void
  onCreateIssue?: () => void
  canCreateIssue: boolean
}

/**
 * 스프린트 칸 헤더.
 *
 * ### 왜 분리했나
 * `SprintColumn` 이 200줄(`DEVELOPMENT.md §2.2`)을 넘어서였다. 경계는 **화면의 경계**를 따랐다 —
 * 여기는 sticky 헤더 한 덩어리고, 남은 쪽은 드롭 영역과 카드 목록이다
 * (F2 의 `components/issue/create/` 분할과 같은 기준).
 *
 * ### 진입점이 제목 행에 있는 이유
 * 이 헤더는 시작/완료 버튼과 번다운 링크가 **세로로 쌓인다**. 진입점을 그 스택에 3번째로
 * 얹으면 헤더가 계속 길어지므로 제목 행(이름·상태·개수)에 아이콘으로 넣는다 (design 리뷰 DR-2).
 * `COMPLETED` 는 드롭이 막힌 것과 같은 기준으로 렌더하지 않는다 (스펙 E-3).
 */
export function SprintColumnHeader({
  projectKey,
  sprint,
  issueCount,
  onStart,
  onComplete,
  onCreateIssue,
  canCreateIssue,
}: SprintColumnHeaderProps): JSX.Element {
  const isCompleted = sprint.status === 'COMPLETED'

  return (
    <div className="sticky top-0 z-10 flex flex-col gap-1 rounded-t-lg bg-(--bg-neutral-solid) px-3 py-2">
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
        {onCreateIssue !== undefined && !isCompleted && (
          <CreateIssueEntryButton
            label={backlogLabels.createIssueInSprint(sprint.name)}
            variant="icon"
            canCreate={canCreateIssue}
            onClick={onCreateIssue}
          />
        )}
      </div>

      {/* 버튼 슬롯 — PLANNED이면 시작, ACTIVE이면 완료, COMPLETED이면 없음 */}
      {sprint.status === 'PLANNED' && (
        <Button
          type="button"
          variant="default"
          size="xs"
          onClick={onStart}
          className={cn(ACTION_BASE_CLASS, 'hover:bg-primary/90')}
          aria-label={backlogLabels.startSprint}
        >
          {backlogLabels.startSprint}
        </Button>
      )}
      {sprint.status === 'ACTIVE' && (
        // PR22 — success 는 Button variant 에 없다. variant="default" 의 bg-primary/
        // text-primary-foreground 를 className 의 bg-success/text-success-foreground 가
        // tailwind-merge 로 덮는다. hover 는 default variant 가 [a]: 로만 주므로 직접 남긴다.
        <Button
          type="button"
          variant="default"
          size="xs"
          onClick={onComplete}
          className={cn(ACTION_BASE_CLASS, 'bg-success text-success-foreground hover:bg-success/90')}
          aria-label={backlogLabels.completeSprint}
        >
          {backlogLabels.completeSprint}
        </Button>
      )}

      {/* 번다운 진입 링크 — 스프린트 상태와 무관하게 항상 표시 (FR-RP-01 D6/D7) */}
      <Link
        to="/projects/$projectKey/sprints/$sprintId/burndown"
        params={{ projectKey, sprintId: sprint.sprintId }}
        className={cn(ACTION_BASE_CLASS, 'bg-secondary text-secondary-foreground hover:bg-secondary/80')}
        aria-label={`${sprint.name} ${burndownLabels.toggle.burndown} 보기`}
      >
        {burndownLabels.toggle.burndown}
      </Link>
    </div>
  )
}
