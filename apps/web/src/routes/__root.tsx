// 최상위 레이아웃 라우트 — Outlet으로 자식 라우트를 렌더하는 공통 껍데기
import { Outlet } from '@tanstack/react-router'
import { useIsAuthenticated } from '@/auth/authStore'
import { Header } from '@/components/Header'
import { useNotificationStream } from '@/notifications/useNotificationStream'
import { useCommandPalette } from '@/components/command-palette/useCommandPalette'
import { CommandPalette } from '@/components/command-palette/CommandPalette'

export const RootLayout = () => {
  // 경로 기반 분기 대신 인증 상태로 분기 — 의미적으로 정확하며 /login 외에 미래 공개 라우트도 자동 처리
  const isAuthenticated = useIsAuthenticated()
  // hook 내부에서 인증 가드를 수행하므로 조건부 호출 없이 항상 호출 (React hook 규칙 준수)
  useNotificationStream()
  // enabled로 인증 상태를 전달 — 비로그인 시 훅 내부에서 리스너 미등록(FR2, E5)
  const { open: isCommandPaletteOpen, setOpen: setCommandPaletteOpen } = useCommandPalette(isAuthenticated)

  return (
    <div>
      {isAuthenticated && <Header />}
      {isAuthenticated && <CommandPalette open={isCommandPaletteOpen} onOpenChange={setCommandPaletteOpen} />}
      <main>
        <Outlet />
      </main>
    </div>
  )
}
