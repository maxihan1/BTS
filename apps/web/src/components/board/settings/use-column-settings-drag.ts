// 보드 설정의 드래그 오케스트레이션 — 두 축(상태 매핑 · 컬럼 순서)을 한 핸들러에서 가른다 (부채 177)
import type { DragEndEvent } from '@dnd-kit/core'
import { toast } from 'sonner'
import type { BoardDetail } from '@/api/boards'
import { boardLabels } from '@/i18n/board-labels'
import { useReplaceColumnStates, isStateConflict } from '@/hooks/use-replace-column-states'
import { useReorderColumns } from '@/hooks/use-update-column'
import { planStateDrop, planColumnReorder } from './state-mapping-drop'

/**
 * 드래그 종료를 처리하는 핸들러를 만든다.
 *
 * ### 왜 훅으로 뺐나
 * `ColumnSettingsPanel` 이 200줄 래칫을 넘었고, 그 래칫의 처방은 「베이스라인에 추가」가 아니라
 * **함수를 쪼개는 것**이다. 드래그 오케스트레이션은 그 컴포넌트에서 가장 크고 가장 응집된
 * 덩어리라 여기로 나왔다 — 렌더와 무관하고, 순수 판정 함수 둘(`planStateDrop`·`planColumnReorder`)을
 * 서버 호출에 잇는 일만 한다.
 *
 * ### 두 축을 한 컨텍스트에서 가른다
 * plan 은 「dnd context 를 분리한다」고 적었으나 `DndContext` 중첩은 이벤트가 바깥까지 올라가
 * 어느 쪽이 처리했는지 모호해진다. 대신 끌리는 쪽이 `data.kind` 를 싣고 여기서 분기한다 —
 * 「상태를 컬럼 헤더에 떨어뜨리는」 잘못된 조합은 `kind` 가 갈라 주므로 컨텍스트를 나눌 이유가 없다.
 *
 * ### in-flight 중에는 드래그를 잠근다 (스펙 E8)
 * 드롭 하나가 서버에 가 있는 동안 두 번째 드롭을 허용하면, 둘 다 **같은 낡은 집합**에서
 * 파생돼 나중 것이 앞 변경을 덮는다(lost update). 서버는 둘 다 200 이고 아무도 오류를 못 본다.
 * 요청을 큐에 쌓는 대신 **드롭 자체를 막는다** — 큐는 「무엇이 반영됐나」를 더 어렵게 만들고,
 * 무효화 재조회가 끝나면 잠금이 저절로 풀리므로 체감 지연이 한 왕복뿐이다.
 * 그래서 이 훅은 [isMutating] 을 함께 돌려주고 호출부가 그것으로 `draggable` 을 끈다.
 *
 * @param board 현재 보드 상세. 드롭 계획이 현재 컬럼·상태 배치를 읽는다.
 * @returns `onDragEnd` 핸들러와 in-flight 여부.
 */
export function useColumnSettingsDrag(board: BoardDetail): {
  handleDragEnd: (event: DragEndEvent) => void
  isMutating: boolean
} {
  const replaceStates = useReplaceColumnStates(board.boardId)
  const reorder = useReorderColumns(board.boardId)

  function handleDragEnd(event: DragEndEvent): void {
    const data = event.active.data.current as
      | { kind?: string; stateKey?: string; fromColumnId?: string | null; columnId?: string }
      | undefined
    const overId = event.over === null ? null : String(event.over.id)

    if (data?.kind === 'column' && data.columnId !== undefined) {
      const next = planColumnReorder(data.columnId, overId, board.columns)
      if (next === null) return
      reorder.mutate(next, {
        onError: () => {
          toast.error(boardLabels.settings.reorderFailed)
        },
      })
      return
    }

    const stateKey = data?.stateKey
    if (stateKey === undefined) return

    const plan = planStateDrop(stateKey, data?.fromColumnId ?? null, overId, board.columns)
    if (plan.changes.length === 0) return

    replaceStates.mutate(plan, {
      // ★모든 실패에서 문구를 낸다. 409 만 갈라 쓰고 나머지는 공통 문구다 — 훅이
      //   무효화로 화면을 되돌리므로 여기서 할 일은 「왜」를 말하는 것뿐이다(G2 · E3).
      onError: (error: unknown) => {
        toast.error(
          isStateConflict(error)
            ? boardLabels.settings.stateConflict
            : boardLabels.settings.stateChangeFailed,
        )
      },
    })
  }

  return { handleDragEnd, isMutating: replaceStates.isPending || reorder.isPending }
}
