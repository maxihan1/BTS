// 백로그 보드 충돌 감지 전략 — 카드 droppable 을 칸 droppable 보다 우선한다
import { pointerWithin, rectIntersection } from '@dnd-kit/core'
import type { CollisionDetection, DroppableContainer } from '@dnd-kit/core'

/** 카드 droppable 인지 판별한다 — 칸 droppable 의 data 에는 `type` 이 없다 */
function isCardContainer(container: DroppableContainer): boolean {
  return (container.data.current as Record<string, unknown> | undefined)?.['type'] === 'card'
}

/**
 * 카드 droppable(type:'card')을 칸 droppable보다 우선하는 충돌 감지 전략.
 *
 * 우선순위 단계는 포인터·키보드 **양쪽에서 동일**하다.
 * 1. 카드 droppable만으로 판정한다. 결과가 있으면 그것을 반환한다.
 * 2. 없으면 칸 droppable만으로 판정한다.
 * 3. 여전히 없으면 전체 droppable 대상 rectIntersection 폴백.
 *
 * 입력에 따라 달라지는 것은 **판정 알고리즘 하나뿐**이다.
 * - 포인터(마우스·터치) — `pointerWithin`. 포인터가 실제로 올라간 대상이 가장 정확하다.
 * - 키보드 — `rectIntersection`. 키보드 드래그에는 포인터 좌표가 없고 `pointerWithin`은
 *   좌표가 없으면 무조건 빈 배열을 반환하기 때문이다
 *   (@dnd-kit/core@6.3.1 core.esm.js `if (!pointerCoordinates) return []`).
 *   알고리즘을 바꾸지 않으면 1·2단계가 통째로 건너뛰어지고 3단계 폴백만 남아
 *   **키보드에서만 카드 우선 규칙이 소멸한다** — 칸은 넓어서 드래그 카드를 통째로 품는 반면
 *   카드끼리는 일부만 겹치므로 겹침 비율이 큰 칸이 1등으로 잡힌다.
 */
export const cardFirstCollision: CollisionDetection = (args) => {
  const detect: CollisionDetection =
    args.pointerCoordinates == null ? rectIntersection : pointerWithin

  const cardCollisions = detect({
    ...args,
    droppableContainers: args.droppableContainers.filter(isCardContainer),
  })
  if (cardCollisions.length > 0) return cardCollisions

  const columnCollisions = detect({
    ...args,
    droppableContainers: args.droppableContainers.filter((c) => !isCardContainer(c)),
  })
  if (columnCollisions.length > 0) return columnCollisions

  return rectIntersection(args)
}
