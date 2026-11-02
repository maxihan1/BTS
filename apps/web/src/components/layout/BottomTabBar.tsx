// 휴대폰(620px 미만) 아래 탭 — 자주 쓰는 넷 + 「전체」(공통 정책 §6, 3차 캠페인 Q5)
import type { JSX } from 'react'
import { Link } from '@tanstack/react-router'
import { Calendar, CircleDot, LayoutDashboard, Menu, UserCheck } from 'lucide-react'
import { useAuthUser } from '@/auth/authStore'
import { useSidebarDrawer } from '@/hooks/use-sidebar-drawer'
import { navLabels } from '@/i18n/nav-labels'
import { Button } from '@/components/ui/button'

/** 칸 하나 — 아이콘 위 · 이름 아래. 지금 자리는 대표색 글자(정책 §1 「지금 고른 것」) */
const TAB_CLASS =
  'flex flex-1 flex-col items-center justify-center gap-0.5 text-[11px] text-muted-foreground ' +
  '[&.active]:text-(--text-selected) [&.active]:font-semibold'

/**
 * 「모든 메뉴가 열려요」(정책 §6) — 넷은 사이드바 메인 메뉴와 같은 순서, 나머지는 「전체」가 연다.
 * 「전체」는 새 메뉴판을 만들지 않고 이미 있는 모바일 드로어(사이드바 전체)를 연다 —
 * 메뉴가 두 벌이 되면 한쪽에만 새 항목이 들어간다.
 * 본문이 탭에 가리지 않게 `ShellLayout` 의 `<main>` 이 같은 높이(h-14)만큼 아래를 비운다.
 */
export function BottomTabBar(): JSX.Element {
  const userId = useAuthUser()?.userId
  const openMenu = useSidebarDrawer((s) => s.setOpen)

  return (
    <nav
      aria-label={navLabels.bottomTabs}
      className="fixed inset-x-0 bottom-0 z-30 flex h-14 border-t border-border bg-card min-[620px]:hidden"
    >
      {userId !== undefined && (
        <Link to="/issues" search={{ assignee: userId }} activeOptions={{ includeSearch: true }} className={TAB_CLASS}>
          <UserCheck aria-hidden="true" className="size-5" />
          {navLabels.myWork}
        </Link>
      )}
      {/* 「내 작업」과 같은 경로라 exact — 사이드바 ISSUES_ACTIVE_OPTIONS 와 같은 판정 */}
      <Link to="/issues" activeOptions={{ exact: true, includeSearch: true }} className={TAB_CLASS}>
        <CircleDot aria-hidden="true" className="size-5" />
        {navLabels.issues}
      </Link>
      <Link to="/dashboards" className={TAB_CLASS}>
        <LayoutDashboard aria-hidden="true" className="size-5" />
        {navLabels.dashboards}
      </Link>
      <Link to="/calendar" className={TAB_CLASS}>
        <Calendar aria-hidden="true" className="size-5" />
        {navLabels.calendar}
      </Link>
      <Button variant="ghost" className={`${TAB_CLASS} h-full rounded-none px-0`} onClick={() => { openMenu(true) }}>
        <Menu aria-hidden="true" className="size-5" />
        {navLabels.allMenu}
      </Button>
    </nav>
  )
}
