// 보드 설정 — 끌 수 있는 워크플로우 상태 배지 (부채 177 R5 · J27)
import type { JSX } from 'react'
import { useDraggable } from '@dnd-kit/core'
import type { ColumnState } from '@/api/boards'
import { Badge } from '@/components/ui/badge'
import { cn } from '@/lib/utils'

/** DraggableState props */
export interface DraggableStateProps {
  /** 그릴 워크플로우 상태 */
  state: ColumnState
  /** 이 상태가 지금 속한 컬럼 UUID. 미매핑 패널에 있으면 `null`. */
  fromColumnId: string | null
  /** 끌 수 있는가. CREATE 권한이 없으면 false — 배지는 보이되 잡히지 않는다. */
  draggable: boolean
}

/**
 * 끌 수 있는 상태 배지.
 *
 * `useDraggable` 의 `data` 에 **출발지**를 실어 보낸다 — `onDragEnd` 가 「어디서 왔나」를 알아야
 * 컬럼 사이 이동의 「빼기 먼저」 계획을 세울 수 있다(`planStateDrop`).
 *
 * 권한이 없으면 리스너를 안 붙인다. 배지를 숨기지는 않는다 — 무엇이 어디 배정돼 있는지는
 * 읽을 수 있어야 하고, 그것이 BROWSE 로 충분한 정보다.
 */
export function DraggableState({ state, fromColumnId, draggable }: DraggableStateProps): JSX.Element {
  const { attributes, listeners, setNodeRef, isDragging } = useDraggable({
    id: `state:${state.key}`,
    disabled: !draggable,
    data: { stateKey: state.key, fromColumnId },
  })

  // ★못 끄는 배지에는 listeners 뿐 아니라 dnd-kit 의 attributes(role="button" · tabIndex=0)도
  //   달지 않는다. 달면 읽기 전용 사용자에게 **눌러도 아무 일 없는 포커스 대상**이 상태 수만큼
  //   생긴다 — Tab 을 그만큼 더 눌러야 다음 컨트롤에 닿는다(리뷰 T0-4).
  const dragProps = draggable ? { ...listeners, ...attributes } : {}

  return (
    <Badge
      ref={setNodeRef}
      variant="neutral"
      className={cn(
        'w-full justify-start font-normal',
        draggable && 'cursor-grab',
        isDragging && 'opacity-50',
      )}
      {...dragProps}
    >
      {state.name}
    </Badge>
  )
}
