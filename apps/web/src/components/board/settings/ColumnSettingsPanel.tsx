// 보드 설정 — Columns 탭 본문 (컬럼 가로 배치 + 미매핑 패널 + 상태 매핑 드래그) (부채 177 R4·R5)
import type { JSX } from 'react'
import { DndContext } from '@dnd-kit/core'
import type { DragEndEvent } from '@dnd-kit/core'
import { toast } from 'sonner'
import type { BoardDetail } from '@/api/boards'
import { boardLabels } from '@/i18n/board-labels'
import { EmptyState } from '@/components/ui/empty-state'
import { useBoardDragSensors } from '../board-drag-sensors'
import { useReplaceColumnStates, isStateConflict } from '@/hooks/use-replace-column-states'
import { ColumnSettingsCard } from './ColumnSettingsCard'
import { UnmappedStatesPanel } from './UnmappedStatesPanel'
import { planStateDrop } from './state-mapping-drop'

/** ColumnSettingsPanel props */
export interface ColumnSettingsPanelProps {
  /** 보드 상세. `GET /boards/{id}` 응답을 그대로 받는다 — 설정 전용 조회 API 를 만들지 않았다. */
  board: BoardDetail
  /** 편집 권한 (CREATE). 없으면 드래그가 잠긴다 — 배지는 그대로 보인다(S7). */
  canConfigure: boolean
}

/**
 * 지라 Board settings 의 **Columns 탭** 본문.
 *
 * 컬럼을 `displayOrder` 순으로 가로 배치하고 오른쪽에 미매핑 상태 패널을 둔다 — 지라와 같은
 * 배치이고, 그래야 「미매핑에서 컬럼으로 끌어다 놓는다」(J27)가 한 화면 안에서 성립한다.
 *
 * ### 반응형은 보드 화면의 검증된 패턴을 승계한다
 * `flex gap-4 overflow-x-auto` — `KanbanBoard.tsx` 와 같다. **모바일 분기를 새로 만들지
 * 않는다**(보드 화면도 안 만든다). 두 화면이 갈리면 컬럼 배치라는 같은 문제의 답이 두 개가 된다.
 *
 * ### 센서는 공유 훅에서 온다
 * `useBoardDragSensors()` 가 포인터·키보드 센서를 함께 건다. 여기서 `PointerSensor` 만 직접
 * 걸면 **키보드 사용자에게 이 기능이 통째로 사라지는데 유닛 테스트는 전부 초록**이다 —
 * 그 자리를 없애려고 구성을 함수 하나로 뽑았다(`board-drag-sensors.ts`).
 *
 * ### 조회 API 를 새로 만들지 않았다
 * `unmappedStates` 는 #444 가 이미 보드 상세 응답에 실었다. 다만 그 응답은 카드도 함께 실어
 * 최대 1000장을 끌어온다 — 그 비용은 NFR **N8** 로 등재했다(이 PR 범위 밖).
 */
export function ColumnSettingsPanel({ board, canConfigure }: ColumnSettingsPanelProps): JSX.Element {
  const sensors = useBoardDragSensors()
  const replaceStates = useReplaceColumnStates(board.boardId)

  function handleDragEnd(event: DragEndEvent): void {
    const data = event.active.data.current as
      | { stateKey?: string; fromColumnId?: string | null }
      | undefined
    const stateKey = data?.stateKey
    if (stateKey === undefined) return

    const plan = planStateDrop(
      stateKey,
      data?.fromColumnId ?? null,
      event.over === null ? null : String(event.over.id),
      board.columns,
    )
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

  if (board.columns.length === 0) {
    // 행동 유도가 필요한 빈 상태다 — 미매핑 0건의 「안심」과 온도가 다르다(스펙 §8b).
    // 추가 버튼은 후속 task 가 이 자리에 붙인다.
    return <EmptyState title={boardLabels.settings.columnsEmpty} />
  }

  return (
    <DndContext sensors={sensors} onDragEnd={handleDragEnd}>
      <div className="flex gap-4 overflow-x-auto pb-4">
        {board.columns.map((column) => (
          <ColumnSettingsCard
            key={column.columnId}
            column={column}
            truncated={board.truncated}
            draggable={canConfigure}
          />
        ))}
        <UnmappedStatesPanel states={board.unmappedStates} draggable={canConfigure} />
      </div>
    </DndContext>
  )
}
