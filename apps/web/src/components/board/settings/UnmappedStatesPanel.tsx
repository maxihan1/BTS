// 보드 설정 — 미매핑 상태 패널 (지라 Unmapped statuses · 부채 177 R4 · J27)
import type { JSX } from 'react'
import { useDroppable } from '@dnd-kit/core'
import type { ColumnState } from '@/api/boards'
import { boardLabels } from '@/i18n/board-labels'
import { cn } from '@/lib/utils'
import { DraggableState } from './DraggableState'
import { UNMAPPED_DROP_ID } from './state-mapping-drop'

/** UnmappedStatesPanel props */
export interface UnmappedStatesPanelProps {
  /** 어느 컬럼에도 매핑되지 않은 상태 목록. 보드 상세 응답의 `unmappedStates` 를 그대로 받는다. */
  states: readonly ColumnState[]
  /** 상태를 끌 수 있는가 — CREATE 권한. 없으면 읽기만 된다. */
  draggable: boolean
}

/**
 * 미매핑 상태 패널 — 지라의 **Unmapped statuses** 에 대응한다(J27).
 *
 * 여기 있는 상태의 이슈는 **보드에 나타나지 않는다**. 보드 응답의 `unplacedCount` 가
 * 「몇 건이 빠졌나」만 세는 데 반해 이 목록은 **왜 빠졌나**를 말한다.
 *
 * ### 빈 상태를 경고로 그리지 않는다
 * 미매핑 0 건은 **정상**이다 — 모든 워크플로우 상태가 컬럼에 배정됐다는 뜻이다. 회색 「없음」이나
 * 경고색으로 그리면 사용자가 정상을 결손으로 읽는다. 이 화면의 빈 상태 3종(컬럼 0개 · 미매핑 0건 ·
 * 상태 0개 컬럼)은 **온도가 서로 다르고**, 그 구분이 스펙 §8b 가 표로 못박은 것이다.
 */
export function UnmappedStatesPanel({ states, draggable }: UnmappedStatesPanelProps): JSX.Element {
  const { setNodeRef, isOver } = useDroppable({ id: UNMAPPED_DROP_ID })

  return (
    <section
      ref={setNodeRef}
      aria-labelledby="board-settings-unmapped-heading"
      className={cn(
        'bg-muted/30 ring-foreground/10 w-64 shrink-0 rounded-lg p-4 ring-1',
        // 드롭 가능함을 보인다 — 하이라이트가 없으면 「여기 놓을 수 있나」를 시도로만 알 수 있다.
        isOver && 'ring-primary ring-2',
      )}
    >
      <h2 id="board-settings-unmapped-heading" className="text-sm font-semibold">
        {boardLabels.settings.unmappedHeading}
      </h2>
      <p className="text-muted-foreground mt-1 text-xs">
        {boardLabels.settings.unmappedDescription}
      </p>

      {states.length === 0 ? (
        // 안심 문구다. `text-muted-foreground` 로 두되 경고색을 쓰지 않는다.
        <p className="text-muted-foreground mt-4 text-sm">{boardLabels.settings.unmappedEmpty}</p>
      ) : (
        <ul className="mt-4 flex flex-col gap-2">
          {states.map((state) => (
            <li key={state.key}>
              <DraggableState state={state} fromColumnId={null} draggable={draggable} />
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
