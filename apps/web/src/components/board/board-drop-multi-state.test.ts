// 컬럼:상태 1:N 이후의 드롭 판정 — toStateKey 전송(R12)과 해결 방안 분기 이전(R13)

import { describe, it, expect } from 'vitest'
import type { BoardDetail, BoardCard } from '@/api/boards'
import { resolveDropAction } from './board-drop'
import type { DragActiveMin, DragOverMin } from './board-drop'

/**
 * 이 파일이 지는 판정은 **1:N 이후에만 존재하는 조합**이다.
 *
 * | 축 | 무엇 | 근거 |
 * |---|---|---|
 * | ① `toStateKey` 전송 | 드롭은 컬럼이 아니라 **상태**를 지목한다 | R12 · R6 · J3 · J4 |
 * | ② 「첫 상태」 | 상태가 여럿이면 응답 `states` 의 **첫 항목**을 보낸다 | E5 |
 * | ③ **R13** | 해결 방안 모달을 **대상 상태**의 category 로 판정한다 | J7 · J8 |
 *
 * ★ ③ 의 결정판은 「컬럼이 DONE 인데 대상 상태는 아니다」이다. 1:1 시절에는 만들 수 없던
 * 조합이라 오늘의 코드가 그것을 틀리게 답한다는 사실이 드러나지 않았다.
 * `board-drop.ts` 가 `toColumn.category === 'DONE'` 으로 분기하는데, 지라는 resolution 을
 * **전환**에 붙이고 BTS 백엔드도 이미 대상 상태로 판단한다(`IssueRepository` `targetStateIsDone`).
 * 어긋난 것은 프론트뿐이고, 1:N 이 그 불일치를 드러낸다.
 */

const COL_TODO = '00000000-0000-4000-8000-0000000000a1'
const COL_MERGED = '00000000-0000-4000-8000-0000000000a2'

function card(issueKey: string, version = 1): BoardCard {
  return {
    issueKey,
    summary: `${issueKey} 요약`,
    assigneeId: null,
    version,
    priority: 3,
    epicKey: null,
    rank: null,
    typeKey: 'task',
    labels: [],
    originalEstimateSeconds: null,
  }
}

/** `noUncheckedIndexedAccess` 아래에서 인덱스 접근이 `| undefined` 라 좁혀 준다. */
function columnAt(b: BoardDetail, index: number): BoardDetail['columns'][number] {
  const col = b.columns[index]
  if (col === undefined) throw new Error(`컬럼 ${index} 이 픽스처에 없다`)
  return col
}

/**
 * 두 번째 컬럼이 상태 둘을 담는다.
 *
 * `category` 는 담은 상태들의 **최댓값**이라(R5) `DONE` 인데, **첫 상태**는 `in_review`
 * (IN_PROGRESS)다. 이 어긋남이 R13 이 읽어야 할 값을 가른다.
 */
const board: BoardDetail = {
  boardId: '00000000-0000-4000-8000-000000000001',
  projectKey: 'ATLAS',
  name: 'ATLAS 보드',
  boardType: 'KANBAN',
  activeSprint: null,
  truncated: false,
  unplacedCount: 0,
  unmappedStates: [],
  swimlaneField: 'NONE',
  quickFilters: [],
  columns: [
    {
      columnId: COL_TODO,
      states: [{ key: 'todo', name: '할 일', category: 'TODO' }],
      name: '할 일',
      category: 'TODO',
      displayOrder: 1,
      wipLimit: null,
      wipExceeded: false,
      cards: [card('ATLAS-1')],
    },
    {
      columnId: COL_MERGED,
      states: [
        { key: 'in_review', name: '검토 중', category: 'IN_PROGRESS' },
        { key: 'closed', name: '완료', category: 'DONE' },
      ],
      name: '마무리',
      category: 'DONE',
      displayOrder: 2,
      wipLimit: null,
      wipExceeded: false,
      cards: [],
    },
  ],
}

function activeOf(issueKey: string, fromColumnId: string): DragActiveMin {
  return { id: issueKey, data: { current: { fromColumnId } } }
}

function overOf(id: string): DragOverMin {
  return { id }
}

describe('resolveDropAction — 컬럼:상태 1:N', () => {
  it('카드 이동 판정이 toStateKey 를 싣는다', () => {
    const result = resolveDropAction(board, activeOf('ATLAS-1', COL_TODO), overOf(COL_MERGED))

    expect(result).toMatchObject({ toColumnId: COL_MERGED, toStateKey: 'in_review' })
  })

  it('상태가 여럿인 컬럼에 떨구면 첫 상태를 보낸다', () => {
    // E5 — 「첫」은 (display_order, state_key) 오름차순 최소이고, 백엔드 응답의 `states` 가
    //      이미 그 순서로 온다. 프론트가 따로 정렬하면 규칙이 두 곳이 되어 언젠가 갈린다.
    const result = resolveDropAction(board, activeOf('ATLAS-1', COL_TODO), overOf(COL_MERGED))

    expect(result).toMatchObject({ toStateKey: 'in_review' })
    expect(result).not.toMatchObject({ toStateKey: 'closed' })
  })

  it('★컬럼 category 가 DONE 이어도 대상 상태가 DONE 이 아니면 해결 방안 모달을 안 띄운다', () => {
    // R13 결정판. 오늘의 코드는 toColumn.category === 'DONE' 을 보므로 needs-resolution 을 낸다.
    const result = resolveDropAction(board, activeOf('ATLAS-1', COL_TODO), overOf(COL_MERGED))

    expect(result.type).toBe('move')
  })

  it('대상 상태가 DONE 이면 해결 방안 모달을 띄운다', () => {
    const doneFirst: BoardDetail = {
      ...board,
      columns: [
        columnAt(board, 0),
        {
          ...columnAt(board, 1),
          states: [
            { key: 'closed', name: '완료', category: 'DONE' },
            { key: 'in_review', name: '검토 중', category: 'IN_PROGRESS' },
          ],
        },
      ],
    }

    const result = resolveDropAction(doneFirst, activeOf('ATLAS-1', COL_TODO), overOf(COL_MERGED))

    expect(result.type).toBe('needs-resolution')
    expect(result).toMatchObject({ toStateKey: 'closed' })
  })

  it('상태 0개 컬럼에 떨구면 아무 일도 하지 않는다', () => {
    // E1 — 매핑을 옮기는 중간 창이거나 갓 만들어진 컬럼이다. 「어느 상태로 가라」가 없으므로
    //      요청을 만들 수 없다. 백엔드도 400 으로 거부한다.
    const empty: BoardDetail = {
      ...board,
      columns: [columnAt(board, 0), { ...columnAt(board, 1), states: [] }],
    }

    const result = resolveDropAction(empty, activeOf('ATLAS-1', COL_TODO), overOf(COL_MERGED))

    expect(result).toEqual({ type: 'noop' })
  })
})
