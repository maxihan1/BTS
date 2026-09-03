// 전환 key 규칙 정본의 형식 고정 + React key 고유성 — 픽스처 대조가 공허해지지 않게 하는 짝
import { describe, it, expect } from 'vitest'
import {
  transitionKey,
  kindTransitionKey,
  composeTransitionKey,
  transitionElementKey,
} from './transition-key'

/**
 * ★**이 파일이 규칙의 형식을 붙잡는다.**
 *
 * `workflow-fixtures.test.ts` 는 「픽스처가 정본 함수를 썼는가」만 잰다 — 정본과 픽스처가
 * 같은 함수를 쓰면 규칙이 통째로 틀려도 그 대조는 초록이다. 그래서 형식 자체는 여기서
 * **리터럴 기대값**으로 고정한다. 둘 중 하나만 있으면 공허하다.
 */
describe('전환 key 합성 규칙 — backend WorkflowTransition.key 게터와 같은 형식', () => {
  it('NORMAL 은 `from__to` 다', () => {
    expect(transitionKey('open', 'in_progress')).toBe('open__in_progress')
  })

  it('GLOBAL·INITIAL 은 `KIND__to` 다', () => {
    expect(kindTransitionKey('GLOBAL', 'done')).toBe('GLOBAL__done')
    expect(kindTransitionKey('INITIAL', 'open')).toBe('INITIAL__open')
  })

  it('출발 상태가 있으면 NORMAL 분기로 간다', () => {
    expect(composeTransitionKey({ fromStateKey: 'open', toStateKey: 'done' })).toBe('open__done')
  })

  it('출발 상태가 없으면 종류로 갈라진다 — null 하나로는 GLOBAL·INITIAL 을 구별할 수 없다', () => {
    expect(composeTransitionKey({ fromStateKey: null, toStateKey: 'done', kind: 'GLOBAL' })).toBe(
      'GLOBAL__done',
    )
    expect(composeTransitionKey({ fromStateKey: null, toStateKey: 'open', kind: 'INITIAL' })).toBe(
      'INITIAL__open',
    )
  })
})

describe('transitionElementKey — 형제 사이에서 고유하다', () => {
  it('`transitionId` 가 있으면 그것을 쓴다 — 1급 식별자다', () => {
    const id = '11111111-1111-4111-8111-111111111111'

    expect(transitionElementKey({ transitionId: id, key: 'open__done', toStateKey: 'done' }, 0)).toBe(
      id,
    )
  })

  it('★같은 (from, to) 쌍의 전환 둘이 서로 다른 key 를 받는다', () => {
    // `UNIQUE(workflow_id, from, to)` 해제로 실제로 생길 수 있는 조합이고,
    // 서버가 409 `AMBIGUOUS_TRANSITION` 을 내는 바로 그 상황이다.
    const same = { key: 'open__done', fromStateKey: 'open', toStateKey: 'done' }

    expect(transitionElementKey(same, 0)).not.toBe(transitionElementKey(same, 1))
  })

  it('`key` 가 null 이어도 값을 만든다 — 서버 계약상 「미계산」이 올 수 있다', () => {
    const produced = transitionElementKey(
      { key: null, fromStateKey: 'open', toStateKey: 'done' },
      2,
    )

    expect(produced).toContain('open__done')
    expect(produced).not.toContain('null')
  })

  it('`key` 도 `fromStateKey` 도 없는 GLOBAL 전환에서 서로 다른 값을 만든다', () => {
    const global = { key: null, fromStateKey: null, toStateKey: 'done' }

    expect(transitionElementKey(global, 0)).not.toBe(transitionElementKey(global, 1))
  })
})
