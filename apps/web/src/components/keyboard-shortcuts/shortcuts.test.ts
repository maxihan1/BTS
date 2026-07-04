// 전역 키보드 단축키 레지스트리 + keydown 판별 순수 로직 단위 테스트 — FR-UX-05 Task-1
import { describe, it, expect } from 'vitest'
import {
  resolveKeydown,
  shouldIgnoreEvent,
  SHORTCUTS,
  PALETTE_HELP_ITEM,
  LEADER_KEY,
  LEADER_TIMEOUT_MS,
} from './shortcuts'

describe('resolveKeydown', () => {
  it('c는 새 이슈 생성 navigate 액션을 반환한다', () => {
    expect(resolveKeydown({ key: 'c' }, null, false)).toEqual({
      kind: 'navigate',
      to: '/issues/new',
    })
  })

  it('/는 검색 navigate 액션을 반환한다', () => {
    expect(resolveKeydown({ key: '/' }, null, false)).toEqual({
      kind: 'navigate',
      to: '/search',
    })
  })

  it('?(Shift+/)는 toggle-help 액션을 반환한다 — e.key로 /와 구분', () => {
    expect(resolveKeydown({ key: '?' }, null, false)).toEqual({ kind: 'toggle-help' })
  })

  it('g(대기 중인 leader 없음)는 set-leader 액션을 반환한다', () => {
    expect(resolveKeydown({ key: 'g' }, null, false)).toEqual({
      kind: 'set-leader',
      leader: 'g',
    })
  })

  it('g 직후 i(pending=g)는 내 이슈로 navigate한다', () => {
    expect(resolveKeydown({ key: 'i' }, 'g', false)).toEqual({
      kind: 'navigate',
      to: '/issues',
    })
  })

  it('g 직후 d(pending=g)는 대시보드로 navigate한다', () => {
    expect(resolveKeydown({ key: 'd' }, 'g', false)).toEqual({
      kind: 'navigate',
      to: '/dashboards',
    })
  })

  it('g 후 무효 키(x)는 시퀀스를 리셋한다 (E2)', () => {
    expect(resolveKeydown({ key: 'x' }, 'g', false)).toEqual({ kind: 'reset' })
  })

  it('pending=g에서 다시 g가 오면 리셋이 아니라 재대기(set-leader)한다 (E3)', () => {
    expect(resolveKeydown({ key: 'g' }, 'g', false)).toEqual({
      kind: 'set-leader',
      leader: 'g',
    })
  })

  it('등록되지 않은 단일 키는 none을 반환한다', () => {
    expect(resolveKeydown({ key: 'z' }, null, false)).toEqual({ kind: 'none' })
  })

  it('도움말 열림 중에는 ?만 반응하고 나머지는 모두 none이다 (E7)', () => {
    expect(resolveKeydown({ key: '?' }, null, true)).toEqual({ kind: 'toggle-help' })
    expect(resolveKeydown({ key: 'c' }, null, true)).toEqual({ kind: 'none' })
    expect(resolveKeydown({ key: '/' }, null, true)).toEqual({ kind: 'none' })
    expect(resolveKeydown({ key: 'g' }, null, true)).toEqual({ kind: 'none' })
    expect(resolveKeydown({ key: 'i' }, 'g', true)).toEqual({ kind: 'none' })
  })
})

describe('shouldIgnoreEvent', () => {
  const baseEvent = {
    isComposing: false,
    metaKey: false,
    ctrlKey: false,
    altKey: false,
    target: null as EventTarget | null,
  }

  it('IME 조합 중이면 true를 반환한다 (E5)', () => {
    expect(shouldIgnoreEvent({ ...baseEvent, isComposing: true })).toBe(true)
  })

  it('meta/ctrl/alt 수정자가 눌리면 true를 반환한다 (FR6)', () => {
    expect(shouldIgnoreEvent({ ...baseEvent, metaKey: true })).toBe(true)
    expect(shouldIgnoreEvent({ ...baseEvent, ctrlKey: true })).toBe(true)
    expect(shouldIgnoreEvent({ ...baseEvent, altKey: true })).toBe(true)
  })

  it('input/textarea/select 포커스면 true를 반환한다 (E4)', () => {
    const input = document.createElement('input')
    const textarea = document.createElement('textarea')
    const select = document.createElement('select')
    expect(shouldIgnoreEvent({ ...baseEvent, target: input })).toBe(true)
    expect(shouldIgnoreEvent({ ...baseEvent, target: textarea })).toBe(true)
    expect(shouldIgnoreEvent({ ...baseEvent, target: select })).toBe(true)
  })

  it('contentEditable 요소면 true를 반환한다', () => {
    const div = document.createElement('div')
    div.setAttribute('contenteditable', 'true')
    expect(shouldIgnoreEvent({ ...baseEvent, target: div })).toBe(true)
  })

  it('수정자 없는 일반 keydown은 false를 반환한다 (Shift 단독 허용 포함)', () => {
    expect(shouldIgnoreEvent(baseEvent)).toBe(false)
    const div = document.createElement('div')
    expect(shouldIgnoreEvent({ ...baseEvent, target: div })).toBe(false)
  })
})

describe('SHORTCUTS', () => {
  it('5종 단축키를 정의한다 (? / c / / / g i / g d)', () => {
    expect(SHORTCUTS).toHaveLength(5)
    expect(SHORTCUTS.map((shortcut) => shortcut.keys)).toEqual([
      ['?'],
      ['c'],
      ['/'],
      ['g', 'i'],
      ['g', 'd'],
    ])
    for (const shortcut of SHORTCUTS) {
      expect(shortcut.description.length).toBeGreaterThan(0)
    }
  })
})

describe('PALETTE_HELP_ITEM', () => {
  it('명령 팔레트 도움말 표기 항목을 제공한다', () => {
    expect(PALETTE_HELP_ITEM).toEqual({ keys: ['Cmd/Ctrl', 'K'], description: '명령 팔레트 열기' })
  })
})

describe('상수', () => {
  it('LEADER_KEY는 g, LEADER_TIMEOUT_MS는 1000ms이다', () => {
    expect(LEADER_KEY).toBe('g')
    expect(LEADER_TIMEOUT_MS).toBe(1000)
  })
})
