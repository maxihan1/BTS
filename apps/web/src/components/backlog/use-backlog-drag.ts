// 백로그 보드 드래그 처리 — 드롭 판정 · 이동/재정렬 mutation · C1 부분 실패 (FR-BL-01/02 D6/D7)
import { useCallback, useRef, useState } from 'react'
import { toast } from 'sonner'
import type { DragEndEvent, DragOverEvent } from '@dnd-kit/core'
import {
  useRerankIssue,
  useAssignToSprint,
  useUnassignFromSprint,
} from '@/hooks/use-backlog'
import {
  resolveBacklogDropAction,
  resolveOverToDropZone,
} from '@/lib/backlog-drag'
import type { BacklogDropAction, NeighborResult } from '@/lib/backlog-drag'
import { isZeroMoveDrop } from '@/lib/backlog-keyboard-coordinates'
import type { BacklogView } from '@/api/backlog'
import { backlogLabels } from '@/i18n/backlog-labels'
import type { BacklogDragData } from './BacklogCard'

/** `useBacklogDrag` 반환값 */
export interface BacklogDrag {
  /** 현재 드래그가 올라가 있는 droppable id — 하이라이트에 쓴다 */
  overDroppableId: string | null
  handleDragOver: (event: DragOverEvent) => void
  handleDragEnd: (event: DragEndEvent) => void
  /**
   * 방금 끝난 드롭의 이동량이 0이었는가 — 드래그 공지에 넘길 **전달 통로**다 (T12).
   *
   * dnd-kit 의 `Announcements.onDragEnd` 는 `{active, over}` 만 받고 delta 가 없다
   * (`@dnd-kit/core@6.3.1` `dist/components/Accessibility/types.d.ts:10`). 그래서 여기서 한 번만
   * 판정하고 그 **결과 자체**를 넘긴다 — 공지가 delta 로 다시 판정하면 두 답이 갈라질 수 있다.
   *
   * 참조는 렌더 사이에 바뀌지 않는다. 공지 빌더의 `useMemo` 를 매 렌더 무효화하지 않기 위함이다.
   */
  wasLastDropZeroMove: () => boolean
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

  // 마지막 드롭의 이동 0 판정. state 가 아니라 ref 인 이유는 이 값이 **화면을 바꾸지 않고**,
  // 공지는 드롭과 같은 배치 안에서 곧바로 읽히기 때문이다 — 리렌더를 기다릴 수 없다.
  const lastDropZeroMoveRef = useRef(false)

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

  /** 판정된 액션을 해당 mutation 으로 보낸다. 판정과 발사를 분리해 둘 다 30줄 아래로 유지한다 */
  function dispatchDropAction(action: BacklogDropAction, issueKey: string): void {
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

  function handleDragEnd(event: DragEndEvent): void {
    setOverDroppableId(null)

    // 집자마자 그대로 놓은 드롭(이동 0)의 판정. **여기서 한 번만** 하고 결과를 남긴다 —
    // 공지가 같은 값을 읽어야 「mutation 은 0건인데 순서를 변경했다고 낭독」이 재발하지 않는다.
    // 키보드(Space→Space)뿐 아니라 마우스(5px 임계를 넘겼다가 원위치로 돌아와 놓기)도 같다.
    const isZeroMove = isZeroMoveDrop(event.delta)
    lastDropZeroMoveRef.current = isZeroMove

    // UPDATE 권한 없으면 드래그 결과를 무시한다
    if (!canReorderIssue) return

    const activeData = event.active.data.current as BacklogDragData | undefined

    if (activeData === undefined || event.over === null) return

    const { issueKey, context: fromContext, sprintId: fromSprintId } = activeData

    // 칸/카드 droppable 양쪽의 입력 구성과 이동 0 판정은 `lib/backlog-drag` 로 공용화돼 있다 —
    // 드래그 공지 모듈이 같은 함수를 써야 공지와 mutation 이 어긋나지 않는다 (C-5 · T12).
    const dropZone = resolveOverToDropZone(backlogView, event.over, isZeroMove)

    if (dropZone === null) return

    dispatchDropAction(
      resolveBacklogDropAction({
        issueKey,
        fromContext,
        fromSprintId,
        toContext: dropZone.context,
        toSprintId: dropZone.sprintId,
        targetKeys: dropZone.orderedKeys,
        dropIndex: dropZone.dropIndex,
      }),
      issueKey,
    )
  }

  // 참조를 고정한다 — 공지 빌더의 `useMemo` 의존성이라 매 렌더 바뀌면 공지가 매번 새로 만들어진다.
  const wasLastDropZeroMove = useCallback(() => lastDropZeroMoveRef.current, [])

  return { overDroppableId, handleDragOver, handleDragEnd, wasLastDropZeroMove }
}
