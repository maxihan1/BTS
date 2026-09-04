// 상태 매핑 드롭 판정 테스트 — 순서 계약(빼기 먼저)과 무동작 경계 (부채 177 Task 6)
import { describe, it, expect } from 'vitest'
import type { BoardColumn, ColumnState } from '@/api/boards'
import { planStateDrop, UNMAPPED_DROP_ID } from './state-mapping-drop'

const COL_A = 'b2c3d4e5-f6a7-4901-8bcd-ef1234567891'
const COL_B = 'c3d4e5f6-a7b8-4012-9cde-f01234567892'

function state(key: string): ColumnState {
  return { key, name: key, category: 'TODO' }
}

function column(columnId: string, keys: string[]): BoardColumn {
  return {
    columnId,
    states: keys.map(state),
    name: columnId,
    category: 'TODO',
    displayOrder: 0,
    cards: [],
    wipLimit: null,
    wipExceeded: false,
  }
}

const columns: BoardColumn[] = [column(COL_A, ['open', 'triage']), column(COL_B, ['closed'])]

describe('planStateDrop — 미매핑에서 컬럼으로 (S2 · J27)', () => {
  it('T-SD-1: 대상 컬럼의 집합 전체에 상태를 더해 한 번만 보낸다', () => {
    const plan = planStateDrop('review', null, COL_B, columns)

    // ★집합 **전체**를 보낸다. 부분 추가 명령이 아니다(#444 R9 와 같은 근거).
    expect(plan.changes).toEqual([{ columnId: COL_B, stateKeys: ['closed', 'review'] }])
  })
})

describe('planStateDrop — 컬럼에서 미매핑으로 (S3 · J28)', () => {
  it('T-SD-2: 출발 컬럼에서 그 상태만 뺀 집합을 보낸다', () => {
    const plan = planStateDrop('triage', COL_A, UNMAPPED_DROP_ID, columns)

    expect(plan.changes).toEqual([{ columnId: COL_A, stateKeys: ['open'] }])
  })
})

describe('planStateDrop — 컬럼 사이 이동은 두 번의 교체다 (X1)', () => {
  it('T-SD-3: 빼기가 먼저다 — 순서를 뒤집으면 서버가 409 를 낸다', () => {
    const plan = planStateDrop('triage', COL_A, COL_B, columns)

    // ★이 판정이 이 파일의 급소다. 한 상태는 한 컬럼에만 속할 수 있으므로(X1 · DB 복합 FK),
    //   A 에 남긴 채 B 에 넣으면 반드시 409 다. 배열 순서가 곧 계약이다.
    expect(plan.changes).toEqual([
      { columnId: COL_A, stateKeys: ['open'] },
      { columnId: COL_B, stateKeys: ['closed', 'triage'] },
    ])
  })

  it('T-SD-4: 첫 요청이 빼기임을 독립으로 못박는다', () => {
    const plan = planStateDrop('triage', COL_A, COL_B, columns)

    // T-SD-3 과 겹쳐 보이지만 목적이 다르다 — 전체 배열을 통째로 비교하는 단언은
    // 상태 키 하나가 바뀌어도 깨져서, 「순서」가 왜 red 인지 흐려진다. 순서만 따로 잰다.
    expect(plan.changes[0]?.columnId).toBe(COL_A)
    expect(plan.changes[0]?.stateKeys).not.toContain('triage')
  })
})

describe('planStateDrop — 아무 일도 하지 않는 경계', () => {
  it('T-SD-5: 드롭 대상이 없으면(허공) 빈 계획이다', () => {
    expect(planStateDrop('open', COL_A, null, columns).changes).toEqual([])
  })

  it('T-SD-6: 제자리 드롭은 빈 계획이다', () => {
    expect(planStateDrop('open', COL_A, COL_A, columns).changes).toEqual([])
  })

  it('T-SD-7: 미매핑에서 미매핑으로는 빈 계획이다', () => {
    expect(planStateDrop('review', null, UNMAPPED_DROP_ID, columns).changes).toEqual([])
  })

  it('T-SD-8: 모르는 대상 id 는 던지지 않고 빈 계획이다', () => {
    // 던지면 드래그 한 번이 화면을 통째로 죽인다. 드롭 판정은 실패해도 조용해야 한다.
    expect(planStateDrop('open', COL_A, 'not-a-column', columns).changes).toEqual([])
  })
})
