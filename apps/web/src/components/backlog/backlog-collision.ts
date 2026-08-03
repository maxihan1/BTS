// 백로그 보드 충돌 감지 전략 — 카드 droppable 을 칸 droppable 보다 우선한다
import { pointerWithin, rectIntersection } from '@dnd-kit/core'
import type { CollisionDetection } from '@dnd-kit/core'

/**
 * 카드 droppable(type:'card')을 칸 droppable보다 우선하는 충돌 감지 전략.
 *
 * 1. 카드 droppable만 포함한 컨테이너 목록으로 pointerWithin을 먼저 시도한다.
 *    pointerWithin이 결과를 반환하면 (포인터가 카드 위에 있음) 그것을 반환한다.
 * 2. 없으면 칸 droppable만으로 pointerWithin을 시도한다.
 * 3. 여전히 없으면 rectIntersection 폴백.
 *
 * droppableContainers를 카드 전용으로 필터링해 우선순위를 보장한다.
 */
export const cardFirstCollision: CollisionDetection = (args) => {
  // 카드 droppable만 추출 (data.current.type === 'card')
  const cardContainers = args.droppableContainers.filter(
    (c) => (c.data.current as Record<string, unknown> | undefined)?.['type'] === 'card',
  )

  // 카드 droppable 대상 pointerWithin
  if (cardContainers.length > 0) {
    const cardCollisions = pointerWithin({ ...args, droppableContainers: cardContainers })
    if (cardCollisions.length > 0) return cardCollisions
  }

  // 칸 droppable 대상 pointerWithin (카드 제외)
  const columnContainers = args.droppableContainers.filter(
    (c) => (c.data.current as Record<string, unknown> | undefined)?.['type'] !== 'card',
  )
  const columnCollisions = pointerWithin({ ...args, droppableContainers: columnContainers })
  if (columnCollisions.length > 0) return columnCollisions

  return rectIntersection(args)
}
