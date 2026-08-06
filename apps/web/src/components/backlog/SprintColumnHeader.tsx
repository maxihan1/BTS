// 스프린트 칸 헤더 — 제목 행(이름·상태·개수·생성 진입점) + 액션 슬롯(시작/완료·번다운)
import type { JSX } from 'react'
import { Link } from '@tanstack/react-router'
import { ChevronDown, ChevronRight } from 'lucide-react'
import { cn } from '@/lib/utils'
import { Button } from '@/components/ui/button'
import { backlogLabels } from '@/i18n/backlog-labels'
import { burndownLabels } from '@/i18n/burndown-labels'
import type { SprintMeta } from '@/api/backlog'
import { CreateIssueEntryButton } from '@/components/issue/CreateIssueEntryButton'

/**
 * 헤더 액션 버튼/링크 공통 베이스 클래스 (시작·완료·번다운 3종이 공유).
 *
 * 세로 스택에서는 폭이 남아 액션이 제목과 **같은 줄**에 서므로 `self-start` 를 뺐다 (F15 §시각 사양).
 * `min-h-11`(44px)은 모바일 터치 타깃 요구(NFR-6)이고 `md` 부터 원래 높이로 돌아간다.
 */
const ACTION_BASE_CLASS =
  'inline-flex min-h-11 items-center rounded px-2 py-1 text-xs font-medium transition-colors md:min-h-0'

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
  /** 이 섹션이 접혀 있는지. 헤더는 아이콘과 `aria-expanded` 만 바꾼다 (F15 FR-2) */
  collapsed: boolean
  /** 접기 토글 클릭 콜백. 접힘 상태의 소유자는 [SprintColumn] 이다 */
  onToggleCollapsed: () => void
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
 * ### 액션이 한 줄인 이유 (F15 §시각 사양)
 * 세로 스택으로 바뀌며 칸 폭이 288px 고정에서 전폭이 됐다. 폭이 남으므로 시작/완료와 번다운을
 * 제목과 **같은 줄**로 올려 헤더 높이를 줄인다 (Jira 대조 G8 해소). 좁은 화면에서는
 * `flex-wrap` 이 자연스럽게 2행을 허용한다.
 * `COMPLETED` 스프린트의 생성 진입점은 드롭이 막힌 것과 같은 기준으로 렌더하지 않는다 (스펙 E-3).
 */
export function SprintColumnHeader({
  projectKey,
  sprint,
  issueCount,
  collapsed,
  onToggleCollapsed,
  onStart,
  onComplete,
  onCreateIssue,
  canCreateIssue,
}: SprintColumnHeaderProps): JSX.Element {
  const isCompleted = sprint.status === 'COMPLETED'

  return (
    <div className="sticky top-0 z-10 flex flex-wrap items-center gap-2 rounded-t-lg bg-(--bg-neutral-solid) px-3 py-2">
      {/* 접기 토글 — 이름은 **`aria-label` 로만** 준다 (FR-2).
          `sr-only` 텍스트를 넣으면 섹션 textContent 맨 앞에 글자가 끼어
          `backlog.spec.ts` 의 `^` 앵커 정규식이 즉사한다.
          ★`aria-controls` 는 넣지 않는다 — 접히면 대상 id 가 DOM 에서 사라져
          dangling IDREF 가 된다 (스펙 §리뷰 반영 C-3). 상태는 `aria-expanded` 가 말한다.
          ★이름은 접힘/펼침으로 **바뀌지 않는다** — 섹션 A 접힘 · B 펼침이 동시에 성립해
          「같은 버튼의 다른 상태」 면제가 안 되기 때문이다 (C-9). */}
      <Button
        type="button"
        variant="ghost"
        size="icon"
        className="min-h-11 min-w-11 text-muted-foreground md:min-h-0 md:min-w-0"
        aria-label={backlogLabels.collapseSection(sprint.name)}
        aria-expanded={!collapsed}
        onClick={onToggleCollapsed}
      >
        {collapsed ? <ChevronRight /> : <ChevronDown />}
      </Button>
      <span className="text-sm font-semibold text-foreground">{sprint.name}</span>
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
      <div className="flex-1" />
      {onCreateIssue !== undefined && !isCompleted && (
        <CreateIssueEntryButton
          label={backlogLabels.createIssueInSprint(sprint.name)}
          variant="icon"
          canCreate={canCreateIssue}
          onClick={onCreateIssue}
        />
      )}

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
