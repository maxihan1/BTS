// 알림 보관함 헤더 종(🔔) 아이콘 컴포넌트 — 미읽음 뱃지 + /inbox 링크 (FR-UX-03 D6/D7)
import { Link } from '@tanstack/react-router'
import { Bell } from 'lucide-react'
import { useUnreadCount } from '@/api/inbox'
import { inboxLabels } from '@/i18n/inbox-labels'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** 뱃지에 표시하는 최대 숫자 — 초과하면 "MAX_BADGE_COUNT+" 로 표시 */
const MAX_BADGE_COUNT = 99

// ─────────────────────────────────────────────────────────────────────────────
// 순수 함수 — 뱃지 표시 텍스트 계산 (REFACTOR 단계 추출 예정)
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 미읽음 카운트를 뱃지 표시 문자열로 변환한다.
 * - 0이면 null (뱃지 숨김)
 * - 1~99이면 숫자 문자열
 * - 100 이상이면 "99+"
 *
 * @param count 미읽음 카운트 (0 이상 정수)
 * @returns 뱃지 텍스트 또는 null (숨김)
 */
function formatUnreadBadge(count: number): string | null {
  if (count <= 0) return null
  if (count > MAX_BADGE_COUNT) return `${MAX_BADGE_COUNT}+`
  return String(count)
}

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Header 우측에 삽입되는 알림 종 아이콘.
 * - useUnreadCount() 훅으로 미읽음 카운트를 구독한다.
 * - 카운트가 0이면 뱃지를 숨기고, 1 이상이면 뱃지에 숫자를 표시한다.
 * - 100 이상이면 "99+"로 표시한다.
 * - 클릭 시 /inbox 페이지로 SPA 이동한다.
 */
export const InboxBell = () => {
  const { data: count = 0 } = useUnreadCount()

  const badgeText = formatUnreadBadge(count)

  return (
    <Link
      to="/inbox"
      className="relative flex items-center justify-center rounded-md p-1.5 hover:bg-accent"
      aria-label={inboxLabels.bell.ariaLabel}
    >
      <Bell className="size-4" />
      {badgeText !== null && (
        <span
          data-testid="inbox-unread-badge"
          className="absolute -right-1 -top-1 flex min-w-[1.125rem] items-center justify-center rounded-full bg-destructive px-1 py-0.5 text-[0.625rem] font-semibold leading-none text-destructive-foreground"
        >
          <span className="sr-only">{inboxLabels.bell.unreadBadgeScreenReader}</span>
          {badgeText}
        </span>
      )}
    </Link>
  )
}
