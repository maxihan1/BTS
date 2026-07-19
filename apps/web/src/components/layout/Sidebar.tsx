// 전역 좌측 사이드바 — 메인 nav(이슈·대시보드·캘린더·즐겨찾기) + 관리 nav(isSystemAdmin 게이팅, 기본 펼침) — FR-UX-06 PR11 Task 5 (아직 트리 미배선, ShellLayout(T7)이 배선)
import { type JSX } from 'react'
import { Link } from '@tanstack/react-router'
import { useAuthUser } from '@/auth/authStore'
import { useSidebarCollapsed } from '@/hooks/use-sidebar-collapsed'
import { FavoritesMenu } from '@/components/favorite/FavoritesMenu'
import { navLabels } from '@/i18n/nav-labels'

// ─────────────────────────────────────────────────────────────────────────────
// 상수
// ─────────────────────────────────────────────────────────────────────────────

/** 사이드바 펼침 폭 (디자인 스펙 §3.1) */
const SIDEBAR_WIDTH_EXPANDED_CLASS = 'w-[264px]'

/** 사이드바 접힘 폭 — 아이콘 레일(FR5) */
const SIDEBAR_WIDTH_COLLAPSED_CLASS = 'w-16'

/**
 * nav 링크 공통 스타일. `[&.active]` — 활성 라우트 시각 강조(Header.tsx 관례 계승).
 * `truncate` — 접힘(64px) 시 텍스트가 넘치지 않게 자름(body 가로 스크롤 금지, NFR2).
 * 텍스트 자체는 DOM에서 제거하지 않으므로 접힘 상태에서도 접근가능 이름은 유지된다(FR5).
 */
const NAV_LINK_CLASS =
  'flex items-center rounded-md px-2 py-1.5 text-sm font-medium truncate ' +
  'text-sidebar-foreground/80 hover:bg-sidebar-accent hover:text-sidebar-accent-foreground ' +
  '[&.active]:bg-sidebar-accent [&.active]:text-sidebar-accent-foreground [&.active]:font-semibold'

/** 메인 nav 실 라우트 링크 — FR3, 백킹 없는 항목(내 작업·최근·필터) 제외(S3) */
const MAIN_NAV_LINKS = [
  { to: '/issues', label: navLabels.issues },
  { to: '/dashboards', label: navLabels.dashboards },
  { to: '/calendar', label: navLabels.calendar },
] as const

/** 관리 nav 링크 6종 — `Header.tsx` `ADMIN_LINKS` 정본 그대로(FR4). T7에서 Header 삭제로 중복 해소 */
const ADMIN_NAV_LINKS = [
  { to: '/admin/workflow-schemes', label: '워크플로우 스킴' },
  { to: '/admin/audit-logs', label: '감사 로그' },
  { to: '/admin/global-permissions', label: '전역 권한' },
  { to: '/admin/notification-policies', label: '알림 정책' },
  { to: '/admin/webhooks', label: 'Webhook' },
  { to: '/admin/slack', label: 'Slack 연결' },
] as const

// ─────────────────────────────────────────────────────────────────────────────
// 컴포넌트
// ─────────────────────────────────────────────────────────────────────────────

/**
 * 전역 좌측 사이드바 — 264px 고정, 독립 스크롤(`overflow-y-auto`).
 *
 * `메인 메뉴` nav(이슈·대시보드·캘린더·즐겨찾기)는 항상 렌더되고, `관리 메뉴` nav는
 * `user.isSystemAdmin === true`일 때만 **기본 펼침** 상태로 렌더된다(FR3/FR4). 접힘 상태는
 * {@link useSidebarCollapsed}로 소비하며, 접혀도(아이콘 레일) 각 링크의 텍스트 라벨을 DOM에서
 * 제거하지 않아 `getByRole('link', { name })` 계약(e2e)이 유지된다(FR5).
 *
 * 🔴 이 컴포넌트는 아직 어떤 트리에도 배선되지 않는다 — `ShellLayout`(T7)이 배선한다.
 */
export function Sidebar(): JSX.Element {
  const user = useAuthUser()
  const { collapsed, toggle } = useSidebarCollapsed()
  const isAdmin = user?.isSystemAdmin === true
  const widthClass = collapsed ? SIDEBAR_WIDTH_COLLAPSED_CLASS : SIDEBAR_WIDTH_EXPANDED_CLASS

  return (
    <aside
      className={`flex h-full flex-col overflow-y-auto border-r border-sidebar-border bg-sidebar text-sidebar-foreground ${widthClass}`}
    >
      <nav aria-label={navLabels.mainNav} className="flex flex-col gap-1 p-2">
        {MAIN_NAV_LINKS.map(({ to, label }) => (
          <Link key={to} to={to} className={NAV_LINK_CLASS}>
            {label}
          </Link>
        ))}
        <FavoritesMenu />
      </nav>

      {isAdmin && (
        <nav aria-label={navLabels.adminNav} className="flex flex-col gap-1 p-2">
          {!collapsed && (
            <p className="px-2 pt-2 text-xs font-semibold uppercase text-sidebar-foreground/60">
              {navLabels.admin}
            </p>
          )}
          {ADMIN_NAV_LINKS.map(({ to, label }) => (
            <Link key={to} to={to} className={NAV_LINK_CLASS}>
              {label}
            </Link>
          ))}
        </nav>
      )}

      <button
        type="button"
        onClick={toggle}
        aria-label={collapsed ? navLabels.expandSidebar : navLabels.collapseSidebar}
        className="mt-auto rounded-md p-2 text-sidebar-foreground/70 hover:bg-sidebar-accent hover:text-sidebar-accent-foreground"
      >
        {collapsed ? '»' : '«'}
      </button>
    </aside>
  )
}
