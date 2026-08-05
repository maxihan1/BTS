// 백로그 세로 스택 키보드 좌표 계산기 단위 테스트 (FR-UX-13 F15 · FR-15/FR-16 · T-KB-3/T-KB-4)
import { describe, it, expect } from 'vitest'
import { KeyboardCode } from '@dnd-kit/core'
import { resolveBacklogDropAction } from './backlog-drag'
import {
  backlogKeyboardCodes,
  backlogKeyboardCoordinates,
  collectBacklogKeyboardCandidates,
  isZeroMoveDrop,
} from './backlog-keyboard-coordinates'
import type {
  BacklogDroppableSnapshot,
  BacklogRect,
} from './backlog-keyboard-coordinates'

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처 — 세로 스택 한 화면
//
//   스프린트 A (카드 2)  ─ 칸 top 100  · ATLAS-1 top 140 · ATLAS-2 top 208
//   스프린트 B (카드 0)  ─ 칸 top 340  (빈 스프린트 — 칸 자체가 후보다)
//   백로그    (카드 2)  ─ 칸 top 460  · ATLAS-3 top 500 · ATLAS-4 top 568
//   스프린트 C (접힘)    ─ rect 없음  (후보에서 빠져야 한다)
//
// 카드와 칸의 중심 x 를 400 으로 맞춰 세로 판정만 남긴다.
// ─────────────────────────────────────────────────────────────────────────────

const CARD_LEFT = 40
const CARD_WIDTH = 720
const CARD_HEIGHT = 60
const COLUMN_LEFT = 24
const COLUMN_WIDTH = 752

const SPRINT_A = 'sprint-a-uuid'
const SPRINT_B = 'sprint-b-uuid'
const SPRINT_C = 'sprint-c-uuid'

const ATLAS_1_TOP = 140
const ATLAS_2_TOP = 208
const ATLAS_3_TOP = 500
const ATLAS_4_TOP = 568
const SPRINT_B_TOP = 340
const SPRINT_B_HEIGHT = 100

function cardRect(top: number): BacklogRect {
  return { top, left: CARD_LEFT, width: CARD_WIDTH, height: CARD_HEIGHT }
}

function columnRect(top: number, height: number): BacklogRect {
  return { top, left: COLUMN_LEFT, width: COLUMN_WIDTH, height }
}

function sprintCard(key: string, sprintId: string, top: number): BacklogDroppableSnapshot {
  return {
    id: `card:sprint:${key}`,
    rect: cardRect(top),
    data: { type: 'card', key, context: 'sprint', sprintId },
  }
}

function backlogCard(key: string, top: number): BacklogDroppableSnapshot {
  return {
    id: `card:backlog:${key}`,
    rect: cardRect(top),
    data: { type: 'card', key, context: 'backlog', sprintId: null },
  }
}

const SPRINT_A_COLUMN: BacklogDroppableSnapshot = {
  id: 'sprint-A',
  rect: columnRect(100, 200),
  data: { context: 'sprint', sprintId: SPRINT_A, orderedKeys: ['ATLAS-1', 'ATLAS-2'] },
}

const EMPTY_SPRINT_B_COLUMN: BacklogDroppableSnapshot = {
  id: 'sprint-B',
  rect: columnRect(SPRINT_B_TOP, SPRINT_B_HEIGHT),
  data: { context: 'sprint', sprintId: SPRINT_B, orderedKeys: [] },
}

const BACKLOG_COLUMN: BacklogDroppableSnapshot = {
  id: 'backlog',
  rect: columnRect(460, 200),
  data: { context: 'backlog', orderedKeys: ['ATLAS-3', 'ATLAS-4'] },
}

/** 접힌 스프린트 — 카드가 렌더되지 않아 칸 rect 도 없다 */
const COLLAPSED_SPRINT_C_COLUMN: BacklogDroppableSnapshot = {
  id: 'sprint-C',
  rect: null,
  data: { context: 'sprint', sprintId: SPRINT_C, orderedKeys: [] },
}

const SNAPSHOTS: readonly BacklogDroppableSnapshot[] = [
  SPRINT_A_COLUMN,
  sprintCard('ATLAS-1', SPRINT_A, ATLAS_1_TOP),
  sprintCard('ATLAS-2', SPRINT_A, ATLAS_2_TOP),
  EMPTY_SPRINT_B_COLUMN,
  BACKLOG_COLUMN,
  backlogCard('ATLAS-3', ATLAS_3_TOP),
  backlogCard('ATLAS-4', ATLAS_4_TOP),
  COLLAPSED_SPRINT_C_COLUMN,
]

/** 빈 스프린트가 없는 화면 — 「섹션 마지막 카드 → 다음 섹션 첫 카드」를 재는 데 쓴다 */
const SNAPSHOTS_WITHOUT_EMPTY_SPRINT = SNAPSHOTS.filter((s) => s.id !== 'sprint-B')

/** 대상 rect 위에 정확히 겹치는 좌상단 좌표 (드래그 카드와 대상 카드 크기가 같을 때) */
function expectedTopLeft(targetCenterY: number): { x: number; y: number } {
  return { x: CARD_LEFT, y: targetCenterY - CARD_HEIGHT / 2 }
}

const ATLAS_2_CENTER_Y = ATLAS_2_TOP + CARD_HEIGHT / 2
const ATLAS_3_CENTER_Y = ATLAS_3_TOP + CARD_HEIGHT / 2
const SPRINT_B_CENTER_Y = SPRINT_B_TOP + SPRINT_B_HEIGHT / 2

// ─────────────────────────────────────────────────────────────────────────────
// collectBacklogKeyboardCandidates — 후보 선별
// ─────────────────────────────────────────────────────────────────────────────

describe('collectBacklogKeyboardCandidates', () => {
  it('카드 droppable 을 전부 후보로 담는다', () => {
    const ids = collectBacklogKeyboardCandidates(SNAPSHOTS).map((c) => c.id)
    expect(ids).toContain('card:sprint:ATLAS-1')
    expect(ids).toContain('card:sprint:ATLAS-2')
    expect(ids).toContain('card:backlog:ATLAS-3')
    expect(ids).toContain('card:backlog:ATLAS-4')
  })

  it('카드가 0건인 섹션은 칸 droppable 을 후보에 넣는다 — 빈 스프린트로 못 옮기면 반쪽이다', () => {
    const ids = collectBacklogKeyboardCandidates(SNAPSHOTS).map((c) => c.id)
    expect(ids).toContain('sprint-B')
  })

  it('카드가 있는 섹션의 칸 droppable 은 후보에서 뺀다 — 방향키 1회가 2회로 늘어난다', () => {
    const ids = collectBacklogKeyboardCandidates(SNAPSHOTS).map((c) => c.id)
    expect(ids).not.toContain('sprint-A')
    expect(ids).not.toContain('backlog')
  })

  it('접힌 섹션은 후보에서 뺀다 — 짝 단언으로 rect 만이 이유임을 못박는다', () => {
    const ids = collectBacklogKeyboardCandidates(SNAPSHOTS).map((c) => c.id)
    expect(ids).not.toContain('sprint-C')

    // 같은 섹션에 rect 만 주면 후보가 된다 → 위 단언이 공허하지 않다
    const expanded: BacklogDroppableSnapshot = {
      ...COLLAPSED_SPRINT_C_COLUMN,
      rect: columnRect(700, 100),
    }
    const expandedIds = collectBacklogKeyboardCandidates([expanded]).map((c) => c.id)
    expect(expandedIds).toContain('sprint-C')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// backlogKeyboardCoordinates — 세로 이동 (FR-15)
// ─────────────────────────────────────────────────────────────────────────────

describe('backlogKeyboardCoordinates — 세로 이동', () => {
  it('↓ 1회에 같은 섹션의 다음 카드 위로 간다', () => {
    const result = backlogKeyboardCoordinates({
      code: KeyboardCode.Down,
      collisionRect: cardRect(ATLAS_1_TOP),
      snapshots: SNAPSHOTS,
    })
    expect(result).toEqual(expectedTopLeft(ATLAS_2_CENTER_Y))
  })

  it('섹션 마지막 카드에서 ↓ 1회에 다음 섹션 첫 카드로 넘어간다', () => {
    const result = backlogKeyboardCoordinates({
      code: KeyboardCode.Down,
      collisionRect: cardRect(ATLAS_2_TOP),
      snapshots: SNAPSHOTS_WITHOUT_EMPTY_SPRINT,
    })
    expect(result).toEqual(expectedTopLeft(ATLAS_3_CENTER_Y))
  })

  it('빈 스프린트가 사이에 있으면 ↓ 1회에 그 스프린트로 간다', () => {
    const result = backlogKeyboardCoordinates({
      code: KeyboardCode.Down,
      collisionRect: cardRect(ATLAS_2_TOP),
      snapshots: SNAPSHOTS,
    })
    expect(result).toEqual(expectedTopLeft(SPRINT_B_CENTER_Y))
  })

  it('↑ 1회에 빈 스프린트로 되돌아간다 — ↓ 의 역방향', () => {
    const result = backlogKeyboardCoordinates({
      code: KeyboardCode.Up,
      collisionRect: cardRect(ATLAS_3_TOP),
      snapshots: SNAPSHOTS,
    })
    expect(result).toEqual(expectedTopLeft(SPRINT_B_CENTER_Y))
  })

  it('섹션 첫 카드에서 ↑ 1회에 이전 섹션 마지막 카드로 넘어간다', () => {
    const result = backlogKeyboardCoordinates({
      code: KeyboardCode.Up,
      collisionRect: cardRect(ATLAS_3_TOP),
      snapshots: SNAPSHOTS_WITHOUT_EMPTY_SPRINT,
    })
    expect(result).toEqual(expectedTopLeft(ATLAS_2_CENTER_Y))
  })

  it('맨 마지막 후보에서 ↓ 는 제자리다', () => {
    const result = backlogKeyboardCoordinates({
      code: KeyboardCode.Down,
      collisionRect: cardRect(ATLAS_4_TOP),
      snapshots: SNAPSHOTS,
    })
    expect(result).toEqual({ x: CARD_LEFT, y: ATLAS_4_TOP })
  })

  it('맨 처음 후보에서 ↑ 는 제자리다', () => {
    const result = backlogKeyboardCoordinates({
      code: KeyboardCode.Up,
      collisionRect: cardRect(ATLAS_1_TOP),
      snapshots: SNAPSHOTS,
    })
    expect(result).toEqual({ x: CARD_LEFT, y: ATLAS_1_TOP })
  })

  it('스냅샷 배열 순서를 뒤집어도 결과가 같다 — 화면 위치로만 고르므로 클라이언트 정렬이 없다', () => {
    const reversed = [...SNAPSHOTS].reverse()
    const result = backlogKeyboardCoordinates({
      code: KeyboardCode.Down,
      collisionRect: cardRect(ATLAS_1_TOP),
      snapshots: reversed,
    })
    expect(result).toEqual(expectedTopLeft(ATLAS_2_CENTER_Y))
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// backlogKeyboardCoordinates — 가로키·기타
// ─────────────────────────────────────────────────────────────────────────────

describe('backlogKeyboardCoordinates — 가로키와 예외', () => {
  it('← 와 → 는 현재 좌표를 그대로 돌려준다 — 세로 스택엔 가로 이웃이 없다', () => {
    const here = { x: CARD_LEFT, y: ATLAS_2_TOP }
    for (const code of [KeyboardCode.Left, KeyboardCode.Right]) {
      const result = backlogKeyboardCoordinates({
        code,
        collisionRect: cardRect(ATLAS_2_TOP),
        snapshots: SNAPSHOTS,
      })
      expect(result).toEqual(here)
    }
  })

  it('방향키가 아닌 키는 null 이다', () => {
    const result = backlogKeyboardCoordinates({
      code: 'KeyA',
      collisionRect: cardRect(ATLAS_2_TOP),
      snapshots: SNAPSHOTS,
    })
    expect(result).toBeNull()
  })

  it('충돌 사각형이 아직 측정되지 않았으면 null 이다', () => {
    const result = backlogKeyboardCoordinates({
      code: KeyboardCode.Down,
      collisionRect: null,
      snapshots: SNAPSHOTS,
    })
    expect(result).toBeNull()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// backlogKeyboardCodes — FR-16 / T-KB-4
// ─────────────────────────────────────────────────────────────────────────────

describe('backlogKeyboardCodes', () => {
  it('start 에서 Enter 를 뺀다 — 카드 안 이슈 링크가 Enter 를 되찾는다', () => {
    expect(backlogKeyboardCodes.start).not.toContain(KeyboardCode.Enter)
    expect(backlogKeyboardCodes.start).toContain(KeyboardCode.Space)
  })

  it('end 에서 Tab 을 뺀다 — Tab 은 드롭이 아니라 포커스 이동이어야 한다', () => {
    expect(backlogKeyboardCodes.end).not.toContain(KeyboardCode.Tab)
    expect(backlogKeyboardCodes.end).toContain(KeyboardCode.Space)
    expect(backlogKeyboardCodes.end).toContain(KeyboardCode.Esc)
  })

  it('cancel 은 Esc 뿐이다', () => {
    expect(backlogKeyboardCodes.cancel).toEqual([KeyboardCode.Esc])
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// isZeroMoveDrop — T-KB-3 (집자마자 놓으면 맨 뒤로 날아가는 문제)
// ─────────────────────────────────────────────────────────────────────────────

describe('isZeroMoveDrop', () => {
  it('방향키를 한 번도 누르지 않은 드롭(이동 0)을 잡아낸다', () => {
    expect(isZeroMoveDrop({ x: 0, y: 0 })).toBe(true)
  })

  it('한 칸이라도 움직였으면 잡지 않는다', () => {
    expect(isZeroMoveDrop({ x: 0, y: ATLAS_2_TOP - ATLAS_1_TOP })).toBe(false)
    expect(isZeroMoveDrop({ x: 12, y: 0 })).toBe(false)
  })

  it('가드가 없으면 mutation 이 1회 발생한다 — 가드가 공허하지 않음을 잰다', () => {
    // 집자마자 놓기: 자기 카드 droppable 은 disabled 라 빠지고 카드 간격이 8px 이라
    // 어느 카드와도 안 겹친다 → 칸 droppable 폴백 → dropIndex = orderedKeys.length
    const orderedKeys = ['ATLAS-1', 'ATLAS-2', 'ATLAS-3']
    const action = resolveBacklogDropAction({
      issueKey: 'ATLAS-1',
      fromContext: 'backlog',
      fromSprintId: null,
      toContext: 'backlog',
      toSprintId: null,
      targetKeys: orderedKeys,
      dropIndex: orderedKeys.length,
    })

    // 가드가 없으면 맨 뒤로 보내는 rerank 가 나간다 (mutation 1회)
    expect(action.kind).toBe('rerank')
    // 가드가 그 경로를 mutation 앞에서 끊는다 (mutation 0회)
    expect(isZeroMoveDrop({ x: 0, y: 0 })).toBe(true)
  })
})
