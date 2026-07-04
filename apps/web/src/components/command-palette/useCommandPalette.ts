// Cmd+K/Ctrl+K 전역 단축키로 명령 팔레트를 여닫는 훅 — FR-UX-04 Task-3
import { useEffect, useState } from 'react'

/** useCommandPalette 반환값 */
interface UseCommandPaletteResult {
  /** 팔레트 열림 여부 */
  readonly open: boolean
  /** 열림 상태를 직접 제어하는 setter — CommandPalette의 onOpenChange에 연결 */
  readonly setOpen: (open: boolean) => void
}

/**
 * Cmd+K(mac) / Ctrl+K(win·linux) 전역 단축키로 명령 팔레트를 토글하는 훅.
 *
 * - `enabled`가 false이면(비로그인) `document` keydown 리스너를 등록하지 않고
 *   열림 상태를 강제로 false로 유지한다(FR2, E5 — 비로그인 시 무반응).
 * - `enabled`가 true이면 리스너를 등록하고, 단축키 입력 시 브라우저 기본
 *   동작을 `preventDefault`로 막은 뒤 열림 상태를 토글한다(FR1, E6, E7).
 * - 언마운트 또는 `enabled` 변경 시 cleanup으로 리스너를 해제해 누수를 막는다.
 *
 * @param enabled 훅 활성화 여부 — RootLayout에서 인증 상태를 전달
 * @returns 팔레트 열림 상태와 setter
 */
export function useCommandPalette(enabled: boolean): UseCommandPaletteResult {
  const [open, setOpen] = useState(false)

  useEffect(() => {
    if (!enabled) {
      setOpen(false)
      return
    }

    function handleKeyDown(e: KeyboardEvent): void {
      if (!(e.metaKey || e.ctrlKey) || e.key !== 'k') return
      e.preventDefault()
      setOpen((prev) => !prev)
    }

    document.addEventListener('keydown', handleKeyDown)
    return () => {
      document.removeEventListener('keydown', handleKeyDown)
    }
  }, [enabled])

  return { open, setOpen }
}
