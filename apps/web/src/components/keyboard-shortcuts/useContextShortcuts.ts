// 화면이 자신의 컨텍스트 단축키 핸들러를 등록/해제하는 훅 + zustand 공유 스토어 — FR-UX-10 F10 Task-2
//
// 🛑 이 모듈은 keydown 리스너를 걸지 않는다. 발화는 `useKeyboardShortcuts`(RootLayout
// 단일 마운트)의 판별 파이프라인이 전담한다 — 컨텍스트 단축키가 전역 `SHORTCUTS` 와
// 같은 키 공간을 공유하므로 leader 대기·도움말 열림 가드를 한 곳에서 걸어야 한다.
// (ADR docs/decisions/2026-08-03-fr-ux-10-f10-context-shortcuts.md D-2)
import { useEffect, useRef } from 'react'
import { create } from 'zustand'
import type { ContextShortcutAction, ShortcutContext } from './context-shortcuts'

// ─────────────────────────────────────────────────────────────────────────────
// 타입
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 화면이 제공하는 컨텍스트 단축키 동작 핸들러.
 *
 * 전부 옵셔널이다 — 등록하지 않은 동작은 조용히 무동작이 된다. 예를 들어
 * `app-shell` 은 `onToggleSidebar` 만 제공하고 목록 항법 핸들러는 두지 않는다.
 */
export interface ContextShortcutHandlers {
  /** 목록 커서 이동 — `delta` 는 +1(아래) 또는 -1(위) */
  readonly onCursorMove?: (delta: 1 | -1) => void
  /** 커서 이슈를 전체화면 상세로 열기 */
  readonly onOpenCurrent?: () => void
  /** 우측 상세 페인(split view) 열기/닫기 */
  readonly onToggleDetailPane?: () => void
  /** 사이드바 접기/펼치기 */
  readonly onToggleSidebar?: () => void
}

/** 컨텍스트별 등록 핸들러를 담는 스토어 상태 */
interface ContextShortcutsState {
  /** 현재 마운트된 컨텍스트 → 그 화면이 등록한 핸들러 */
  readonly handlers: Partial<Record<ShortcutContext, ContextShortcutHandlers>>
  /** 컨텍스트 핸들러를 등록(또는 갱신)한다 */
  readonly register: (context: ShortcutContext, handlers: ContextShortcutHandlers) => void
  /** 컨텍스트 등록을 해제한다 */
  readonly unregister: (context: ShortcutContext) => void
}

// ─────────────────────────────────────────────────────────────────────────────
// 스토어 — 모듈 전역 단일 인스턴스 (use-sidebar-collapsed.ts 패턴 승계)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 컨텍스트 단축키 핸들러 레지스트리.
 *
 * 컴포넌트 트리 밖(전역 keydown 리스너)에서 `getState()` 로 읽어야 하므로
 * Context API 가 아니라 zustand 를 쓴다 — `use-sidebar-collapsed.ts` 와 같은 이유.
 */
export const useContextShortcutsStore = create<ContextShortcutsState>((set) => ({
  handlers: {},
  register: (context, handlers): void =>
    set((state) => ({ handlers: { ...state.handlers, [context]: handlers } })),
  unregister: (context): void =>
    set((state) => {
      const next = { ...state.handlers }
      delete next[context]
      return { handlers: next }
    }),
}))

// ─────────────────────────────────────────────────────────────────────────────
// 활성 컨텍스트 판정 + dispatch
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 컨텍스트 우선순위 — 숫자가 작을수록 좁다(먼저 선택된다).
 *
 * ★`Record<ShortcutContext, …>` 로 둔 것이 요점이다. `ShortcutContext` 유니온에
 * 새 레이어(F11 의 `issue-detail`, 보드 등)를 추가하면 여기 항목이 빠졌을 때
 * **타입 에러로 즉시 막힌다**. 배열이나 if 분기로 두면 새 컨텍스트가 조용히
 * 우선순위 밖으로 떨어져 영영 활성이 되지 않는다.
 */
const CONTEXT_PRIORITY: Record<ShortcutContext, number> = {
  'issue-list': 0,
  'app-shell': 1,
}

/** 좁은 순으로 정렬된 컨텍스트 목록 — 판정에서 앞에서부터 훑는다 */
const CONTEXTS_NARROWEST_FIRST = (Object.keys(CONTEXT_PRIORITY) as ShortcutContext[]).sort(
  (a, b) => CONTEXT_PRIORITY[a] - CONTEXT_PRIORITY[b],
)

/**
 * 등록된 것 중 **가장 좁은** 컨텍스트를 활성으로 판정한다.
 *
 * 아무것도 등록되지 않았으면 `app-shell` 이 기본값이다 — 셸이 미등록이어도
 * `[` 판별 자체는 성립해야 하기 때문.
 *
 * @returns 현재 활성 컨텍스트
 */
export function resolveActiveContext(): ShortcutContext {
  const { handlers } = useContextShortcutsStore.getState()
  return CONTEXTS_NARROWEST_FIRST.find((context) => handlers[context] !== undefined) ?? 'app-shell'
}

/**
 * 판별된 액션을 등록 핸들러로 흘린다. 미등록 핸들러는 조용히 무동작.
 *
 * 핸들러는 **레이어 병합**으로 찾는다 — `issue-list` 가 활성이어도
 * `toggle-sidebar` 는 `app-shell` 이 등록한 핸들러로 간다. 액션 종류가 곧
 * 소속 레이어를 결정하므로 별도 컨텍스트 인자가 필요 없다.
 *
 * @param action `resolveContextKeydown` 이 판별한 동작
 */
export function dispatchContextAction(action: ContextShortcutAction): void {
  const { handlers } = useContextShortcutsStore.getState()
  const list = handlers['issue-list']
  const shell = handlers['app-shell']

  switch (action.kind) {
    case 'cursor-move':
      list?.onCursorMove?.(action.delta)
      return
    case 'open-current':
      list?.onOpenCurrent?.()
      return
    case 'toggle-detail-pane':
      list?.onToggleDetailPane?.()
      return
    case 'toggle-sidebar':
      shell?.onToggleSidebar?.()
      return
    case 'none':
      return
    default: {
      // ★exhaustive 가드 — `ContextShortcutAction` 에 새 액션을 추가하고 여기 case 를
      // 빠뜨리면 **컴파일 에러**가 난다. 이 default 가 없으면 반환 타입이 void 라
      // TypeScript 가 누락을 잡아주지 않아, 새 단축키가 판별까지는 되고 dispatch 에서
      // 조용히 사라진다(발화는 하는데 아무 일도 안 일어나는 가장 찾기 힘든 형태).
      const exhaustive: never = action
      void exhaustive
      return
    }
  }
}

// ─────────────────────────────────────────────────────────────────────────────
// 훅
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 이 화면의 컨텍스트 단축키 핸들러를 등록하고, 언마운트 시 해제한다.
 *
 * 핸들러 객체는 매 렌더 새로 만들어지는 것이 보통이라(인라인 화살표 함수)
 * 의존성 배열에 직접 넣으면 매 렌더 재등록이 돈다. ref 미러링으로 **최신
 * 핸들러를 안정 래퍼 뒤에 숨겨** 등록은 마운트/언마운트 시 1회씩만 일어나되
 * 호출은 항상 최신 함수로 가게 한다(stale 클로저 방지).
 *
 * @param context 이 화면이 여는 컨텍스트 레이어
 * @param handlers 동작별 핸들러(전부 옵셔널)
 */
export function useContextShortcuts(
  context: ShortcutContext,
  handlers: ContextShortcutHandlers,
): void {
  const handlersRef = useRef(handlers)
  handlersRef.current = handlers

  useEffect(() => {
    const { register, unregister } = useContextShortcutsStore.getState()
    register(context, {
      onCursorMove: (delta) => handlersRef.current.onCursorMove?.(delta),
      onOpenCurrent: () => handlersRef.current.onOpenCurrent?.(),
      onToggleDetailPane: () => handlersRef.current.onToggleDetailPane?.(),
      onToggleSidebar: () => handlersRef.current.onToggleSidebar?.(),
    })
    return () => unregister(context)
  }, [context])
}
