// 컨텍스트 단축키 레지스트리·판별·커서 경계 계산 단위 테스트 — FR-UX-10 F10 Task-1
import { describe, expect, it } from 'vitest'
import {
  CONTEXT_SHORTCUTS,
  nextCursorKey,
  resolveContextKeydown,
  type ShortcutContext,
} from './context-shortcuts'

// ─────────────────────────────────────────────────────────────────────────────
// 레지스트리 — 단일 진실 출처(FR8/C4). 훅 dispatch 와 도움말 모달이 이 상수를 함께 구동한다.
// ─────────────────────────────────────────────────────────────────────────────

describe('CONTEXT_SHORTCUTS 레지스트리', () => {
  it('F10 범위인 5종만 담는다 — F11 상세 액션 키는 아직 없다', () => {
    expect(CONTEXT_SHORTCUTS).toHaveLength(5)
    expect(CONTEXT_SHORTCUTS.map((s) => s.key)).toEqual(['j', 'k', 'o', 't', '['])
  })

  it('각 항목이 key·context·description·action 을 모두 채운다', () => {
    for (const shortcut of CONTEXT_SHORTCUTS) {
      expect(shortcut.key).toBeTruthy()
      expect(shortcut.description).toBeTruthy()
      expect(shortcut.action.kind).not.toBe('none')
      expect(['issue-list', 'app-shell']).toContain(shortcut.context)
    }
  })

  it('목록 항법 4종은 issue-list, 사이드바 토글은 app-shell 컨텍스트다', () => {
    const contextOf = (key: string): string =>
      CONTEXT_SHORTCUTS.find((s) => s.key === key)?.context ?? 'MISSING'

    expect(contextOf('j')).toBe('issue-list')
    expect(contextOf('k')).toBe('issue-list')
    expect(contextOf('o')).toBe('issue-list')
    expect(contextOf('t')).toBe('issue-list')
    expect(contextOf('[')).toBe('app-shell')
  })

  it('키가 중복되지 않는다 — 한 키가 두 동작으로 갈리면 판별이 모호해진다', () => {
    const keys = CONTEXT_SHORTCUTS.map((s) => s.key)
    expect(new Set(keys).size).toBe(keys.length)
  })

  it('전역 단축키 5종(? c / g)과 키가 겹치지 않는다 — 겹치면 전역이 항상 이겨 컨텍스트 키가 죽는다', () => {
    const globalKeys = ['?', 'c', '/', 'g']
    for (const shortcut of CONTEXT_SHORTCUTS) {
      expect(globalKeys).not.toContain(shortcut.key)
    }
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 판별 — 컨텍스트는 레이어다(FR2). issue-list ⊃ app-shell, 좁은 쪽부터 찾는다.
// ─────────────────────────────────────────────────────────────────────────────

describe('resolveContextKeydown — 컨텍스트 레이어 판별', () => {
  it('issue-list 에서 j 는 커서 아래로 이동한다', () => {
    expect(resolveContextKeydown('j', 'issue-list')).toEqual({
      layer: 'issue-list',
      action: { kind: 'cursor-move', delta: 1 },
    })
  })

  it('issue-list 에서 k 는 커서 위로 이동한다', () => {
    expect(resolveContextKeydown('k', 'issue-list')).toEqual({
      layer: 'issue-list',
      action: { kind: 'cursor-move', delta: -1 },
    })
  })

  it('issue-list 에서 o 는 현재 커서 이슈를 연다', () => {
    expect(resolveContextKeydown('o', 'issue-list')).toEqual({
      layer: 'issue-list',
      action: { kind: 'open-current' },
    })
  })

  it('issue-list 에서 t 는 상세 페인을 토글한다', () => {
    expect(resolveContextKeydown('t', 'issue-list')).toEqual({
      layer: 'issue-list',
      action: { kind: 'toggle-detail-pane' },
    })
  })

  it('★넓은 컨텍스트 폴백 — issue-list 에서도 [ 는 app-shell 항목으로 발화한다', () => {
    expect(resolveContextKeydown('[', 'issue-list')).toEqual({
      layer: 'app-shell',
      action: { kind: 'toggle-sidebar' },
    })
  })

  it('★E12 — app-shell 에서 j/k/o/t 는 무동작이다 (목록 밖에서 커서가 움직이면 안 된다)', () => {
    for (const key of ['j', 'k', 'o', 't']) {
      expect(resolveContextKeydown(key, 'app-shell')).toBeNull()
    }
  })

  it('app-shell 에서 [ 는 발화한다', () => {
    expect(resolveContextKeydown('[', 'app-shell')).toEqual({
      layer: 'app-shell',
      action: { kind: 'toggle-sidebar' },
    })
  })

  it('등록되지 않은 키는 어느 컨텍스트에서도 무동작이다', () => {
    const contexts: ShortcutContext[] = ['issue-list', 'app-shell']
    for (const context of contexts) {
      expect(resolveContextKeydown('z', context)).toBeNull()
      expect(resolveContextKeydown('J', context)).toBeNull()
    }
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 커서 경계 — E1~E5. wrap 하지 않고 경계에서 멈춘다(FR6).
// null = 무동작(커서를 바꾸지 않는다).
// ─────────────────────────────────────────────────────────────────────────────

describe('nextCursorKey — 커서 경계 계산', () => {
  const keys = ['ATLAS-1', 'ATLAS-2', 'ATLAS-3']

  it('중간에서 아래로 한 칸 이동한다', () => {
    expect(nextCursorKey(keys, 'ATLAS-2', 1)).toBe('ATLAS-3')
  })

  it('중간에서 위로 한 칸 이동한다', () => {
    expect(nextCursorKey(keys, 'ATLAS-2', -1)).toBe('ATLAS-1')
  })

  it('E1 — 목록이 비면 어느 방향이든 무동작이다', () => {
    expect(nextCursorKey([], null, 1)).toBeNull()
    expect(nextCursorKey([], 'ATLAS-1', -1)).toBeNull()
  })

  it('E2 — 커서가 없으면 j 가 첫 행을 잡는다', () => {
    expect(nextCursorKey(keys, null, 1)).toBe('ATLAS-1')
  })

  it('E2 — 커서가 없으면 k 도 첫 행을 잡는다 (wrap 없음 규칙과 일관)', () => {
    expect(nextCursorKey(keys, null, -1)).toBe('ATLAS-1')
  })

  it('E3 — 첫 행에서 위로는 무동작이다 (마지막으로 감싸지 않는다)', () => {
    expect(nextCursorKey(keys, 'ATLAS-1', -1)).toBeNull()
  })

  it('E4 — 마지막 행에서 아래로는 무동작이다 (첫 행으로 감싸지 않는다)', () => {
    expect(nextCursorKey(keys, 'ATLAS-3', 1)).toBeNull()
  })

  it('E5 — 커서가 현재 목록에 없으면(필터 변경 직후) 첫 행부터 시작한다', () => {
    expect(nextCursorKey(keys, 'INFRA-99', 1)).toBe('ATLAS-1')
    expect(nextCursorKey(keys, 'INFRA-99', -1)).toBe('ATLAS-1')
  })

  it('행이 하나뿐이면 커서 진입은 되지만 그 뒤 이동은 무동작이다', () => {
    const single = ['ATLAS-1']
    expect(nextCursorKey(single, null, 1)).toBe('ATLAS-1')
    expect(nextCursorKey(single, 'ATLAS-1', 1)).toBeNull()
    expect(nextCursorKey(single, 'ATLAS-1', -1)).toBeNull()
  })
})
