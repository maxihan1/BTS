// Cmd+K/Ctrl+K 전역 단축키로 명령 팔레트를 여닫는 훅 + 열림 상태 공유 스토어 — FR-UX-04 Task-3 / FR-UX-10 F11 Task-3
import { useEffect } from 'react'
import { create } from 'zustand'

/** 명령 팔레트를 토글하는 단축키 문자 — Cmd(mac)/Ctrl(win·linux) + 이 키 */
const TOGGLE_KEY = 'k'

/** useCommandPalette 반환값 */
interface UseCommandPaletteResult {
  /** 팔레트 열림 여부 */
  readonly open: boolean
  /** 열림 상태를 직접 제어하는 setter — CommandPalette의 onOpenChange에 연결 */
  readonly setOpen: (open: boolean) => void
}

/** 팔레트 열림 상태 스토어의 상태 + 액션 */
interface CommandPaletteState {
  /** 팔레트 열림 여부 */
  readonly open: boolean
  /** 열림 상태를 직접 지정한다 */
  readonly setOpen: (open: boolean) => void
  /** 열림 상태를 반전한다 — Cmd/Ctrl+K 전용 (`.` 는 여는 것만 한다) */
  readonly toggle: () => void
}

/**
 * 명령 팔레트 열림 상태 — 모듈 전역 zustand 스토어.
 *
 * ★왜 `useState` 가 아니라 zustand 인가. 팔레트를 렌더하는 것은 `RootLayout`인데
 * `.`(FR-UX-10 F11)의 등록 지점은 그 자손인 `ShellLayout`의 `app-shell` 컨텍스트다.
 * 열림 상태를 `RootLayout`의 `useState`가 소유하면 `ShellLayout`은 그 상태에 prop으로
 * 닿을 수 없어, 키는 판별·`preventDefault`까지 통과하는데 팔레트만 안 열린다 —
 * 브라우저 기본 동작만 사라지는 ADR D-5-a 의 그 형태다. 컴포넌트 트리 밖에서
 * 상태를 건드려야 하므로 `use-sidebar-collapsed.ts`·`useContextShortcuts.ts` 와 같은
 * 이유로 zustand 를 쓴다.
 *
 * ★localStorage 에 영속하지 않는다. 팔레트 열림은 사용자 설정이 아니라 한 순간의 세션
 * UI 상태라, 되살리면 다음 방문이 팔레트가 뜬 채로 시작한다(`use-sidebar-collapsed`
 * 가 영속하는 것은 그쪽이 사용자 선호값이기 때문이다).
 */
export const useCommandPaletteStore = create<CommandPaletteState>((set) => ({
  open: false,
  setOpen: (open): void => set({ open }),
  // ★반전은 스토어 안에서 한 단계로 한다. 호출부가 `setOpen(!getState().open)` 으로
  // 읽고 쓰면 두 단계라, 리스너 클로저가 낡은 값을 읽을 여지가 생긴다
  // (`use-sidebar-collapsed.ts` 의 `toggle` 과 같은 모양).
  toggle: (): void => set((state) => ({ open: !state.open })),
}))

/**
 * keydown 이벤트가 명령 팔레트 토글 단축키(Cmd+K/Ctrl+K)인지 판별한다.
 *
 * @param e keydown 이벤트
 * @returns 토글 단축키이면 true
 */
function isToggleShortcut(e: KeyboardEvent): boolean {
  return (e.metaKey || e.ctrlKey) && e.key === TOGGLE_KEY
}

/**
 * Cmd+K(mac) / Ctrl+K(win·linux) 전역 단축키로 명령 팔레트를 토글하는 훅.
 *
 * - `enabled`가 false이면(비로그인) `document` keydown 리스너를 등록하지 않고
 *   열림 상태를 강제로 false로 유지한다(FR2, E5 — 비로그인 시 무반응).
 *   스토어가 모듈 전역이라 이 초기화가 곧 **세션 간 누수 차단**이다 — 로그아웃 시
 *   비우지 않으면 다음 로그인이 팔레트가 열린 채로 시작한다.
 * - `enabled`가 true이면 리스너를 등록하고, 단축키 입력 시 브라우저 기본
 *   동작을 `preventDefault`로 막은 뒤 열림 상태를 토글한다(FR1, E6, E7).
 * - 언마운트 또는 `enabled` 변경 시 cleanup으로 리스너를 해제해 누수를 막는다.
 *
 * ★상태는 {@link useCommandPaletteStore}가 소유한다. 이 훅은 그 스토어를 구독해
 * 값을 돌려줄 뿐이므로, `.`(F11)처럼 컴포넌트 트리 밖에서 연 팔레트도 여기 반영된다.
 * 반환 시그니처(`{ open, setOpen }`)는 `useState` 시절 그대로라 `RootLayout`은 무변경이다.
 *
 * @param enabled 훅 활성화 여부 — RootLayout에서 인증 상태를 전달
 * @returns 팔레트 열림 상태와 setter
 */
export function useCommandPalette(enabled: boolean): UseCommandPaletteResult {
  const open = useCommandPaletteStore((state) => state.open)
  const setOpen = useCommandPaletteStore((state) => state.setOpen)
  const toggle = useCommandPaletteStore((state) => state.toggle)

  useEffect(() => {
    if (!enabled) {
      setOpen(false)
      return
    }

    function handleKeyDown(e: KeyboardEvent): void {
      if (!isToggleShortcut(e)) return
      e.preventDefault()
      // ★`open` 클로저가 아니라 스토어의 `toggle` 을 부른다. 이 effect 는 `enabled`
      // 가 바뀔 때만 다시 도므로 클로저의 `open` 은 등록 시점 값에 고정돼 있다.
      toggle()
    }

    document.addEventListener('keydown', handleKeyDown)
    return () => {
      document.removeEventListener('keydown', handleKeyDown)
    }
  }, [enabled, setOpen, toggle])

  return { open, setOpen }
}
