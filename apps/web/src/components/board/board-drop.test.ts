// board-drop 순수 헬퍼 단위 테스트 — 컬럼 이동/셀(컬럼×스윔레인) 내 순서변경 4분기 판정
import { describe, it, expect } from 'vitest'
import type { BoardDetail, BoardCard, SwimlaneField } from '@/api/boards'
import type { CardAssigneeDisplay } from './BoardCard'
import { resolveDropAction } from './board-drop'
import type { DragActiveMin, DragOverMin } from './board-drop'

// ─────────────────────────────────────────────────────────────────────────────
// 테스트 픽스처
// ─────────────────────────────────────────────────────────────────────────────

const COL_TODO = '00000000-0000-4000-8000-000000000010'
const COL_INPROGRESS = '00000000-0000-4000-8000-000000000020'
const COL_DONE = '00000000-0000-4000-8000-000000000030'

const ASSIGNEE_A: CardAssigneeDisplay = { state: 'named', name: '김앨리스' }
const ASSIGNEE_B: CardAssigneeDisplay = { state: 'named', name: '박보검' }

/** ASSIGNEE_A(김앨리스)의 실제 UUID — 표시 이름(assigneeNames)과는 별개 필드(BoardCard.assigneeId) */
const ASSIGNEE_A_ID = '00000000-0000-4000-8000-0000000000a1'

/** ASSIGNEE_B(박보검)의 실제 UUID */
const ASSIGNEE_B_ID = '00000000-0000-4000-8000-0000000000b1'

/** assignee 스윔레인 판정용 — 이슈 키 → 담당자 표시 상태 맵 */
const assigneeNames = new Map<string, CardAssigneeDisplay>([
  ['ATLAS-1', ASSIGNEE_A],
  ['ATLAS-2', ASSIGNEE_A],
  ['ATLAS-3', ASSIGNEE_A],
  ['ATLAS-4', ASSIGNEE_B],
  ['ATLAS-5', ASSIGNEE_A],
  ['ATLAS-6', ASSIGNEE_A],
])

/** card()가 기본값 대신 덮어쓸 필드 — field-change 픽스처에서 실제 값(assigneeId/priority/epicKey)을 지정할 때 사용 */
interface CardOverrides {
  assigneeId?: string | null
  priority?: number
  epicKey?: string | null
}

function card(issueKey: string, version = 1, overrides: CardOverrides = {}): BoardCard {
  return {
    issueKey,
    summary: issueKey,
    assigneeId: overrides.assigneeId ?? null,
    version,
    priority: overrides.priority ?? 1,
    epicKey: overrides.epicKey ?? null,
    rank: null,
  }
}

/**
 * 테스트 보드.
 * COL_TODO: ATLAS-1~3(그룹 김앨리스, rank순) + ATLAS-4(그룹 박보검) — 셀 판정/reorder 시나리오.
 * COL_INPROGRESS / COL_DONE: 각 1장 — 컬럼 이동 시나리오.
 * swimlaneField: ASSIGNEE — 셀 = 컬럼 × 담당자 그룹.
 */
const board: BoardDetail = {
  boardId: '00000000-0000-4000-8000-000000000001',
  projectKey: 'ATLAS',
  name: 'ATLAS 보드',
  truncated: false,
  unplacedCount: 0,
  swimlaneField: 'ASSIGNEE',
  quickFilters: [],
  columns: [
    {
      columnId: COL_TODO,
      stateKey: 'todo',
      name: '할 일',
      category: 'TODO',
      displayOrder: 1,
      wipLimit: null,
      wipExceeded: false,
      cards: [
        card('ATLAS-1', 1, { assigneeId: ASSIGNEE_A_ID }),
        card('ATLAS-2', 1, { assigneeId: ASSIGNEE_A_ID }),
        card('ATLAS-3', 1, { assigneeId: ASSIGNEE_A_ID }),
        card('ATLAS-4', 1, { assigneeId: ASSIGNEE_B_ID }),
      ],
    },
    {
      columnId: COL_INPROGRESS,
      stateKey: 'in-progress',
      name: '진행 중',
      category: 'IN_PROGRESS',
      displayOrder: 2,
      wipLimit: null,
      wipExceeded: false,
      cards: [card('ATLAS-6')],
    },
    {
      columnId: COL_DONE,
      stateKey: 'done',
      name: '완료',
      category: 'DONE',
      displayOrder: 3,
      wipLimit: null,
      wipExceeded: false,
      cards: [card('ATLAS-5')],
    },
  ],
}

function activeOf(issueKey: string, fromColumnId: string): DragActiveMin {
  return { id: issueKey, data: { current: { fromColumnId } } }
}

function overOf(id: string): DragOverMin {
  return { id }
}

// ─────────────────────────────────────────────────────────────────────────────
// 테스트
// ─────────────────────────────────────────────────────────────────────────────

describe('resolveDropAction — 셀 4분기 판정', () => {
  it('over가 null이면 noop을 반환한다', () => {
    const result = resolveDropAction(board, activeOf('ATLAS-1', COL_TODO), null, assigneeNames)
    expect(result).toEqual({ type: 'noop' })
  })

  it('다른 컬럼(non-DONE)으로 드롭하면 move를 반환한다', () => {
    const result = resolveDropAction(
      board,
      activeOf('ATLAS-1', COL_TODO),
      overOf(COL_INPROGRESS),
      assigneeNames,
    )
    expect(result).toEqual({
      type: 'move',
      issueKey: 'ATLAS-1',
      fromColumnId: COL_TODO,
      toColumnId: COL_INPROGRESS,
      expectedVersion: 1,
    })
  })

  it('DONE 컬럼으로 드롭하면 needs-resolution을 반환한다', () => {
    const result = resolveDropAction(board, activeOf('ATLAS-1', COL_TODO), overOf(COL_DONE), assigneeNames)
    expect(result).toEqual({
      type: 'needs-resolution',
      issueKey: 'ATLAS-1',
      fromColumnId: COL_TODO,
      toColumnId: COL_DONE,
      expectedVersion: 1,
    })
  })

  it('같은 컬럼·다른 스윔레인 그룹(담당자 다른 카드) 위로 드롭하면 field-change를 반환한다 (FR-UX-06 PR21b)', () => {
    // ATLAS-1(그룹 김앨리스, assigneeId=A) → ATLAS-4(그룹 박보검, assigneeId=B) 위 드롭.
    // 대상 카드(ATLAS-4) 자신의 assigneeId로 재할당한다 — 그룹 대표값이 아니다(FR-2).
    const result = resolveDropAction(board, activeOf('ATLAS-1', COL_TODO), overOf('ATLAS-4'), assigneeNames)
    expect(result).toEqual({
      type: 'field-change',
      issueKey: 'ATLAS-1',
      field: 'assignee',
      toAssigneeId: ASSIGNEE_B_ID,
      expectedVersion: 1,
    })
  })

  it('같은 셀 내 카드 위로 드롭하면 reorder를 반환하고 이웃 issueKey를 정확히 계산한다', () => {
    // 셀 = [ATLAS-1, ATLAS-2, ATLAS-3]. ATLAS-1을 ATLAS-3 위로 드롭 → 맨 뒤로 이동.
    const result = resolveDropAction(board, activeOf('ATLAS-1', COL_TODO), overOf('ATLAS-3'), assigneeNames)
    expect(result).toEqual({
      type: 'reorder',
      issueKey: 'ATLAS-1',
      columnId: COL_TODO,
      previousIssueKey: 'ATLAS-3',
      nextIssueKey: undefined,
      expectedVersion: 1,
    })
  })

  it('맨 앞으로 드롭하면 previousIssueKey가 없다', () => {
    // 셀 = [ATLAS-1, ATLAS-2, ATLAS-3]. ATLAS-3을 ATLAS-1 위로 드롭 → 맨 앞으로 이동.
    const result = resolveDropAction(board, activeOf('ATLAS-3', COL_TODO), overOf('ATLAS-1'), assigneeNames)
    expect(result).toEqual({
      type: 'reorder',
      issueKey: 'ATLAS-3',
      columnId: COL_TODO,
      previousIssueKey: undefined,
      nextIssueKey: 'ATLAS-1',
      expectedVersion: 1,
    })
  })

  it('컬럼 배경(빈 셀 등)으로 드롭하면 셀 맨 뒤로 이동한다', () => {
    // 셀 = [ATLAS-1, ATLAS-2, ATLAS-3]. ATLAS-2를 컬럼 배경(over=COL_TODO)으로 드롭 → 맨 뒤.
    const result = resolveDropAction(board, activeOf('ATLAS-2', COL_TODO), overOf(COL_TODO), assigneeNames)
    expect(result).toEqual({
      type: 'reorder',
      issueKey: 'ATLAS-2',
      columnId: COL_TODO,
      previousIssueKey: 'ATLAS-3',
      nextIssueKey: undefined,
      expectedVersion: 1,
    })
  })

  it('자기 자신 위에 드롭하면 noop을 반환한다 (제자리)', () => {
    const result = resolveDropAction(board, activeOf('ATLAS-2', COL_TODO), overOf('ATLAS-2'), assigneeNames)
    expect(result).toEqual({ type: 'noop' })
  })

  it('이미 맨 뒤인 카드를 컬럼 배경으로 드롭해도 위치 변화가 없으면 noop을 반환한다 (제자리)', () => {
    // ATLAS-3은 이미 셀의 맨 뒤. 컬럼 배경으로 드롭해도 순서 불변 → noop.
    const result = resolveDropAction(board, activeOf('ATLAS-3', COL_TODO), overOf(COL_TODO), assigneeNames)
    expect(result).toEqual({ type: 'noop' })
  })

  it('assigneeNames를 생략해도(기본값) 컬럼 이동 판정은 정상 동작한다 (하위 호환)', () => {
    const result = resolveDropAction(board, activeOf('ATLAS-1', COL_TODO), overOf(COL_INPROGRESS))
    expect(result).toEqual({
      type: 'move',
      issueKey: 'ATLAS-1',
      fromColumnId: COL_TODO,
      toColumnId: COL_INPROGRESS,
      expectedVersion: 1,
    })
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// field-change 픽스처 — FR-UX-06 PR21b. 스윔레인 그룹이 다른 카드 위 드롭 판정
// ─────────────────────────────────────────────────────────────────────────────

/**
 * swimlaneField·단일 컬럼 카드 목록만 다른 필드변경 전용 최소 보드 픽스처를 만든다.
 * PR21 판정(move/needs-resolution)과 무관 — 필드변경 분기만 검증하므로 컬럼 1개면 충분.
 */
function buildFieldChangeBoard(swimlaneField: SwimlaneField, cards: BoardCard[]): BoardDetail {
  return {
    boardId: '00000000-0000-4000-8000-000000000002',
    projectKey: 'ATLAS',
    name: 'ATLAS 보드(필드변경 테스트)',
    truncated: false,
    unplacedCount: 0,
    swimlaneField,
    quickFilters: [],
    columns: [
      {
        columnId: COL_TODO,
        stateKey: 'todo',
        name: '할 일',
        category: 'TODO',
        displayOrder: 1,
        wipLimit: null,
        wipExceeded: false,
        cards,
      },
    ],
  }
}

const priorityBoard = buildFieldChangeBoard('PRIORITY', [
  card('P-1', 1, { priority: 3 }),
  card('P-2', 1, { priority: 1 }),
])

const epicBoard = buildFieldChangeBoard('EPIC', [
  card('E-1', 1, { epicKey: 'ATLAS-10' }),
  card('E-2', 1, { epicKey: 'ATLAS-20' }),
  card('E-3', 1, { epicKey: null }),
])

const noneBoard = buildFieldChangeBoard('NONE', [card('N-1'), card('N-2')])

const assigneeUnassignedBoard = buildFieldChangeBoard('ASSIGNEE', [
  card('AU-1', 1, { assigneeId: ASSIGNEE_A_ID }),
  card('AU-2', 1, { assigneeId: null }),
])

/** AU-2는 맵에서 생략 — groupCardsBySwimlane 기본값(unassigned)으로 분류된다 */
const assigneeUnassignedNames = new Map<string, CardAssigneeDisplay>([['AU-1', ASSIGNEE_A]])

const assigneeHomonymBoard = buildFieldChangeBoard('ASSIGNEE', [
  card('AH-1', 1, { assigneeId: ASSIGNEE_A_ID }),
  card('AH-2', 1, { assigneeId: ASSIGNEE_A_ID }),
])

/** AH-1은 이름 미확인(unknown), AH-2는 named(김앨리스) — 표시 그룹은 다르지만 실제 assigneeId는 동일 */
const assigneeHomonymNames = new Map<string, CardAssigneeDisplay>([
  ['AH-1', { state: 'unknown' }],
  ['AH-2', ASSIGNEE_A],
])

describe('resolveDropAction — 스윔레인 필드변경(field-change, FR-UX-06 PR21b)', () => {
  it('ASSIGNEE 스윔레인에서 미배정 그룹으로 드롭하면 toAssigneeId가 null이다', () => {
    const result = resolveDropAction(
      assigneeUnassignedBoard,
      activeOf('AU-1', COL_TODO),
      overOf('AU-2'),
      assigneeUnassignedNames,
    )
    expect(result).toEqual({
      type: 'field-change',
      issueKey: 'AU-1',
      field: 'assignee',
      toAssigneeId: null,
      expectedVersion: 1,
    })
  })

  it('표시 그룹은 다르지만(unknown vs named) 실제 담당자가 같으면 noop을 반환한다 (E3·D2 same-value)', () => {
    const result = resolveDropAction(
      assigneeHomonymBoard,
      activeOf('AH-1', COL_TODO),
      overOf('AH-2'),
      assigneeHomonymNames,
    )
    expect(result).toEqual({ type: 'noop' })
  })

  it('PRIORITY 스윔레인에서 다른 우선순위 그룹 카드 위로 드롭하면 field-change(대상 카드 priority)를 반환한다', () => {
    const result = resolveDropAction(priorityBoard, activeOf('P-1', COL_TODO), overOf('P-2'))
    expect(result).toEqual({
      type: 'field-change',
      issueKey: 'P-1',
      field: 'priority',
      toPriority: 1,
      expectedVersion: 1,
    })
  })

  it('EPIC 스윔레인에서 다른 에픽 그룹 카드 위로 드롭하면 field-change(fromEpicKey 포함)를 반환한다', () => {
    const result = resolveDropAction(epicBoard, activeOf('E-1', COL_TODO), overOf('E-2'))
    expect(result).toEqual({
      type: 'field-change',
      issueKey: 'E-1',
      field: 'epic',
      toEpicKey: 'ATLAS-20',
      fromEpicKey: 'ATLAS-10',
      expectedVersion: 1,
    })
  })

  it('EPIC 스윔레인에서 "에픽 없음" 그룹으로 드롭하면 toEpicKey가 null이다', () => {
    const result = resolveDropAction(epicBoard, activeOf('E-1', COL_TODO), overOf('E-3'))
    expect(result).toEqual({
      type: 'field-change',
      issueKey: 'E-1',
      field: 'epic',
      toEpicKey: null,
      fromEpicKey: 'ATLAS-10',
      expectedVersion: 1,
    })
  })

  it('NONE 스윔레인은 그룹이 하나뿐이라 다른 카드 위 드롭도 reorder로 처리된다 (필드변경 없음)', () => {
    const result = resolveDropAction(noneBoard, activeOf('N-1', COL_TODO), overOf('N-2'))
    expect(result).toEqual({
      type: 'reorder',
      issueKey: 'N-1',
      columnId: COL_TODO,
      previousIssueKey: 'N-2',
      nextIssueKey: undefined,
      expectedVersion: 1,
    })
  })

  it('다른 그룹 카드를 컬럼 배경으로 드롭하면 필드변경이 아니라 현재 셀 안에서 처리된다 (E4)', () => {
    // ATLAS-4(그룹 박보검, 셀에 혼자)를 컬럼 배경(over=COL_TODO)으로 드롭 → 자기 셀 안에서 위치 불변 → noop.
    // over가 카드가 아니므로(overIssueKey undefined) 그룹 판정 자체가 이뤄지지 않아 field-change로 새지 않는다.
    const result = resolveDropAction(board, activeOf('ATLAS-4', COL_TODO), overOf(COL_TODO), assigneeNames)
    expect(result).toEqual({ type: 'noop' })
  })
})
