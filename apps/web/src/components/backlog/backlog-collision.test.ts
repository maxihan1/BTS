// 백로그 충돌 감지 전략 단위 테스트 — 마우스/키보드 양쪽에서 카드 우선을 단언한다 (FR-UX-13 F15 FR-8)
import { describe, it, expect } from 'vitest'
import type {
  Active,
  ClientRect,
  CollisionDetection,
  DroppableContainer,
  UniqueIdentifier,
} from '@dnd-kit/core'
import { cardFirstCollision } from './backlog-collision'

/** `cardFirstCollision` 이 받는 인자 타입 (dnd-kit 이 별도 이름으로 export 하지 않는다) */
type CollisionArgs = Parameters<CollisionDetection>[0]

// ─────────────────────────────────────────────────────────────────────────────
// 지오메트리 — 세로 스택 화면을 축약한 좌표계
// ─────────────────────────────────────────────────────────────────────────────
//
//   y=0   ┌ sprint-s1 섹션 ────────────┐
//   y=72  │   ┌ card:ATLAS-9 ───────┐  │
//   y=144 │   └─────────────────────┘  │
//   y=160 └───────────────────────────┘
//   y=176 ┌ backlog 섹션 ──────────────┐
//   y=200 │   ┌ card:ATLAS-1 ───────┐  │
//   y=272 │   └─────────────────────┘  │
//   y=400 └───────────────────────────┘
//
// 세로 스택이라 x 축은 전폭 고정이고 y 축만 의미를 갖는다.

const WIDTH = 800

function rect(top: number, height: number): ClientRect {
  return { top, left: 0, right: WIDTH, bottom: top + height, width: WIDTH, height }
}

const SPRINT_COLUMN_ID = 'sprint-s1'
const BACKLOG_COLUMN_ID = 'backlog'
const SPRINT_CARD_ID = 'card:ATLAS-9'
const BACKLOG_CARD_ID = 'card:ATLAS-1'

const RECTS = new Map<UniqueIdentifier, ClientRect>([
  [SPRINT_COLUMN_ID, rect(0, 160)],
  [SPRINT_CARD_ID, rect(72, 72)],
  [BACKLOG_COLUMN_ID, rect(176, 224)],
  [BACKLOG_CARD_ID, rect(200, 72)],
])

function container(id: UniqueIdentifier, data: Record<string, unknown>): DroppableContainer {
  return {
    id,
    key: id,
    data: { current: data },
    disabled: false,
    node: { current: null },
    rect: { current: RECTS.get(id) ?? null },
  }
}

/** 실제 화면 등록 순서 — 칸이 먼저 등록되고 그 안의 카드가 뒤따른다 */
const CONTAINERS: DroppableContainer[] = [
  container(SPRINT_COLUMN_ID, { context: 'sprint', sprintId: 's1', orderedKeys: ['ATLAS-9'] }),
  container(SPRINT_CARD_ID, { type: 'card', key: 'ATLAS-9', context: 'sprint', sprintId: 's1' }),
  container(BACKLOG_COLUMN_ID, { context: 'backlog', orderedKeys: ['ATLAS-1'] }),
  container(BACKLOG_CARD_ID, { type: 'card', key: 'ATLAS-1', context: 'backlog', sprintId: null }),
]

const ACTIVE: Active = {
  id: 'backlog:ATLAS-2',
  data: { current: { context: 'backlog', issueKey: 'ATLAS-2' } },
  rect: { current: { initial: null, translated: null } },
}

function buildArgs(
  collisionRect: ClientRect,
  pointerCoordinates: { x: number; y: number } | null,
): CollisionArgs {
  return {
    active: ACTIVE,
    collisionRect,
    droppableRects: RECTS,
    droppableContainers: CONTAINERS,
    pointerCoordinates,
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 충돌 사각형 3종
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 아래 섹션에서 올라온 드래그 카드 (132~204).
 *
 * 칸 겹침 비율이 카드 겹침 비율보다 **크다** — 칸은 넓어서 드래그 카드를 통째로 품고
 * 카드는 12px 만 겹치기 때문이다. 즉 `rectIntersection` 을 전체 droppable 에 그냥 돌리면
 * 칸이 1등으로 나온다. 카드 우선 규칙이 살아 있어야만 카드가 먼저 나온다.
 */
const OVERLAPS_CARD = rect(132, 72)

/** 두 섹션 사이 여백 (146~198) — 어느 카드와도 겹치지 않고 칸 두 개만 스친다 */
const BETWEEN_CARDS = rect(146, 52)

/** 화면 밖 (2000~2072) — 아무 droppable 과도 겹치지 않는다 */
const OFF_SCREEN = rect(2000, 72)

function ids(collisions: ReturnType<CollisionDetection>): UniqueIdentifier[] {
  return collisions.map((collision) => collision.id)
}

// ─────────────────────────────────────────────────────────────────────────────
// 키보드 경로 — pointerCoordinates 가 null
// ─────────────────────────────────────────────────────────────────────────────

describe('cardFirstCollision — 키보드 경로 (pointerCoordinates: null)', () => {
  it('T-KB-2: 카드 droppable 이 칸보다 먼저 반환된다', () => {
    const result = cardFirstCollision(buildArgs(OVERLAPS_CARD, null))

    expect(result[0]?.id).toBe(SPRINT_CARD_ID)
  })

  it('T-KB-2: 카드가 잡히면 칸 droppable 은 결과에 섞이지 않는다', () => {
    const result = cardFirstCollision(buildArgs(OVERLAPS_CARD, null))

    expect(ids(result)).toEqual([SPRINT_CARD_ID, BACKLOG_CARD_ID])
  })

  it('겹치는 카드가 없으면 칸 droppable 로 넘어간다', () => {
    const result = cardFirstCollision(buildArgs(BETWEEN_CARDS, null))

    expect(ids(result).slice().sort()).toEqual([BACKLOG_COLUMN_ID, SPRINT_COLUMN_ID].sort())
  })

  it('아무것도 겹치지 않으면 빈 배열이다', () => {
    expect(cardFirstCollision(buildArgs(OFF_SCREEN, null))).toEqual([])
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 마우스 경로 — 회귀 방지. 아래 3건은 키보드 분기 추가 전후로 동일해야 한다
// ─────────────────────────────────────────────────────────────────────────────

describe('cardFirstCollision — 마우스 경로 (회귀 방지)', () => {
  it('포인터가 카드 위에 있으면 그 카드만 반환한다', () => {
    const result = cardFirstCollision(buildArgs(OVERLAPS_CARD, { x: 400, y: 100 }))

    expect(ids(result)).toEqual([SPRINT_CARD_ID])
  })

  it('포인터가 칸 안이지만 카드 밖이면 칸을 반환한다', () => {
    const result = cardFirstCollision(buildArgs(OVERLAPS_CARD, { x: 400, y: 30 }))

    expect(ids(result)).toEqual([SPRINT_COLUMN_ID])
  })

  it('포인터가 어느 droppable 에도 없으면 전체 rectIntersection 폴백이라 칸이 1등이다', () => {
    // 키보드 경로와 **같은 충돌 사각형**인데 결과가 다르다 — 이 대비가 분기의 존재 근거다
    const result = cardFirstCollision(buildArgs(OVERLAPS_CARD, { x: 5000, y: 5000 }))

    expect(result[0]?.id).toBe(SPRINT_COLUMN_ID)
  })
})
