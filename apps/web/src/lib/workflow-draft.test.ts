// 초안 편집 리듀서 테스트 — 캐스케이드 · 규칙 보존 · 발행 차단 사유 · 이관 후보
import { describe, it, expect } from 'vitest'
import {
  draftReducer,
  initialDraftState,
  toWireDefinition,
  removedStatusKeys,
  migrationTargets,
  publishBlockReason,
  stateNameMap,
} from './workflow-draft'
import type { DraftDefinition } from '@/api/workflows-draft.types'

// 좌표는 서버가 항상 실어 보낸다(필수-nullable). 아직 배치하지 않아 셋 다 null 이다 — FR-WF-07 D8.
const OPEN = { key: 'open', name: '열림', category: 'TODO', displayOrder: 1, layoutX: null, layoutY: null } as const
const DOING = {
  key: 'doing',
  name: '진행 중',
  category: 'IN_PROGRESS',
  displayOrder: 2,
  layoutX: null,
  layoutY: null,
} as const
const DONE = { key: 'done', name: '완료', category: 'DONE', displayOrder: 3, layoutX: null, layoutY: null } as const

/** 규칙이 붙은 전환 하나를 포함하는 기본 정의 */
function definition(): DraftDefinition {
  return {
    key: 'wf',
    name: '워크플로우',
    description: null,
    states: [OPEN, DOING, DONE],
    transitions: [
      { from: null, to: 'open', name: '이슈 생성', kind: 'INITIAL', validators: [], postActions: [] },
      {
        from: 'open',
        to: 'doing',
        name: '작업 시작',
        kind: 'NORMAL',
        validators: [{ type: 'RequiredField', config: { field: 'assignee' } }],
        postActions: [{ type: 'AddWatcher', config: {} }],
      },
      { from: 'doing', to: 'done', name: '완료', kind: 'NORMAL', validators: [], postActions: [] },
    ],
  }
}

/** 서버에서 막 받은 상태 */
function loaded() {
  return draftReducer(initialDraftState, {
    type: 'loadFromServer',
    definition: definition(),
    baseVersion: 4,
    exists: true,
    canResetToDefault: true,
  })
}

describe('loadFromServer', () => {
  it('앵커와 정의를 싣고 revision 을 0 으로 되돌린다', () => {
    const state = loaded()

    expect(state.baseVersion).toBe(4)
    expect(state.revision).toBe(0)
    expect(state.draft.states).toHaveLength(3)
  })

  it('전환마다 로컬 id 를 붙이고 그 id 가 서로 다르다', () => {
    // 초안 전환에는 서버 id 가 없다 — 배열 인덱스를 React key 로 쓰면 삭제 시 엉뚱한 행이
    // 리마운트된다. 로컬 id 가 그 자리를 메운다.
    const ids = loaded().draft.transitions.map((t) => t.localId)

    expect(new Set(ids).size).toBe(ids.length)
    expect(ids.every((id) => id.length > 0)).toBe(true)
  })
})

describe('removeState — 전환 캐스케이드', () => {
  it('그 상태를 가리키는 전환을 함께 뺀다', () => {
    // ★ 상태만 빼면 Workflow.of 의 requireValidTransitions 가 400 을 내고, 그 순간부터
    //   자동저장이 **전부 실패**한다. 사용자는 무엇이 잘못됐는지 모른 채 편집이 멈춘다.
    const state = draftReducer(loaded(), { type: 'removeState', key: 'doing' })

    expect(state.draft.states.map((s) => s.key)).toEqual(['open', 'done'])
    // open→doing 과 doing→done 이 둘 다 사라진다.
    expect(state.draft.transitions.map((t) => t.name)).toEqual(['이슈 생성'])
  })

  it('남은 전환의 규칙은 그대로다', () => {
    const state = draftReducer(loaded(), { type: 'removeState', key: 'done' })
    const started = state.draft.transitions.find((t) => t.name === '작업 시작')

    expect(started?.validators).toEqual([{ type: 'RequiredField', config: { field: 'assignee' } }])
  })

  it('displayOrder 를 1 부터 다시 매긴다', () => {
    const state = draftReducer(loaded(), { type: 'removeState', key: 'open' })

    expect(state.draft.states.map((s) => s.displayOrder)).toEqual([1, 2])
  })

  it('원본을 변형하지 않는다', () => {
    const before = loaded()
    const snapshot = JSON.stringify(before.draft)

    draftReducer(before, { type: 'removeState', key: 'doing' })

    expect(JSON.stringify(before.draft)).toBe(snapshot)
  })
})

describe('addState', () => {
  it('카탈로그의 이름·카테고리를 그대로 싣는다', () => {
    // 화면이 임의 값을 채우면 DraftIdentityGuard.requireStatesMatchCatalog 가 저장을 400 으로 막는다.
    const state = draftReducer(loaded(), {
      type: 'addState',
      entry: { key: 'blocked', name: '차단됨', category: 'TODO' },
    })
    const added = state.draft.states.at(-1)

    expect(added).toMatchObject({ key: 'blocked', name: '차단됨', category: 'TODO', displayOrder: 4 })
  })

  it('이미 있는 상태는 다시 넣지 않는다', () => {
    const state = draftReducer(loaded(), {
      type: 'addState',
      entry: { key: 'open', name: '열림', category: 'TODO' },
    })

    expect(state.draft.states).toHaveLength(3)
  })
})

describe('reorderStates', () => {
  it('주어진 순서대로 displayOrder 를 1..n 으로 다시 매긴다', () => {
    const state = draftReducer(loaded(), { type: 'reorderStates', orderedKeys: ['done', 'open', 'doing'] })

    expect(state.draft.states.map((s) => s.key)).toEqual(['done', 'open', 'doing'])
    expect(state.draft.states.map((s) => s.displayOrder)).toEqual([1, 2, 3])
  })
})

describe('전환 편집', () => {
  it('updateTransition 이 validators·postActions 를 보존한다', () => {
    // ★ 목록 편집기는 규칙을 편집하지 않는다(FR-WF-06 소관). 그런데 리듀서가 규칙을 버리면
    //   그 초안을 발행하는 순간 DraftRuleWriter.writeAll 이 **빈 목록으로 덮어쓴다** —
    //   화면에 아무 표시 없이 전 워크플로우의 전환 규칙이 사라지는 자리다.
    const before = loaded()
    const target = before.draft.transitions.find((t) => t.name === '작업 시작')!

    const state = draftReducer(before, {
      type: 'updateTransition',
      localId: target.localId,
      input: { from: 'open', to: 'doing', name: '착수', kind: 'NORMAL' },
    })
    const updated = state.draft.transitions.find((t) => t.localId === target.localId)

    expect(updated?.name).toBe('착수')
    expect(updated?.validators).toEqual([{ type: 'RequiredField', config: { field: 'assignee' } }])
    expect(updated?.postActions).toEqual([{ type: 'AddWatcher', config: {} }])
  })

  it('createTransition 은 규칙 없이 시작한다', () => {
    const state = draftReducer(loaded(), {
      type: 'createTransition',
      input: { from: 'done', to: 'open', name: '재열기', kind: 'NORMAL' },
    })
    const created = state.draft.transitions.at(-1)

    expect(created).toMatchObject({ from: 'done', to: 'open', name: '재열기', kind: 'NORMAL' })
    expect(created?.validators).toEqual([])
  })

  it('GLOBAL·INITIAL 전환은 from 이 null 이다', () => {
    const state = draftReducer(loaded(), {
      type: 'createTransition',
      input: { from: 'open', to: 'done', name: '어디서나 완료', kind: 'GLOBAL' },
    })

    // 출발지가 없다는 것이 GLOBAL 의 정의다. from 을 실어 보내면 백엔드가 400 이다.
    expect(state.draft.transitions.at(-1)?.from).toBeNull()
  })

  it('INITIAL 이 이미 있으면 두 번째를 거절한다', () => {
    const before = loaded()

    const state = draftReducer(before, {
      type: 'createTransition',
      input: { from: null, to: 'done', name: '또 다른 시작', kind: 'INITIAL' },
    })

    // 발행 경계가 「정확히 1개」를 요구한다. 로컬에서 막으면 저장 왕복 없이 즉시 안다.
    expect(state.draft.transitions).toHaveLength(before.draft.transitions.length)
    expect(state.lastRejection).toBeTruthy()
  })

  it('deleteTransition 이 그 전환만 지운다', () => {
    const before = loaded()
    const target = before.draft.transitions.find((t) => t.name === '완료')!

    const state = draftReducer(before, { type: 'deleteTransition', localId: target.localId })

    expect(state.draft.transitions.map((t) => t.name)).toEqual(['이슈 생성', '작업 시작'])
  })
})

describe('revision', () => {
  it('편집마다 오른다', () => {
    const a = draftReducer(loaded(), { type: 'setName', value: '새 이름' })
    const b = draftReducer(a, { type: 'setDescription', value: '설명' })

    expect(a.revision).toBe(1)
    expect(b.revision).toBe(2)
  })

  it('거절된 편집은 revision 을 올리지 않는다', () => {
    // 올리면 자동저장이 「바뀐 게 있다」고 믿고 같은 정의를 다시 보낸다.
    const before = loaded()
    const state = draftReducer(before, {
      type: 'createTransition',
      input: { from: null, to: 'done', name: '또 다른 시작', kind: 'INITIAL' },
    })

    expect(state.revision).toBe(before.revision)
  })

  it('resetToDefault 가 0 으로 되돌리고 앵커를 갈아 끼운다', () => {
    const edited = draftReducer(loaded(), { type: 'setName', value: '고친 이름' })

    const state = draftReducer(edited, {
      type: 'resetToDefault',
      definition: definition(),
      baseVersion: 9,
      canResetToDefault: true,
    })

    expect(state.revision).toBe(0)
    expect(state.baseVersion).toBe(9)
    expect(state.draft.name).toBe('워크플로우')
  })
})

describe('toWireDefinition', () => {
  it('로컬 id 를 떼고 규칙은 남긴다', () => {
    const wire = toWireDefinition(loaded().draft)

    expect(wire.transitions[1]).not.toHaveProperty('localId')
    expect(wire.transitions[1]?.validators).toHaveLength(1)
  })

  it('스키마가 통과시키는 형태다', async () => {
    // 로컬 필드가 남으면 `.strict()` 스키마가 저장 요청을 만들기 전에 터진다.
    const { draftDefinitionSchema } = await import('@/api/workflows-draft.types')

    expect(() => draftDefinitionSchema.parse(toWireDefinition(loaded().draft))).not.toThrow()
  })
})

describe('파생 셀렉터', () => {
  const published = definition()

  it('removedStatusKeys 는 발행본에는 있고 초안에 없는 상태다', () => {
    const state = draftReducer(loaded(), { type: 'removeState', key: 'done' })

    expect(removedStatusKeys(state.draft, published)).toEqual(['done'])
  })

  it('migrationTargets 는 초안과 발행본 **양쪽에** 있는 상태뿐이다', () => {
    // ★ 백엔드 F8(초안에 있어야) + F16(발행 전 도착지 금지)을 화면이 그대로 지킨다.
    //   새로 추가한 상태는 아직 발행되지 않아 도착지가 될 수 없다.
    const withNew = draftReducer(loaded(), {
      type: 'addState',
      entry: { key: 'blocked', name: '차단됨', category: 'TODO' },
    })
    const state = draftReducer(withNew, { type: 'removeState', key: 'done' })

    const targets = migrationTargets(state.draft, published)

    expect(targets.map((s) => s.key)).toEqual(['open', 'doing'])
    expect(targets.map((s) => s.key)).not.toContain('blocked')
  })

  it('stateNameMap 은 키를 사람이 읽는 이름으로 옮긴다', () => {
    expect(stateNameMap(published)['done']).toBe('완료')
  })
})

describe('publishBlockReason', () => {
  it('정상 정의는 사유가 없다', () => {
    expect(publishBlockReason(loaded().draft)).toBeNull()
  })

  it('시작 전환이 없으면 사유를 준다', () => {
    // INITIAL 이 가리키던 상태를 빼면 그 전환도 캐스케이드로 사라진다 — 발행이 400 이 될
    // 것을 저장 왕복 없이 로컬에서 안다.
    const state = draftReducer(loaded(), { type: 'removeState', key: 'open' })

    expect(state.draft.transitions.some((t) => t.kind === 'INITIAL')).toBe(false)
    expect(publishBlockReason(state.draft)).toBeTruthy()
  })

  it('상태가 하나도 없으면 사유를 준다', () => {
    let state = loaded()
    for (const key of ['open', 'doing', 'done']) {
      state = draftReducer(state, { type: 'removeState', key })
    }

    expect(publishBlockReason(state.draft)).toBeTruthy()
  })
})

describe('moveState — 다이어그램 노드 배치 (FR-WF-07 D8)', () => {
  it('좌표를 바꾸고 초안을 dirty 로 만든다', () => {
    const state = draftReducer(loaded(), { type: 'moveState', key: 'doing', x: 120.5, y: -40 })

    const moved = state.draft.states.find((s) => s.key === 'doing')
    expect(moved?.layoutX).toBe(120.5)
    expect(moved?.layoutY).toBe(-40)
    expect(state.revision).toBeGreaterThan(0)
  })

  it('다른 상태의 좌표를 건드리지 않는다', () => {
    const state = draftReducer(loaded(), { type: 'moveState', key: 'doing', x: 10, y: 20 })

    // 옮기지 않은 두 상태는 서버가 준 값(여기서는 null)을 그대로 지킨다.
    expect(state.draft.states.find((s) => s.key === 'open')?.layoutX).toBeNull()
    expect(state.draft.states.find((s) => s.key === 'done')?.layoutX).toBeNull()
  })

  it('유한수가 아닌 좌표는 무시한다', () => {
    // NaN·Infinity 가 초안에 실리면 PUT 이 400 이 되는데, 화면은 끌던 노드가 제자리로
    // 돌아가는 것만 보고 이유를 모른다. 리듀서에서 먼저 막는다.
    const before = loaded()
    const nan = draftReducer(before, { type: 'moveState', key: 'doing', x: Number.NaN, y: 0 })
    const inf = draftReducer(before, { type: 'moveState', key: 'doing', x: 0, y: Number.POSITIVE_INFINITY })

    expect(nan.draft.states.find((s) => s.key === 'doing')?.layoutX).toBeNull()
    expect(inf.draft.states.find((s) => s.key === 'doing')?.layoutY).toBeNull()
    expect(nan.revision).toBe(before.revision)
    expect(inf.revision).toBe(before.revision)
  })

  it('없는 상태 키는 아무것도 바꾸지 않는다', () => {
    const before = loaded()
    const state = draftReducer(before, { type: 'moveState', key: 'ghost', x: 1, y: 2 })

    expect(state.draft.states).toEqual(before.draft.states)
    expect(state.revision).toBe(before.revision)
  })

  it('새로 추가한 상태는 좌표가 null 이라 자동 배치를 받는다', () => {
    const state = draftReducer(loaded(), {
      type: 'addState',
      entry: { key: 'blocked', name: '막힘', category: 'TODO' },
    })

    const added = state.draft.states.find((s) => s.key === 'blocked')
    expect(added?.layoutX).toBeNull()
    expect(added?.layoutY).toBeNull()
  })
})
