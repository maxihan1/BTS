// 화면이 자신의 컨텍스트 단축키 핸들러를 등록/해제하는 훅 + zustand 공유 스토어 — FR-UX-10 F10 Task-2
//
// 🛑 이 모듈은 keydown 리스너를 걸지 않는다. 발화는 `useKeyboardShortcuts`(RootLayout
// 단일 마운트)의 판별 파이프라인이 전담한다 — 컨텍스트 단축키가 전역 `SHORTCUTS` 와
// 같은 키 공간을 공유하므로 leader 대기·도움말 열림 가드를 한 곳에서 걸어야 한다.
// (ADR docs/decisions/2026-08-03-fr-ux-10-f10-context-shortcuts.md D-2)
import { useEffect, useRef } from 'react'
import { create } from 'zustand'
import type { ContextShortcutHit, ShortcutContext } from './context-shortcuts'

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
  /** 담당자 선택 컨트롤로 포커스 이동 (F11 `a`) */
  readonly onFocusAssignee?: () => void
  /** 담당자를 나로 지정, 이미 나면 해제 (F11 `i`) */
  readonly onAssignToMe?: () => void
  /** 댓글 입력창으로 포커스 이동 (F11 `m`) */
  readonly onFocusComment?: () => void
  /** 제목 인라인 편집 진입 (F11 `e`) */
  readonly onEditTitle?: () => void
  /** 라벨 편집 컨트롤로 포커스 이동 (F11 `l`) */
  readonly onFocusLabels?: () => void
  /** 이 이슈 즐겨찾기 켜기/끄기 (F11 `s`) */
  readonly onToggleFavorite?: () => void
  /** 이 이슈 관심(watch) 켜기/끄기 (F11 `w`) */
  readonly onToggleWatch?: () => void
  /** 명령 팔레트 열기 (F11 `.`) */
  readonly onOpenCommandPalette?: () => void
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
  'issue-detail': 0,
  'issue-list': 1,
  'app-shell': 2,
}

/** 좁은 순으로 정렬된 컨텍스트 목록 — 판정에서 앞에서부터 훑는다 */
const CONTEXTS_NARROWEST_FIRST = (Object.keys(CONTEXT_PRIORITY) as ShortcutContext[]).sort(
  (a, b) => CONTEXT_PRIORITY[a] - CONTEXT_PRIORITY[b],
)

/** 판별 한 번이 필요로 하는 두 값 — 활성 레이어와 등록 집합 */
export interface ContextSnapshot {
  /** 등록된 것 중 가장 좁은 레이어. 아무것도 없으면 `app-shell` */
  readonly active: ShortcutContext
  /** 핸들러가 등록돼 있는 레이어 집합 — 폴백 대상을 여기로 좁힌다(E5) */
  readonly registered: ReadonlySet<ShortcutContext>
}

/**
 * 활성 레이어와 등록 집합을 **스토어 한 번 읽기로** 함께 계산한다.
 *
 * ★따로 읽으면 두 값이 서로 다른 시점의 스토어를 볼 수 있다. 두 읽기 사이에 등록 해제가
 * 커밋되면(라우팅 중 상세 언마운트 등) `active` 는 이미 사라진 레이어를 가리키는데
 * `registered` 에는 그게 없어, 판별이 폴백 첫 칸부터 건너뛰고 키가 조용히 죽는다.
 * 한 번 읽어 둘을 함께 만들면 그 어긋난 짝이 성립할 수 없다.
 *
 * @returns 같은 시점의 활성 레이어 + 등록 집합
 */
export function readContextSnapshot(): ContextSnapshot {
  const registered = new Set(
    Object.keys(useContextShortcutsStore.getState().handlers) as ShortcutContext[],
  )
  return {
    active: CONTEXTS_NARROWEST_FIRST.find((context) => registered.has(context)) ?? 'app-shell',
    registered,
  }
}

/**
 * 등록된 것 중 **가장 좁은** 컨텍스트를 활성으로 판정한다.
 *
 * 아무것도 등록되지 않았으면 `app-shell` 로 떨어진다 — 이 폴백값만으로 키가 발화하지는
 * 않는다. 판별은 등록 집합도 함께 보므로 셸이 미등록이면 `[` 역시 `null` 이 된다(E5).
 *
 * @returns 현재 활성 컨텍스트
 */
export function resolveActiveContext(): ShortcutContext {
  return readContextSnapshot().active
}

/**
 * 현재 등록된 컨텍스트 집합. 판별이 정적 폴백표를 그대로 믿지 않게 하는 입력이다(E5).
 *
 * @returns 핸들러가 등록돼 있는 레이어 집합
 */
export function getRegisteredContexts(): ReadonlySet<ShortcutContext> {
  return readContextSnapshot().registered
}

/**
 * 판별 결과를 **그 결과가 지목한 레이어**의 핸들러로 흘린다. 미등록이면 조용히 무동작.
 *
 * ★레이어를 여기서 재추론하지 않는다. `resolveContextKeydown` 이 소유 레이어를 함께
 * 돌려주므로 그대로 조회한다 — 컨텍스트가 늘거나 기존 단축키가 다른 레이어로 옮겨가도
 * 이 함수는 고칠 게 없다. 예전처럼 `handlers['issue-list']` 를 하드코딩하면 재배치 시
 * 판별은 성공하는데 dispatch 만 엉뚱한 곳을 봐 조용히 무동작이 된다.
 *
 * @param hit `resolveContextKeydown` 이 판별한 레이어 + 동작. `null` 이면 무동작
 */
export function dispatchContextAction(hit: ContextShortcutHit): void {
  if (hit === null) return

  const target = useContextShortcutsStore.getState().handlers[hit.layer]
  const { action } = hit

  switch (action.kind) {
    case 'cursor-move':
      target?.onCursorMove?.(action.delta)
      return
    case 'open-current':
      target?.onOpenCurrent?.()
      return
    case 'toggle-detail-pane':
      target?.onToggleDetailPane?.()
      return
    case 'toggle-sidebar':
      target?.onToggleSidebar?.()
      return
    case 'focus-assignee':
      target?.onFocusAssignee?.()
      return
    case 'assign-to-me':
      target?.onAssignToMe?.()
      return
    case 'focus-comment':
      target?.onFocusComment?.()
      return
    case 'edit-title':
      target?.onEditTitle?.()
      return
    case 'focus-labels':
      target?.onFocusLabels?.()
      return
    case 'toggle-favorite':
      target?.onToggleFavorite?.()
      return
    case 'toggle-watch':
      target?.onToggleWatch?.()
      return
    case 'open-command-palette':
      target?.onOpenCommandPalette?.()
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
 * ★`enabled=false` 면 **등록 자체를 하지 않는다.** 콜백만 `undefined` 로 넘기는 방식과
 * 다른 점은 판별 단계에서 갈린다는 것이다 — 등록이 없으면 그 레이어가 활성이 아니므로
 * `resolveContextKeydown` 이 `null` 을 내고, 파이프라인이 `preventDefault` 조차 하지
 * 않는다. 콜백만 끊으면 키를 삼키고도 아무 일이 안 일어나 브라우저 기본 동작
 * (Firefox quick-find 등)만 사라진다.
 *
 * @param context 이 화면이 여는 컨텍스트 레이어
 * @param handlers 동작별 핸들러(전부 옵셔널)
 * @param enabled 등록 여부 — 화면이 실제로 그 조작을 받을 수 있을 때만 true
 */
export function useContextShortcuts(
  context: ShortcutContext,
  handlers: ContextShortcutHandlers,
  enabled = true,
): void {
  const handlersRef = useRef(handlers)

  // ★렌더 본문이 아니라 커밋 후 effect 에서 미러링한다. React 19 는 렌더 중 `ref.current`
  // 쓰기를 명시적으로 금지하는데, 여기서는 학술적 문제가 아니다 — TanStack Router 가
  // 네비게이션을 `startTransition` 으로 감싸고 커서 이동(`moveCursorTo`)이 네비게이션이라,
  // 커서를 옮길 때마다 이 컴포넌트가 중단 가능한 transition 렌더를 탄다. 버려진 렌더의
  // 클로저가 ref 에 남으면 다음 `j` 가 **커밋된 적 없는 `selectedKey`** 로 계산한다.
  // keydown 은 언제나 커밋 뒤에 도착하므로 effect 미러링에는 stale 창이 없다.
  // deps 없음 = 매 커밋 후 갱신 (`useKeyboardShortcuts` 의 `helpOpenRef` 미러링 선례).
  useEffect(() => {
    handlersRef.current = handlers
  })

  useEffect(() => {
    const { register, unregister } = useContextShortcutsStore.getState()
    if (!enabled) {
      // 비활성 전환 시 이전 등록을 반드시 걷어낸다 — 남겨두면 화면이 조작을 못 받는
      // 상태(로딩·에러·좁은폭·모달 열림)인데도 레이어가 활성으로 남는다.
      unregister(context)
      return undefined
    }
    register(context, {
      onCursorMove: (delta) => handlersRef.current.onCursorMove?.(delta),
      onOpenCurrent: () => handlersRef.current.onOpenCurrent?.(),
      onToggleDetailPane: () => handlersRef.current.onToggleDetailPane?.(),
      onToggleSidebar: () => handlersRef.current.onToggleSidebar?.(),
      onFocusAssignee: () => handlersRef.current.onFocusAssignee?.(),
      onAssignToMe: () => handlersRef.current.onAssignToMe?.(),
      onFocusComment: () => handlersRef.current.onFocusComment?.(),
      onEditTitle: () => handlersRef.current.onEditTitle?.(),
      onFocusLabels: () => handlersRef.current.onFocusLabels?.(),
      onToggleFavorite: () => handlersRef.current.onToggleFavorite?.(),
      onToggleWatch: () => handlersRef.current.onToggleWatch?.(),
      onOpenCommandPalette: () => handlersRef.current.onOpenCommandPalette?.(),
    })
    return () => unregister(context)
  }, [context, enabled])
}
