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
  it('14종(F10 목록·셸 5 + F11 상세 액션 8 + F24 드로어 닫기 1)이 순서대로 등록돼 있다', () => {
    expect(CONTEXT_SHORTCUTS).toHaveLength(14)
    expect(CONTEXT_SHORTCUTS.map((s) => s.key)).toEqual([
      'j',
      'k',
      'o',
      't',
      '[',
      'a',
      'i',
      'm',
      'e',
      'l',
      's',
      'w',
      '.',
      'Escape',
    ])
  })

  it('각 항목이 key·context·description·action 을 모두 채운다', () => {
    for (const shortcut of CONTEXT_SHORTCUTS) {
      expect(shortcut.key).toBeTruthy()
      expect(shortcut.description).toBeTruthy()
      expect(shortcut.action.kind).not.toBe('none')
      expect(['issue-detail', 'issue-list', 'app-shell']).toContain(shortcut.context)
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

  it('F11 상세 액션 7종은 issue-detail 레이어이고 팔레트만 app-shell 이다', () => {
    const contextOf = (key: string): string =>
      CONTEXT_SHORTCUTS.find((s) => s.key === key)?.context ?? 'MISSING'

    for (const key of ['a', 'i', 'm', 'e', 'l', 's', 'w']) {
      expect(contextOf(key)).toBe('issue-detail')
    }
    // `.` 은 팔레트를 여는데 팔레트는 전역 기능이다 — 목록 화면에서도 눌러야 열린다.
    expect(contextOf('.')).toBe('app-shell')
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
  // 판별의 3번째 인자는 **지금 등록돼 있는 레이어 집합**이다(E5). 이 describe 는
  // 폴백표 자체를 보는 자리라 전 레이어가 등록된 상태로 고정한다 — 미등록 레이어를
  // 건너뛰는 규칙은 아래 「등록 인지 폴백」 describe 가 따로 맡는다.
  const ALL_LAYERS: ReadonlySet<ShortcutContext> = new Set([
    'issue-detail',
    'issue-list',
    'app-shell',
  ])

  it('issue-list 에서 j 는 커서 아래로 이동한다', () => {
    expect(resolveContextKeydown('j', 'issue-list', ALL_LAYERS)).toEqual({
      layer: 'issue-list',
      action: { kind: 'cursor-move', delta: 1 },
    })
  })

  it('issue-list 에서 k 는 커서 위로 이동한다', () => {
    expect(resolveContextKeydown('k', 'issue-list', ALL_LAYERS)).toEqual({
      layer: 'issue-list',
      action: { kind: 'cursor-move', delta: -1 },
    })
  })

  it('issue-list 에서 o 는 현재 커서 이슈를 연다', () => {
    expect(resolveContextKeydown('o', 'issue-list', ALL_LAYERS)).toEqual({
      layer: 'issue-list',
      action: { kind: 'open-current' },
    })
  })

  it('issue-list 에서 t 는 상세 페인을 토글한다', () => {
    expect(resolveContextKeydown('t', 'issue-list', ALL_LAYERS)).toEqual({
      layer: 'issue-list',
      action: { kind: 'toggle-detail-pane' },
    })
  })

  it('★넓은 컨텍스트 폴백 — issue-list 에서도 [ 는 app-shell 항목으로 발화한다', () => {
    expect(resolveContextKeydown('[', 'issue-list', ALL_LAYERS)).toEqual({
      layer: 'app-shell',
      action: { kind: 'toggle-sidebar' },
    })
  })

  it('★E12 — app-shell 에서 j/k/o/t 는 무동작이다 (목록 밖에서 커서가 움직이면 안 된다)', () => {
    for (const key of ['j', 'k', 'o', 't']) {
      expect(resolveContextKeydown(key, 'app-shell', ALL_LAYERS)).toBeNull()
    }
  })

  it('app-shell 에서 [ 는 발화한다', () => {
    expect(resolveContextKeydown('[', 'app-shell', ALL_LAYERS)).toEqual({
      layer: 'app-shell',
      action: { kind: 'toggle-sidebar' },
    })
  })

  it('issue-detail 에서 a 는 담당자 선택 포커스로 판별된다 (F11)', () => {
    expect(resolveContextKeydown('a', 'issue-detail', ALL_LAYERS)).toEqual({
      layer: 'issue-detail',
      action: { kind: 'focus-assignee' },
    })
  })

  it('★상세 액션 키는 목록 화면에서 발화하지 않는다 — 좁은 레이어는 넓은 쪽으로 새지 않는다', () => {
    for (const key of ['a', 'i', 'm', 'e', 'l', 's', 'w']) {
      expect(resolveContextKeydown(key, 'issue-list', ALL_LAYERS)).toBeNull()
    }
  })

  it('★넓은 컨텍스트 폴백 — 목록 화면에서도 . 는 app-shell 항목으로 팔레트를 연다', () => {
    expect(resolveContextKeydown('.', 'issue-list', ALL_LAYERS)).toEqual({
      layer: 'app-shell',
      action: { kind: 'open-command-palette' },
    })
  })

  it('등록되지 않은 키는 어느 컨텍스트에서도 무동작이다', () => {
    const contexts: ShortcutContext[] = ['issue-list', 'app-shell']
    for (const context of contexts) {
      expect(resolveContextKeydown('z', context, ALL_LAYERS)).toBeNull()
      expect(resolveContextKeydown('J', context, ALL_LAYERS)).toBeNull()
    }
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 등록 인지 폴백 — E5 / S9. 정적 폴백표만 믿으면 두 방향으로 틀린다.
// 상세를 좁다는 이유로 단독 활성으로 두면 와이드 split 에서 j/k 가 죽고(S9),
// 반대로 폴백을 무조건 열면 전체화면 상세에서 j 가 preventDefault 만 하고 끝난다(E5).
// ─────────────────────────────────────────────────────────────────────────────

describe('resolveContextKeydown — 등록 인지 폴백 (E5)', () => {
  it('상세가 활성이어도 목록이 등록돼 있으면 j 가 목록 레이어로 판별된다 (S9 와이드 split)', () => {
    const registered = new Set<ShortcutContext>(['issue-detail', 'issue-list', 'app-shell'])
    expect(resolveContextKeydown('j', 'issue-detail', registered)).toEqual({
      layer: 'issue-list',
      action: { kind: 'cursor-move', delta: 1 },
    })
  })

  it('목록이 등록돼 있지 않으면 j 는 판별되지 않는다 (E5 — 전체화면 상세)', () => {
    const registered = new Set<ShortcutContext>(['issue-detail', 'app-shell'])
    expect(resolveContextKeydown('j', 'issue-detail', registered)).toBeNull()
  })

  it('등록되지 않은 app-shell 로는 폴백하지 않는다', () => {
    const registered = new Set<ShortcutContext>(['issue-list'])
    expect(resolveContextKeydown('[', 'issue-list', registered)).toBeNull()
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
