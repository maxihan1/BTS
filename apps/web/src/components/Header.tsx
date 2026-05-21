// 전역 헤더 컴포넌트 — 사용자명 표시 + 로그아웃 드롭다운 메뉴
import { useNavigate } from '@tanstack/react-router'
import { useAuthUser } from '@/auth/authStore'
import { useLogoutMutation } from '@/auth/useLogoutMutation'
import {
  DropdownMenu,
  DropdownMenuTrigger,
  DropdownMenuContent,
  DropdownMenuItem,
} from '@/components/ui/dropdown-menu'

export const Header = () => {
  const user = useAuthUser()
  const navigate = useNavigate()
  const logoutMutation = useLogoutMutation()

  const handleLogout = () => {
    logoutMutation.mutate(undefined, {
      // 성공·실패 무관하게 /login으로 이동 — 사용자 로그아웃 의도 우선
      onSettled: () => {
        void navigate({ to: '/login' })
      },
    })
  }

  const username = user?.username ?? ''

  return (
    <header className="flex h-14 items-center border-b bg-background px-4">
      <div className="flex-1" />
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <button
            type="button"
            className="flex items-center gap-2 rounded-md px-3 py-1.5 text-sm font-medium hover:bg-accent"
            aria-label={`${username} 계정 메뉴`}
          >
            {username}
          </button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end">
          <DropdownMenuItem
            variant="destructive"
            onSelect={handleLogout}
            disabled={logoutMutation.isPending}
          >
            로그아웃
          </DropdownMenuItem>
        </DropdownMenuContent>
      </DropdownMenu>
    </header>
  )
}
