// 컨텍스트 단축키 등록 훅/스토어 단위 테스트 — FR-UX-10 F10 Task-2
import { renderHook } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  dispatchContextAction,
  getRegisteredContexts,
  resolveActiveContext,
  useContextShortcuts,
  useContextShortcutsStore,
  type ContextShortcutHandlers,
} from './useContextShortcuts'
import {
  CONTEXT_SHORTCUTS,
  type ContextShortcutAction,
  type ShortcutContext,
} from './context-shortcuts'

/** 각 테스트가 깨끗한 스토어에서 시작하도록 등록분을 비운다 */
beforeEach(() => {
  useContextShortcutsStore.setState({ handlers: {} })
})

// ─────────────────────────────────────────────────────────────────────────────
// 등록/해제 — 마운트 라이프사이클. 누수 0 (NFR5).
// ─────────────────────────────────────────────────────────────────────────────

describe('useContextShortcuts — 등록/해제', () => {
  it('마운트하면 그 컨텍스트가 활성이 된다', () => {
    renderHook(() => useContextShortcuts('issue-list', {}))

    expect(useContextShortcutsStore.getState().handlers['issue-list']).toBeDefined()
  })

  it('언마운트하면 등록이 사라진다 — 목록을 떠나면 j/k 가 죽어야 한다', () => {
    const { unmount } = renderHook(() => useContextShortcuts('issue-list', {}))
    unmount()

    expect(useContextShortcutsStore.getState().handlers['issue-list']).toBeUndefined()
  })

  it('두 컨텍스트를 동시에 등록할 수 있다 — 셸과 목록은 공존한다', () => {
    renderHook(() => useContextShortcuts('app-shell', {}))
    renderHook(() => useContextShortcuts('issue-list', {}))

    const { handlers } = useContextShortcutsStore.getState()
    expect(handlers['app-shell']).toBeDefined()
    expect(handlers['issue-list']).toBeDefined()
  })

  it('핸들러가 바뀌면 최신 것으로 갱신된다 (stale 클로저 방지)', () => {
    const first = vi.fn()
    const second = vi.fn()
    const { rerender } = renderHook(
      ({ handler }) => useContextShortcuts('issue-list', { onOpenCurrent: handler }),
      { initialProps: { handler: first } },
    )

    rerender({ handler: second })
    useContextShortcutsStore.getState().handlers['issue-list']?.onOpenCurrent?.()

    expect(first).not.toHaveBeenCalled()
    expect(second).toHaveBeenCalledOnce()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 활성 컨텍스트 판정 — 좁은 레이어가 이긴다(FR2).
// ─────────────────────────────────────────────────────────────────────────────

describe('resolveActiveContext — 활성 레이어 판정', () => {
  it('아무것도 등록되지 않았으면 app-shell 이 기본이다', () => {
    expect(resolveActiveContext()).toBe('app-shell')
  })

  it('issue-list 가 등록돼 있으면 그것이 활성이다 (좁은 쪽 우선)', () => {
    renderHook(() => useContextShortcuts('issue-list', {}))

    expect(resolveActiveContext()).toBe('issue-list')
  })

  it('issue-list 를 떠나면 app-shell 로 되돌아간다', () => {
    const { unmount } = renderHook(() => useContextShortcuts('issue-list', {}))
    unmount()

    expect(resolveActiveContext()).toBe('app-shell')
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// 등록 집합 노출 — 판별이 정적 폴백표를 그대로 믿지 않게 하는 입력(E5).
// ─────────────────────────────────────────────────────────────────────────────

describe('getRegisteredContexts — 등록 집합 노출', () => {
  it('등록된 컨텍스트 집합을 그대로 돌려준다', () => {
    useContextShortcutsStore.getState().register('issue-detail', {})
    useContextShortcutsStore.getState().register('app-shell', {})

    expect(getRegisteredContexts()).toEqual(new Set(['issue-detail', 'app-shell']))
  })

  it('★해제된 컨텍스트는 즉시 빠진다 — 와이드 split 에서 전체화면 상세로 넘어가면 목록이 사라진다', () => {
    const { unmount } = renderHook(() => useContextShortcuts('issue-list', {}))
    renderHook(() => useContextShortcuts('issue-detail', {}))
    unmount()

    expect(getRegisteredContexts()).toEqual(new Set(['issue-detail']))
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// dispatch — 판별된 액션을 등록 핸들러로 흘린다. 미등록이면 조용히 무동작.
// ─────────────────────────────────────────────────────────────────────────────

describe('dispatchContextAction — 액션 → 핸들러', () => {
  it('cursor-move 는 delta 를 그대로 전달한다', () => {
    const onCursorMove = vi.fn()
    renderHook(() => useContextShortcuts('issue-list', { onCursorMove }))

    dispatchContextAction({ layer: 'issue-list', action: { kind: 'cursor-move', delta: 1 } })
    dispatchContextAction({ layer: 'issue-list', action: { kind: 'cursor-move', delta: -1 } })

    expect(onCursorMove).toHaveBeenNthCalledWith(1, 1)
    expect(onCursorMove).toHaveBeenNthCalledWith(2, -1)
  })

  it('open-current · toggle-detail-pane 을 각 핸들러로 흘린다', () => {
    const onOpenCurrent = vi.fn()
    const onToggleDetailPane = vi.fn()
    renderHook(() => useContextShortcuts('issue-list', { onOpenCurrent, onToggleDetailPane }))

    dispatchContextAction({ layer: 'issue-list', action: { kind: 'open-current' } })
    dispatchContextAction({ layer: 'issue-list', action: { kind: 'toggle-detail-pane' } })

    expect(onOpenCurrent).toHaveBeenCalledOnce()
    expect(onToggleDetailPane).toHaveBeenCalledOnce()
  })

  it('★레이어 병합 — issue-list 활성 중에도 app-shell 의 toggle-sidebar 가 호출된다', () => {
    const onToggleSidebar = vi.fn()
    renderHook(() => useContextShortcuts('app-shell', { onToggleSidebar }))
    renderHook(() => useContextShortcuts('issue-list', {}))

    dispatchContextAction({ layer: 'app-shell', action: { kind: 'toggle-sidebar' } })

    expect(onToggleSidebar).toHaveBeenCalledOnce()
  })

  it('핸들러가 등록되지 않은 액션은 조용히 무동작이다 (던지지 않는다)', () => {
    renderHook(() => useContextShortcuts('issue-list', {}))

    expect(() => dispatchContextAction({ layer: 'issue-list', action: { kind: 'open-current' } })).not.toThrow()
    expect(() => dispatchContextAction({ layer: 'issue-list', action: { kind: 'cursor-move', delta: 1 } })).not.toThrow()
  })

  it('none 액션은 아무 핸들러도 호출하지 않는다', () => {
    const onCursorMove = vi.fn()
    const onOpenCurrent = vi.fn()
    renderHook(() => useContextShortcuts('issue-list', { onCursorMove, onOpenCurrent }))

    dispatchContextAction(null)

    expect(onCursorMove).not.toHaveBeenCalled()
    expect(onOpenCurrent).not.toHaveBeenCalled()
  })

  it('★레이어를 그대로 따른다 — 판별이 지목한 레이어의 핸들러만 불린다', () => {
    const listCursor = vi.fn()
    const shellCursor = vi.fn()
    renderHook(() => useContextShortcuts('issue-list', { onCursorMove: listCursor }))
    renderHook(() => useContextShortcuts('app-shell', { onCursorMove: shellCursor }))

    // 같은 액션이라도 레이어가 다르면 다른 핸들러로 간다. dispatch 가 액션 종류로
    // 레이어를 재추론하면 이 단언이 깨진다 — 재배치를 잡는 지점이다.
    dispatchContextAction({ layer: 'app-shell', action: { kind: 'cursor-move', delta: 1 } })

    expect(shellCursor).toHaveBeenCalledWith(1)
    expect(listCursor).not.toHaveBeenCalled()
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// dispatch — F11 상세 액션 8종 (FR-UX-10 F11)
//
// ★스토어의 `register()` 를 직접 부르지 않고 **훅을 렌더**한다. 직접 등록하면 훅이 세우는
// 안정 래퍼(`onFocusAssignee: () => handlersRef.current.onFocusAssignee?.()`)를 건너뛰어,
// 그 한 줄을 빠뜨려도 테스트가 초록으로 남는다 — 판별도 dispatch 도 통과하는데 화면
// 핸들러만 영영 안 불리는, 가장 찾기 힘든 형태다(F10 이 4종을 일일이 나열한 이유).
// ─────────────────────────────────────────────────────────────────────────────

/** 액션 하나와 그것을 받아야 할 핸들러 자리를 짝지은 표 — 이름 오배선이 한 줄로 드러난다 */
interface DispatchCase {
  readonly label: string
  readonly layer: ShortcutContext
  readonly action: ContextShortcutAction
  /** 스파이를 그 액션이 가야 할 핸들러 자리에 꽂아 등록 객체를 만든다 */
  readonly withSpy: (spy: () => void) => ContextShortcutHandlers
}

const F11_DISPATCH_CASES: readonly DispatchCase[] = [
  {
    label: 'a → focus-assignee',
    layer: 'issue-detail',
    action: { kind: 'focus-assignee' },
    withSpy: (spy) => ({ onFocusAssignee: spy }),
  },
  {
    label: 'i → assign-to-me',
    layer: 'issue-detail',
    action: { kind: 'assign-to-me' },
    withSpy: (spy) => ({ onAssignToMe: spy }),
  },
  {
    label: 'm → focus-comment',
    layer: 'issue-detail',
    action: { kind: 'focus-comment' },
    withSpy: (spy) => ({ onFocusComment: spy }),
  },
  {
    label: 'e → edit-title',
    layer: 'issue-detail',
    action: { kind: 'edit-title' },
    withSpy: (spy) => ({ onEditTitle: spy }),
  },
  {
    label: 'l → focus-labels',
    layer: 'issue-detail',
    action: { kind: 'focus-labels' },
    withSpy: (spy) => ({ onFocusLabels: spy }),
  },
  {
    label: 's → toggle-favorite',
    layer: 'issue-detail',
    action: { kind: 'toggle-favorite' },
    withSpy: (spy) => ({ onToggleFavorite: spy }),
  },
  {
    label: 'w → toggle-watch',
    layer: 'issue-detail',
    action: { kind: 'toggle-watch' },
    withSpy: (spy) => ({ onToggleWatch: spy }),
  },
  {
    label: '. → open-command-palette',
    layer: 'app-shell',
    action: { kind: 'open-command-palette' },
    withSpy: (spy) => ({ onOpenCommandPalette: spy }),
  },
  {
    label: 'Escape → close-sidebar-drawer',
    layer: 'sidebar-drawer',
    action: { kind: 'close-sidebar-drawer' },
    withSpy: (spy) => ({ onCloseSidebarDrawer: spy }),
  },
]

describe('dispatchContextAction — F11 상세 액션 8종', () => {
  it.each(F11_DISPATCH_CASES)('$label 을 등록 핸들러로 흘린다', ({ layer, action, withSpy }) => {
    const spy = vi.fn()
    renderHook(() => useContextShortcuts(layer, withSpy(spy)))

    dispatchContextAction({ layer, action })

    expect(spy).toHaveBeenCalledTimes(1)
  })

  it('★레지스트리 전량이 핸들러를 정확히 하나 부른다 (판별은 되는데 무동작인 키 0)', () => {
    // 레지스트리에서 파생하므로 키가 늘어도 이 단언은 따라온다 — switch case 누락(0회) ·
    // 래퍼 미러링 누락(0회) · 두 핸들러 동시 발화(2회)를 한 자리에서 잡는다.
    const spies = {
      onCursorMove: vi.fn(),
      onOpenCurrent: vi.fn(),
      onToggleDetailPane: vi.fn(),
      onToggleSidebar: vi.fn(),
      onFocusAssignee: vi.fn(),
      onAssignToMe: vi.fn(),
      onFocusComment: vi.fn(),
      onEditTitle: vi.fn(),
      onFocusLabels: vi.fn(),
      onToggleFavorite: vi.fn(),
      onToggleWatch: vi.fn(),
      onOpenCommandPalette: vi.fn(),
      onCloseSidebarDrawer: vi.fn(),
    } satisfies Required<ContextShortcutHandlers>
    const totalCalls = (): number =>
      Object.values(spies).reduce((sum, spy) => sum + spy.mock.calls.length, 0)

    renderHook(() => useContextShortcuts('issue-detail', spies))
    renderHook(() => useContextShortcuts('issue-list', spies))
    renderHook(() => useContextShortcuts('app-shell', spies))
    renderHook(() => useContextShortcuts('sidebar-drawer', spies))

    for (const shortcut of CONTEXT_SHORTCUTS) {
      const before = totalCalls()
      dispatchContextAction({ layer: shortcut.context, action: shortcut.action })

      expect(totalCalls() - before, `"${shortcut.key}" 가 핸들러를 정확히 1회 부르지 않았다`).toBe(
        1,
      )
    }
  })
})

// ─────────────────────────────────────────────────────────────────────────────
// enabled 게이트 (독립 리뷰 M-4 / I-3)
//
// 콜백만 undefined 로 넘기던 옛 방식은 등록이 남아 레이어가 활성이었고, 그래서 키를
// 삼키고(preventDefault) 아무 일도 안 했다. 등록 자체를 끊으면 판별 단계에서 갈린다.
// ─────────────────────────────────────────────────────────────────────────────

describe('useContextShortcuts — enabled 게이트', () => {
  it('enabled=false 면 등록하지 않는다', () => {
    renderHook(() => useContextShortcuts('issue-list', {}, false))

    expect(useContextShortcutsStore.getState().handlers['issue-list']).toBeUndefined()
    expect(resolveActiveContext()).toBe('app-shell')
  })

  it('★true → false 전환 시 기존 등록을 걷어낸다 (남으면 죽은 레이어가 활성으로 남는다)', () => {
    const { rerender } = renderHook(
      ({ enabled }) => useContextShortcuts('issue-list', {}, enabled),
      { initialProps: { enabled: true } },
    )
    expect(useContextShortcutsStore.getState().handlers['issue-list']).toBeDefined()

    rerender({ enabled: false })

    expect(useContextShortcutsStore.getState().handlers['issue-list']).toBeUndefined()
  })

  it('false → true 전환 시 다시 등록된다', () => {
    const { rerender } = renderHook(
      ({ enabled }) => useContextShortcuts('issue-list', {}, enabled),
      { initialProps: { enabled: false } },
    )

    rerender({ enabled: true })

    expect(useContextShortcutsStore.getState().handlers['issue-list']).toBeDefined()
  })

  it('생략하면 기본값 true 다 (기존 호출부 무회귀)', () => {
    renderHook(() => useContextShortcuts('issue-list', {}))

    expect(useContextShortcutsStore.getState().handlers['issue-list']).toBeDefined()
  })
})
