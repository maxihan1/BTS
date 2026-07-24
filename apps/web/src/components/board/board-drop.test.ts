// board-drop 순수 헬퍼 단위 테스트 — 컬럼 이동/셀(컬럼×스윔레인) 내 순서변경 4분기 판정
import { describe, it, expect } from 'vitest'
import type { BoardDetail, BoardCard } from '@/api/boards'
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

/** assignee 스윔레인 판정용 — 이슈 키 → 담당자 표시 상태 맵 */
const assigneeNames = new Map<string, CardAssigneeDisplay>([
  ['ATLAS-1', ASSIGNEE_A],
  ['ATLAS-2', ASSIGNEE_A],
  ['ATLAS-3', ASSIGNEE_A],
  ['ATLAS-4', ASSIGNEE_B],
  ['ATLAS-5', ASSIGNEE_A],
  ['ATLAS-6', ASSIGNEE_A],
])

function card(issueKey: string, version = 1): BoardCard {
  return {
    issueKey,
    summary: issueKey,
    assigneeId: null,
    version,
    priority: 1,
    epicKey: null,
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
      cards: [card('ATLAS-1'), card('ATLAS-2'), card('ATLAS-3'), card('ATLAS-4')],
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

  it('같은 컬럼·다른 스윔레인 그룹으로 드롭하면 noop을 반환한다', () => {
    // ATLAS-1(그룹 김앨리스) → ATLAS-4(그룹 박보검) 위 드롭. 같은 컬럼이지만 다른 셀.
    const result = resolveDropAction(board, activeOf('ATLAS-1', COL_TODO), overOf('ATLAS-4'), assigneeNames)
    expect(result).toEqual({ type: 'noop' })
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
