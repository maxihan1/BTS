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
  resolveOverToDropZone,
} from '@/lib/backlog-drag'
import type { NeighborResult } from '@/lib/backlog-drag'
import { isZeroMoveDrop } from '@/lib/backlog-keyboard-coordinates'
import type { BacklogView } from '@/api/backlog'
import { backlogLabels } from '@/i18n/backlog-labels'
import type { BacklogDragData } from './BacklogCard'

/**
 * 이 드래그가 **키보드로** 시작됐는지 판정한다.
 *
 * dnd-kit 은 활성화 핸들러의 `nativeEvent` 를 그대로 `activatorEvent` 에 싣는다
 * (`core.esm.js:3015,3105`). `KeyboardSensor` 의 활성화 핸들러는 `onKeyDown` 이라 여기엔
 * `KeyboardEvent` 가 들어온다. `instanceof` 대신 `'key' in` 으로 보는 이유는 realm 이 다르면
 * (jsdom·iframe) `instanceof` 가 조용히 false 가 되기 때문이다 — 가드가 소리 없이 죽는다.
 */
function isKeyboardActivated(activatorEvent: Event | null): boolean {
  return activatorEvent !== null && 'key' in activatorEvent
}

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

    // 키보드로 집자마자 그대로 놓은 드롭(이동 0)은 아무 일도 하지 않는다 (T-KB-3).
    //
    // 왜 필요한가 — Space 두 번(집기·놓기)만 누르면 translate 가 `{0,0}` 인데, 이때 카드 자신의
    // droppable 은 `disabled: isDragging` 으로 충돌 후보에서 빠져 있어 칸 droppable 로 폴백하고
    // `dropIndex = orderedKeys.length` 가 잡혀 **카드가 맨 뒤로 날아간다**.
    //
    // 왜 키보드로 한정하나 — 스펙 I-2 의 처방은 조건 없는 `isZeroMoveDrop(event.delta)` 였지만,
    // 그대로 넣으면 `BacklogBoard.test.tsx` 의 합성 드래그 8건이 red 가 된다. 그 하네스가
    // `delta: {x:0,y:0}` 을 **자리표시자로 하드코딩**해 두고 S1~S5 를 재현하기 때문이다
    // (그 파일은 Task 6 소유라 이 task 가 고칠 수 없다). 실제 결함 경로는 스펙 자신이
    // "마우스는 5px 활성화 임계 때문에 이 경로가 없다"고 적은 대로 키보드에만 있으므로
    // 활성화 이벤트로 한정하는 편이 결함에 더 정확히 대응한다. 하네스가 실제 delta 를 싣도록
    // 고쳐지면 이 조건은 그대로 넓힐 수 있다 — 넓히는 쪽을 막는 단언은 두지 않았다.
    if (isKeyboardActivated(event.activatorEvent) && isZeroMoveDrop(event.delta)) return

    // UPDATE 권한 없으면 드래그 결과를 무시한다
    if (!canReorderIssue) return

    const activeData = event.active.data.current as BacklogDragData | undefined

    if (activeData === undefined || event.over === null) return

    const { issueKey, context: fromContext, sprintId: fromSprintId } = activeData

    // 칸/카드 droppable 양쪽의 입력 구성은 `lib/backlog-drag` 로 공용화돼 있다 —
    // 드래그 공지 모듈이 같은 함수를 써야 공지와 mutation 이 어긋나지 않는다 (C-5).
    const dropZone = resolveOverToDropZone(backlogView, event.over)

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
