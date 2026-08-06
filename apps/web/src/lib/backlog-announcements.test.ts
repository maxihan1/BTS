// 백로그 드래그 공지 빌더 단위 테스트 (FR-UX-13 F15 · FR-9 · FR-17 · T-KB-1/T-KB-5)
import { describe, it, expect } from 'vitest'
import { defaultScreenReaderInstructions } from '@dnd-kit/core'
import type { Active, Announcements, ClientRect, Over } from '@dnd-kit/core'
import type { BacklogIssue, BacklogView, SprintMeta } from '@/api/backlog'
import type { BacklogCardContext, BacklogDragData } from '@/components/backlog/BacklogCard'
import { backlogLabels } from '@/i18n/backlog-labels'
import { cardDroppableId } from './backlog-drag'
import {
  buildBacklogAnnouncements,
  backlogScreenReaderInstructions,
} from './backlog-announcements'

const announce = backlogLabels.announce

// ─────────────────────────────────────────────────────────────────────────────
// 픽스처 — 세로 스택 한 화면
//
//   스프린트 A  ATLAS-4 · ATLAS-5
//   스프린트 B  ATLAS-9            ← 카드 1장. 같은 칸에 다시 놓으면 noop-move (E1)
//   백로그      ATLAS-1 · ATLAS-2 · ATLAS-3
// ─────────────────────────────────────────────────────────────────────────────

const SPRINT_A_ID = 'sprint-a-uuid'
const SPRINT_A_NAME = '스프린트 A'
const SPRINT_B_ID = 'sprint-b-uuid'
const SPRINT_B_NAME = '스프린트 B'

const BACKLOG_KEYS = ['ATLAS-1', 'ATLAS-2', 'ATLAS-3']
const SPRINT_A_KEYS = ['ATLAS-4', 'ATLAS-5']
const SPRINT_B_KEYS = ['ATLAS-9']

function issue(key: string): BacklogIssue {
  return {
    key,
    summary: `${key} 요약`,
    currentStateKey: 'open',
    assigneeId: null,
    priority: 1,
    rank: key,
    version: 0,
    epicKey: null,
  }
}

function sprintMeta(sprintId: string, name: string): SprintMeta {
  return {
    sprintId,
    name,
    goal: null,
    status: 'PLANNED',
    startDate: null,
    endDate: null,
    version: 0,
  }
}

const VIEW: BacklogView = {
  backlog: BACKLOG_KEYS.map(issue),
  sprints: [
    { sprint: sprintMeta(SPRINT_A_ID, SPRINT_A_NAME), issues: SPRINT_A_KEYS.map(issue) },
    { sprint: sprintMeta(SPRINT_B_ID, SPRINT_B_NAME), issues: SPRINT_B_KEYS.map(issue) },
  ],
  truncated: false,
}

// ─────────────────────────────────────────────────────────────────────────────
// dnd-kit 이벤트 인자 — 공지가 읽는 부분만 실제 컴포넌트와 동일하게 채운다
// ─────────────────────────────────────────────────────────────────────────────

/** 공지는 rect 를 읽지 않는다. 타입을 채우기 위한 빈 값 */
const ZERO_RECT: ClientRect = { width: 0, height: 0, top: 0, left: 0, right: 0, bottom: 0 }

/**
 * 드래그 중인 카드.
 *
 * id 형식(`${context}:${key}`)은 `BacklogCard.tsx:153` 의 `useDraggable({ id })` 와 같다 —
 * 공지가 이 접두를 떼어내는지가 T-KB-1 의 관측 대상이다.
 */
function activeCard(context: BacklogCardContext, issueKey: string, sprintId: string | null): Active {
  const data: BacklogDragData = { issueKey, context, sprintId }
  return {
    id: `${context}:${issueKey}`,
    data: { current: data },
    rect: { current: { initial: null, translated: null } },
  }
}

/** 칸 droppable — `BacklogColumn.tsx:61`·`SprintColumn.tsx:76` 의 data 와 같은 형태 */
function overColumn(
  context: BacklogCardContext,
  sprintId: string | null,
  orderedKeys: readonly string[],
): Over {
  return {
    id: sprintId === null ? 'backlog' : `sprint-${sprintId}`,
    rect: ZERO_RECT,
    disabled: false,
    data: { current: { context, sprintId, orderedKeys } },
  }
}

/** 카드 droppable — `BacklogCard.tsx` 의 dropData 와 같은 형태 */
function overCard(context: BacklogCardContext, sprintId: string | null, key: string): Over {
  return {
    id: cardDroppableId(context, key),
    rect: ZERO_RECT,
    disabled: false,
    data: { current: { type: 'card', key, context, sprintId } },
  }
}

const BACKLOG_CARD = activeCard('backlog', 'ATLAS-1', null)
const SPRINT_A_CARD = activeCard('sprint', 'ATLAS-4', SPRINT_A_ID)
const SPRINT_B_CARD = activeCard('sprint', 'ATLAS-9', SPRINT_B_ID)

const BACKLOG_COLUMN = overColumn('backlog', null, BACKLOG_KEYS)
const SPRINT_A_COLUMN = overColumn('sprint', SPRINT_A_ID, SPRINT_A_KEYS)
const SPRINT_B_COLUMN = overColumn('sprint', SPRINT_B_ID, SPRINT_B_KEYS)

// ─────────────────────────────────────────────────────────────────────────────
// 이벤트 전수 — start 1 · over 3갈래 · end 5갈래 · cancel 1
//
// ★ end 는 다섯이다. `resolveBacklogDropAction` 의 반환 kind 가
//   noop · noop-move · rerank · assign · unassign 이라 하나라도 빠뜨리면 그 순간
//   스크린리더가 **침묵**하는데 테스트는 초록이다 (스펙 §리뷰 반영 C-4).
// ─────────────────────────────────────────────────────────────────────────────

interface AnnouncementCase {
  /** 무엇을 재는지 — 실패 출력에 그대로 뜬다 */
  readonly name: string
  /** 기대 문구. 하드코딩하지 않고 `backlogLabels` 를 참조한다 */
  readonly expected: string
  readonly invoke: (a: Announcements) => string | undefined
}

const CASES: readonly AnnouncementCase[] = [
  {
    name: 'onDragStart — 백로그 카드를 집으면 이슈 키를 읽는다',
    expected: announce.dragStart('ATLAS-1'),
    invoke: (a) => a.onDragStart({ active: BACKLOG_CARD }),
  },
  {
    name: 'onDragOver — 스프린트 칸 위이면 스프린트 이름을 읽는다',
    expected: announce.overSprint(SPRINT_A_NAME),
    invoke: (a) => a.onDragOver({ active: BACKLOG_CARD, over: SPRINT_A_COLUMN }),
  },
  {
    name: 'onDragOver — 백로그 칸 위이면 백로그를 읽는다',
    expected: announce.overBacklog,
    invoke: (a) => a.onDragOver({ active: SPRINT_A_CARD, over: BACKLOG_COLUMN }),
  },
  {
    name: 'onDragOver — 드롭 대상이 없으면 영역 이탈을 읽는다',
    expected: announce.outOfDropZone,
    invoke: (a) => a.onDragOver({ active: BACKLOG_CARD, over: null }),
  },
  {
    name: 'onDragOver — 판정이 noop-move 이면 이동 불가를 읽는다',
    expected: announce.cannotMoveHere,
    invoke: (a) => a.onDragOver({ active: SPRINT_B_CARD, over: SPRINT_B_COLUMN }),
  },
  {
    name: 'onDragEnd — assign 이면 옮긴 스프린트 이름을 읽는다',
    expected: announce.movedToSprint(SPRINT_A_NAME),
    invoke: (a) => a.onDragEnd({ active: BACKLOG_CARD, over: SPRINT_A_COLUMN }),
  },
  {
    name: 'onDragEnd — unassign 이면 백로그로 옮겼음을 읽는다',
    expected: announce.movedToBacklog,
    invoke: (a) => a.onDragEnd({ active: SPRINT_A_CARD, over: BACKLOG_COLUMN }),
  },
  {
    name: 'onDragEnd — rerank 이면 순서 변경을 읽는다',
    expected: announce.reordered,
    invoke: (a) => a.onDragEnd({ active: BACKLOG_CARD, over: BACKLOG_COLUMN }),
  },
  {
    name: 'onDragEnd — 제자리 드롭(noop)이면 변경 없음을 읽는다',
    expected: announce.noChange,
    invoke: (a) => a.onDragEnd({ active: BACKLOG_CARD, over: overCard('backlog', null, 'ATLAS-1') }),
  },
  {
    name: 'onDragEnd — noop-move 이면 이동 불가를 읽는다 (C-4)',
    expected: announce.cannotMoveHere,
    invoke: (a) => a.onDragEnd({ active: SPRINT_B_CARD, over: SPRINT_B_COLUMN }),
  },
  {
    name: 'onDragCancel — 취소를 읽는다',
    expected: announce.cancelled,
    invoke: (a) => a.onDragCancel({ active: BACKLOG_CARD, over: null }),
  },
]

/** 이동 결과를 알리는 다섯 갈래 — 권한 게이팅(T-KB-5)의 관측 대상이기도 하다 */
const END_CASES = CASES.filter((c) => c.name.startsWith('onDragEnd'))

/**
 * 「실제로 끌어 옮겼다」를 알리는 이동 0 판정기 (T12).
 *
 * 아래 케이스 전수는 전부 **움직인 드롭**을 재므로 이 값을 쓴다. 이동 0 쪽은 전용 describe 가
 * 따로 잰다 — 두 상황을 한 빌더로 섞으면 어느 쪽이 red 인지 실패 출력이 말해 주지 않는다.
 */
const REAL_MOVE = (): boolean => false

describe('buildBacklogAnnouncements', () => {
  const announcements = buildBacklogAnnouncements(VIEW, true, REAL_MOVE)

  describe('이벤트 전수 — 침묵하는 갈래가 없다', () => {
    for (const testCase of CASES) {
      it(testCase.name, () => {
        expect(testCase.invoke(announcements)).toBe(testCase.expected)
      })
    }

    it('onDragEnd 는 다섯 갈래를 모두 덮는다 (C-4)', () => {
      expect(END_CASES).toHaveLength(5)
      const messages = END_CASES.map((c) => c.invoke(announcements))
      // 다섯이 서로 다른 문구여야 한다 — 뭉치면 「제자리(noop)」와 「못 놓는 자리(noop-move)」가 같아진다
      expect(new Set(messages).size).toBe(5)
    })

    it('공지 문구가 10종 전부 서로 다르고 빈 문자열이 없다', () => {
      const messages = CASES.map((c) => c.invoke(announcements))
      // 11개 호출 중 noop-move 가 over·end 양쪽에서 같은 문구라 서로 다른 값은 10종이다.
      expect(new Set(messages).size).toBe(10)
      for (const message of messages) {
        expect(typeof message).toBe('string')
        expect(message).not.toBe('')
      }
    })
  })

  describe('T-KB-1 — 내부 droppable id 를 노출하지 않는다', () => {
    it('모든 공지에 backlog:/sprint: 접두 id 가 없다', () => {
      for (const testCase of CASES) {
        expect(testCase.invoke(announcements)).not.toMatch(/backlog:|sprint:/)
      }
    })

    it('스프린트 카드를 집어도 이슈 키만 읽는다', () => {
      const message = announcements.onDragStart({ active: SPRINT_A_CARD })
      expect(message).toBe(announce.dragStart('ATLAS-4'))
      expect(message).toContain('ATLAS-4')
      expect(message).not.toContain('sprint:ATLAS-4')
    })
  })

  describe('T-KB-5 — 권한이 없으면 이동을 알리지 않는다 (FR-17)', () => {
    const readOnly = buildBacklogAnnouncements(VIEW, false, REAL_MOVE)

    it('onDragEnd 다섯 갈래가 전부 권한 없음 문구가 된다', () => {
      for (const testCase of END_CASES) {
        expect(testCase.invoke(readOnly)).toBe(announce.forbidden)
      }
    })

    it('「옮겼습니다」·「변경했습니다」류가 하나도 나오지 않는다', () => {
      // `use-backlog-drag.ts` 의 `if (!canReorderIssue) return` 이 mutation 을 0건으로 막는다.
      // 그런데 공지가 이동을 읽으면 스크린리더 사용자에게만 거짓말이 된다.
      for (const testCase of END_CASES) {
        const message = testCase.invoke(readOnly)
        expect(message).not.toContain('옮겼습니다')
        expect(message).not.toBe(announce.reordered)
      }
    })

    it('집기·이탈 공지는 권한과 무관하게 그대로 나온다', () => {
      expect(readOnly.onDragStart({ active: BACKLOG_CARD })).toBe(announce.dragStart('ATLAS-1'))
      expect(readOnly.onDragOver({ active: BACKLOG_CARD, over: null })).toBe(announce.outOfDropZone)
    })
  })

  describe('뷰와 어긋난 대상', () => {
    it('뷰에 없는 스프린트로 옮겨도 침묵하거나 거짓말하지 않는다', () => {
      // 드래그 도중 뷰가 갱신돼 스프린트가 사라진 경우. mutation 은 그대로 나가므로
      // 「이동할 수 없다」로 읽으면 화면과 어긋난다 — 이름을 못 찾아도 이동 사실은 알린다.
      // (`KanbanBoard.tsx:59` findColumnName 의 방어적 fallback 관례와 같다)
      const staleView: BacklogView = { ...VIEW, sprints: [] }
      const stale = buildBacklogAnnouncements(staleView, true, REAL_MOVE)
      const message = stale.onDragEnd({ active: BACKLOG_CARD, over: SPRINT_A_COLUMN })
      expect(message).toContain('옮겼습니다')
      expect(message).not.toBe(announce.cannotMoveHere)
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 이동 0 드롭 — 공지가 mutation 과 어긋나지 않는다 (T12 결함 A · FR-9)
//
// ★실브라우저 실측. 키보드로 집자마자 놓으면(Space→Space) `use-backlog-drag.ts` 의 가드가
//   mutation 을 0건으로 막는데, 낭독은 「순서를 변경했습니다.」였다. 가드가 훅 **안에만**
//   있어서 공지가 그 사실을 몰랐다 — FR-9 가 못박은 「공지와 mutation 은 같은 판정을 본다」의
//   유일한 예외였고, 그 예외가 곧 거짓말이었다.
//
// dnd-kit 의 `Announcements.onDragEnd` 는 `{active, over}` 만 받고 **delta 가 없다**
// (`@dnd-kit/core@6.3.1` `dist/components/Accessibility/types.d.ts:10`). 그래서 이동 0 여부는
// 빌더 밖에서 들어와야 한다 — 세 번째 인자가 그 통로다.
// ─────────────────────────────────────────────────────────────────────────────

describe('이동 0 드롭 (T12 · FR-9)', () => {
  /** 이동 0이었다고 알려 주는 빌더 — 실제로는 `useBacklogDrag` 가 판정해 넘긴다 */
  const afterZeroMove = buildBacklogAnnouncements(VIEW, true, () => true)
  /** 짝 — 실제로 끌어 옮긴 드롭 */
  const afterRealMove = buildBacklogAnnouncements(VIEW, true, () => false)

  it('같은 칸 드롭에서 「순서를 변경했습니다」가 아니라 「변경 사항이 없습니다」를 읽는다', () => {
    const message = afterZeroMove.onDragEnd({ active: BACKLOG_CARD, over: BACKLOG_COLUMN })
    expect(message).toBe(announce.noChange)
    expect(message).not.toBe(announce.reordered)
  })

  it('크로스 칸 드롭에서도 「옮겼습니다」를 읽지 않는다', () => {
    // 칸 droppable 로 폴백해 `assign` 이 잡히던 자리다. mutation 은 0건인데 낭독만 옮겼다고 한다.
    const message = afterZeroMove.onDragEnd({ active: BACKLOG_CARD, over: SPRINT_A_COLUMN })
    expect(message).toBe(announce.noChange)
    expect(message).not.toContain('옮겼습니다')
  })

  it('짝 단언 — 이동이 있으면 같은 드롭이 그대로 이동 공지를 낸다 (전부를 막지 않는다)', () => {
    // 이 짝이 없으면 `describeEnd` 를 통째로 `noChange` 로 만들어도 위 두 건이 통과한다.
    expect(afterRealMove.onDragEnd({ active: BACKLOG_CARD, over: BACKLOG_COLUMN }))
      .toBe(announce.reordered)
    expect(afterRealMove.onDragEnd({ active: BACKLOG_CARD, over: SPRINT_A_COLUMN }))
      .toBe(announce.movedToSprint(SPRINT_A_NAME))
  })

  it('드롭이 아닌 순간(집기·이동중)은 이동 0 판정을 쓰지 않는다', () => {
    // 집은 직후에도 이동량은 0이다. 그때 「이동할 수 없다」를 읽으면 아직 아무것도 정하지
    // 않은 사용자에게 겁을 준다 — 이동 0은 **드롭 판정**에만 걸린다.
    expect(afterZeroMove.onDragStart({ active: BACKLOG_CARD })).toBe(announce.dragStart('ATLAS-1'))
    expect(afterZeroMove.onDragOver({ active: BACKLOG_CARD, over: BACKLOG_COLUMN }))
      .toBe(announce.overBacklog)
    expect(afterZeroMove.onDragOver({ active: BACKLOG_CARD, over: SPRINT_A_COLUMN }))
      .toBe(announce.overSprint(SPRINT_A_NAME))
  })
})

describe('backlogScreenReaderInstructions', () => {
  it('dnd-kit 영어 기본 안내를 한국어로 대체한다', () => {
    expect(backlogScreenReaderInstructions.draggable).toBe(announce.instructions)
    expect(backlogScreenReaderInstructions.draggable).not.toBe(
      defaultScreenReaderInstructions.draggable,
    )
  })
})
