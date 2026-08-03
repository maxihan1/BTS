// 컨텍스트 단축키 등록 훅/스토어 단위 테스트 — FR-UX-10 F10 Task-2
import { renderHook } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  dispatchContextAction,
  resolveActiveContext,
  useContextShortcuts,
  useContextShortcutsStore,
} from './useContextShortcuts'

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
// dispatch — 판별된 액션을 등록 핸들러로 흘린다. 미등록이면 조용히 무동작.
// ─────────────────────────────────────────────────────────────────────────────

describe('dispatchContextAction — 액션 → 핸들러', () => {
  it('cursor-move 는 delta 를 그대로 전달한다', () => {
    const onCursorMove = vi.fn()
    renderHook(() => useContextShortcuts('issue-list', { onCursorMove }))

    dispatchContextAction({ kind: 'cursor-move', delta: 1 })
    dispatchContextAction({ kind: 'cursor-move', delta: -1 })

    expect(onCursorMove).toHaveBeenNthCalledWith(1, 1)
    expect(onCursorMove).toHaveBeenNthCalledWith(2, -1)
  })

  it('open-current · toggle-detail-pane 을 각 핸들러로 흘린다', () => {
    const onOpenCurrent = vi.fn()
    const onToggleDetailPane = vi.fn()
    renderHook(() => useContextShortcuts('issue-list', { onOpenCurrent, onToggleDetailPane }))

    dispatchContextAction({ kind: 'open-current' })
    dispatchContextAction({ kind: 'toggle-detail-pane' })

    expect(onOpenCurrent).toHaveBeenCalledOnce()
    expect(onToggleDetailPane).toHaveBeenCalledOnce()
  })

  it('★레이어 병합 — issue-list 활성 중에도 app-shell 의 toggle-sidebar 가 호출된다', () => {
    const onToggleSidebar = vi.fn()
    renderHook(() => useContextShortcuts('app-shell', { onToggleSidebar }))
    renderHook(() => useContextShortcuts('issue-list', {}))

    dispatchContextAction({ kind: 'toggle-sidebar' })

    expect(onToggleSidebar).toHaveBeenCalledOnce()
  })

  it('핸들러가 등록되지 않은 액션은 조용히 무동작이다 (던지지 않는다)', () => {
    renderHook(() => useContextShortcuts('issue-list', {}))

    expect(() => dispatchContextAction({ kind: 'open-current' })).not.toThrow()
    expect(() => dispatchContextAction({ kind: 'cursor-move', delta: 1 })).not.toThrow()
  })

  it('none 액션은 아무 핸들러도 호출하지 않는다', () => {
    const onCursorMove = vi.fn()
    const onOpenCurrent = vi.fn()
    renderHook(() => useContextShortcuts('issue-list', { onCursorMove, onOpenCurrent }))

    dispatchContextAction({ kind: 'none' })

    expect(onCursorMove).not.toHaveBeenCalled()
    expect(onOpenCurrent).not.toHaveBeenCalled()
  })
})
