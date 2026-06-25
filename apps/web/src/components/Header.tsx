// 전역 헤더 컴포넌트 — 관리 nav(isSystemAdmin 게이팅) + 사용자명 표시 + 로그아웃 드롭다운 메뉴
import { useNavigate, Link } from '@tanstack/react-router'
import { Search } from 'lucide-react'
import { useAuthUser } from '@/auth/authStore'
import { useLogoutMutation } from '@/auth/useLogoutMutation'
import {
  DropdownMenu,
  DropdownMenuTrigger,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
} from '@/components/ui/dropdown-menu'
import { FavoritesMenu } from '@/components/favorite/FavoritesMenu'
import { InboxBell } from '@/components/inbox/InboxBell'

// ─────────────────────────────────────────────────────────────────────────────
// admin 링크 목록 — isSystemAdmin=true 시 관리 메뉴에 표시할 링크
// ─────────────────────────────────────────────────────────────────────────────

/** 관리 메뉴 링크 정의 — 확장 시 이 배열에만 추가 */
const ADMIN_LINKS = [
  { to: '/admin/workflow-schemes', label: '워크플로우 스킴' },
  { to: '/admin/audit-logs', label: '감사 로그' },
  { to: '/admin/notification-policies', label: '알림 정책' },
] as const

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

  // === true 명시비교 — undefined/null/'admin' 오인 방지 (routeGuard.requireSystemAdmin 일관)
  const isAdmin = user?.isSystemAdmin === true

  return (
    <header className="flex h-14 items-center border-b bg-background px-4">
      {/* 메인 nav — 인증 사용자 전용 공통 링크 */}
      <nav className="flex items-center gap-4 text-sm font-medium" aria-label="메인 메뉴">
        <Link
          to="/dashboards"
          className="text-muted-foreground hover:text-foreground [&.active]:text-foreground [&.active]:font-semibold"
        >
          대시보드
        </Link>
      </nav>
      {/* 관리 nav — SYSTEM_ADMIN 전용 (isSystemAdmin === true일 때만 렌더) */}
      {isAdmin && (
        <nav className="flex items-center gap-4 text-sm font-medium ml-4" aria-label="관리 메뉴">
          {ADMIN_LINKS.map(({ to, label }) => (
            <Link
              key={to}
              to={to}
              className="text-muted-foreground hover:text-foreground [&.active]:text-foreground [&.active]:font-semibold"
            >
              {label}
            </Link>
          ))}
        </nav>
      )}
      <div className="flex-1" />
      <FavoritesMenu />
      <button
        type="button"
        className="rounded-md p-1.5 hover:bg-accent"
        aria-label="검색"
        onClick={() => { void navigate({ to: '/search' }) }}
      >
        <Search className="size-4" />
      </button>
      <InboxBell />
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
          <DropdownMenuItem asChild>
            <Link to="/settings/mfa">2단계 인증</Link>
          </DropdownMenuItem>
          <DropdownMenuSeparator />
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
