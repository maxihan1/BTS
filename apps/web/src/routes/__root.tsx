// 최상위 레이아웃 라우트 — Outlet으로 자식 라우트를 렌더하는 공통 껍데기
import { Outlet } from '@tanstack/react-router'
import { useIsAuthenticated } from '@/auth/authStore'
import { useNotificationStream } from '@/notifications/useNotificationStream'
import { useCommandPalette } from '@/components/command-palette/useCommandPalette'
import { CommandPalette } from '@/components/command-palette/CommandPalette'
import { useKeyboardShortcuts } from '@/components/keyboard-shortcuts/useKeyboardShortcuts'
import { ShortcutsHelpDialog } from '@/components/keyboard-shortcuts/ShortcutsHelpDialog'

export const RootLayout = () => {
  // 경로 기반 분기 대신 인증 상태로 분기 — 의미적으로 정확하며 /login 외에 미래 공개 라우트도 자동 처리
  const isAuthenticated = useIsAuthenticated()
  // hook 내부에서 인증 가드를 수행하므로 조건부 호출 없이 항상 호출 (React hook 규칙 준수)
  useNotificationStream()
  // enabled로 인증 상태를 전달 — 비로그인 시 훅 내부에서 리스너 미등록(FR2, E5)
  const { open: isCommandPaletteOpen, setOpen: setCommandPaletteOpen } = useCommandPalette(isAuthenticated)
  // enabled로 인증 상태를 전달 — 비로그인 시 훅 내부에서 keydown 리스너 미등록(FR7, E8)
  const { helpOpen, setHelpOpen, keymap } = useKeyboardShortcuts(isAuthenticated)

  return (
    <div>
      {/* 크롬(Header)은 PR11부터 `_shell`(ShellLayout)이 소유 — RootLayout은 전역 오버레이/훅만 유지(FR6, G1) */}
      {/* `<main>` 랜드마크는 PR12부터 RootLayout이 소유하지 않는다 — ShellLayout(_shell)·login이 각자
          소유한다(C3, FR-UX-06 PR12 Task 5). RootLayout이 Outlet 전체를 <main>으로 감싸면 header(banner)·
          aside(complementary)가 main 안에 중첩돼 landmark가 오염되므로 여기서는 <main> 없이 Outlet만 렌더한다. */}
      {isAuthenticated && <CommandPalette open={isCommandPaletteOpen} onOpenChange={setCommandPaletteOpen} />}
      {isAuthenticated && (
        <ShortcutsHelpDialog open={helpOpen} onOpenChange={setHelpOpen} keymap={keymap} />
      )}
      <Outlet />
    </div>
  )
}
