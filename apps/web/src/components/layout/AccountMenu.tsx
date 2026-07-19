// 계정 아바타 드롭다운 — Header.tsx 계정 메뉴 로직 재현(FR-UX-06 PR11 Task 6, TopBar 서브컴포넌트)
import { useState } from 'react'
import { useNavigate, Link } from '@tanstack/react-router'
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
import { StatusModal } from '@/components/status/StatusModal'
import { OooModal } from '@/components/ooo/OooModal'
import { oooLabels } from '@/i18n/ooo-labels'
import { formatOooReturnDate } from '@/lib/ooo-datetime'

/**
 * 계정 트리거에 표시할 라벨을 계산한다 — displayName 우선, 없으면 username, 둘 다 없으면 빈 문자열.
 * `Header.tsx`의 동명 헬퍼와 동일 로직이다 — Header 축소/삭제(T7) 시 중복이 해소된다.
 *
 * @param user whoami 응답(authStore.user) — 미인증이면 null
 * @returns 표시할 계정 라벨
 */
function resolveAccountLabel(user: WhoamiResponse | null): string {
  return user?.displayName ?? user?.username ?? ''
}

/**
 * 계정 아바타 드롭다운 — 상태 배지·부재중 배지·프로필/설정 하위 링크·로그아웃을 포함한다.
 *
 * `Header.tsx`의 계정 드롭다운을 그대로 재현한다(TopBar 흡수 전 의도된 일시 중복,
 * T7이 Header를 축소/삭제하며 해소한다).
 */
export function AccountMenu() {
  const user = useAuthUser()
  const avatarVersion = useAuthStore((s) => s.avatarVersion)
  const navigate = useNavigate()
  const logoutMutation = useLogoutMutation()
  const [statusOpen, setStatusOpen] = useState(false)
  const [oooOpen, setOooOpen] = useState(false)

  // 활성 상태 이모지(whoami view-layer, FR-PR-02). 빈 문자열/null이면 배지 미표시.
  const statusEmoji = user?.statusEmoji != null && user.statusEmoji !== '' ? user.statusEmoji : null
  const statusText = user?.statusText ?? null

  // 활성 부재중(whoami view-layer, FR-PR-03).
  const oooActive = user?.oooActive === true
  const oooReturnDate = formatOooReturnDate(user?.oooUntil ?? null)
  const oooSuffix = oooActive
    ? `, ${oooLabels.headerBadge}${oooReturnDate !== null ? `(${oooLabels.headerBadgeReturnPrefix} ${oooReturnDate})` : ''}`
    : ''

  const handleLogout = () => {
    logoutMutation.mutate(undefined, {
      // 성공·실패 무관하게 /login으로 이동 — 사용자 로그아웃 의도 우선
      onSettled: () => {
        void navigate({ to: '/login' })
      },
    })
  }

  const accountLabel = resolveAccountLabel(user)

  return (
    <>
      <DropdownMenu>
        <DropdownMenuTrigger asChild>
          <button
            type="button"
            className="flex items-center gap-2 rounded-md px-3 py-1.5 text-sm font-medium hover:bg-accent"
            aria-label={`${accountLabel}${statusText !== null ? `, ${statusText}` : ''}${oooSuffix} 계정 메뉴`}
          >
            {/* aria-hidden — 버튼의 aria-label이 접근 가능한 이름을 이미 제공하므로 내부 요소 중복 announce 방지 */}
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
              {oooActive && (
                <span
                  title={oooReturnDate !== null ? `${oooLabels.headerBadgeReturnPrefix}: ${oooReturnDate}` : undefined}
                  className="rounded-full bg-muted px-1.5 py-0.5 text-[10px] font-medium text-muted-foreground"
                >
                  {oooLabels.headerBadge}
                </span>
              )}
            </span>
          </button>
        </DropdownMenuTrigger>
        <DropdownMenuContent align="end">
          <DropdownMenuItem onSelect={() => { setStatusOpen(true) }}>상태 설정</DropdownMenuItem>
          <DropdownMenuItem onSelect={() => { setOooOpen(true) }}>{oooLabels.accountMenuItem}</DropdownMenuItem>
          <DropdownMenuItem asChild>
            <Link to="/settings/profile">프로필</Link>
          </DropdownMenuItem>
          <DropdownMenuItem asChild>
            <Link to="/settings/preferences">환경 설정</Link>
          </DropdownMenuItem>
          <DropdownMenuItem asChild>
            <Link to="/settings/keymap">단축키</Link>
          </DropdownMenuItem>
          <DropdownMenuItem asChild>
            <Link to="/settings/calendar">캘린더 구독</Link>
          </DropdownMenuItem>
          <DropdownMenuItem asChild>
            <Link to="/settings/slack">Slack 알림</Link>
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
      <OooModal open={oooOpen} onOpenChange={setOooOpen} />
    </>
  )
}
