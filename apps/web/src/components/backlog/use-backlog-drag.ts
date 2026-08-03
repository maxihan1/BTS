// 백로그 보드 드래그 처리 — 드롭 판정 · 이동/재정렬 mutation · C1 부분 실패 (FR-BL-01/02 D6/D7)
import { useState } from 'react'
import { toast } from 'sonner'
import type { DragEndEvent, DragOverEvent } from '@dnd-kit/core'
import {
  useRerankIssue,
  useAssignToSprint,
  useUnassignFromSprint,
} from '@/hooks/use-backlog'
import {
  resolveBacklogDropAction,
  extractColumnDropZone,
  extractCardDropZone,
} from '@/lib/backlog-drag'
import type { NeighborResult, DropZoneData } from '@/lib/backlog-drag'
import type { BacklogView } from '@/api/backlog'
import { backlogLabels } from '@/i18n/backlog-labels'
import type { BacklogDragData } from './BacklogCard'

/** `useBacklogDrag` 반환값 */
export interface BacklogDrag {
  /** 현재 드래그가 올라가 있는 droppable id — 하이라이트에 쓴다 */
  overDroppableId: string | null
  handleDragOver: (event: DragOverEvent) => void
  handleDragEnd: (event: DragEndEvent) => void
}

/**
 * 백로그 보드의 드래그 앤 드롭 처리.
 *
 * `BacklogBoard` 가 200줄(`DEVELOPMENT.md §2.2`)을 넘어 분리했다. 경계는 **책임**을 따랐다 —
 * 여기는 「드래그 결과를 어떤 mutation 으로 옮기나」이고, 남은 쪽은 배치와 렌더다.
 *
 * @param projectKey mutation 대상 프로젝트 키
 * @param backlogView 카드 droppable 경로에서 대상 칸의 순서를 조회할 현재 데이터
 * @param canReorderIssue UPDATE 권한 — false 면 드래그 결과를 무시한다
 */
export function useBacklogDrag(
  projectKey: string,
  backlogView: BacklogView | undefined,
  canReorderIssue: boolean,
): BacklogDrag {
  const [overDroppableId, setOverDroppableId] = useState<string | null>(null)

  const rerankIssue = useRerankIssue(projectKey)
  const assignToSprint = useAssignToSprint(projectKey)
  const unassignFromSprint = useUnassignFromSprint(projectKey)

  /**
   * C1: 이동 성공 후 rerank를 실행한다. rerank 실패 시 **경고** 토스트.
   *
   * 🛑 에러 토스트를 쓰면 안 된다 — 이동은 이미 완료됐다. 「이동이 실패했다」로 읽히면
   * 사용자가 다시 끌어 옮긴다. (이슈 생성의 스프린트 배정 부분 실패와 같은 형태다.)
   */
  function rerankAfterMove(issueKey: string, rerank: NeighborResult | undefined): void {
    if (rerank === undefined) return
    rerankIssue.mutate(
      { issueKey, body: rerank },
      { onError: () => toast.warning(backlogLabels.rerankFailedWarning) },
    )
  }

  function handleDragOver(event: DragOverEvent): void {
    setOverDroppableId(event.over ? String(event.over.id) : null)
  }

  function handleDragEnd(event: DragEndEvent): void {
    setOverDroppableId(null)

    // UPDATE 권한 없으면 드래그 결과를 무시한다
    if (!canReorderIssue) return

    const activeData = event.active.data.current as BacklogDragData | undefined
    const overData = event.over?.data.current as Record<string, unknown> | undefined

    if (activeData === undefined || event.over === null) return

    const { issueKey, context: fromContext, sprintId: fromSprintId } = activeData

    // 칸 droppable over 경로 (orderedKeys가 data에 포함된 경우)
    let dropZone: DropZoneData | null = extractColumnDropZone(overData)

    if (dropZone === null && backlogView !== undefined) {
      // 카드 droppable over 경로: 대상 칸의 orderedKeys를 board 데이터에서 콜백으로 조회한다
      dropZone = extractCardDropZone(overData, (ctx, sid) => {
        if (ctx === 'backlog') return backlogView.backlog.map((i) => i.key)
        const entry = backlogView.sprints.find((s) => s.sprint.sprintId === sid)
        return entry !== undefined ? entry.issues.map((i) => i.key) : []
      })
    }

    if (dropZone === null) return

    const action = resolveBacklogDropAction({
      issueKey,
      fromContext,
      fromSprintId,
      toContext: dropZone.context,
      toSprintId: dropZone.sprintId,
      targetKeys: dropZone.orderedKeys,
      dropIndex: dropZone.dropIndex,
    })

    if (action.kind === 'noop' || action.kind === 'noop-move') return

    if (action.kind === 'assign') {
      assignToSprint.mutate(
        { sprintId: action.sprintId, issueKey },
        {
          onSuccess: () => rerankAfterMove(issueKey, action.rerank),
          onError: () => toast.error(backlogLabels.moveFailedError),
        },
      )
      return
    }

    if (action.kind === 'unassign') {
      unassignFromSprint.mutate(
        { sprintId: action.sprintId, issueKey },
        {
          onSuccess: () => rerankAfterMove(issueKey, action.rerank),
          onError: () => toast.error(backlogLabels.moveFailedError),
        },
      )
      return
    }

    // rerank-only (S1, S5)
    rerankIssue.mutate(
      { issueKey, body: action.rerank },
      { onError: () => toast.error(backlogLabels.moveFailedError) },
    )
  }

  return { overDroppableId, handleDragOver, handleDragEnd }
}
