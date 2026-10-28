// 보드 컬럼 끝 인라인 생성 — 만든 이슈를 그 컬럼 상태로 전환하고 활성 스프린트에 넣는다 (JB-B-1 · 결정 D2)
import type { JSX } from 'react'
import { useQueryClient } from '@tanstack/react-query'
import { toast } from 'sonner'
import type { BoardColumn } from '@/api/boards'
import { assignToSprint } from '@/api/backlog'
import { transitionIssue } from '@/api/issues'
import type { IssueResponse } from '@/api/issues'
import { invalidateIssueViews } from '@/api/issue-view-invalidation'
import { InlineCreateIssue } from '@/components/issue/InlineCreateIssue'
import { issueCreateStrings } from '@/i18n/ko'
import { boardLabels } from '@/i18n/board-labels'

interface BoardColumnInlineCreateProps {
  projectKey: string
  /** 상태가 하나 이상 매핑된 컬럼만 — 부모가 거른다 */
  column: Pick<BoardColumn, 'name' | 'states'>
  /** 스크럼 보드의 활성 스프린트. 칸반이면 null — 넣지 않으면 스크럼 보드에 카드가 안 보인다 */
  sprintId: string | null
}

/**
 * 「여기서 만들면 여기에 생긴다」를 지키는 수단 = **생성 직후 전환**(결정 D2 · ADR fr-ux-09-f3 D-3 대체).
 *
 * 초기 상태가 이미 이 컬럼에 매핑돼 있으면 전환하지 않는다. 아니면 드래그와 같은 규칙으로 컬럼의
 * **첫 상태**로 전환한다 — 워크플로 조건 · 필수 화면이 막으면 이슈는 그대로 두고 경고로 알린다.
 * 생성 API 에 초기 상태를 받게 하면 워크플로를 건너뛰는 두 번째 길이 생긴다 — 그래서 택하지 않았다.
 */
export function BoardColumnInlineCreate({ projectKey, column, sprintId }: BoardColumnInlineCreateProps): JSX.Element {
  const queryClient = useQueryClient()

  async function place(issue: IssueResponse): Promise<void> {
    const target = column.states[0]
    if (target !== undefined && !column.states.some((s) => s.key === issue.currentStateKey)) {
      await transitionIssue(issue.key, { toStatusKey: target.key, expectedVersion: issue.version }).catch(() => {
        toast.warning(issueCreateStrings.columnMoveFailed(issue.key, column.name))
      })
    }
    if (sprintId !== null) {
      await assignToSprint(sprintId, issue.key).catch(() => {
        toast.warning(issueCreateStrings.sprintAssignFailed(issue.key))
      })
    }
    await invalidateIssueViews(queryClient, issue.key)
  }

  return (
    <InlineCreateIssue
      projectKey={projectKey}
      openLabel={boardLabels.column.inlineCreate(column.name)}
      placeholder={issueCreateStrings.inlineBoardPlaceholder}
      defaultTypeKey="story"
      onCreated={(issue) => void place(issue)}
    />
  )
}
