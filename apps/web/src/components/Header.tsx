// 전역 헤더 컴포넌트 — 관리 nav(isSystemAdmin 게이팅) + 계정 트리거(아바타+상태 배지+표시이름) + 로그아웃 드롭다운 메뉴
import { useState } from 'react'
import { useNavigate, Link } from '@tanstack/react-router'
import { Search } from 'lucide-react'
import type { WhoamiResponse } from '@/api/schemas'
import { useAuthUser, useAuthStore } from '@/auth/authStore'
import { useLogoutMutation } from '@/auth/useLogoutMutation'
import {
  DropdownMenu,
  DropdownMenuTrigger,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuSeparator,
} from '@/components/ui/dropdown-menu'
import { Avatar } from '@/components/ui/avatar'
import { FavoritesMenu } from '@/components/favorite/FavoritesMenu'
import { InboxBell } from '@/components/inbox/InboxBell'
import { StatusModal } from '@/components/status/StatusModal'

// ─────────────────────────────────────────────────────────────────────────────
// admin 링크 목록 — isSystemAdmin=true 시 관리 메뉴에 표시할 링크
// ─────────────────────────────────────────────────────────────────────────────

/** 관리 메뉴 링크 정의 — 확장 시 이 배열에만 추가 */
const ADMIN_LINKS = [
  { to: '/admin/workflow-schemes', label: '워크플로우 스킴' },
  { to: '/admin/audit-logs', label: '감사 로그' },
  { to: '/admin/notification-policies', label: '알림 정책' },
  { to: '/admin/webhooks', label: 'Webhook' },
] as const

/**
 * 계정 트리거에 표시할 라벨을 계산한다 — displayName 우선, 없으면 username, 둘 다 없으면 빈 문자열.
 * 트리거 텍스트 · aria-label · Avatar 이니셜 폴백이 모두 이 우선순위를 공유한다(FR-PR-01 F10).
 *
 * @param user whoami 응답(authStore.user) — 미인증이면 null
 * @returns 표시할 계정 라벨
 */
function resolveAccountLabel(user: WhoamiResponse | null): string {
  return user?.displayName ?? user?.username ?? ''
}

/**
 * 전역 헤더 — 메인/관리 nav, 즐겨찾기, 검색, 알림 보관함, 계정 드롭다운을 렌더한다.
 *
 * 계정 드롭다운 트리거는 Avatar(아바타 이미지 또는 이니셜 폴백) + displayName(없으면
 * username 폴백)을 함께 표시한다(spec S9). 드롭다운 메뉴에는 프로필/2단계 인증/PAT
 * 설정 링크와 로그아웃 항목이 있다.
 */
export const Header = () => {
  const user = useAuthUser()
  const avatarVersion = useAuthStore((s) => s.avatarVersion)
  const navigate = useNavigate()
  const logoutMutation = useLogoutMutation()
  const [statusOpen, setStatusOpen] = useState(false)

  // 활성 상태 이모지(whoami view-layer, FR-PR-02). 빈 문자열/null이면 배지 미표시.
  const statusEmoji = user?.statusEmoji != null && user.statusEmoji !== '' ? user.statusEmoji : null
  const statusText = user?.statusText ?? null

  const handleLogout = () => {
    logoutMutation.mutate(undefined, {
      // 성공·실패 무관하게 /login으로 이동 — 사용자 로그아웃 의도 우선
      onSettled: () => {
        void navigate({ to: '/login' })
      },
    })
  }

  const accountLabel = resolveAccountLabel(user)

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
            aria-label={`${accountLabel}${statusText !== null ? `, ${statusText}` : ''} 계정 메뉴`}
          >
            {/* aria-hidden — 버튼의 aria-label이 접근 가능한 이름(계정명 + 상태 텍스트)을 이미 제공하므로
                내부 Avatar(role=img)/텍스트/상태 배지가 스크린리더에 중복 announce 되지 않게 숨긴다 */}
            <span aria-hidden="true" className="flex items-center gap-2">
              <span className="relative inline-flex">
                <Avatar
                  avatarUrl={user?.avatarUrl}
                  displayName={user?.displayName}
                  username={user?.username}
                  size="sm"
                  cacheBust={avatarVersion}
                />
                {statusEmoji !== null && (
                  <span
                    title={statusText ?? undefined}
                    className="absolute -bottom-1 -right-1 rounded-full bg-background text-xs leading-none"
                  >
                    {statusEmoji}
                  </span>
                )}
              </span>
              <span>{accountLabel}</span>
            </span>
          </button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end">
          <DropdownMenuItem onSelect={() => { setStatusOpen(true) }}>상태 설정</DropdownMenuItem>
          <DropdownMenuItem asChild>
            <Link to="/settings/profile">프로필</Link>
          </DropdownMenuItem>
          <DropdownMenuItem asChild>
            <Link to="/settings/mfa">2단계 인증</Link>
          </DropdownMenuItem>
          <DropdownMenuItem asChild>
            <Link to="/settings/pats">Personal Access Token</Link>
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
      <StatusModal open={statusOpen} onOpenChange={setStatusOpen} />
    </header>
  )
}
