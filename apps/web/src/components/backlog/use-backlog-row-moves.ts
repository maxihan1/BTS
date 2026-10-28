// 백로그 행 작업 메뉴의 「이동」 항목 — 섹션 안 순서 · 다른 스프린트 · 백로그 (JCB-17)
import { useCallback } from 'react'
import { toast } from 'sonner'
import { useAssignToSprint, useRerankIssue, useUnassignFromSprint } from '@/hooks/use-backlog'
import { backlogLabels } from '@/i18n/backlog-labels'
import type { BacklogView } from '@/api/backlog'
import { buildInListMoves } from '@/components/issue/issue-move-actions-context'
import type { IssueMoveAction, IssueMoveActionsResolver } from '@/components/issue/issue-move-actions-context'

/**
 * 백로그 화면이 {@link IssueMoveActionsContext} 에 넣을 resolver 를 만든다.
 *
 * 순서는 **필터 전 원본**(`view`)으로 셈한다 — rank 는 전역이라 필터로 숨긴 이슈도 이웃이다.
 * 섹션 안 이동은 {@link buildInListMoves}(보드 컬럼과 공용) · rerank API 를 쓴다.
 * 다른 스프린트 · 백로그로 보내기는 배정 API 만 부른다(위치는 서버 기본값).
 *
 * @param canReorderIssue false 면 resolver 가 없다 — 메뉴에 이동 항목 자체가 뜨지 않는다
 */
export function useBacklogRowMoves(
  projectKey: string,
  view: BacklogView | undefined,
  canReorderIssue: boolean,
): IssueMoveActionsResolver | null {
  // `mutate` 는 observer 에 묶인 안정 참조라 useCallback 의존성으로 안전하다
  const rerank = useRerankIssue(projectKey).mutate
  const assign = useAssignToSprint(projectKey).mutate
  const unassign = useUnassignFromSprint(projectKey).mutate

  const resolve = useCallback(
    (issueKey: string): IssueMoveAction[] => {
      if (view === undefined) return []
      const onError = { onError: () => toast.error(backlogLabels.moveFailedError) }

      const sections = [
        { sprintId: null, name: null, keys: view.backlog.map((i) => i.key) },
        ...view.sprints
          .filter((s) => s.sprint.status !== 'COMPLETED')
          .map((s) => ({ sprintId: s.sprint.sprintId, name: s.sprint.name, keys: s.issues.map((i) => i.key) })),
      ]
      const here = sections.find((s) => s.keys.includes(issueKey))
      if (here === undefined) return []

      const actions: IssueMoveAction[] = buildInListMoves(here.keys, issueKey, (body) =>
        rerank({ issueKey, body }, onError),
      )

      for (const s of sections) {
        if (s.sprintId === null || s.name === null || s === here) continue
        const sprintId = s.sprintId
        // 「에」는 받침과 무관하다 — `josaEuro` 는 숫자 끝(「스프린트 1」)에 「으로」를 붙인다
        actions.push({ label: `${s.name}에 추가`, onSelect: () => assign({ sprintId, issueKey }, onError) })
      }
      if (here.sprintId !== null) {
        const sprintId = here.sprintId
        actions.push({ label: '백로그로', onSelect: () => unassign({ sprintId, issueKey }, onError) })
      }
      return actions
    },
    [view, rerank, assign, unassign],
  )

  return canReorderIssue ? resolve : null
}
